package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.model.EarningsEvent;
import com.portfolio.port.EarningsCalendarFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Earnings-calendar feed backed by Nasdaq's public JSON endpoint
 * ({@code api.nasdaq.com/api/calendar/earnings?date=YYYY-MM-DD}) — one HTTP GET per calendar date,
 * returning every company reporting that day with a consensus EPS estimate, session marker and
 * market cap. Forward-looking (unlike the EDGAR filing-date proxy), which is what the Earnings tab's
 * "+N days" half needs.
 *
 * <p><b>The feed answers in two shapes and flips per date, not per row.</b> While a date is still
 * ahead (today included — a company that reported before this morning's open still shows no actual
 * until tomorrow) each row carries {@code epsForecast}, a {@code time} session marker and
 * {@code lastYearEPS}. Once the date has passed the same rows carry {@code eps} — what was actually
 * reported — and {@code surprise}, while {@code time} degrades to {@code time-not-supplied} and
 * {@code lastYearEPS} disappears. Both shapes are read here into one record; which fields are
 * present is what tells the two apart, and {@code EarningsCalendarService} is where the
 * consequence lives (a date fetched before it settled has to be asked once more afterwards, and
 * the answer merged rather than swapped in, or the session would be lost).
 *
 * <p><b>{@code surprise} is read past, not parsed.</b> It is a pure function of the two numbers
 * beside it, and across 481 settled rows it disagreed with them twice (MAA reported 2.10 against a
 * 2.10 consensus and was labelled −65.71%; VKTX −1.55 against 1.00 was labelled −55% where the
 * arithmetic gives −255%) and was simply absent on 31 more where the company had met consensus to
 * the cent — a true zero published as a blank. Recomputing from {@code eps} and
 * {@code epsForecast} is the same call {@code P/B} makes against Yahoo's own ratio, for the same
 * reason: a vendor ratio that contradicts the figures printed next to it cannot be the one shown.
 *
 * <p>Like {@link GiltPriceFetcher} against its source page, the default Java {@code User-Agent} is
 * refused (403), so a browser UA is sent. All fields arrive as display strings ({@code "$7.98"},
 * {@code "$665,246,306,497"}, {@code "($0.11)"}) and are parsed into real numbers here so the report
 * can sort and threshold on them.
 */
public class NasdaqEarningsFetcher implements EarningsCalendarFeed {

    private static final Logger log = LoggerFactory.getLogger(NasdaqEarningsFetcher.class);

    static final String URL_FMT = "https://api.nasdaq.com/api/calendar/earnings?date=%s";
    // Default client (no User-Agent) gets 403 — mirror what YahooPriceFetcher/GiltPriceFetcher send.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<EarningsEvent> forDate(LocalDate date) {
        String body = get(date);
        if (body == null) return null; // fetch failure — caller falls back to cache
        try {
            return parse(mapper.readTree(body), date);
        } catch (Exception e) {
            log.warn("Nasdaq earnings parse failed for {}", date, e);
            return null;
        }
    }

    @Override
    public String sourceName() {
        return "Nasdaq earnings calendar";
    }

    private String get(LocalDate date) {
        String url = String.format(URL_FMT, date);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(TIMEOUT).GET().build();
                HttpResponse<String> resp = BoundedHttp.send(client, req);
                if (resp.statusCode() == 200) return resp.body();
                log.warn("Nasdaq earnings HTTP {} for {} (attempt {})", resp.statusCode(), date, attempt);
            } catch (Exception e) {
                log.warn("Nasdaq earnings error for {} (attempt {})", date, attempt, e);
            }
        }
        return null;
    }

    /**
     * Maps {@code data.rows[]} to events. A missing/null {@code data} node (Nasdaq's shape for a day
     * with no scheduled reports) yields an empty list — a valid, cacheable "no reports" result, not a
     * failure. Rows without a symbol are skipped.
     *
     * <p>Handles both row shapes without being told which it is looking at: every field is read
     * where it appears and left null where it does not, so a settled row yields an
     * {@code actualEps} and no session, and a scheduled row the reverse.
     */
    static List<EarningsEvent> parse(JsonNode root, LocalDate date) {
        List<EarningsEvent> out = new ArrayList<>();
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray()) return out; // no-reports day (data null / rows absent)
        for (JsonNode row : rows) {
            String symbol = text(row, "symbol");
            if (symbol == null) continue;
            out.add(new EarningsEvent(
                    date,
                    symbol,
                    text(row, "name"),
                    normTime(text(row, "time")),
                    parseMoney(text(row, "marketCap")),
                    parseNumber(text(row, "epsForecast")),
                    parseInt(text(row, "noOfEsts")),
                    parseNumber(text(row, "lastYearEPS")),
                    parseNumber(text(row, "eps"))));
        }
        return out;
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Nasdaq's session codes → short markers; anything else (incl. "time-not-supplied") → "TBD". */
    static String normTime(String raw) {
        if (raw == null) return "TBD";
        return switch (raw) {
            case "time-pre-market" -> "BMO";
            case "time-after-hours" -> "AMC";
            default -> "TBD";
        };
    }

    /** Parse a money/number display string to a double: strips {@code $ , %}, treats {@code (x)} as −x. */
    static Double parseNumber(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("N/A")) return null;
        boolean neg = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            neg = true;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace("$", "").replace(",", "").replace("%", "").trim();
        if (t.startsWith("-")) {
            neg = true;
            t = t.substring(1);
        }
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whole-dollar market cap. */
    static Long parseMoney(String s) {
        Double d = parseNumber(s);
        return d == null ? null : Math.round(d);
    }

    static Integer parseInt(String s) {
        Double d = parseNumber(s);
        return d == null ? null : (int) Math.round(d);
    }
}
