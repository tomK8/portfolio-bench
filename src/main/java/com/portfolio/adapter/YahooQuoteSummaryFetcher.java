package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pulls a current-state fundamentals snapshot for a single ticker from Yahoo's
 * {@code v10/finance/quoteSummary} endpoint. One HTTP call returns market cap, trailing /
 * forward P/E, PEG, dividend yield, margins, balance-sheet ratios, analyst target,
 * sector, 52-week change vs S&P, moving averages, the next scheduled earnings date and a
 * few dozen other fields. Free, no key, same browser-UA dance as {@link YahooPriceFetcher}.
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
                    ",topHoldings" +
                    ",cashflowStatementHistoryQuarterly,incomeStatementHistoryQuarterly" +
                    ",balanceSheetHistoryQuarterly,earningsTrend,calendarEvents" +
                    "&crumb=%s";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    // Bootstrap host for the A1/A3 session cookies the crumb endpoint requires. fc.yahoo.com is
    // the only host that mints them for an unauthenticated EU/UK client (finance.yahoo.com only
    // sets a useless dflow cookie → getcrumb answers 401 Invalid Cookie). Its cert chain is valid
    // but no longer lists fc.yahoo.com as a SAN, so the bootstrap goes through a dedicated client
    // that validates the chain while skipping only the hostname match — see bootstrapClient().
    private static final String COOKIE_BOOTSTRAP_URL = "https://fc.yahoo.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** Yahoo's own day label, when it has given us one. */
    private static final Pattern ISO_DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

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
    private HttpClient bootstrapHttpClient; // lazy; only touched under ensureCrumb's lock

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
        // Bootstrap: hit fc.yahoo.com to populate A1/A3 cookies into the shared CookieManager.
        // Sent through the hostname-lenient client (chain still validated); response body is
        // discarded — only the Set-Cookie side effect matters. Best-effort: on failure we still
        // try the crumb fetch, which can ride cookies from a prior successful bootstrap.
        try {
            HttpRequest bootstrap = HttpRequest.newBuilder(URI.create(COOKIE_BOOTSTRAP_URL))
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT).GET().build();
            bootstrapClient().send(bootstrap, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("quoteSummary: cookie bootstrap failed ({}), attempting crumb anyway",
                    e.toString());
        }
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

    /**
     * Client for the fc.yahoo.com bootstrap only. Shares the main client's cookie store, but its
     * trust manager delegates chain validation to the JDK default via the plain (non-endpoint-
     * aware) overload — the chain is still verified against the system CAs, only the hostname
     * match that fc.yahoo.com's cert fails is skipped. Every other request in this class goes
     * through the fully-verifying {@link #client}.
     */
    private HttpClient bootstrapClient() throws Exception {
        if (bootstrapHttpClient != null) return bootstrapHttpClient;
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509ExtendedTrustManager system = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) { system = x; break; }
        }
        if (system == null) throw new IllegalStateException("No system X509ExtendedTrustManager");
        X509ExtendedTrustManager systemTm = system;
        X509ExtendedTrustManager chainOnly = new X509ExtendedTrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                     Socket socket) throws CertificateException {
                systemTm.checkServerTrusted(chain, authType); // chain check, no hostname match
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                     SSLEngine engine) throws CertificateException {
                systemTm.checkServerTrusted(chain, authType);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                systemTm.checkServerTrusted(chain, authType);
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                     Socket socket) throws CertificateException {
                systemTm.checkClientTrusted(chain, authType, socket);
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                     SSLEngine engine) throws CertificateException {
                systemTm.checkClientTrusted(chain, authType, engine);
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                systemTm.checkClientTrusted(chain, authType);
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return systemTm.getAcceptedIssuers();
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{chainOnly}, null);
        bootstrapHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cookies)
                .sslContext(ctx)
                .build();
        return bootstrapHttpClient;
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
        JsonNode th = r.path("topHoldings");
        JsonNode cfsQ = r.path("cashflowStatementHistoryQuarterly").path("cashflowStatements");
        JsonNode issQ = r.path("incomeStatementHistoryQuarterly").path("incomeStatementHistory");
        JsonNode bsQ = r.path("balanceSheetHistoryQuarterly").path("balanceSheetStatements");
        JsonNode et = r.path("earningsTrend").path("trend");
        JsonNode cal = r.path("calendarEvents").path("earnings");

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

        // TTM cash-flow inputs from financialData
        BigDecimal ocfTtm   = num(fin, "operatingCashflow");
        BigDecimal fcfTtm   = num(fin, "freeCashflow");
        BigDecimal revTtm   = num(fin, "totalRevenue");
        BigDecimal ebitdaTtm = num(fin, "ebitda");
        BigDecimal totalDebt = num(fin, "totalDebt");
        BigDecimal totalCash = num(fin, "totalCash");

        // Capex (TTM) = OCF − FCF (positive = cash outflow)
        BigDecimal capexTtm = (ocfTtm != null && fcfTtm != null)
                ? ocfTtm.subtract(fcfTtm) : null;
        // FCF Margin = FCF / Revenue
        BigDecimal fcfMarginTtm = (fcfTtm != null && revTtm != null && revTtm.signum() != 0)
                ? fcfTtm.divide(revTtm, 6, RoundingMode.HALF_UP) : null;
        // Net Debt = Total Debt − Cash
        BigDecimal netDebt = (totalDebt != null && totalCash != null)
                ? totalDebt.subtract(totalCash) : null;
        // Net Debt / EBITDA
        BigDecimal netDebtToEbitda = (netDebt != null && ebitdaTtm != null && ebitdaTtm.signum() != 0)
                ? netDebt.divide(ebitdaTtm, 6, RoundingMode.HALF_UP) : null;

        // FCF Growth YoY: compare TTM (quarters 0-3) vs prior-year TTM (quarters 4-7)
        BigDecimal fcfGrowthYoy = fcfGrowthFromQuarterly(cfsQ);

        // ROIC = NOPAT / Invested Capital, both derived from quarterly statements
        BigDecimal roic = roicFromQuarterly(issQ, bsQ);

        // Secondary fields (popup detail)
        Map<String, BigDecimal> extra = new LinkedHashMap<>();
        // Valuation
        putIf(extra, "priceToBook", num(dks, "priceToBook"));
        putIf(extra, "priceToSalesTrailing12Months", num(sd, "priceToSalesTrailing12Months"));
        putIf(extra, "enterpriseToEbitda", num(dks, "enterpriseToEbitda"));
        putIf(extra, "enterpriseValue", num(dks, "enterpriseValue"));
        // Cash flow (TTM)
        putIf(extra, "operatingCashflow", ocfTtm);
        putIf(extra, "capexTtm", capexTtm);
        putIf(extra, "freeCashflow", fcfTtm);
        putIf(extra, "fcfMarginTtm", fcfMarginTtm);
        putIf(extra, "fcfGrowthYoy", fcfGrowthYoy);
        // Quality & returns
        putIf(extra, "roic", roic);
        putIf(extra, "returnOnEquity", num(fin, "returnOnEquity"));
        putIf(extra, "returnOnAssets", num(fin, "returnOnAssets"));
        putIf(extra, "profitMargins", num(fin, "profitMargins"));
        putIf(extra, "operatingMargins", num(fin, "operatingMargins"));
        putIf(extra, "grossMargins", num(fin, "grossMargins"));
        // Balance sheet / leverage
        putIf(extra, "netDebtToEbitda", netDebtToEbitda);
        putIf(extra, "netDebt", netDebt);
        putIf(extra, "debtToEquity", num(fin, "debtToEquity"));
        putIf(extra, "totalCash", totalCash);
        putIf(extra, "totalDebt", totalDebt);
        // Income
        putIf(extra, "totalRevenue", revTtm);
        putIf(extra, "ebitda", ebitdaTtm);
        putIf(extra, "revenueGrowth", num(fin, "revenueGrowth"));
        putIf(extra, "earningsGrowth", num(fin, "earningsGrowth"));
        putForwardGrowth(extra, et);
        // Dividends
        putIf(extra, "dividendYield", num(sd, "dividendYield"));
        putIf(extra, "dividendRate", num(sd, "dividendRate"));
        putIf(extra, "payoutRatio", num(sd, "payoutRatio"));
        // Performance & technicals
        putIf(extra, "52WeekChange", num(dks, "52WeekChange"));
        putIf(extra, "SandP52WeekChange", num(dks, "SandP52WeekChange"));
        putIf(extra, "fiftyDayAverage", num(sd, "fiftyDayAverage"));
        putIf(extra, "twoHundredDayAverage", num(sd, "twoHundredDayAverage"));
        // Sentiment / float
        putIf(extra, "shortPercentOfFloat", num(dks, "shortPercentOfFloat"));
        putIf(extra, "heldPercentInstitutions", num(dks, "heldPercentInstitutions"));
        putIf(extra, "numberOfAnalystOpinions", num(fin, "numberOfAnalystOpinions"));
        putIf(extra, "sharesOutstanding", num(dks, "sharesOutstanding"));

        putFundValuation(extra, th);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sector", text(prof, "sector"));
        labels.put("industry", text(prof, "industry"));
        labels.put("recommendationKey", text(fin, "recommendationKey"));
        labels.put("country", text(prof, "country"));
        // The listing's own name. Cheap (the `price` module is already requested) and the only
        // name a row dated purely from this snapshot can carry — the market calendar is not
        // there to supply one. `longName` is the clean form across markets; `shortName` is the
        // exchange's ("BARCLAYS PLC ORD 25P", "RHEINMETALL AG                I") and is the
        // fallback only because a name is better than none.
        String name = text(price, "longName");
        labels.put("companyName", name != null ? name : text(price, "shortName"));
        putNextEarnings(labels, cal);

        return new QuoteSummary(ticker, currency, currentPrice, marketCap, trailingPe, forwardPe,
                pegRatio, beta, week52High, week52Low, targetMeanPrice, extra, labels,
                parseSectorWeights(th), parseFundHoldings(th), false);
    }

    /**
     * Forward growth estimates from the {@code earningsTrend} module, which is the only source
     * that covers a company with negative trailing earnings. Yahoo omits both {@code trailingPE}
     * and {@code financialData.earningsGrowth} for a loss-maker, which stripped two of the four
     * growth inputs from the style classifier at once (the third, P/E compression, needs a
     * positive trailing P/E it no longer has). Intel and Moderna were left with a single growth
     * metric and fell out of the value/growth split entirely as UNKNOWN.
     *
     * <p>Only the <b>{@code +1y}</b> entry — next full fiscal year — is stored. {@code 0y} is the
     * current year and largely reported already, so it mostly restates the trailing figure it
     * would be voting alongside.
     *
     * <p><b>Revenue growth is stored unconditionally; earnings growth is gated on a positive
     * earnings base.</b> That asymmetry is the point of this method. Revenue cannot be negative,
     * so its growth rate always means what it says. Yahoo computes {@code trend.growth} off
     * whatever the base year's EPS was, sign included: Moderna's {@code +1y} growth arrives as
     * <b>+0.4249</b> while its estimated EPS goes from <b>−8.58</b> to <b>−4.91</b>. The loss is
     * shrinking; earnings are not growing. Storing that verbatim would score a deeply loss-making
     * name as a grower off a number that is positive only because it is a ratio of two negatives —
     * the same negative-base trap the classifier's P/E-compression test already guards against by
     * requiring both multiples to be positive. Both endpoints must be positive here for the same
     * reason, so a loss-maker abstains rather than voting with a meaningless figure.
     */
    private static void putForwardGrowth(Map<String, BigDecimal> extra, JsonNode trend) {
        if (!trend.isArray()) return;
        JsonNode currentYear = null, nextYear = null;
        for (JsonNode e : trend) {
            String period = text(e, "period");
            if ("0y".equals(period)) currentYear = e;
            else if ("+1y".equals(period)) nextYear = e;
        }
        if (nextYear == null) return;
        putIf(extra, "forwardRevenueGrowth", num(nextYear.path("revenueEstimate"), "growth"));

        BigDecimal baseEps = currentYear == null
                ? null : num(currentYear.path("earningsEstimate"), "avg");
        BigDecimal nextEps = num(nextYear.path("earningsEstimate"), "avg");
        if (baseEps != null && baseEps.signum() > 0 && nextEps != null && nextEps.signum() > 0) {
            putIf(extra, "forwardEarningsGrowth", num(nextYear, "growth"));
        }
    }

    /**
     * Aggregate valuation multiples for a fund, from {@code topHoldings.equityHoldings} — the
     * only place a fund's P/E is available at all, since a fund has no earnings of its own. Rides
     * the {@code topHoldings} module already requested for sector look-through, so this costs
     * <b>zero extra HTTP calls</b>.
     *
     * <p><b>Yahoo ships these inverted.</b> The field named {@code priceToEarnings} arrives as
     * {@code 0.03396} for a Nasdaq-100 tracker — that is the earnings <i>yield</i>, i.e. 1/29.4.
     * Storing it verbatim would put every fund at a P/E under 1 and read as the cheapest thing in
     * the portfolio, so each is reciprocated here, once, at the edge. A non-positive value is
     * dropped rather than inverted: it cannot be a meaningful multiple and would explode.
     *
     * <p>Written into {@code extra} under {@code fund*} keys rather than new columns — the map is
     * free-form JSON on the way to SQLite, so this needs no migration. Deliberately <b>not</b>
     * stored: {@code threeYearEarningsGrowth}, which Yahoo returns null for on every fund held
     * here, leaving its scale (fraction or percent, inverted or not) unverifiable. A growth axis
     * fed by a number nobody has ever seen a value for is worse than an admitted gap.
     */
    private static void putFundValuation(Map<String, BigDecimal> extra, JsonNode th) {
        JsonNode eh = th.path("equityHoldings");
        if (eh.isMissingNode()) return;
        putIf(extra, "fundPriceToEarnings", reciprocal(num(eh, "priceToEarnings")));
        putIf(extra, "fundPriceToBook", reciprocal(num(eh, "priceToBook")));
        putIf(extra, "fundPriceToSales", reciprocal(num(eh, "priceToSales")));
        putIf(extra, "fundPriceToCashflow", reciprocal(num(eh, "priceToCashflow")));
    }

    private static BigDecimal reciprocal(BigDecimal v) {
        if (v == null || v.signum() <= 0) return null;
        return BigDecimal.ONE.divide(v, java.math.MathContext.DECIMAL64);
    }

    /**
     * Fund sector composition from the {@code topHoldings} module — present for ETFs and funds,
     * absent for ordinary stocks (yielding an empty map, which the classifier reads as "not a
     * fund"). Yahoo ships each sector as its own single-key object
     * ({@code [{"technology":{"raw":0.5782}}, …]}) with snake_case keys, and the weights sum to
     * ~1.0. Duplicate keys are summed defensively rather than last-one-wins.
     */
    private static Map<String, BigDecimal> parseSectorWeights(JsonNode th) {
        JsonNode arr = th.path("sectorWeightings");
        if (!arr.isArray()) return Map.of();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (JsonNode entry : arr) {
            for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                BigDecimal w = num(entry, key);
                if (w != null && w.signum() != 0) out.merge(key, w, BigDecimal::add);
            }
        }
        return out;
    }

    /**
     * The fund's named constituents — Yahoo publishes only the top 10, so this covers roughly
     * half of a broad index fund by weight. Used to attribute a fund's AI/Tech share beyond its
     * raw technology weight; the shortfall is why that figure is reported as a lower bound.
     */
    private static List<QuoteSummary.FundHolding> parseFundHoldings(JsonNode th) {
        JsonNode arr = th.path("holdings");
        if (!arr.isArray()) return List.of();
        List<QuoteSummary.FundHolding> out = new ArrayList<>();
        for (JsonNode h : arr) {
            String symbol = text(h, "symbol");
            BigDecimal weight = num(h, "holdingPercent");
            if (symbol != null && weight != null) {
                out.add(new QuoteSummary.FundHolding(symbol, text(h, "holdingName"), weight));
            }
        }
        return List.copyOf(out);
    }

    /**
     * FCF growth YoY using quarterly cashflow statements. Sums the most-recent 4 quarters
     * (TTM) and the prior 4 quarters, then returns (ttm − prior) / |prior|. Returns null if
     * fewer than 8 quarters are available or if any quarter's freeCashflow is missing.
     */
    private static BigDecimal fcfGrowthFromQuarterly(JsonNode stmts) {
        if (!stmts.isArray() || stmts.size() < 8) return null;
        BigDecimal ttm = BigDecimal.ZERO, prior = BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            BigDecimal v = num(stmts.get(i), "freeCashflow");
            if (v == null) return null;
            ttm = ttm.add(v);
        }
        for (int i = 4; i < 8; i++) {
            BigDecimal v = num(stmts.get(i), "freeCashflow");
            if (v == null) return null;
            prior = prior.add(v);
        }
        if (prior.signum() == 0) return null;
        return ttm.subtract(prior).divide(prior.abs(), 6, RoundingMode.HALF_UP);
    }

    /**
     * ROIC = NOPAT / Invested Capital, both on a TTM basis.
     * NOPAT = TTM EBIT × (1 − effective tax rate from last 4 quarters).
     * Invested Capital = most-recent-quarter (equity + total debt − cash).
     * Effective tax rate is clamped to [0, 0.5] to avoid distortion from one-off items.
     */
    private static BigDecimal roicFromQuarterly(JsonNode incStmts, JsonNode bsStmts) {
        if (!incStmts.isArray() || incStmts.size() < 4) return null;
        if (!bsStmts.isArray() || bsStmts.isEmpty()) return null;

        BigDecimal ttmEbit = BigDecimal.ZERO, ttmTax = BigDecimal.ZERO, ttmPretax = BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            BigDecimal ebit    = num(incStmts.get(i), "ebit");
            BigDecimal tax     = num(incStmts.get(i), "incomeTaxExpense");
            BigDecimal pretax  = num(incStmts.get(i), "incomeBeforeTax");
            if (ebit == null || tax == null || pretax == null) return null;
            ttmEbit   = ttmEbit.add(ebit);
            ttmTax    = ttmTax.add(tax);
            ttmPretax = ttmPretax.add(pretax);
        }

        JsonNode latest = bsStmts.get(0);
        BigDecimal equity    = num(latest, "totalStockholderEquity");
        BigDecimal ltDebt    = firstNonNull(num(latest, "longTermDebt"), BigDecimal.ZERO);
        BigDecimal stDebt    = firstNonNull(num(latest, "shortLongTermDebt"), BigDecimal.ZERO);
        BigDecimal bsCash    = num(latest, "cash");
        if (equity == null || bsCash == null) return null;

        BigDecimal taxRate = BigDecimal.ZERO;
        if (ttmPretax.signum() != 0) {
            taxRate = ttmTax.divide(ttmPretax, 6, RoundingMode.HALF_UP);
            if (taxRate.compareTo(BigDecimal.ZERO) < 0) taxRate = BigDecimal.ZERO;
            if (taxRate.compareTo(new BigDecimal("0.5")) > 0) taxRate = new BigDecimal("0.5");
        }
        BigDecimal nopat = ttmEbit.multiply(BigDecimal.ONE.subtract(taxRate));
        BigDecimal investedCapital = equity.add(ltDebt).add(stDebt).subtract(bsCash);
        if (investedCapital.signum() == 0) return null;
        return nopat.divide(investedCapital, 6, RoundingMode.HALF_UP);
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
     * Next scheduled earnings report, from the {@code calendarEvents} module. It rides the one
     * quoteSummary call the snapshot already makes, so the date costs no extra request and lands
     * in {@code fundamentals_snapshot} with everything else — the tab and the watchlist read it
     * from SQLite like any other cached field and never wait on Yahoo for it.
     *
     * <p>Coverage is wider than the market-wide Nasdaq calendar behind the Earnings tab, which is
     * US-only: LGEN.L, BNP.PA and TSCO.L all answer here. An instrument with no earnings at all
     * (every ETF tried) returns nothing, and nothing is what gets stored — absent, not a zero date.
     *
     * <p>Yahoo publishes {@code earningsDate} as an <b>array</b>: one entry once a date is
     * announced, two when the feed only knows a window ("some time between the 24th and the 1st").
     * Both ends are kept, because a window is a different fact from a day, and
     * {@code isEarningsDateEstimate} separates Yahoo's own guess from the company's announcement —
     * an estimated date can move by weeks, so publishing it as if it were fixed would be the same
     * mistake as absorbing an unpriced holding into a total.
     *
     * <p><b>The day comes from Yahoo's {@code fmt}, not from {@code raw}.</b> The raw epoch is the
     * scheduled <em>instant</em> — 1796155200 is 20:00 UTC, i.e. MRVL reporting after the US close
     * on 1 December — and a report scheduled late enough in the US evening lands on the following
     * UTC date. Yahoo's own label already names the trading day it belongs to; the epoch is only
     * the fallback for a payload that carries no label, the same anchor-on-the-trading-day rule
     * the Live tab's Day delta follows.
     */
    private static void putNextEarnings(Map<String, String> labels, JsonNode earnings) {
        JsonNode dates = earnings.path("earningsDate");
        if (!dates.isArray() || dates.isEmpty()) return;
        String first = isoDay(dates.get(0));
        if (first == null) return;
        labels.put("earningsDate", first);
        String last = isoDay(dates.get(dates.size() - 1));
        if (last != null && !last.equals(first)) labels.put("earningsDateEnd", last);
        JsonNode estimate = earnings.path("isEarningsDateEstimate");
        if (estimate.isBoolean()) labels.put("earningsDateEstimate", Boolean.toString(estimate.asBoolean()));
    }

    /** Yahoo's {@code fmt} day label when it is one, else its {@code raw} epoch second in UTC. */
    private static String isoDay(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String fmt = node.path("fmt").asText(null);
        if (fmt != null && ISO_DAY.matcher(fmt).matches()) return fmt;
        JsonNode raw = node.isObject() ? node.path("raw") : node;
        if (!raw.isNumber()) return null;
        return Instant.ofEpochSecond(raw.asLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
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
                               Map<String, BigDecimal> sectorWeights,
                               List<FundHolding> topHoldings,
                               boolean missing) {

        /**
         * Back-compatible form for every caller that has no fund composition to supply — which
         * is all of them outside {@link #parse}, since composition only ever comes from Yahoo.
         */
        public QuoteSummary(String symbol, String currency, BigDecimal price, BigDecimal marketCap,
                            BigDecimal trailingPe, BigDecimal forwardPe, BigDecimal pegRatio,
                            BigDecimal beta, BigDecimal week52High, BigDecimal week52Low,
                            BigDecimal targetMeanPrice, Map<String, BigDecimal> extra,
                            Map<String, String> labels, boolean missing) {
            this(symbol, currency, price, marketCap, trailingPe, forwardPe, pegRatio, beta,
                    week52High, week52Low, targetMeanPrice, extra, labels,
                    Map.of(), List.of(), missing);
        }

        public static QuoteSummary empty(String symbol) {
            return new QuoteSummary(symbol, null, null, null, null, null, null, null, null,
                    null, null, Map.of(), Map.of(), true);
        }

        /**
         * One named constituent of a fund. {@code weight} is a fraction of fund NAV (0.0804 =
         * 8.04%), in the same scale as {@link #sectorWeights}.
         */
        public record FundHolding(String symbol, String name, BigDecimal weight) {
        }
    }
}
