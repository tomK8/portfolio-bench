package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.GiltYield;
import com.portfolio.port.GiltYieldFeed;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Benchmark gilt yields from CNBC's quote service: every tenor asked for, in one keyless GET.
 *
 * <p>CNBC because it is the one free source that quotes all four of the tenors the curve is read
 * at, live. The Bank of England's database is official but publishes only 5-, 10- and 20-year
 * par yields, from a fitted curve, a business day late; Yahoo carries no gilt yields at all. The
 * Bank's curves are linked from the Reference page rather than fetched.
 *
 * <p>Each quote is CNBC's generic tenor series, {@code GB10Y-GB} — the yield of the gilt it
 * currently designates the benchmark, named in the same quote by coupon and maturity. Read per
 * quote:
 * <ul>
 *   <li>{@code last} and {@code previous_day_closing} — yields, as text with a {@code %} sign.</li>
 *   <li>{@code last_time} — ISO with a colon-less offset, and <em>not</em> one zone: on
 *       2026-11-25 the 2-year came stamped {@code -0500} and the other three {@code +0000}.</li>
 *   <li>{@code coupon}, {@code maturity_date} — the benchmark gilt.</li>
 * </ul>
 * <b>{@code change_pct} is never read.</b> On a yield quote it is the benchmark bond's
 * <em>price</em> change, so it carries the opposite sign to the yield's move ({@code -0.0346%} on
 * a day the 2-year yield rose 1.5bp). The move is recomputed from the two yields instead — see
 * {@link GiltYield#changeBp()}.
 *
 * <p>An unknown symbol comes back as {@code {"symbol": …, "code": 1}} and no fields; it is left
 * out, as is any quote without a readable {@code last}, so the caller can name the tenor rather
 * than show a blank or zero row.
 */
public class CnbcGiltYieldFetcher implements GiltYieldFeed {

    static final String URL_FMT = "https://quote.cnbc.com/quote-html-webservice/restQuote/"
            + "symbolType/symbol?symbols=%s&requestMethod=itv&noform=1&output=json";
    private static final String QUOTE_PAGE = "https://www.cnbc.com/quotes/";
    /** Fails fast: the page polls, and the next poll is the retry. */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    // Matches what GiltPriceFetcher and YahooPriceFetcher send.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    private static final Pattern SYMBOL = Pattern.compile("GB(\\d{1,2})Y-GB");
    private static final DateTimeFormatter LAST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<GiltYield> benchmarkYields(List<Integer> tenorYears) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(tenorYears)))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = BoundedHttp.send(client, req);
        if (resp.statusCode() != 200) {
            throw new IOException("CNBC answered HTTP " + resp.statusCode());
        }
        return parse(mapper.readTree(resp.body()));
    }

    @Override
    public String sourceName() {
        return "CNBC";
    }

    /** CNBC's generic series for a tenor: {@code GB10Y-GB}. */
    static String symbol(int tenorYears) {
        return "GB" + tenorYears + "Y-GB";
    }

    static String url(List<Integer> tenorYears) {
        String symbols = tenorYears.stream()
                .map(CnbcGiltYieldFetcher::symbol)
                .collect(Collectors.joining("|"));
        return String.format(URL_FMT, URLEncoder.encode(symbols, StandardCharsets.UTF_8));
    }

    /**
     * The quotes in a response, minus any that are not a gilt tenor or carry no yield.
     *
     * @throws IOException when the response is not the quote envelope at all — a changed shape
     *                     must read as a failure, not as "no tenors answered"
     */
    static List<GiltYield> parse(JsonNode root) throws IOException {
        JsonNode quotes = root.path("FormattedQuoteResult").path("FormattedQuote");
        if (quotes.isMissingNode() || quotes.isNull()) {
            throw new IOException("CNBC response has no FormattedQuote");
        }
        // One symbol has always come back as a one-element array; take a bare object too.
        List<JsonNode> list = new ArrayList<>();
        if (quotes.isArray()) quotes.forEach(list::add);
        else list.add(quotes);

        List<GiltYield> out = new ArrayList<>();
        for (JsonNode q : list) {
            if (q.path("code").asInt(0) != 0) continue;
            Matcher m = SYMBOL.matcher(q.path("symbol").asText(""));
            if (!m.matches()) continue;
            BigDecimal yield = percent(q.path("last"));
            if (yield == null) continue;

            int tenor = Integer.parseInt(m.group(1));
            LocalDate maturity = date(q.path("maturity_date"));
            BigDecimal coupon = percent(q.path("coupon"));
            String benchmark = coupon == null || maturity == null
                    ? null
                    : Instruments.giltId(coupon.toPlainString(), maturity.getYear());
            out.add(new GiltYield(tenor, yield, percent(q.path("previous_day_closing")),
                    instant(q.path("last_time")), benchmark, maturity, QUOTE_PAGE + symbol(tenor)));
        }
        return out;
    }

    /** {@code "4.8037%"} → 4.8037; blank, a dash or anything unreadable → null, never zero. */
    static BigDecimal percent(JsonNode node) {
        String s = node.asText("").replace("%", "").replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant instant(JsonNode node) {
        try {
            return OffsetDateTime.parse(node.asText(""), LAST_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDate date(JsonNode node) {
        try {
            return LocalDate.parse(node.asText(""));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
