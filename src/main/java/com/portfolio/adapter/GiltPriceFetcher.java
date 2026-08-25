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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>The SEDOL in the same name cell rides along in {@link Snapshot#sedolToSymbol()}, which is
 * the only free source of a SEDOL → gilt-id mapping this app has. It exists because a broker can
 * name a gilt by a code of its own: Interactive Investor's cash statement calls the 4.375% 2030
 * {@code T30} and its description ({@code "20000 HIS MAJE4LN STK30"}) carries neither coupon nor
 * maturity, so the row's SEDOL is the <em>only</em> thing in the file that identifies the
 * instrument. Reading it here costs nothing — it is already in the response this job makes hourly
 * — and it is what keeps that mapping out of a hand-maintained list that would need an edit for
 * every gilt ever bought.
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
    // Name cell tail: "… GBP | GB00BSQNRD01 | BSQNRD0". A SEDOL is 7 alphanumerics.
    private static final Pattern SEDOL = Pattern.compile("\\|\\s*([0-9A-Z]{7})\\s*$");

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * One scrape of the all-gilts table: a bar per row, timestamped at fetch time, plus the
     * SEDOL → gilt-id mapping the same rows carry. Both come out of a single HTTP GET.
     */
    public record Snapshot(List<IntradayBar> bars, Map<String, String> sedolToSymbol) {
        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of());
        }
    }

    /** Fetches the all-gilts table. Degrades to an empty snapshot; never throws. */
    public Snapshot fetch() {
        String body = get();
        if (body == null) {
            log.warn("Gilt fetch failed");
            return Snapshot.empty();
        }
        try {
            Snapshot snapshot = parse(body, Instant.now());
            if (snapshot.bars().isEmpty()) {
                log.warn("Gilt fetch returned 200 but no rows parsed — page layout changed?");
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("Gilt parse failed", e);
            return Snapshot.empty();
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
    Snapshot parse(String html, Instant ts) {
        List<IntradayBar> bars = new ArrayList<>();
        Map<String, String> sedols = new LinkedHashMap<>();
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

            String sedol = sedol(cells.get(0));
            if (sedol != null) sedols.put(sedol, symbol);
        }
        return new Snapshot(bars, sedols);
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

    /** The SEDOL from the name cell's "GBP | {ISIN} | {SEDOL}" tail, or null if it isn't there. */
    private static String sedol(String nameCell) {
        Matcher m = SEDOL.matcher(nameCell);
        return m.find() ? m.group(1) : null;
    }

    private static Double priceValue(String cell) {
        Matcher m = PRICE.matcher(cell);
        return m.matches() ? Double.valueOf(m.group(1)) : null;
    }
}
