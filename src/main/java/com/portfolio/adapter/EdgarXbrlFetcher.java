package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.FiledPeriods.Fact;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One us-gaap concept's period-tagged history for one US filer, from SEC XBRL's
 * {@code companyconcept} endpoint. Free, no auth, and GAAP by construction — the filer tagged
 * these figures itself, with the window attached, which is the one thing Yahoo's
 * {@code financialData} never states.
 *
 * <p><b>companyconcept, not companyfacts.</b> The facts endpoint hands back every concept a
 * company has ever tagged — megabytes per symbol — where a concept is tens of kilobytes. Two or
 * three concepts per symbol over ~40 US lines is far less traffic than one whole-company blob
 * each, and a concept the filer never tagged is a cheap 404 rather than a parse over everything.
 *
 * <p><b>A 404 is an answer.</b> Plenty of real filers never tag {@code OperatingIncomeLoss}:
 * banks have no operating-income subtotal, and several large caps present straight to pre-tax
 * income. That is an absence to report, not a failure to retry, so it logs at debug and returns
 * empty — the same "reads degrade" convention as the price adapters.
 *
 * <p><b>Only 10-K and 10-Q.</b> A foreign private issuer resolves a CIK but files 20-F/6-K,
 * whose figures are tagged on a different basis and often in another currency; filtering on the
 * form is what keeps ASML and TSM out without naming them. Amendments and the comparatives a
 * later filing re-tags are collapsed by keeping the <b>latest filed</b> value per window, so a
 * restatement wins over the figure it replaced.
 *
 * <p>SEC requires a contact User-Agent and asks callers to stay under 10 req/s; the service
 * driving this throttles between symbols.
 */
public class EdgarXbrlFetcher {

    private static final Logger log = LoggerFactory.getLogger(EdgarXbrlFetcher.class);

    private static final String CONCEPT_URL =
            "https://data.sec.gov/api/xbrl/companyconcept/CIK%010d/us-gaap/%s.json";
    private static final String USER_AGENT = "PortfolioBench tomaszp@gmail.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final CikRegistry registry;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public EdgarXbrlFetcher(CikRegistry registry) {
        this.registry = registry;
    }

    /**
     * Why a concept yielded nothing. The three empty outcomes are genuinely different questions
     * and the panel reports them differently, so they are not collapsed here — measured on one
     * fetch: BAC and LLY answer {@code 404} ({@link #UNTAGGED}), ASML answers 51 facts of which
     * every one is a {@code 20-F} ({@link #NOT_DOMESTIC}, and in EUR), and V answers {@code 200}
     * with an empty {@code USD} array, which is {@link #UNTAGGED} by another route.
     */
    public enum Status {
        /** Facts on 10-K/10-Q were found. */
        OK,
        /** The filer has never tagged this concept, or tagged it with no figures. */
        UNTAGGED,
        /** Tagged, but only on forms a domestic filer does not use — 20-F, 40-F, 6-K. */
        NOT_DOMESTIC,
        /** No CIK for the symbol, or the request failed. Says nothing about the filer. */
        UNAVAILABLE
    }

    /** The facts for one concept, the unit they are filed in, and why there are none. */
    public record Series(List<Fact> facts, String unit, Status status) {

        public boolean isEmpty() {
            return facts.isEmpty();
        }

        static Series unavailable() {
            return new Series(List.of(), null, Status.UNAVAILABLE);
        }
    }

    public Optional<Integer> cik(String symbol) {
        return registry == null ? Optional.empty() : registry.cik(symbol);
    }

    /**
     * Fetch one concept for {@code symbol}. Never empty-handed: an empty {@link Series} carries
     * the {@link Status} saying why, because "this filer reports no operating income" and "this
     * filer reports on a foreign form" are different facts about the company and the panel says
     * which. A failure is {@link Status#UNAVAILABLE} and claims nothing either way.
     */
    public Series series(String symbol, String concept) {
        Optional<Integer> cik = cik(symbol);
        if (cik.isEmpty()) return Series.unavailable();
        Response resp = get(String.format(CONCEPT_URL, cik.get(), concept));
        if (resp.status() == 404) return new Series(List.of(), null, Status.UNTAGGED);
        if (resp.body() == null) return Series.unavailable();
        try {
            return parse(resp.body());
        } catch (Exception e) {
            log.warn("EDGAR XBRL parse failed for {} {}", symbol, concept, e);
            return Series.unavailable();
        }
    }

    /**
     * Visible for tests. One {@link Fact} per filed window, latest filing winning a restated
     * one, carrying the unit the figures are in.
     *
     * <p>The unit is taken as filed rather than assumed: {@code USD} where present, else the
     * single key the payload offers. A US filer reporting in another currency is therefore
     * labelled in it instead of being silently read as dollars.
     */
    Series parse(String json) throws Exception {
        JsonNode units = mapper.readTree(json).path("units");
        if (!units.isObject() || units.isEmpty()) {
            return new Series(List.of(), null, Status.UNTAGGED);
        }
        String unit = units.has("USD") ? "USD" : units.fieldNames().next();

        record Slot(BigDecimal value, String filed) {}
        Map<String, Slot> perWindow = new LinkedHashMap<>();
        int seen = 0;
        for (JsonNode n : units.path(unit)) {
            seen++;
            String form = n.path("form").asText("");
            if (!form.startsWith("10-K") && !form.startsWith("10-Q")) continue;
            String start = n.path("start").asText(null);
            String end = n.path("end").asText(null);
            String filed = n.path("filed").asText(null);
            String val = n.path("val").asText(null);
            if (start == null || end == null || filed == null || val == null) continue;

            String window = start + "/" + end;
            Slot prev = perWindow.get(window);
            if (prev == null || filed.compareTo(prev.filed()) > 0) {
                perWindow.put(window, new Slot(new BigDecimal(val), filed));
            }
        }

        List<Fact> facts = new ArrayList<>(perWindow.size());
        for (Map.Entry<String, Slot> e : perWindow.entrySet()) {
            String[] window = e.getKey().split("/");
            facts.add(new Fact(LocalDate.parse(window[0]), LocalDate.parse(window[1]),
                    e.getValue().value()));
        }
        // Facts on file but none of them domestic: the filer reports on 20-F/40-F/6-K, so these
        // figures are on another basis (and often another currency) rather than simply missing.
        Status status = !facts.isEmpty() ? Status.OK
                : (seen > 0 ? Status.NOT_DOMESTIC : Status.UNTAGGED);
        return new Series(facts, unit, status);
    }

    /** One HTTP outcome. A 404 is kept apart from a failure: it is the SEC saying "not tagged". */
    private record Response(int status, String body) {}

    private Response get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Response(200, resp.body());
            // 404 = this filer never tagged this concept. Ordinary, not a fault.
            if (resp.statusCode() == 404) log.debug("EDGAR XBRL 404 (untagged) for {}", url);
            else log.warn("EDGAR XBRL HTTP {} for {}", resp.statusCode(), url);
            return new Response(resp.statusCode(), null);
        } catch (Exception e) {
            log.warn("EDGAR XBRL error {}", url, e);
        }
        return new Response(0, null);
    }
}
