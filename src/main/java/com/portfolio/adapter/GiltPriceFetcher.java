package com.portfolio.adapter;

import com.portfolio.domain.model.IntradayBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-shot scraper for UK gilt prices from dividenddata.co.uk. Yahoo doesn't quote gilts,
 * so this fills the same role {@link YahooPriceFetcher} fills for stocks. The all-gilts table
 * is rendered server-side, so one HTTP GET returns every TG and TR row in one go — there is
 * no per-ticker iteration here.
 *
 * <p>The page identifies gilts by TIDM (e.g. {@code TG44}), but our internal {@code securityId}
 * uses the human-readable {@code "GILT {coupon}% {year}"} form produced by
 * {@code AJBellSippParser.extractBondId}. We key on {@code (coupon, year)} from the dividenddata
 * row's decimal-coupon and maturity-date columns so the resulting symbols join cleanly to
 * {@code Holding.securityId}. {@code (coupon, year)} is unique across all gilts on the page.
 */
public class GiltPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(GiltPriceFetcher.class);

    static final String URL = "https://www.dividenddata.co.uk/uk-gilts-prices-yields.py";
    // Default client (no User-Agent) gets 403; this matches what YahooPriceFetcher sends.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    // Row scan: each <tr>…</tr>, then each <th>/<td>…</tr> cell. DOTALL because rows span lines.
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern POUND = Pattern.compile("(?:&pound;|£)\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern COUPON_PCT = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)%$");
    private static final Pattern MATURITY_YEAR = Pattern.compile("(\\d{4})\\s*$");

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Fetches the all-gilts table and returns one bar per row, timestamped at fetch time. */
    public List<IntradayBar> fetch() {
        String body = get();
        if (body == null) {
            log.warn("Gilt fetch failed");
            return List.of();
        }
        try {
            return parse(body, Instant.now());
        } catch (Exception e) {
            log.warn("Gilt parse failed", e);
            return List.of();
        }
    }

    private String get() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
                log.warn("Gilt HTTP {} (attempt {})", resp.statusCode(), attempt);
            } catch (Exception e) {
                log.warn("Gilt error (attempt {})", attempt, e);
            }
        }
        return null;
    }

    /**
     * Parses every row whose first cell starts with a gilt TIDM (TG/TR + digits). Rows that
     * don't carry both a decimal coupon and a maturity year are skipped silently — the page
     * also has a navigation row, summary rows etc. that legitimately don't match.
     */
    List<IntradayBar> parse(String html, Instant ts) {
        List<IntradayBar> bars = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            List<String> cells = cells(row);
            if (cells.size() < 6) continue;
            if (!isGiltTidm(cells.get(0))) continue;

            String coupon = couponDecimal(cells.get(2));
            String year = maturityYear(cells.get(3));
            Double price = poundsValue(cells.get(5));
            if (coupon == null || year == null || price == null) continue;

            String symbol = "GILT " + coupon + "% " + year;
            bars.add(new IntradayBar(symbol, ts, price, null, "GBP"));
        }
        return bars;
    }

    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        Matcher m = CELL.matcher(row);
        while (m.find()) out.add(TAG.matcher(m.group(1)).replaceAll("").trim());
        return out;
    }

    private static boolean isGiltTidm(String cell) {
        return cell.length() >= 3
                && (cell.charAt(0) == 'T')
                && (cell.charAt(1) == 'G' || cell.charAt(1) == 'R')
                && Character.isLetterOrDigit(cell.charAt(2));
    }

    private static String couponDecimal(String cell) {
        Matcher m = COUPON_PCT.matcher(cell);
        return m.matches() ? m.group(1) : null;
    }

    private static String maturityYear(String cell) {
        Matcher m = MATURITY_YEAR.matcher(cell);
        return m.find() ? m.group(1) : null;
    }

    private static Double poundsValue(String cell) {
        Matcher m = POUND.matcher(cell);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }
}
