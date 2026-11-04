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
 * <p><b>The feed answers in several shapes and flips per date, not per row.</b> While a date is
 * still ahead (today included — a company that reported before this morning's open still shows no
 * actual until tomorrow) each row carries {@code epsForecast}, a {@code time} session marker and
 * {@code lastYearRptDt}/{@code lastYearEPS}. Once the date has flipped the same rows carry
 * {@code eps} — what was actually reported — and {@code surprise}, while {@code time} degrades to
 * {@code time-not-supplied} and the last-year pair disappears. Every shape is read here into one
 * record; which fields are present is what tells them apart, and {@code EarningsCalendarService}
 * is where the consequence lives (a date fetched before it settled has to be asked once more
 * afterwards, and the answer merged rather than swapped in, or the session would be lost).
 *
 * <p><b>An {@code eps} on a row that has not finished flipping is not this quarter's result.</b>
 * In the hours after a date passes, the roster comes back still looking scheduled with an
 * {@code eps} column already filled in — holding <i>last year's</i> figure. Read as an actual it
 * publishes the year-ago quarter as what was just reported. It has happened twice, through a
 * different signature each time:
 *
 * <ul>
 *   <li><b>2026-10-29</b>, fetched 06:03 New York the next morning: 270 of 303 rows cached that
 *       way, and SNDK's year-ago $0.90 shown against a $46.19 consensus as a 98% miss on a quarter
 *       that in fact beat at $48.92. Those rows still carried the last-year pair.</li>
 *   <li><b>2026-11-03</b>, fetched 02:30 New York the next morning, four days after the first fix
 *       shipped: the whole roster had <i>already dropped</i> the last-year pair but kept its
 *       session marker, with {@code surprise} a uniform {@code "N/A"}. AMD's year-ago $0.97 was
 *       published against a $1.68 consensus as a 42% miss. 253 of the 253 rows carrying both
 *       figures had them equal.</li>
 * </ul>
 *
 * <p>So the shapes are told apart <b>structurally and on two signatures at once</b> — see
 * {@link #scheduledShape}: a row must have shed the last-year pair <i>and</i> its session marker
 * before its {@code eps} is read. Never on whether {@code eps} looks plausible beside
 * {@code lastYearEPS}: a company that earns exactly what it earned a year ago is an ordinary
 * quarter, not a bad row. {@code EarningsCalendarService.settled} is the other half — a roster
 * still in the scheduled shape is not the feed's final answer, so the date is asked again rather
 * than closed.
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
     * {@code actualEps} and no session, and a scheduled row the reverse. The one field that is not
     * simply taken where it appears is {@code eps}, which a scheduled row can carry holding last
     * year's figure — see {@link #scheduledShape}.
     */
    static List<EarningsEvent> parse(JsonNode root, LocalDate date) {
        List<EarningsEvent> out = new ArrayList<>();
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray()) return out; // no-reports day (data null / rows absent)
        boolean flipped = rosterHasFlipped(rows);
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
                    (!flipped || scheduledShape(row)) ? null : parseNumber(text(row, "eps"))));
        }
        return out;
    }

    /**
     * Whether this roster has flipped to reporting what happened — the gate every row's
     * {@code eps} has to pass before it is read as an actual.
     *
     * <p>The feed flips a date as a whole, so this is asked of the roster rather than the row, and
     * it is asked because the two per-row signatures are both <b>absent</b> on a scheduled row for
     * a company that never stated a session. On 2026-11-03, 42 of 264 rows were exactly that:
     * {@code time-not-supplied} because the company had not said when it would report, no
     * last-year pair, and an {@code eps} holding the year-ago quarter. Nothing about such a row
     * distinguishes it from a settled one. The roster it arrived in does.
     *
     * <p><b>The marker is that the feed has started publishing surprises at all.</b> An unflipped
     * roster carries {@code "N/A"} in every one (264 of 264 on 2026-11-03, hours after the date
     * passed); a flipped one carries real numbers for most of its rows (2026-11-02 and 2026-10-29
     * both). That is a presence test over the whole roster, not a threshold on any row.
     *
     * <p>This does <b>not</b> contradict the rule that {@code surprise} is read past and never
     * parsed. That rule is about its <i>value</i>, which disagrees with the two figures printed
     * beside it often enough to be unusable. What is read here is only that the column has begun
     * to be populated — the one thing it is reliable about. The surprise shown on screen is still
     * recomputed from {@code eps} and {@code epsForecast}.
     *
     * <p>A flipped roster on which <i>every</i> company met consensus to the cent would read as
     * unflipped and have its figures withheld. For a roster of any size that is not a real
     * scenario, and the failure is the recoverable one: the date keeps reading unsettled, so it is
     * asked again rather than closed on a fabrication.
     */
    static boolean rosterHasFlipped(JsonNode rows) {
        for (JsonNode row : rows) {
            if (parseNumber(text(row, "surprise")) != null) return true;
        }
        return false;
    }

    /**
     * Whether this row has stopped looking scheduled — the test for whether its {@code eps} is
     * what was just reported or last year's figure wearing this quarter's label.
     *
     * <p><b>Two independent signatures, and a row must have shed both.</b> Either one on its own
     * has been observed surviving into the window where {@code eps} is already filled in with the
     * year-ago number, which is the whole hazard:
     *
     * <ul>
     *   <li><b>The last-year pair.</b> {@code lastYearRptDt}/{@code lastYearEPS} are present on
     *       every scheduled row (1044 sampled across three forward dates; 479 of 479 on
     *       2026-11-04) and on no settled one. The key is the test, never the value: a company
     *       with no year-ago quarter sends a blank {@code lastYearEPS} beside an {@code eps} of
     *       {@code $0}, which read as an actual is a manufactured zero.</li>
     *   <li><b>A named session.</b> {@code time} degrades to {@code time-not-supplied} when a date
     *       flips — 308 of 309 rows on 2026-10-29, 154 of 157 on 2026-11-02 — so a row still
     *       saying {@code time-pre-market} or {@code time-after-hours} has not flipped.</li>
     * </ul>
     *
     * <p>The second was added because the first alone failed on <b>2026-11-03</b>, four days after
     * it shipped. That whole roster came back with the last-year pair <i>already dropped</i>, the
     * session marker intact, {@code surprise} a uniform {@code "N/A"}, and {@code eps} holding the
     * year-ago quarter: AMD's {@code $0.97} is its Sep-2025 figure to the cent, published against
     * a {@code $1.68} consensus as a 42% miss on a quarter whose figures were not out. 253 of the
     * 253 rows carrying both figures had them equal — the same fingerprint as 2026-10-29, through
     * a different hole. <b>There are four shapes, not three</b>, and the transitional one can shed
     * either signature first.
     *
     * <p>The cost of the stricter test is a handful of rows on an otherwise-settled date that keep
     * a session marker (GRAB and AEHR on 2026-11-02, HLN on 2026-10-29 — all with
     * {@code surprise: "N/A"}, i.e. in exactly this unflipped state row-by-row). Their actual is
     * withheld for now and the date keeps reading unscheduled, so it is asked again. That is the
     * recoverable direction: an actual withheld for a pass shows as <i>awaiting figures</i> and
     * arrives later, while an actual invented from last year closes the date and is undetectable
     * afterwards.
     *
     * <p>{@code surprise} is deliberately <b>not</b> the test, tempting as its uniform absence
     * here is. It is missing on genuinely settled rows too — 31 of them, where the company met
     * consensus to the cent — so requiring it would discard real figures to catch fake ones.
     */
    static boolean scheduledShape(JsonNode row) {
        return row.has("lastYearRptDt") || row.has("lastYearEPS")
                || !"TBD".equals(normTime(text(row, "time")));
    }

    /** Package-private: {@link NasdaqCompanyEarningsFetcher} parses the same feed's display strings. */
    static String text(JsonNode row, String field) {
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
