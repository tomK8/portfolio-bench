package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches daily OHLCV bars from Yahoo Finance's chart API. Prices are returned in the
 * instrument's listing currency (see {@link PriceBar}); no FX conversion happens here.
 */
public class YahooPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(YahooPriceFetcher.class);

    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s" +
                    "?period1=%d&period2=%d&interval=1d&events=div,split";
    private static final String INTRADAY_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s" +
                    "?period1=%d&period2=%d&interval=1m&includePrePost=false";
    // Yahoo rejects requests without a browser-like User-Agent.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Daily bars for {@code [start, end]} inclusive; empty list on persistent failure (logged). */
    public List<PriceBar> fetch(String yahooTicker, LocalDate start, LocalDate end) {
        long p1 = start.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = String.format(CHART_URL, yahooTicker, p1, p2);

        String body = get(url);
        if (body == null) {
            log.warn("Yahoo fetch failed for {}", yahooTicker);
            return List.of();
        }
        try {
            return parse(yahooTicker, body);
        } catch (Exception e) {
            log.warn("Yahoo parse failed for {}", yahooTicker, e);
            return List.of();
        }
    }

    private String get(String url) {   // retry once, then give up
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
                log.warn("Yahoo HTTP {} for {} (attempt {})", resp.statusCode(), url, attempt);
            } catch (Exception e) {
                log.warn("Yahoo error {} (attempt {})", url, attempt, e);
            }
        }
        return null;
    }

    List<PriceBar> parse(String ticker, String json) throws Exception {   // package-private for tests
        JsonNode result = mapper.readTree(json).path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) return List.of();
        JsonNode r = result.get(0);

        String currency = r.path("meta").path("currency").asText(null);
        int gmtOffset = r.path("meta").path("gmtoffset").asInt(0);

        JsonNode ts = r.path("timestamp");
        JsonNode q = r.path("indicators").path("quote").path(0);
        JsonNode open = q.path("open"), high = q.path("high"),
                low = q.path("low"), close = q.path("close"), vol = q.path("volume");

        // First pass: read bars with placeholder adjClose = close. We don't trust Yahoo's
        // bundled adjclose field — for many UK listings (LGEN.L is the canary) it's only
        // split-adjusted, not dividend-adjusted, so total-return math via the column is wrong.
        // Yahoo publishes the full dividend + split event stream alongside in the same
        // response (we already pass events=div,split in the URL), so we re-derive adj_close
        // ourselves below.
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            Double c = num(close, i);
            if (c == null) continue;          // holiday / gap row
            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong() + gmtOffset)
                    .atZone(ZoneOffset.UTC).toLocalDate();
            Long volume = vol.path(i).isNumber() ? vol.get(i).asLong() : null;
            bars.add(new PriceBar(ticker, date, num(open, i), num(high, i),
                    num(low, i), c, c, 1.0, volume, currency));
        }
        if (bars.isEmpty()) return bars;

        JsonNode eventsNode = r.path("events");
        List<CorporateEvent> dividends = parseDividends(eventsNode);
        List<SplitEvent> splits = parseSplits(eventsNode);
        if (dividends.isEmpty() && splits.isEmpty()) return bars;

        return applyAdjustments(bars, dividends, splits);
    }

    /**
     * Walks bars, dividend events and split events backward in lockstep to derive two
     * per-bar quantities:
     *
     * <ul>
     *   <li><b>{@code adj_close}</b> — total-return-adjusted close. For each dividend whose
     *       date falls strictly after the current bar's date but not after any later bar
     *       we've already passed, multiply the running factor by
     *       {@code (prevClose − D) / prevClose}.</li>
     *   <li><b>{@code splitFactor}</b> — cumulative split ratio from this bar's date forward
     *       to today. Yahoo's {@code close} column is split-adjusted to current basis (e.g.
     *       GOOG's 2016 close is ~$39 even though the raw price was ~$780); multiplying by
     *       {@code splitFactor} recovers the raw close in basis-at-date. For each split with
     *       date after the current bar's date, multiply the running factor by
     *       {@code numerator / denominator} (= 20 for a 20:1 split, 0.1 for a 1:10 reverse).</li>
     * </ul>
     *
     * <p>The dividend factor is intentionally split-naïve: Yahoo's dividend amounts are on
     * the same split-adjusted basis as the close, so {@code (prevClose − D) / prevClose} is
     * exact in either basis — no split-unwinding needed inside the dividend walk.
     */
    private static List<PriceBar> applyAdjustments(List<PriceBar> bars,
                                                   List<CorporateEvent> dividends,
                                                   List<SplitEvent> splits) {
        dividends.sort((a, b) -> a.date.compareTo(b.date));
        splits.sort((a, b) -> a.date.compareTo(b.date));
        double divFactor = 1.0;
        double splitFactor = 1.0;
        int divIdx = dividends.size() - 1;
        int splitIdx = splits.size() - 1;
        PriceBar[] reversed = new PriceBar[bars.size()];
        for (int barIdx = bars.size() - 1; barIdx >= 0; barIdx--) {
            PriceBar bar = bars.get(barIdx);
            while (divIdx >= 0 && dividends.get(divIdx).date.isAfter(bar.date())) {
                CorporateEvent e = dividends.get(divIdx);
                if (bar.close() > 0) {
                    divFactor *= (bar.close() - e.amount) / bar.close();
                }
                divIdx--;
            }
            while (splitIdx >= 0 && splits.get(splitIdx).date.isAfter(bar.date())) {
                splitFactor *= splits.get(splitIdx).ratio();
                splitIdx--;
            }
            reversed[barIdx] = new PriceBar(bar.symbol(), bar.date(), bar.open(), bar.high(),
                    bar.low(), bar.close(), bar.close() * divFactor, splitFactor,
                    bar.volume(), bar.currency());
        }
        List<PriceBar> out = new ArrayList<>(bars.size());
        for (PriceBar pb : reversed) out.add(pb);
        return out;
    }

    /**
     * Reads {@code events.dividends} (amount = cash dividend per share, on the same
     * split-adjusted basis as the close column). Empty when the dividends block is missing.
     */
    private static List<CorporateEvent> parseDividends(JsonNode eventsNode) {
        List<CorporateEvent> events = new ArrayList<>();
        if (eventsNode.isMissingNode() || !eventsNode.isObject()) return events;
        JsonNode divs = eventsNode.path("dividends");
        if (divs.isObject()) {
            divs.fields().forEachRemaining(entry -> {
                JsonNode e = entry.getValue();
                long d = e.path("date").asLong(0);
                double a = e.path("amount").asDouble(0);
                if (d > 0 && a > 0) {
                    events.add(new CorporateEvent(
                            Instant.ofEpochSecond(d).atZone(ZoneOffset.UTC).toLocalDate(), a));
                }
            });
        }
        return events;
    }

    /**
     * Reads {@code events.splits}. Each entry exposes {@code numerator} / {@code denominator}
     * — a 20:1 forward split is {@code 20/1 = 20.0}, a 1:10 reverse split is {@code 1/10 = 0.1}.
     */
    private static List<SplitEvent> parseSplits(JsonNode eventsNode) {
        List<SplitEvent> events = new ArrayList<>();
        if (eventsNode.isMissingNode() || !eventsNode.isObject()) return events;
        JsonNode splits = eventsNode.path("splits");
        if (splits.isObject()) {
            splits.fields().forEachRemaining(entry -> {
                JsonNode e = entry.getValue();
                long d = e.path("date").asLong(0);
                double num = e.path("numerator").asDouble(0);
                double den = e.path("denominator").asDouble(0);
                if (d > 0 && num > 0 && den > 0) {
                    events.add(new SplitEvent(
                            Instant.ofEpochSecond(d).atZone(ZoneOffset.UTC).toLocalDate(),
                            num, den));
                }
            });
        }
        return events;
    }

    /** Internal: one dividend event from Yahoo's events block. */
    private record CorporateEvent(LocalDate date, double amount) {
    }

    /** Internal: one stock-split event. {@link #ratio} = numerator/denominator. */
    private record SplitEvent(LocalDate date, double numerator, double denominator) {
        double ratio() {
            return numerator / denominator;
        }
    }

    private static Double num(JsonNode arr, int i) {
        JsonNode n = arr.path(i);
        return n.isNumber() ? n.asDouble() : null;
    }

    /**
     * 1-minute closes for {@code yahooTicker} in {@code [from, to]}. Yahoo's chart endpoint
     * caps the 1-minute interval at ~7 days per request; callers should clamp accordingly.
     * Off-hours minutes (weekends, pre/post-market) simply aren't returned. Empty on persistent
     * failure (logged).
     */
    public List<IntradayBar> fetchIntraday(String yahooTicker, Instant from, Instant to) {
        String url = String.format(INTRADAY_URL, yahooTicker, from.getEpochSecond(), to.getEpochSecond());
        String body = get(url);
        if (body == null) {
            log.warn("Yahoo intraday fetch failed for {}", yahooTicker);
            return List.of();
        }
        try {
            return parseIntraday(yahooTicker, body);
        } catch (Exception e) {
            log.warn("Yahoo intraday parse failed for {}", yahooTicker, e);
            return List.of();
        }
    }

    List<IntradayBar> parseIntraday(String ticker, String json) throws Exception {   // package-private for tests
        JsonNode result = mapper.readTree(json).path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) return List.of();
        JsonNode r = result.get(0);

        String currency = r.path("meta").path("currency").asText(null);
        JsonNode ts = r.path("timestamp");
        JsonNode q = r.path("indicators").path("quote").path(0);
        JsonNode close = q.path("close"), vol = q.path("volume");

        List<IntradayBar> bars = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            Double c = num(close, i);
            if (c == null) continue;   // off-hours minute, no trade
            Instant t = Instant.ofEpochSecond(ts.get(i).asLong());
            Long volume = vol.path(i).isNumber() ? vol.get(i).asLong() : null;
            bars.add(new IntradayBar(ticker, t, c, volume, currency));
        }
        return bars;
    }
}
