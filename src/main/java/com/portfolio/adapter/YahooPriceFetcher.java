package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
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

    /** Probe window — long enough that a quiet week or a holiday still yields a bar. */
    private static final int PROBE_LOOKBACK_DAYS = 30;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Daily bars for {@code [start, end]} inclusive; empty list on persistent failure (logged). */
    public List<PriceBar> fetch(String yahooTicker, LocalDate start, LocalDate end) {
        long p1 = start.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = String.format(CHART_URL, urlTicker(yahooTicker), p1, p2);

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

    /**
     * Percent-encodes the characters Yahoo tickers can contain that are illegal in a URI path.
     * Index tickers start with {@code ^} (e.g. {@code ^NDX}, {@code ^IRX}), which
     * {@link URI#create} rejects outright; everything else we fetch is URI-safe as-is.
     */
    static String urlTicker(String yahooTicker) {   // package-private for tests
        return yahooTicker.replace("^", "%5E");
    }

    private String get(String url) {
        Response resp = request(url);
        return resp.status() == 200 ? resp.body() : null;
    }

    /**
     * One GET, retried once. Unlike {@link #get} the body is kept on a non-200 as well: Yahoo
     * answers an unknown ticker with a 404 carrying a JSON error description, and that is exactly
     * what separates "the ticker is wrong" from "Yahoo is unreachable" — a distinction
     * {@link #probe} has to report rather than collapse into an empty list. A 404 is deterministic,
     * so it is not retried.
     */
    private Response request(String url) {
        Response last = new Response(0, null);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return new Response(200, resp.body());
                log.warn("Yahoo HTTP {} for {} (attempt {})", resp.statusCode(), url, attempt);
                last = new Response(resp.statusCode(), resp.body());
                if (resp.statusCode() == 404) return last;
            } catch (Exception e) {
                log.warn("Yahoo error {} (attempt {})", url, attempt, e);
                last = new Response(last.status(), null);
            }
        }
        return last;
    }

    private record Response(int status, String body) {
    }

    /**
     * Ask Yahoo whether a ticker resolves, before it is saved as a mapping. One chart call over a
     * short window; the answer carries the listing's name, currency, exchange and last close.
     *
     * <p>Those context fields are the point. A mapping that resolves to nothing is easy to spot
     * later (the position simply never prices), but a mapping that resolves to the <em>wrong live
     * listing</em> is not: unmapped {@code BLND} (British Land, LSE, ~440p) hits a live NYSE ticker
     * near $1.50 and would price the holding wrongly rather than not at all. Showing the name and
     * currency at entry time is what catches that shape.
     */
    public TickerProbe probe(String yahooTicker) {
        String tk = yahooTicker == null ? "" : yahooTicker.trim();
        if (tk.isEmpty()) return TickerProbe.failed(tk, true, "Enter a Yahoo ticker to check.");

        LocalDate end = LocalDate.now();
        long p1 = end.minusDays(PROBE_LOOKBACK_DAYS).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Response resp = request(String.format(CHART_URL, urlTicker(tk), p1, p2));

        if (resp.body() == null) {
            return TickerProbe.failed(tk, false, "Could not reach Yahoo"
                    + (resp.status() > 0 ? " (HTTP " + resp.status() + ")" : "") + " — try again.");
        }
        try {
            JsonNode chart = mapper.readTree(resp.body()).path("chart");
            JsonNode result = chart.path("result");
            if (!result.isArray() || result.isEmpty()) {
                String desc = chart.path("error").path("description").asText(null);
                return TickerProbe.failed(tk, true,
                        desc != null && !desc.isBlank() ? desc : "Yahoo has no listing for " + tk + ".");
            }
            JsonNode meta = result.get(0).path("meta");
            List<PriceBar> bars = parse(tk, resp.body());
            PriceBar last = bars.isEmpty() ? null : bars.get(bars.size() - 1);
            String name = firstText(meta, "longName", "shortName");
            String exchange = firstText(meta, "fullExchangeName", "exchangeName");
            return new TickerProbe(
                    !bars.isEmpty(), true, tk, name, firstText(meta, "currency"), exchange,
                    last == null ? null : BigDecimal.valueOf(last.close())
                            .setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros(),
                    last == null ? null : last.date(),
                    bars.size(),
                    bars.isEmpty()
                            ? "Yahoo knows " + tk + " but returned no recent prices — check it still trades."
                            : null);
        } catch (Exception e) {
            log.warn("Yahoo probe parse failed for {}", tk, e);
            return TickerProbe.failed(tk, true, "Could not read Yahoo's answer for " + tk + ".");
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * The outcome of a {@link #probe}. {@code found} means the ticker resolved <em>and</em> has
     * recent prices — i.e. saving it as a mapping will actually price the position. {@code reachable}
     * separates a rejected ticker from an unreachable Yahoo, so the dashboard can offer "save
     * anyway" for the second and not the first.
     */
    public record TickerProbe(boolean found, boolean reachable, String ticker, String name,
                              String currency, String exchange, BigDecimal lastClose,
                              LocalDate lastDate, int bars, String message) {

        static TickerProbe failed(String ticker, boolean reachable, String message) {
            return new TickerProbe(false, reachable, ticker, null, null, null, null, null, 0, message);
        }
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
        String url = String.format(INTRADAY_URL, urlTicker(yahooTicker),
                from.getEpochSecond(), to.getEpochSecond());
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
