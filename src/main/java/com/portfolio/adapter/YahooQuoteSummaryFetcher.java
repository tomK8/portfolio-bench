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
 * <p><b>The feed states no period and no accounting basis, and is not internally consistent
 * about either.</b> Nothing here can be assumed TTM because its neighbour is. Within the one
 * {@code financialData} module, {@code profitMargins}, {@code grossMargins} and
 * {@code ebitdaMargins} are TTM while {@code operatingMargins} is the <b>most recent quarter</b> —
 * measured on AVGO 2026-10-06, where 0.54307 × 29.591B (the latest quarter's revenue) returns
 * that quarter's operating income to five decimals. So a field is surfaced here only once its
 * period is known, and a field whose period cannot be established does not get a column at
 * all: this screen exists to compare symbols, so one that means different things in different
 * rows is worse than an absent one. See docs/fundamentals.md for the per-field audit.
 *
 * <p>The three {@code *HistoryQuarterly} modules are deliberately <b>not</b> requested. Yahoo
 * still answers them, but hollowed out — the income statement keeps only {@code totalRevenue}
 * and {@code netIncome}, while {@code grossProfit}, {@code ebit} and {@code incomeTaxExpense}
 * come back as a literal {@code 0} and the balance-sheet and cash-flow statements are entirely
 * null. The ROIC and FCF-growth-YoY derivations that read them were removed in favour of no
 * column; those zeros made them a standing "absent is never zero" hazard, one non-null
 * {@code incomeBeforeTax} away from publishing a NOPAT of 0 as a measured return.
 *
 * <p>Failures degrade — empty {@link QuoteSummary} on HTTP error, parse error, or unknown
 * ticker. Callers should not crash the snapshot for one bad ticker.
 */
public class YahooQuoteSummaryFetcher {

    private static final Logger log = LoggerFactory.getLogger(YahooQuoteSummaryFetcher.class);

    private static final String URL_FMT =
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%s" +
                    "?modules=price,summaryDetail,defaultKeyStatistics,financialData,summaryProfile" +
                    ",topHoldings,earningsTrend,calendarEvents" +
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
    /**
     * How near a forward EPS must sit to one fiscal year's consensus — as a fraction of the gap
     * between the two candidate years — before that year is named. See {@link #putForwardPeriod}.
     */
    private static final BigDecimal MATCH_BAND = new BigDecimal("0.2");

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
        // The currency the *statements* are filed in, which is not always the one the line is
        // quoted in (ASML quotes USD and files EUR; AZN.L quotes GBp and files USD). Every
        // ratio below that divides a market-side figure by a statement-side one is only
        // meaningful when these two agree — see comparable() and docs/fundamentals.md.
        String finCurrency = text(fin, "financialCurrency");
        boolean comparable = comparable(currency, finCurrency);
        Map<String, String> excluded = new LinkedHashMap<>();

        // TTM cash-flow inputs from financialData
        BigDecimal ocfTtm   = num(fin, "operatingCashflow");
        BigDecimal fcfTtm   = num(fin, "freeCashflow");
        BigDecimal revTtm   = num(fin, "totalRevenue");
        BigDecimal ebitdaTtm = num(fin, "ebitda");
        BigDecimal totalDebt = num(fin, "totalDebt");
        BigDecimal totalCash = num(fin, "totalCash");

        // No capex. It used to be derived as OCF - freeCashflow, which is not capital
        // expenditure: Yahoo's freeCashflow is its own modelled *levered* free cash flow, not
        // the cash-flow statement's OCF minus purchases of property, plant and equipment. The
        // residual is therefore a quantity with no name, and measurably so - on a live fetch of
        // 68 symbols it came out *negative* for 5 of the 52 that carried both sides (BRK.B
        // -25.34bn, NFLX -13.42bn, LGEN -5.46bn, SNPS -0.49bn, MRVL -0.21bn), because Yahoo's
        // FCF exceeds its own OCF there. A negative capital expenditure is not a measurement,
        // and no sign convention repairs it: for ORCL and BABA the same subtraction exceeds OCF
        // entirely (93bn against 47bn, 161bn against 78bn) because their levered FCF is
        // negative while operating cash flow is strongly positive. The feed carries no capital
        // expenditure anywhere now that the quarterly cash-flow statement comes back null, so
        // the column is gone rather than relabelled - see docs/fundamentals.md.
        //
        // The margin keeps Yahoo's numerator and is labelled for what that numerator is. Both
        // sides are statement-side figures from one module, so it needs no currency gate.
        BigDecimal fcfMarginTtm = (fcfTtm != null && revTtm != null && revTtm.signum() != 0)
                ? fcfTtm.divide(revTtm, 6, RoundingMode.HALF_UP) : null;
        // Net Debt = Total Debt − Cash
        BigDecimal netDebt = (totalDebt != null && totalCash != null)
                ? totalDebt.subtract(totalCash) : null;
        // Net Debt / EBITDA. Both sides come from financialData, so they share a currency
        // whatever the line is quoted in — this one needs no gate.
        BigDecimal netDebtToEbitda = (netDebt != null && ebitdaTtm != null && ebitdaTtm.signum() != 0)
                ? netDebt.divide(ebitdaTtm, 6, RoundingMode.HALF_UP) : null;

        // P/B is recomputed rather than read, because Yahoo's own priceToBook is literally
        // price / bookValue with no unit handling — and for a pence-quoted line that divides
        // pence by pounds, inflating every UK ratio exactly 100x. It published REL at 3676
        // (really 36.8), BARC at 92.6 (0.93) and BLND at 66.6 (0.67) — and P/B is the primary
        // yardstick for precisely the banks and REITs it broke. bookValue is present whenever
        // priceToBook is (61 of 61 observed), so there is one path and no fallback.
        BigDecimal bookValue = num(dks, "bookValue");
        BigDecimal priceToBook = (currentPrice != null && bookValue != null
                && bookValue.signum() != 0)
                ? currentPrice.divide(minorUnitsPerMajor(currency), 10, RoundingMode.HALF_UP)
                        .divide(bookValue, 6, RoundingMode.HALF_UP)
                : null;

        // Secondary fields (popup detail)
        Map<String, BigDecimal> extra = new LinkedHashMap<>();
        // Valuation. These three divide a market-side figure (price, market cap, enterprise
        // value) by a statement-side one (book value, revenue, EBITDA), so each is gated on the
        // two sides sharing a currency; a gated field is recorded by name with its reason rather
        // than quietly dropped.
        putOrExclude(extra, excluded, "priceToBook", priceToBook, comparable, currency, finCurrency);
        putOrExclude(extra, excluded, "priceToSalesTrailing12Months",
                num(sd, "priceToSalesTrailing12Months"), comparable, currency, finCurrency);
        // EBITDA at or below zero makes the ratio meaningless *and* actively misleading: a
        // negative EV/EBITDA sorts to the top of a cheapest-first scan.
        BigDecimal evEbitda = num(dks, "enterpriseToEbitda");
        if (ebitdaTtm != null && ebitdaTtm.signum() <= 0) {
            excluded.put("enterpriseToEbitda", "EBITDA is not positive, so the ratio has no scale");
        } else {
            putOrExclude(extra, excluded, "enterpriseToEbitda", evEbitda,
                    comparable, currency, finCurrency);
        }
        // Enterprise value is gated on the same evidence as the EV/EBITDA above it, because
        // Yahoo builds it by *adding* two currencies rather than dividing them. Measured on the
        // 2026-10-14 fetch: NVO's EV of 263,676,936,192 against a market cap of
        // 168,536,670,208 (USD) plus a net debt of 95,144,992,768 (DKK) = 263,681,662,976 —
        // agreement to 0.002%, with the kroner added to the dollars unconverted. AZN, BP. and
        // SKHY reproduce the same sum within 1%. On some rows it is worse than mis-denominated:
        // ASML's EV is 36.65T against its own 658bn market cap, and TSM's is +16.03T on a net
        // *cash* position. No currency makes any of that true, so the amount is withheld with
        // its reason rather than published under a currency symbol it does not belong to.
        putOrExclude(extra, excluded, "enterpriseValue", num(dks, "enterpriseValue"), comparable,
                "Yahoo builds enterprise value by adding net debt in " + finCurrency
                        + " to a market cap in " + currency + " without converting either, so "
                        + "this amount is a sum of two currencies");
        // The P/B denominator, kept so the recomputed ratio is auditable on the row itself —
        // the same reason netIncomeToCommon is stored beside the trailing P/E. Gated with the
        // ratio it is the denominator of: when the statements are in another currency the P/B
        // above is withheld, and a denominator with no ratio to reproduce audits nothing while
        // still asserting a currency the figure is not in. It cannot be rescued by relabelling
        // it either — on a mismatched row it reconciles in neither currency: ASML's 1.1414
        // against 384.1m shares implies 438m of equity, while the debtToEquity on the same row
        // puts 1.98bn of debt at 9.09% of equity, i.e. 21.8bn. And it is not even stable —
        // AAPL's 7.36 was identical across the 13 and 14 October fetches while ASML's moved
        // 1.1457 -> 1.1414, which a filed balance-sheet figure does not do.
        putOrExclude(extra, excluded, "bookValue", bookValue, comparable,
                "book value per share is filed in " + finCurrency + " while this line is quoted "
                        + "in " + currency + ", so it is not the figure the price divides by — "
                        + "the P/B it is the denominator of is withheld on the same evidence");
        // Cash flow (TTM)
        putIf(extra, "operatingCashflow", ocfTtm);
        putIf(extra, "freeCashflow", fcfTtm);
        putIf(extra, "fcfMarginTtm", fcfMarginTtm);
        // Quality & returns. No operatingMargins: Yahoo's is the latest quarter, not the TTM
        // the rest of this block is on, and the feed carries no TTM operating income to put in
        // its place now that the quarterly statements are hollow.
        putIf(extra, "returnOnEquity", num(fin, "returnOnEquity"));
        putIf(extra, "returnOnAssets", num(fin, "returnOnAssets"));
        putIf(extra, "profitMargins", num(fin, "profitMargins"));
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
        // TTM net income attributable to common shareholders, on a GAAP basis. Shown beside
        // revenue and EBITDA to complete the income block, and it is also what makes the
        // trailing P/E auditable: AVGO's trailingEps of 7.83 into this 38.265B implies 4.887B
        // diluted shares, which is the check that says the trailing side is GAAP. The forward
        // side is consensus and is not.
        putIf(extra, "netIncomeToCommon", num(dks, "netIncomeToCommon"));

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
        // Provenance. All three ride the call we already make and were previously discarded,
        // which is why "as of when, in what currency" could only ever be claimed by a column
        // heading for every row at once instead of answered per row.
        labels.put("financialCurrency", finCurrency);
        labels.put("mostRecentQuarter", epochDay(dks, "mostRecentQuarter"));
        labels.put("lastFiscalYearEnd", epochDay(dks, "lastFiscalYearEnd"));
        // Excluded, and said so: one entry per gated field, keyed "excluded:<field>".
        excluded.forEach((k, why) -> labels.put("excluded:" + k, why));
        putNextEarnings(labels, cal);
        putForwardPeriod(labels, et, num(dks, "forwardEps"));

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
     * <b>Which fiscal year the forward P/E is built on</b>, read off the feed rather than
     * assumed. Nothing in {@code summaryDetail} or {@code defaultKeyStatistics} names the period
     * behind {@code forwardEps}, and "next year" is not a safe reading of it: the year
     * {@code earningsTrend} calls {@code 0y} is the one <em>in progress</em>, and the forward EPS
     * is the one after that. Measured on a live fetch (2026-10-06), {@code forwardEps} reproduced
     * the {@code +1y} consensus on 11 of 13 symbols carrying both estimates, exactly — KLAC
     * 6.74039, MSFT 23.67732, NVDA 15.9146, TSLA 2.14567 — so KLAC's forward multiple is priced
     * on the year ending <b>June 2028</b> while its last reported one ended June 2026. How far out
     * that is varies with the fiscal calendar and the time of year, which is precisely why the
     * screen has to say it instead of leaving the reader to assume twelve months.
     *
     * <p>The period is therefore chosen by <b>reconciliation</b> — the device the operating line
     * already uses on revenue tags — not by a rule about which entry Yahoo "should" have used:
     * whichever year's consensus EPS reproduces {@code forwardEps} is the year it was built from.
     * The two modules refresh on their own clocks, so the match is not always exact (AMZN was
     * 0.58% off its {@code +1y}, META 1.35%, TSM 0.57%), and the test is a <b>separation</b> one
     * rather than an absolute tolerance: the figure must sit within {@link #MATCH_BAND} of the gap
     * between the two years' estimates. A forward EPS in the middle ground names no year at all,
     * which is the honest answer — the two candidates are a year of growth apart (KLAC 5.44
     * against 6.74), so anything equidistant cannot be attributed to either.
     */
    private static void putForwardPeriod(Map<String, String> labels, JsonNode trend,
                                         BigDecimal forwardEps) {
        if (forwardEps == null || !trend.isArray()) return;
        JsonNode currentYear = null, nextYear = null;
        for (JsonNode e : trend) {
            String period = text(e, "period");
            if ("0y".equals(period)) currentYear = e;
            else if ("+1y".equals(period)) nextYear = e;
        }
        if (currentYear == null || nextYear == null) return;

        BigDecimal thisYear = num(currentYear.path("earningsEstimate"), "avg");
        BigDecimal afterThat = num(nextYear.path("earningsEstimate"), "avg");
        if (thisYear == null || afterThat == null) return;

        BigDecimal separation = afterThat.subtract(thisYear).abs();
        if (separation.signum() == 0) return;         // two identical candidates name no year
        BigDecimal toThisYear = forwardEps.subtract(thisYear).abs();
        BigDecimal toAfterThat = forwardEps.subtract(afterThat).abs();
        boolean later = toAfterThat.compareTo(toThisYear) < 0;
        BigDecimal nearest = later ? toAfterThat : toThisYear;
        if (nearest.compareTo(separation.multiply(MATCH_BAND)) > 0) return;

        String end = text(later ? nextYear : currentYear, "endDate");
        if (end != null && ISO_DAY.matcher(end).matches()) {
            labels.put("forwardEpsPeriodEnd", end);
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
     * Whether a market-side figure and a statement-side figure may be divided by one another.
     *
     * <p>True when the line is quoted in the same currency it files in. {@code GBp} and
     * {@code GBP} count as the same currency — a London line quoted in pence files in pounds,
     * which is a *scale* difference handled by {@link #minorUnitsPerMajor}, not a currency one.
     * A missing {@code financialCurrency} is treated as comparable: funds and trusts file no
     * statements at all, and the fields this gates are absent for them anyway, so refusing on
     * absence would report an exclusion nobody asked about.
     */
    private static boolean comparable(String quoteCurrency, String financialCurrency) {
        if (quoteCurrency == null || financialCurrency == null) return true;
        return major(quoteCurrency).equalsIgnoreCase(major(financialCurrency));
    }

    /** The major unit a minor-unit quote belongs to — {@code GBp} prices a {@code GBP} company. */
    private static String major(String currency) {
        return "GBp".equals(currency) ? "GBP" : currency;
    }

    /**
     * How many of the quote's units make one unit of everything else in the payload. London
     * lines quote in pence while market cap, book value, enterprise value and every statement
     * figure are in pounds, so a price has to be scaled before it meets any of them.
     */
    private static BigDecimal minorUnitsPerMajor(String quoteCurrency) {
        return "GBp".equals(quoteCurrency) ? new BigDecimal("100") : BigDecimal.ONE;
    }

    /**
     * Store a gated field, or record why it was withheld. Keeps "excluded, and said so" in one
     * place: a reader can tell a ratio we could not trust from one the feed never sent, which a
     * bare absence cannot express.
     */
    private static void putOrExclude(Map<String, BigDecimal> extra, Map<String, String> excluded,
                                     String key, BigDecimal value, boolean comparable,
                                     String quoteCurrency, String financialCurrency) {
        putOrExclude(extra, excluded, key, value, comparable,
                "statements are in " + financialCurrency + " but the line is quoted in "
                        + quoteCurrency + ", so this ratio divides two currencies");
    }

    /**
     * The same gate for a field whose reason is not "this ratio divides two currencies" — an
     * <i>amount</i> Yahoo itself built by adding two currencies together, say. The test is the
     * same one; only the sentence the panel shows differs.
     */
    private static void putOrExclude(Map<String, BigDecimal> extra, Map<String, String> excluded,
                                     String key, BigDecimal value, boolean comparable,
                                     String reason) {
        if (value == null) return;
        if (comparable) {
            extra.put(key, value);
            return;
        }
        excluded.put(key, reason);
    }

    /**
     * An epoch-second date field as an ISO day, or null. Yahoo sends these as
     * {@code {"raw": 1785628800, "fmt": "2026-07-31"}}; the raw seconds are authoritative and
     * the {@code fmt} is localised, so the day is derived rather than read.
     */
    private static String epochDay(JsonNode parent, String field) {
        BigDecimal secs = num(parent, field);
        if (secs == null || secs.signum() <= 0) return null;
        return Instant.ofEpochSecond(secs.longValue()).atZone(ZoneOffset.UTC)
                .toLocalDate().toString();
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
