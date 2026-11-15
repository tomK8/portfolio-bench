package com.portfolio.adapter;

import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.GiltRef;
import com.portfolio.domain.model.IntradayBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>The rest of what the name cell carries rides along as a {@link GiltRef} per row — ISIN,
 * SEDOL and the full maturity date — because the id above is the one name for a gilt that
 * <em>nobody else uses</em>. Two consumers need the other names. Interactive Investor's cash
 * statement calls the 4.375% 2030 {@code T30} and its description ({@code "20000 HIS MAJE4LN
 * STK30"}) carries neither coupon nor maturity, so the row's SEDOL is the <em>only</em> thing in
 * the file that identifies the instrument; and the Watchlist's gilt picker is searched with
 * whatever identifier the holder has in hand — an ISIN off the LSE's own quote page, say. Reading
 * it here costs nothing — it is already in the response this job makes hourly — and it is what
 * keeps those mappings out of a hand-maintained list that would need an edit for every gilt ever
 * bought.
 *
 * <p>The page also prints each gilt's <b>yield to maturity</b>, at four tax rates. Only the
 * {@code YTM 0% tax} column — the gross redemption yield — is read: every account this app
 * tracks is a tax-sheltered wrapper, so the other three answer a question nobody here is asking.
 * It is the one figure that makes two gilts comparable, it cannot be derived from a price
 * without solving for it, and it is free in a response already being made. Unlike the
 * identifiers it is a <em>quote</em>, not a fact about the instrument, which is why it travels
 * separately and is republished rather than merged.
 *
 * <p><b>The SEDOL is taken from the ISIN, not from the SEDOL the page prints.</b> See
 * {@link Instruments#sedolFromIsin}: HL strips the leading zero off the old numeric SEDOLs, so
 * the printed tail is 6 characters for the 6% 2028 and the 4.25% 2032 and a 7-character pattern
 * over it read nothing at all for either.
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
    // The maturity cell in full: "7 March 2036", "31 July 2036" — no leading zero on the day.
    private static final DateTimeFormatter MATURITY_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    // Name cell tail: "… GBP | GB00BSQNRD01 | BSQNRD0". Every gilt ISIN is GB00 + SEDOL + check.
    private static final Pattern ISIN = Pattern.compile("\\|\\s*(GB00[0-9A-Z]{8})\\b");
    // The printed SEDOL, used only when the row carries no ISIN. 7 alphanumerics.
    private static final Pattern SEDOL = Pattern.compile("\\|\\s*([0-9A-Z]{7})\\s*$");
    // "YTM 0% tax", a plain decimal percentage ("5.34"). The column is to the right of the
    // price, and the three taxed variants to the right of it are deliberately not read.
    private static final int YTM_CELL = 4;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * One scrape of the all-gilts table: a bar per row, timestamped at fetch time, plus the
     * identifiers the same rows carry. Both come out of a single HTTP GET.
     */
    public record Snapshot(List<IntradayBar> bars, List<GiltRef> refs,
                          Map<String, BigDecimal> redemptionYields) {
        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of());
        }

        /**
         * SEDOL → gilt id, for the cash statement that names a gilt by nothing else. Derived
         * from {@link #refs} rather than collected alongside them, so the map a re-import joins
         * on cannot disagree with the identifiers the picker is searched by.
         */
        public Map<String, String> sedolToSymbol() {
            Map<String, String> out = new LinkedHashMap<>();
            for (GiltRef ref : refs) {
                if (ref.sedol() != null) out.put(ref.sedol(), ref.symbol());
            }
            return out;
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
                HttpResponse<String> resp = BoundedHttp.send(client, req);
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
        List<GiltRef> refs = new ArrayList<>();
        Map<String, BigDecimal> ytm = new LinkedHashMap<>();
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
            refs.add(new GiltRef(symbol, isin(cells.get(0)), sedol(cells.get(0)),
                    maturityDate(cells.get(2))));
            BigDecimal yield = redemptionYield(cells);
            if (yield != null) ytm.put(symbol, yield);
        }
        return new Snapshot(bars, refs, ytm);
    }

    /**
     * The gross redemption yield, or null when the page is not printing one. A row that has
     * dropped the column keeps its price and its identifiers: a gilt without a yield is a gilt
     * missing one figure, not a gilt we failed to read.
     */
    private static BigDecimal redemptionYield(List<String> cells) {
        if (cells.size() <= YTM_CELL) return null;
        Matcher m = PRICE.matcher(cells.get(YTM_CELL));
        return m.matches() ? new BigDecimal(m.group(1)) : null;
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

    /** The ISIN from the name cell's "GBP | {ISIN} | {SEDOL}" tail, or null if it isn't there. */
    private static String isin(String nameCell) {
        Matcher m = ISIN.matcher(nameCell);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The row's SEDOL, from its ISIN — see {@link Instruments#sedolFromIsin} for why the ISIN
     * is the authority and not the SEDOL printed beside it. The printed tail is the fallback
     * for a row that carries no ISIN at all, which no current row does.
     */
    private static String sedol(String nameCell) {
        String fromIsin = Instruments.sedolFromIsin(isin(nameCell));
        if (fromIsin != null) return fromIsin;
        Matcher m = SEDOL.matcher(nameCell);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The maturity cell as a date. Null when it doesn't parse — the year the id is built from
     * comes from {@link #maturityYear} either way, so a format change here costs the picker its
     * ordering and nothing else.
     */
    private static LocalDate maturityDate(String cell) {
        try {
            return LocalDate.parse(cell.trim(), MATURITY_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double priceValue(String cell) {
        Matcher m = PRICE.matcher(cell);
        return m.matches() ? Double.valueOf(m.group(1)) : null;
    }
}
