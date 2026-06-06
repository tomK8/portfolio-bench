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
                    num(low, i), c, c, volume, currency));
        }
        if (bars.isEmpty()) return bars;

        List<CorporateEvent> events = parseEvents(r.path("events"));
        if (events.isEmpty()) return bars;

        return applyAdjustments(bars, events);
    }

    /**
     * Walks bars and events backward in lockstep to derive total-return adj_close. For each
     * event encountered (i.e. one whose date falls strictly after the current bar's date but
     * not after any later bar we've already passed), update a multiplicative factor:
     *
     * <ul>
     *   <li>Split with ratio R (R-for-1): factor /= R — for bars on or before the split, prices
     *       were R× higher per share than today's share count, so scale down.</li>
     *   <li>Dividend with amount D: factor *= (prevClose − D_original) / prevClose. {@code prevClose}
     *       is the raw close on the trading day immediately before the ex-date (= the current bar).
     *       {@code D_original} is the dividend amount on that day's share basis, which equals
     *       Yahoo's reported {@code D} divided by the running {@code splitFactor} (Yahoo reports
     *       dividends on the current-share basis, so any split between the dividend and today
     *       has to be unwound before comparing to the raw historical close).</li>
     * </ul>
     */
    private static List<PriceBar> applyAdjustments(List<PriceBar> bars, List<CorporateEvent> events) {
        events.sort((a, b) -> a.date.compareTo(b.date));
        double splitFactor = 1.0;
        double divFactor = 1.0;
        int eventIdx = events.size() - 1;
        List<PriceBar> out = new ArrayList<>(bars.size());
        // Build the output in reverse, then flip — simpler than mutating PriceBar (it's a record).
        PriceBar[] reversed = new PriceBar[bars.size()];
        for (int barIdx = bars.size() - 1; barIdx >= 0; barIdx--) {
            PriceBar bar = bars.get(barIdx);
            while (eventIdx >= 0 && events.get(eventIdx).date.isAfter(bar.date())) {
                CorporateEvent e = events.get(eventIdx);
                if (e.isSplit()) {
                    if (e.amount > 0) splitFactor /= e.amount;
                } else {
                    double dOriginal = splitFactor > 0 ? e.amount / splitFactor : e.amount;
                    if (bar.close() > 0) {
                        divFactor *= (bar.close() - dOriginal) / bar.close();
                    }
                }
                eventIdx--;
            }
            double factor = splitFactor * divFactor;
            reversed[barIdx] = new PriceBar(bar.symbol(), bar.date(), bar.open(), bar.high(),
                    bar.low(), bar.close(), bar.close() * factor, bar.volume(), bar.currency());
        }
        for (PriceBar pb : reversed) out.add(pb);
        return out;
    }

    /**
     * Reads {@code events.dividends} (amount = cash dividend per share, current-share basis)
     * and {@code events.splits} (amount = split ratio, e.g. 4 for a 4-for-1) into a flat list.
     * Empty when the events block is missing.
     */
    private static List<CorporateEvent> parseEvents(JsonNode eventsNode) {
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
                            Instant.ofEpochSecond(d).atZone(ZoneOffset.UTC).toLocalDate(), a, false));
                }
            });
        }
        JsonNode splits = eventsNode.path("splits");
        if (splits.isObject()) {
            splits.fields().forEachRemaining(entry -> {
                JsonNode e = entry.getValue();
                long d = e.path("date").asLong(0);
                double a = e.path("numerator").asDouble(0);
                double denom = e.path("denominator").asDouble(1);
                double ratio = denom > 0 ? a / denom : a;
                if (d > 0 && ratio > 0) {
                    events.add(new CorporateEvent(
                            Instant.ofEpochSecond(d).atZone(ZoneOffset.UTC).toLocalDate(), ratio, true));
                }
            });
        }
        return events;
    }

    /** Internal: one dividend or split event from Yahoo's events block. */
    private record CorporateEvent(LocalDate date, double amount, boolean isSplit) {
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
