package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls quarterly diluted EPS from SEC EDGAR's XBRL Company Facts API. Free, no auth, ~10y
 * of history for any US filer. The API hands back every concept the company has ever
 * tagged; we only care about {@code us-gaap:EarningsPerShareDiluted} in {@code USD/shares}.
 *
 * <p>Quarterly Q1–Q3 EPS come straight from 10-Q filings. Q4 is not reported as a quarter —
 * it appears only as the annual figure in the 10-K (fp=FY), so we synthesise it by
 * subtracting Q1+Q2+Q3 of the same fiscal year. Restated values (amendments, e.g. 10-Q/A)
 * are collapsed by keeping the latest {@code filed} per {@code (end, fp)} key.
 *
 * <p>SEC requires a contact User-Agent on every request and asks callers to stay under
 * 10 req/s. We're well under both. CIKs for the supported tickers are hard-coded; extending
 * to a wider set would mean pulling {@code company_tickers.json} from EDGAR.
 */
public class EdgarFundamentalsFetcher {

    private static final Logger log = LoggerFactory.getLogger(EdgarFundamentalsFetcher.class);

    private static final String FACTS_URL =
            "https://data.sec.gov/api/xbrl/companyfacts/CIK%010d.json";
    private static final String USER_AGENT =
            "PortfolioBench tomaszp@gmail.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Hyperscalers only, for now. Extend by adding rows. */
    private static final Map<String, Integer> CIK_BY_TICKER = new LinkedHashMap<>();
    static {
        CIK_BY_TICKER.put("AAPL", 320193);
        CIK_BY_TICKER.put("MSFT", 789019);
        CIK_BY_TICKER.put("GOOGL", 1652044);
        CIK_BY_TICKER.put("GOOG", 1652044);     // same issuer as GOOGL
        CIK_BY_TICKER.put("AMZN", 1018724);
        CIK_BY_TICKER.put("META", 1326801);
        CIK_BY_TICKER.put("NVDA", 1045810);
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public static List<String> supportedTickers() {
        return new ArrayList<>(CIK_BY_TICKER.keySet());
    }

    public static boolean isSupported(String ticker) {
        return ticker != null && CIK_BY_TICKER.containsKey(ticker.toUpperCase());
    }

    /**
     * One quarter's diluted EPS. Two filing dates because they answer different questions:
     *
     * @param availableFrom when the quarter first became public (earliest filing) — gates
     *                      whether it counts in a TTM at chart date D
     * @param valueFiled    the filing date of the value we kept (latest filing) — what
     *                      per-share basis the value is in. Used to pick the right
     *                      splitFactor when rescaling to today's basis.
     */
    public record EpsQuarter(LocalDate periodEnd, int fy, String fp, BigDecimal eps,
                             LocalDate availableFrom, LocalDate valueFiled) {}

    /**
     * Fetch + parse quarterly EPS for {@code ticker}. Returns sorted by {@code periodEnd}
     * ascending. Empty list on network/parse failure (logged) — callers degrade rather than
     * crash, same convention as {@link YahooPriceFetcher}.
     */
    public List<EpsQuarter> fetchQuarterlyEps(String ticker) {
        Integer cik = CIK_BY_TICKER.get(ticker == null ? "" : ticker.toUpperCase());
        if (cik == null) {
            log.warn("EDGAR: no CIK for ticker {}", ticker);
            return List.of();
        }
        String url = String.format(FACTS_URL, cik);
        String body = get(url);
        if (body == null) return List.of();
        try {
            return parse(body);
        } catch (Exception e) {
            log.warn("EDGAR parse failed for {}", ticker, e);
            return List.of();
        }
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("EDGAR HTTP {} for {}", resp.statusCode(), url);
        } catch (Exception e) {
            log.warn("EDGAR error {}", url, e);
        }
        return null;
    }

