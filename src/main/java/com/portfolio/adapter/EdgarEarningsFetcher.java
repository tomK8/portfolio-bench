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
 * <p>CIKs are hard-coded for the swing universe's US filers (same approach as
 * {@link EdgarFundamentalsFetcher}). A symbol with no CIK here is {@link #isVerifiable not verifiable}
 * — ASML files 20-F/6-K (no 10-Q, so this proxy can't see it) and SEMI.L has no SEC filings at all,
 * so both are left out on purpose rather than mapped to a wrong or empty result.
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

    /** US 10-Q/10-K filers in the swing universe. Extend by adding rows. */
    private static final Map<String, Integer> CIK_BY_TICKER = new LinkedHashMap<>();
    static {
        CIK_BY_TICKER.put("MU", 723125);
        CIK_BY_TICKER.put("AMAT", 6951);
        CIK_BY_TICKER.put("KLAC", 319201);
        CIK_BY_TICKER.put("LRCX", 707549);
        CIK_BY_TICKER.put("AVGO", 1730168);
        CIK_BY_TICKER.put("INTC", 50863);
        CIK_BY_TICKER.put("MRVL", 1835632);
        CIK_BY_TICKER.put("ANET", 1596532);
        CIK_BY_TICKER.put("TSLA", 1318605);
        CIK_BY_TICKER.put("NVDA", 1045810);
        CIK_BY_TICKER.put("GOOG", 1652044);
        CIK_BY_TICKER.put("GOOGL", 1652044);
        CIK_BY_TICKER.put("MSFT", 789019);
        CIK_BY_TICKER.put("AAPL", 320193);
        CIK_BY_TICKER.put("AMZN", 1018724);
        CIK_BY_TICKER.put("META", 1326801);
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isVerifiable(String symbol) {
        return symbol != null && CIK_BY_TICKER.containsKey(symbol.toUpperCase());
    }

    @Override
    public String sourceName() {
        return "EDGAR 10-Q/10-K filing dates";
    }

    @Override
    public List<LocalDate> earningsDates(String symbol) {
        Integer cik = CIK_BY_TICKER.get(symbol == null ? "" : symbol.toUpperCase());
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
