package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.port.EarningsCalendarProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.TreeSet;

/**
 * Earnings-date proxy from SEC EDGAR's submissions API
 * ({@code data.sec.gov/submissions/CIK##########.json}) — the filing dates of a company's
 * {@code 10-Q}/{@code 10-K} reports, which sit within a few days of the earnings release. Free, no
 * auth, ~10y of history for any US filer. Used only to <em>flag</em> flush events as
 * earnings-adjacent (§D); it is not a precise announcement calendar.
 *
 * <p>CIKs resolve <b>dynamically</b> via {@link CikRegistry} (SEC's {@code company_tickers.json}),
 * so a new swing name needs no source edit. A small {@link #CIK_OVERRIDES} map is kept only as an
 * offline fallback / override for the core universe (keeps unit tests network-free). A symbol that
 * resolves to no CIK is {@link #isVerifiable not verifiable}; note that resolving a CIK is
 * <em>necessary but not sufficient</em> for §D verification — a foreign filer like ASML resolves a
 * CIK but files 20-F/6-K, so it yields no 10-Q/10-K dates and callers (see {@code EarningsService})
 * treat "obtained ≥1 date" as the real verified signal.
 *
 * <p>SEC requires a contact User-Agent and asks callers to stay under 10 req/s; we send one and stay
 * well under. Network/parse failures degrade to an empty list (logged), same convention as the other
 * adapters — callers mark the symbol as "verified but no dates in range" rather than crashing.
 */
public class EdgarEarningsFetcher implements EarningsCalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(EdgarEarningsFetcher.class);

    private static final String SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK%010d.json";
    private static final String BATCH_URL = "https://data.sec.gov/submissions/%s";
    private static final String USER_AGENT = "PortfolioBench tomaszp@gmail.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Offline fallback / override for the core universe's US 10-Q/10-K filers. The primary source is
     * the dynamic {@link CikRegistry}; this map only pins these names when the SEC file can't be
     * fetched (offline dev, unit tests) and is consulted first so an override always wins.
     */
    private static final Map<String, Integer> CIK_OVERRIDES = new LinkedHashMap<>();
    static {
        CIK_OVERRIDES.put("MU", 723125);
        CIK_OVERRIDES.put("AMAT", 6951);
        CIK_OVERRIDES.put("KLAC", 319201);
        CIK_OVERRIDES.put("LRCX", 707549);
        CIK_OVERRIDES.put("AVGO", 1730168);
        CIK_OVERRIDES.put("INTC", 50863);
        CIK_OVERRIDES.put("MRVL", 1835632);
        CIK_OVERRIDES.put("ANET", 1596532);
        CIK_OVERRIDES.put("TSLA", 1318605);
        CIK_OVERRIDES.put("NVDA", 1045810);
        CIK_OVERRIDES.put("GOOG", 1652044);
        CIK_OVERRIDES.put("GOOGL", 1652044);
        CIK_OVERRIDES.put("MSFT", 789019);
        CIK_OVERRIDES.put("AAPL", 320193);
        CIK_OVERRIDES.put("AMZN", 1018724);
        CIK_OVERRIDES.put("META", 1326801);
    }

    private final CikRegistry registry;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Override-only (no dynamic resolution). Used by unit tests so no network is touched. */
    public EdgarEarningsFetcher() {
        this(null);
    }

    public EdgarEarningsFetcher(CikRegistry registry) {
        this.registry = registry;
    }

    /** CIK from the override map first, then the dynamic registry; null when unresolvable. */
    private Integer resolveCik(String symbol) {
        if (symbol == null) return null;
        Integer override = CIK_OVERRIDES.get(symbol.toUpperCase());
        if (override != null) return override;
        return registry == null ? null : registry.cik(symbol).orElse(null);
    }

    @Override
    public boolean isVerifiable(String symbol) {
        return resolveCik(symbol) != null;
    }

    @Override
    public String sourceName() {
        return "EDGAR 10-Q/10-K filing dates";
    }

    @Override
    public void refresh() {
        if (registry != null) registry.refresh();
    }

    @Override
    public List<LocalDate> earningsDates(String symbol) {
        Integer cik = resolveCik(symbol);
        if (cik == null) return List.of();
        String body = get(String.format(SUBMISSIONS_URL, cik));
        if (body == null) return List.of();
        TreeSet<LocalDate> dates = new TreeSet<>();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode filings = root.path("filings");
            collect(filings.path("recent"), dates);
            // Older filings are split into extra batch files (only needed for long windows).
            JsonNode files = filings.path("files");
            if (files.isArray()) {
                for (JsonNode f : files) {
                    String name = f.path("name").asText(null);
                    if (name == null) continue;
                    String batch = get(String.format(BATCH_URL, name));
                    if (batch != null) collect(mapper.readTree(batch), dates);
                }
            }
        } catch (Exception e) {
            log.warn("EDGAR submissions parse failed for {}", symbol, e);
            return List.of();
        }
        return new ArrayList<>(dates);
    }

    /** Pull {@code filingDate} for every {@code 10-Q}/{@code 10-K} out of a parallel-array filings node. */
    static void collect(JsonNode node, TreeSet<LocalDate> out) {
        JsonNode forms = node.path("form");
        JsonNode filingDates = node.path("filingDate");
        if (!forms.isArray() || !filingDates.isArray()) return;
        int m = Math.min(forms.size(), filingDates.size());
        for (int i = 0; i < m; i++) {
            String form = forms.get(i).asText("");
            if (!form.startsWith("10-Q") && !form.startsWith("10-K")) continue;
            try {
                out.add(LocalDate.parse(filingDates.get(i).asText()));
            } catch (Exception ignored) {
                // skip an unparseable date
            }
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
            log.warn("EDGAR submissions HTTP {} for {}", resp.statusCode(), url);
        } catch (Exception e) {
            log.warn("EDGAR submissions error {}", url, e);
        }
        return null;
    }
}