    /**
     * Visible for tests. Returns one quarterly diluted-EPS record per fiscal quarter, latest
     * filing wins.
     *
     * <p>Three traps in EDGAR XBRL make this less obvious than it looks:
     *
     * <ol>
     *   <li>{@code fy} and {@code fp} describe the <em>filing</em>, not the period — a
     *       10-Q filed in FY2018 reports the prior year's same calendar quarter as a
     *       comparative under the <em>same</em> {@code fy=2018 fp=Q3} tag. Keying by
     *       {@code (fy, fp)} silently conflates them. We key by period {@code end} instead.
     *   <li>The 10-Q for a fiscal Q2 reports both Q2-alone (~91 days) <em>and</em> H1 YTD
     *       (~183 days); Q3 also reports a 9-month YTD. We drop anything outside
     *       {@code 75..105} days.
     *   <li>The 10-K reports every fiscal quarter standalone as 90-day records tagged
     *       {@code fp=FY} (alongside the 364-day annual). Filtering on {@code fp} drops the
     *       10-K's Q4 record and forces synthesis. We ignore {@code fp} entirely and let
     *       the period length speak.
     * </ol>
     *
     * <p>Restatements (10-Q/A, comparative re-tagging in later 10-Ks) are handled by the
     * "latest filing wins per period" rule — so for split-affected tickers the value we keep
     * is the most recent (typically post-split) restatement.
     */
    List<EpsQuarter> parse(String json) throws Exception {
        JsonNode units = mapper.readTree(json)
                .path("facts").path("us-gaap")
                .path("EarningsPerShareDiluted").path("units").path("USD/shares");
        if (!units.isArray() || units.isEmpty()) return List.of();

        // Per-period bookkeeping: keep the LATEST filing's value (so restated/post-split
        // values win) but remember the EARLIEST filing's date (so {@code availableFrom} is
        // when the quarter first became public, not when it was last touched). Without this
        // a 10-K restating a 2-year-old quarter would push that quarter's availability
        // forward by 2 years and the TTM at intervening chart dates would be missing it.
        record Slot(BigDecimal latestVal, LocalDate latestFiled, LocalDate earliestFiled) {}
        Map<LocalDate, Slot> perPeriod = new HashMap<>();
        for (JsonNode n : units) {
            String form = n.path("form").asText("");
            if (!form.startsWith("10-K") && !form.startsWith("10-Q")) continue;
            String startStr = n.path("start").asText(null);
            String endStr = n.path("end").asText(null);
            String filedStr = n.path("filed").asText(null);
            String valStr = n.path("val").asText(null);
            if (startStr == null || endStr == null || filedStr == null || valStr == null) continue;

            LocalDate start = LocalDate.parse(startStr);
            LocalDate end = LocalDate.parse(endStr);
            long days = ChronoUnit.DAYS.between(start, end);
            if (days < 75 || days > 105) continue;     // keep quarterlies only

            BigDecimal val = new BigDecimal(valStr);
            LocalDate filed = LocalDate.parse(filedStr);

            Slot prev = perPeriod.get(end);
            if (prev == null) {
                perPeriod.put(end, new Slot(val, filed, filed));
            } else {
                LocalDate earliest = filed.isBefore(prev.earliestFiled) ? filed : prev.earliestFiled;
                if (filed.isAfter(prev.latestFiled)) {
                    perPeriod.put(end, new Slot(val, filed, earliest));
                } else {
                    perPeriod.put(end, new Slot(prev.latestVal, prev.latestFiled, earliest));
                }
            }
        }

        List<EpsQuarter> out = new ArrayList<>(perPeriod.size());
        for (Map.Entry<LocalDate, Slot> e : perPeriod.entrySet()) {
            out.add(new EpsQuarter(e.getKey(), 0, "",
                    e.getValue().latestVal,
                    e.getValue().earliestFiled,
                    e.getValue().latestFiled));
        }
        out.sort((a, b) -> a.periodEnd.compareTo(b.periodEnd));
        return out;
    }
}
