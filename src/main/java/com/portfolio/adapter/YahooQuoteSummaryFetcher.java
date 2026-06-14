package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulls a current-state fundamentals snapshot for a single ticker from Yahoo's
 * {@code v10/finance/quoteSummary} endpoint. One HTTP call returns market cap, trailing /
 * forward P/E, PEG, dividend yield, margins, balance-sheet ratios, analyst target,
 * sector, 52-week change vs S&P, moving averages and a few dozen other fields. Free,
 * no key, same browser-UA dance as {@link YahooPriceFetcher}.
 *
 * <p>Modules requested cover everything the Snapshot tab surfaces; unused modules cost a
 * few KB extra per response and one fewer round-trip if we add columns later. Fields are
 * extracted with {@link #num} which tolerates Yahoo's {@code {"raw": 1.23, "fmt": "1.23"}}
 * wrapping as well as bare primitives and missing nodes (returns null in all those cases).
 *
 * <p>Failures degrade — empty {@link QuoteSummary} on HTTP error, parse error, or unknown
 * ticker. Callers should not crash the snapshot for one bad ticker.
 */
public class YahooQuoteSummaryFetcher {

    private static final Logger log = LoggerFactory.getLogger(YahooQuoteSummaryFetcher.class);

    private static final String URL_FMT =
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%s" +
                    "?modules=price,summaryDetail,defaultKeyStatistics,financialData,summaryProfile" +
                    "&crumb=%s";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    private static final String COOKIE_BOOTSTRAP_URL = "https://fc.yahoo.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    // Yahoo's quoteSummary endpoint requires a session crumb + matching cookie (anti-scraping,
    // introduced ~2024). HttpClient's CookieManager stores Set-Cookie headers from the
    // bootstrap request automatically and replays them on subsequent calls to the same domain.
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(cookies)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String crumb;

    public QuoteSummary fetch(String yahooTicker) {
        try {
            ensureCrumb();
        } catch (Exception e) {
            log.warn("quoteSummary: could not obtain crumb", e);
            return QuoteSummary.empty(yahooTicker);
        }
        String url = String.format(URL_FMT,
                URLEncoder.encode(yahooTicker, StandardCharsets.UTF_8),
                URLEncoder.encode(crumb, StandardCharsets.UTF_8));
        String body = get(url);
        // One retry on 401: a stale crumb gives back Invalid Crumb; discard + re-handshake.
        if (body == null) {
            crumb = null;
            try {
                ensureCrumb();
                url = String.format(URL_FMT,
                        URLEncoder.encode(yahooTicker, StandardCharsets.UTF_8),
                        URLEncoder.encode(crumb, StandardCharsets.UTF_8));
                body = get(url);
            } catch (Exception e) {
                log.warn("quoteSummary: crumb refresh failed for {}", yahooTicker, e);
            }
        }
        if (body == null) return QuoteSummary.empty(yahooTicker);
        try {
            return parse(yahooTicker, body);
        } catch (Exception e) {
            log.warn("quoteSummary parse failed for {}", yahooTicker, e);
            return QuoteSummary.empty(yahooTicker);
        }
    }

    /** Cached after first success; refreshed lazily if a request comes back unauthorised. */
    private synchronized void ensureCrumb() throws Exception {
        if (crumb != null) return;
        // Bootstrap: hit any Yahoo endpoint to populate A1/A1S cookies into the CookieManager.
        HttpRequest bootstrap = HttpRequest.newBuilder(URI.create(COOKIE_BOOTSTRAP_URL))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT).GET().build();
        client.send(bootstrap, HttpResponse.BodyHandlers.discarding());
        // Crumb fetch — the cookie store now has what query2 needs to mint a session crumb.
        HttpRequest crumbReq = HttpRequest.newBuilder(URI.create(CRUMB_URL))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> crumbResp = client.send(crumbReq, HttpResponse.BodyHandlers.ofString());
        if (crumbResp.statusCode() != 200) {
            throw new IllegalStateException("crumb HTTP " + crumbResp.statusCode() +
                    ": " + crumbResp.body());
        }
        String c = crumbResp.body().trim();
        if (c.isEmpty()) throw new IllegalStateException("Empty crumb response from Yahoo");
        crumb = c;
        log.info("Acquired Yahoo session crumb");
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("quoteSummary HTTP {} for {}", resp.statusCode(), url);
        } catch (Exception e) {
            log.warn("quoteSummary error {}", url, e);
        }
        return null;
    }

    /** Visible for tests. */
    QuoteSummary parse(String ticker, String json) throws Exception {
        JsonNode result = mapper.readTree(json).path("quoteSummary").path("result");
        if (!result.isArray() || result.isEmpty()) return QuoteSummary.empty(ticker);
        JsonNode r = result.get(0);

        JsonNode price = r.path("price");
        JsonNode sd = r.path("summaryDetail");
        JsonNode dks = r.path("defaultKeyStatistics");
        JsonNode fin = r.path("financialData");
        JsonNode prof = r.path("summaryProfile");

        // Core columns (visible in the table)
        BigDecimal currentPrice = firstNonNull(num(price, "regularMarketPrice"), num(fin, "currentPrice"));
        BigDecimal marketCap = num(price, "marketCap");
        BigDecimal trailingPe = firstNonNull(num(sd, "trailingPE"), num(dks, "trailingPE"));
        BigDecimal forwardPe = firstNonNull(num(sd, "forwardPE"), num(dks, "forwardPE"));
        BigDecimal pegRatio = num(dks, "pegRatio");
        BigDecimal beta = firstNonNull(num(sd, "beta"), num(dks, "beta"));
        BigDecimal week52High = num(sd, "fiftyTwoWeekHigh");
        BigDecimal week52Low = num(sd, "fiftyTwoWeekLow");
        BigDecimal targetMeanPrice = num(fin, "targetMeanPrice");
        String currency = text(price, "currency");

        // Secondary fields (popup detail)
        Map<String, BigDecimal> extra = new LinkedHashMap<>();
        putIf(extra, "dividendYield", num(sd, "dividendYield"));
        putIf(extra, "dividendRate", num(sd, "dividendRate"));
        putIf(extra, "payoutRatio", num(sd, "payoutRatio"));
        putIf(extra, "priceToBook", num(dks, "priceToBook"));
        putIf(extra, "priceToSalesTrailing12Months", num(sd, "priceToSalesTrailing12Months"));
        putIf(extra, "enterpriseToEbitda", num(dks, "enterpriseToEbitda"));
        putIf(extra, "enterpriseValue", num(dks, "enterpriseValue"));
        putIf(extra, "profitMargins", num(fin, "profitMargins"));
        putIf(extra, "operatingMargins", num(fin, "operatingMargins"));
        putIf(extra, "grossMargins", num(fin, "grossMargins"));
        putIf(extra, "returnOnEquity", num(fin, "returnOnEquity"));
        putIf(extra, "returnOnAssets", num(fin, "returnOnAssets"));
        putIf(extra, "debtToEquity", num(fin, "debtToEquity"));
        putIf(extra, "totalCash", num(fin, "totalCash"));
        putIf(extra, "freeCashflow", num(fin, "freeCashflow"));
        putIf(extra, "revenueGrowth", num(fin, "revenueGrowth"));
        putIf(extra, "earningsGrowth", num(fin, "earningsGrowth"));
        putIf(extra, "52WeekChange", num(dks, "52WeekChange"));
        putIf(extra, "SandP52WeekChange", num(dks, "SandP52WeekChange"));
        putIf(extra, "fiftyDayAverage", num(sd, "fiftyDayAverage"));
        putIf(extra, "twoHundredDayAverage", num(sd, "twoHundredDayAverage"));
        putIf(extra, "shortPercentOfFloat", num(dks, "shortPercentOfFloat"));
        putIf(extra, "heldPercentInstitutions", num(dks, "heldPercentInstitutions"));
        putIf(extra, "numberOfAnalystOpinions", num(fin, "numberOfAnalystOpinions"));
        putIf(extra, "sharesOutstanding", num(dks, "sharesOutstanding"));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sector", text(prof, "sector"));
        labels.put("industry", text(prof, "industry"));
        labels.put("recommendationKey", text(fin, "recommendationKey"));
        labels.put("country", text(prof, "country"));

        return new QuoteSummary(ticker, currency, currentPrice, marketCap, trailingPe, forwardPe,
                pegRatio, beta, week52High, week52Low, targetMeanPrice, extra, labels, false);
    }

    /**
     * Unwrap Yahoo's {@code {"raw": 1.23, "fmt": "1.23"}} pattern. Returns null for missing nodes,
     * non-numeric values, or {@code "Infinity"} (which Yahoo sometimes emits for divide-by-zero).
     */
    private static BigDecimal num(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        if (n.isObject()) n = n.path("raw");
        if (n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) {
            double d = n.asDouble();
            if (Double.isFinite(d)) return BigDecimal.valueOf(d);
        }
        return null;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.isObject() ? n.path("raw").asText(null) : n.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    private static void putIf(Map<String, BigDecimal> map, String key, BigDecimal v) {
        if (v != null) map.put(key, v);
    }

    /**
     * One ticker's snapshot. Nulls mean "not provided by Yahoo for this instrument" (typical
     * for UK ETFs, bonds, ADRs). {@code missing=true} on full fetch failure so the UI can show
     * a placeholder row rather than dropping the symbol.
     */
    public record QuoteSummary(String symbol, String currency,
                               BigDecimal price, BigDecimal marketCap,
                               BigDecimal trailingPe, BigDecimal forwardPe, BigDecimal pegRatio,
                               BigDecimal beta, BigDecimal week52High, BigDecimal week52Low,
                               BigDecimal targetMeanPrice,
                               Map<String, BigDecimal> extra,
                               Map<String, String> labels,
                               boolean missing) {
        public static QuoteSummary empty(String symbol) {
            return new QuoteSummary(symbol, null, null, null, null, null, null, null, null,
                    null, null, Map.of(), Map.of(), true);
        }
    }
}
