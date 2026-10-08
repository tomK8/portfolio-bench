package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.EdgarXbrlFetcher.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Diluted EPS as the filer tagged it, from SEC XBRL's {@code companyconcept} endpoint —
 * {@code us-gaap:EarningsPerShareDiluted} in {@code USD/shares}. Free, no auth, ~18 years of
 * history for a long-standing US filer. Feeds the P/E-history chart, which needs a trailing
 * twelve months as of every past date, so what comes back here is <b>every period the filer
 * tagged</b> — quarters, nine-month year-to-dates and the annual — and {@link
 * com.portfolio.domain.FiledPeriods} decides which of them may be added together.
 *
 * <p><b>Nothing here is a maintained list.</b> CIKs resolve through {@link CikRegistry} (SEC's
 * own {@code company_tickers.json}), as they do for the earnings proxy and the operating line.
 * A hard-coded map of seven hyperscalers used to decide which tickers the chart would offer; it
 * could only ever be out of date, and it kept the whole semiconductor complex — AMAT, AMD, KLAC,
 * LRCX, MU, QCOM, TXN, every one of them with a decade or more of filed EPS — off a screen that
 * was already able to draw them.
 *
 * <p><b>companyconcept, not companyfacts.</b> The facts endpoint returns every concept a company
 * has ever tagged (megabytes); one concept is 20–45KB. Same reasoning, and same endpoint, as
 * {@link EdgarXbrlFetcher} — these two adapters read different concepts for different panels and
 * keep their own parsers, because this one needs each fact's <b>two</b> filing dates and that one
 * does not.
 *
 * <p><b>Why both filing dates.</b> A later filing restates an earlier quarter — in an amendment,
 * or as a comparative in a 10-K. The <em>value</em> to keep is the latest one (post-split,
 * post-restatement); the date the quarter <em>became public</em> is the earliest. Using the
 * restatement date for both would hide a two-year-old quarter from every chart point in between
 * and the P/E line would dip through a window where the market plainly knew the number.
 *
 * <p>Failures degrade: an empty history carrying the {@link Status} that says why, never an
 * exception. SEC requires a contact User-Agent and asks callers to stay under 10 req/s; the chart
 * fetches one symbol at a time and caches for a day.
 */
public class EdgarFundamentalsFetcher {

    private static final Logger log = LoggerFactory.getLogger(EdgarFundamentalsFetcher.class);

