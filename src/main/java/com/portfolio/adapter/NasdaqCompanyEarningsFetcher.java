package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The two <em>per-company</em> halves of Nasdaq's public earnings JSON, beside the per-date calendar
 * {@link NasdaqEarningsFetcher} already reads:
 *
 * <ul>
 *   <li>{@code /api/company/{sym}/earnings-surprise} — the last four quarters as actually reported:
 *       the fiscal quarter, <b>the date it was reported on</b>, the EPS and the consensus it was
 *       measured against.</li>
 *   <li>{@code /api/analyst/{sym}/earnings-forecast} — the consensus ahead, by named fiscal period,
 *       with the number of estimates behind it and how many analysts have revised up and down over
 *       the last four weeks.</li>
 * </ul>
 *
 * <p><b>Same host, same basis, deliberately.</b> Yahoo answers all of these questions too and was
 * measured against this feed before being rejected for them: its {@code earningsTrend} module puts
 * one period's consensus in another period's block (PLTR's {@code 0q} block, labelled with the
 * quarter just reported, carries the <i>next</i> quarter's estimate), stamps a stale
 * {@code endDate} on it, publishes a literal {@code 0.0} where it has no historical point (AZN's
 * 30-, 60- and 90-day readings), and returns revision counts that contradict their own arithmetic
 * (PLTR: 13 upgrades in the last 7 days inside 1 in the last 30). Every one of those is a failure
 * this codebase already has a name for — a vendor figure that disagrees with the figures beside it,
 * and a measured zero standing in for "we do not know". Nasdaq names the fiscal period on every row
 * it publishes, which is the whole reason it is the one asked.
 *
 * <p>It also keeps <b>one EPS basis per row</b>. Nasdaq's per-company history reproduces the
 * calendar's own figures exactly — AAPL 2.05 against 1.98, AZN 2.79 against 2.60, VRTX 4.21 against
 * 4.36 — while Yahoo states 2.10, 2.79 and 4.76 for the same three quarters on its own adjusted
 * basis. A "surprise against the company's own last four quarters" assembled from both sources
 * would be comparing this quarter's beat with last quarter's measured a different way.
 *
 * <p>Three-valued like the calendar feed: a non-null (possibly empty) answer is a successful read,
 * {@code null} is a failure. The distinction is what stops a cache storing an outage as a finding.
 * Browser UA for the same reason as everywhere else here — the default Java one is refused.
 */
public class NasdaqCompanyEarningsFetcher {

    private static final Logger log = LoggerFactory.getLogger(NasdaqCompanyEarningsFetcher.class);

    static final String SURPRISE_URL = "https://api.nasdaq.com/api/company/%s/earnings-surprise";
    static final String FORECAST_URL = "https://api.nasdaq.com/api/analyst/%s/earnings-forecast";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** The feed's own date format: {@code 11/2/2026}, unpadded. */
    private static final DateTimeFormatter REPORTED = DateTimeFormatter.ofPattern("M/d/yyyy");

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * One quarter the company has already reported.
     *
     * @param reported          the day the figures came out — the anchor a price reaction is
     *                          measured around, and the reason this endpoint is worth a request at
     *                          all: no other free source states it per company
     * @param fiscalQuarterEnd  the feed's own label for the period ({@code "Sep 2026"}), kept as
     *                          text because it is one — the quarter a company calls its fourth is
     *                          not derivable from the month it ends in
     */
    public record PriorPrint(LocalDate reported, String fiscalQuarterEnd,
                             Double eps, Double consensus) {
    }

    /**
     * One period's consensus ahead. {@code up}/{@code down} are counts of analysts who moved their
     * estimate over the last four weeks — the feed states that window, which is why it can be
     * printed as a window rather than as "recently".
     */
    public record ForecastRow(String fiscalEnd, Double consensus, Integer numEstimates,
                              Integer up, Integer down) {
    }

    /** Both halves of the forecast table, each already in the feed's own order (nearest first). */
    public record Forecast(String symbol, List<ForecastRow> quarterly, List<ForecastRow> yearly) {
    }

    /**
     * The company's last four reported quarters, newest first, or null on a failed read. Rows with
     * no readable report date are dropped: the date is the only field the caller cannot do without.
     */
    public List<PriorPrint> surpriseHistory(String symbol) {
        String body = get(String.format(SURPRISE_URL, encode(symbol)), symbol, "earnings-surprise");
        if (body == null) return null;
        try {
            return parseSurprise(mapper.readTree(body));
        } catch (Exception e) {
            log.warn("Nasdaq earnings-surprise parse failed for {}", symbol, e);
            return null;
        }
    }

    /** The consensus ahead by fiscal period, or null on a failed read. */
    public Forecast forecast(String symbol) {
        String body = get(String.format(FORECAST_URL, encode(symbol)), symbol, "earnings-forecast");
        if (body == null) return null;
        try {
            return parseForecast(mapper.readTree(body), symbol);
        } catch (Exception e) {
            log.warn("Nasdaq earnings-forecast parse failed for {}", symbol, e);
            return null;
        }
    }

    // ---- parsing (static + pure, so it can be pinned without a network) --------------------

    static List<PriorPrint> parseSurprise(JsonNode root) {
        List<PriorPrint> out = new ArrayList<>();
        JsonNode rows = root.path("data").path("earningsSurpriseTable").path("rows");
        if (!rows.isArray()) return out;
        for (JsonNode row : rows) {
            LocalDate reported = parseReported(NasdaqEarningsFetcher.text(row, "dateReported"));
            if (reported == null) continue;
            String fiscal = NasdaqEarningsFetcher.text(row, "fiscalQtrEnd");
            out.add(new PriorPrint(reported,
                    fiscal == null || fiscal.isBlank() ? null : fiscal.trim(),
                    NasdaqEarningsFetcher.parseNumber(NasdaqEarningsFetcher.text(row, "eps")),
                    NasdaqEarningsFetcher.parseNumber(
                            NasdaqEarningsFetcher.text(row, "consensusForecast"))));
        }
        return out;
    }

    static Forecast parseForecast(JsonNode root, String symbol) {
        JsonNode data = root.path("data");
        return new Forecast(symbol,
                parseForecastRows(data.path("quarterlyForecast").path("rows")),
                parseForecastRows(data.path("yearlyForecast").path("rows")));
    }

    private static List<ForecastRow> parseForecastRows(JsonNode rows) {
        List<ForecastRow> out = new ArrayList<>();
        if (!rows.isArray()) return out;
        for (JsonNode row : rows) {
            String fiscalEnd = NasdaqEarningsFetcher.text(row, "fiscalEnd");
            if (fiscalEnd == null || fiscalEnd.isBlank()) continue; // the period is the key
            out.add(new ForecastRow(fiscalEnd.trim(),
                    NasdaqEarningsFetcher.parseNumber(
                            NasdaqEarningsFetcher.text(row, "consensusEPSForecast")),
                    NasdaqEarningsFetcher.parseInt(NasdaqEarningsFetcher.text(row, "noOfEstimates")),
                    NasdaqEarningsFetcher.parseInt(NasdaqEarningsFetcher.text(row, "up")),
                    NasdaqEarningsFetcher.parseInt(NasdaqEarningsFetcher.text(row, "down"))));
        }
        return out;
    }

    static LocalDate parseReported(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), REPORTED);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- transport -------------------------------------------------------------------------

    private String get(String url, String symbol, String what) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = BoundedHttp.send(client, req);
            if (res.statusCode() != 200) {
                log.warn("Nasdaq {} for {} returned HTTP {}", what, symbol, res.statusCode());
                return null;
            }
            return res.body();
        } catch (Exception e) {
            log.warn("Nasdaq {} fetch failed for {}: {}", what, symbol, e.toString());
            return null;
        }
    }

    private static String encode(String symbol) {
        return java.net.URLEncoder.encode(symbol == null ? "" : symbol.trim().toUpperCase(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
