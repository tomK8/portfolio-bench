package com.portfolio.adapter;

import com.portfolio.domain.Instruments;
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
 * Single-shot scraper for UK gilt prices from Hargreaves Lansdown's public gilt-prices page.
 * Yahoo doesn't quote gilts, so this fills the same role {@link YahooPriceFetcher} fills for
 * stocks. The all-gilts table is rendered server-side, so one HTTP GET returns every
 * conventional gilt in one go — there is no per-ticker iteration here.
 *
 * <p>(Previously scraped dividenddata.co.uk, which went behind a Cloudflare JS challenge in
 * July 2026 — no header combination gets past a managed challenge, so the source had to move.)
 *
 * <p>The page identifies gilts by name/ISIN/SEDOL, but our internal {@code securityId} uses the
 * human-readable {@code "GILT {coupon}% {year}"} form produced by
 * {@link Instruments#giltId}. We key on {@code (coupon, year)} from the row's
 * decimal-coupon and maturity-date columns so the resulting symbols join cleanly to
 * {@code Holding.securityId}. {@code (coupon, year)} is unique across all conventional gilts
 * on the page (index-linked gilts live on a separate page and are not fetched).
 */
public class GiltPriceFetcher {

    private static final Logger log = LoggerFactory.getLogger(GiltPriceFetcher.class);

    static final String URL = "https://www.hl.co.uk/shares/corporate-bonds-gilts/bond-prices/uk-gilts";
    // Default client (no User-Agent) risks a 403; this matches what YahooPriceFetcher sends.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    // Row scan: each <tr>…</tr>, then each <th>/<td>…</tr> cell. DOTALL because rows span lines.
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    // HL renders the coupon as a plain decimal with trailing zeros ("4.750") and the price as
    // a plain decimal in pounds per £100 nominal ("95.190") — no currency symbol.
    private static final Pattern COUPON_DECIMAL = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern PRICE = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)$");
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
            List<IntradayBar> bars = parse(body, Instant.now());
            if (bars.isEmpty()) log.warn("Gilt fetch returned 200 but no rows parsed — page layout changed?");
            return bars;
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
     * Parses every row shaped {@code name | coupon | maturity | price [| actions]} whose name
     * cell mentions "Treasury" (all conventional gilts are "Treasury {coupon}% {date}"). Rows
     * that don't carry a decimal coupon, a maturity year and a plain-decimal price are skipped
     * silently — the page also has a header row, navigation etc. that legitimately don't match.
     */
    List<IntradayBar> parse(String html, Instant ts) {
        List<IntradayBar> bars = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            List<String> cells = cells(row);
            if (cells.size() < 4) continue;
            if (!cells.get(0).contains("Treasury")) continue;

            String coupon = couponDecimal(cells.get(1));
            String year = maturityYear(cells.get(2));
            Double price = priceValue(cells.get(3));
            if (coupon == null || year == null || price == null) continue;

            String symbol = Instruments.giltId(coupon, Integer.parseInt(year));
            bars.add(new IntradayBar(symbol, ts, price, null, "GBP"));
        }
        return bars;
    }

    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        Matcher m = CELL.matcher(row);
        while (m.find()) out.add(TAG.matcher(m.group(1)).replaceAll(" ").replaceAll("\\s+", " ").trim());
        return out;
    }

    /**
     * The coupon cell verbatim. HL pads with trailing zeros ("4.750", "1.000");
     * {@link Instruments#giltId} trims them, so the join holds without a second trim here.
     */
    private static String couponDecimal(String cell) {
        Matcher m = COUPON_DECIMAL.matcher(cell);
        return m.matches() ? m.group(1) : null;
    }

    private static String maturityYear(String cell) {
        Matcher m = MATURITY_YEAR.matcher(cell);
        return m.find() ? m.group(1) : null;
    }

    private static Double priceValue(String cell) {
        Matcher m = PRICE.matcher(cell);
        return m.matches() ? Double.valueOf(m.group(1)) : null;
    }
}