    private static final String CONCEPT_URL =
            "https://data.sec.gov/api/xbrl/companyconcept/CIK%010d/us-gaap/%s.json";
    private static final String CONCEPT = "EarningsPerShareDiluted";
    /** A per-share figure is filed in this unit; a {@code pure} or bare {@code USD} copy is not it. */
    private static final String UNIT = "USD/shares";
    private static final String USER_AGENT = "PortfolioBench tomaszp@gmail.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final CikRegistry registry;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public EdgarFundamentalsFetcher(CikRegistry registry) {
        this.registry = registry;
    }

    /**
     * One filed EPS figure, over the window the filer tagged it with.
     *
     * @param availableFrom when the figure first became public (earliest filing) — what gates it
     *                      into the trailing twelve months at a past chart date
     * @param valueFiled    the filing date of the value kept (latest filing) — which per-share
     *                      basis it is in, so the split rescaling pivots on the right factor
     */
    public record EpsFact(LocalDate start, LocalDate periodEnd, BigDecimal eps,
                          LocalDate availableFrom, LocalDate valueFiled) {}

    /**
     * What one symbol's EPS history came back as. The four silences are the same four the
     * operating line tells apart, and for the same reason: "this filer does not report in
     * us-gaap", "SEC lists no filer under this ticker" and "EDGAR did not answer" are different
     * facts and only one of them is worth retrying.
     */
    public record EpsHistory(List<EpsFact> facts, Status status) {

        public boolean isEmpty() {
            return facts.isEmpty();
        }

        public static EpsHistory of(Status status) {
            return new EpsHistory(List.of(), status);
        }
    }

    /** CIK for a bare US ticker, or empty — SEC's registry is the only authority consulted. */
    public Optional<Integer> cik(String ticker) {
        return registry == null ? Optional.empty() : registry.cik(ticker);
    }

    /**
     * Every filed diluted-EPS figure for {@code ticker}, sorted by period end ascending. Empty
     * with a {@link Status} rather than an exception on any failure, unknown ticker, or filer
     * that does not tag the concept.
     */
    public EpsHistory fetch(String ticker) {
        Optional<Integer> cik = cik(ticker);
        if (cik.isEmpty()) return EpsHistory.of(Status.UNAVAILABLE);
        String url = String.format(CONCEPT_URL, cik.get(), CONCEPT);
        Response resp = get(url);
        if (resp.status() == 404) return EpsHistory.of(Status.UNTAGGED);
        if (resp.body() == null) return EpsHistory.of(Status.UNAVAILABLE);
        try {
            return parse(resp.body());
        } catch (Exception e) {
            log.warn("EDGAR EPS parse failed for {}", ticker, e);
            return EpsHistory.of(Status.UNAVAILABLE);
        }
    }

    private Response get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Response(200, resp.body());
            if (resp.statusCode() == 404) log.debug("EDGAR EPS 404 (untagged) for {}", url);
            else log.warn("EDGAR EPS HTTP {} for {}", resp.statusCode(), url);
            return new Response(resp.statusCode(), null);
        } catch (Exception e) {
            log.warn("EDGAR EPS error {}", url, e);
        }
        return new Response(0, null);
    }

    private record Response(int status, String body) {}

    /**
     * Visible for tests. One record per filed window, newest filing's value with the oldest
     * filing's date.
     *
     * <p>Three traps in EDGAR XBRL make this less obvious than it looks:
     *
     * <ol>
     *   <li>{@code fy} and {@code fp} describe the <em>filing</em>, not the period — a 10-Q filed
     *       in FY2018 reports the prior year's same calendar quarter as a comparative under the
     *       <em>same</em> {@code fy=2018 fp=Q3} tag. Keying by {@code (fy, fp)} silently
     *       conflates them, so periods are keyed by {@code (start, end)} and {@code fp} is
     *       ignored entirely.
     *   <li>A filing tags more than its own quarter — a fiscal Q3 10-Q carries the quarter and
     *       the nine-month year-to-date. Both are kept here, because the year-to-date is what
     *       recovers the quarter a 10-K leaves implicit; it is {@link
     *       com.portfolio.domain.FiledPeriods} that refuses to add them together.
     *   <li>Only {@code 10-K} and {@code 10-Q} count. A foreign private issuer can resolve a CIK
     *       and file 20-F/6-K on another basis — which is how ARM and BABA answer 200 here with
     *       nothing a domestic series may use.
     * </ol>
     */
    EpsHistory parse(String json) throws Exception {
        JsonNode units = mapper.readTree(json).path("units").path(UNIT);
        if (!units.isArray() || units.isEmpty()) return EpsHistory.of(Status.UNTAGGED);

        // Per-window bookkeeping: the LATEST filing's value (so a restatement wins) with the
        // EARLIEST filing's date (so availability is when the figure first became public).
        record Key(LocalDate start, LocalDate end) {}
        record Slot(BigDecimal latestVal, LocalDate latestFiled, LocalDate earliestFiled) {}
        Map<Key, Slot> perWindow = new HashMap<>();
        int seen = 0;
        for (JsonNode n : units) {
            seen++;
            String form = n.path("form").asText("");
            if (!form.startsWith("10-K") && !form.startsWith("10-Q")) continue;
            String startStr = n.path("start").asText(null);
            String endStr = n.path("end").asText(null);
            String filedStr = n.path("filed").asText(null);
            String valStr = n.path("val").asText(null);
            if (startStr == null || endStr == null || filedStr == null || valStr == null) continue;

            Key key = new Key(LocalDate.parse(startStr), LocalDate.parse(endStr));
            BigDecimal val = new BigDecimal(valStr);
            LocalDate filed = LocalDate.parse(filedStr);

            Slot prev = perWindow.get(key);
            if (prev == null) {
                perWindow.put(key, new Slot(val, filed, filed));
            } else {
                LocalDate earliest = filed.isBefore(prev.earliestFiled) ? filed : prev.earliestFiled;
                if (filed.isAfter(prev.latestFiled)) {
                    perWindow.put(key, new Slot(val, filed, earliest));
                } else {
                    perWindow.put(key, new Slot(prev.latestVal, prev.latestFiled, earliest));
                }
            }
        }

        List<EpsFact> out = new ArrayList<>(perWindow.size());
        perWindow.forEach((key, slot) -> out.add(new EpsFact(key.start(), key.end(),
                slot.latestVal(), slot.earliestFiled(), slot.latestFiled())));
        out.sort((a, b) -> a.periodEnd.compareTo(b.periodEnd));
        // Figures on file but none of them domestic: the filer reports on 20-F/40-F/6-K, so its
        // EPS is on another basis rather than simply missing.
        Status status = !out.isEmpty() ? Status.OK
                : (seen > 0 ? Status.NOT_DOMESTIC : Status.UNTAGGED);
        return new EpsHistory(out, status);
    }
}
