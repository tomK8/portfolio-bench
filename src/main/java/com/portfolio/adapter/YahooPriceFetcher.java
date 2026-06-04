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
        JsonNode adj = r.path("indicators").path("adjclose").path(0).path("adjclose");
        JsonNode open = q.path("open"), high = q.path("high"),
                low = q.path("low"), close = q.path("close"), vol = q.path("volume");

        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            Double c = num(close, i);
            if (c == null) continue;          // holiday / gap row
            Double ac = num(adj, i);
            if (ac == null) ac = c;           // fall back to unadjusted if adjclose absent
            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong() + gmtOffset)
                    .atZone(ZoneOffset.UTC).toLocalDate();
            Long volume = vol.path(i).isNumber() ? vol.get(i).asLong() : null;
            bars.add(new PriceBar(ticker, date, num(open, i), num(high, i),
                    num(low, i), c, ac, volume, currency));
        }
        return bars;
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
