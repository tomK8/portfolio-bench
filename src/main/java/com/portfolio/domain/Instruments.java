package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-domain predicates over the internal {@code securityId} string. Concentrated here so
 * the "what counts as a bond" rule lives in one place — previously split between
 * {@code PortfolioAggregator} and the adapter layer.
 */
public final class Instruments {

    /**
     * Physical-metal ETCs. These are wrappers around bullion, so what matters is the underlying:
     * no earnings, no yield, no expected drift.
     *
     * <p>The same instruments are the ones {@code sectors.properties} has to override to
     * {@code COMMODITIES} (Yahoo has no sector for them either), and
     * {@code InstrumentsTest.everyCommodityOverrideInSectorsPropertiesIsZeroDrift} reads that file
     * to make sure a symbol added there can't be forgotten here — two lists of the same judgement
     * is how they drift apart.
     */
    private static final Set<String> BULLION_ETCS = Set.of(
            "SGLN", "IGLN", "PHGP", "PHAU", "GBS", "SGBX",   // gold
            "SSLN", "PHSP", "PHAG",                            // silver
            "PHPD", "PHPT");                                   // palladium / platinum

    /**
     * Bond funds — a gilt wrapped in an ETF is still a gilt, and {@link #isBond} only recognises
     * the direct holdings (whose ids carry a coupon or a {@code GILT} prefix).
     */
    private static final Set<String> BOND_FUNDS = Set.of("VGOV", "IGLT", "GLTY");

    /**
     * Cash-equivalent wrappers: money-market and ultra-short-dated government ETFs, held as a
     * place to park cash rather than as an investment in anything. The wrapper is the only thing
     * standing between the holder and an overnight rate, so what it <em>is</em> — not what the
     * feed says it holds — decides the bucket, exactly as for bullion and gilt funds.
     *
     * <p><b>CSH2 is why the feed cannot be trusted here.</b> It is swap-based: it holds a
     * substitute basket of shares and swaps that basket's return away for an overnight rate, so
     * the holder has no economic exposure to the basket at all. Yahoo publishes the basket's
     * sector weightings regardless, and fund look-through dutifully spread £67,809 of overnight
     * cash across twelve equity sectors — £24,310 of it into Technology, which the AI/Tech tile
     * then counted as a theme bet (2026-10-12). IB01 (0–1yr US Treasury bills) has the opposite
     * failure: Yahoo publishes no sector for it at all, so it sat in the red "excluded from every
     * sector" strip looking like a feed outage. Both are cash.
     *
     * <p><b>Deliberately only what is held.</b> Unlike the bullion list, this one is not stocked
     * with siblings: a ticker listed here that turns out not to be a cash equivalent is a
     * <em>silent</em> misclassification, while one that is missing surfaces loudly by name in the
     * amber strip — which is how IB01 was found. A loud gap beats a quiet error, so the list
     * grows when something is bought, not in anticipation.
     */
    private static final Set<String> CASH_EQUIVALENTS = Set.of("CSH2", "IB01");

    /** The id shape {@link #giltId} produces, for re-canonicalising strings already stored. */
    private static final Pattern GILT_ID =
            Pattern.compile("GILT\\s+(\\d+(?:\\.\\d+)?)%\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);

    /**
     * The coupon marker {@link #couponOf} reads. Deliberately looser than {@link #GILT_ID} — it
     * matches the same {@code %} that {@link #isBond} accepts as evidence of a bond, so any id the
     * predicate calls a bond can have its coupon read.
     */
    private static final Pattern COUPON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Instruments() {
    }

    /**
     * The wrapper sets, for the tests that pin every member through the classifiers. Package-
     * private rather than public: they are a guard against these lists being duplicated into a
     * properties file, so exposing them for general use would defeat the point.
     */
    static Set<String> bullionEtcs() {
        return BULLION_ETCS;
    }

    static Set<String> bondFunds() {
        return BOND_FUNDS;
    }

    static Set<String> cashEquivalents() {
        return CASH_EQUIVALENTS;
    }

    /**
     * The instrument's asset class, for the drift term in the open-orders touch model. Resolution
     * order: direct bond → bond fund → bullion ETC → cash-equivalent wrapper → equity.
     *
     * <p>Only the classes that <em>change the number</em> are detected. Everything unrecognised is
     * {@link AssetClass#EQUITY}, which is both the common case and the same drift an equity fund
     * would carry — see {@link AssetClass#ETF} for why no fund list is maintained here.
     */
    public static AssetClass assetClassOf(String securityId) {
        if (securityId == null || securityId.isBlank()) return AssetClass.UNKNOWN;
        String s = securityId.trim().toUpperCase();
        if (isBond(s) || BOND_FUNDS.contains(s)) return AssetClass.GILT;
        if (BULLION_ETCS.contains(s)) return AssetClass.COMMODITY;
        if (CASH_EQUIVALENTS.contains(s)) return AssetClass.CASH_EQUIVALENT;
        return AssetClass.EQUITY;
    }

    /**
     * A bond, in this codebase, is any holding whose internal id either starts with
     * {@code GILT} (the format produced by both holdings and cash-statement parsers after
     * normalisation) or carries a {@code %} coupon marker (a defensive catch-all for any
     * unnormalised bond row). Used to:
     * <ul>
     *   <li>sort bonds into their own section on the Portfolio sheet,</li>
     *   <li>skip them when fetching from Yahoo (no coverage) — gilt prices come from
     *       {@code GiltPriceFetchJob} instead,</li>
     *   <li>switch {@code PortfolioAggregator.realtime} to the per-£100-nominal value formula
     *       ({@code qty × price / 100}) instead of the per-share one.</li>
     * </ul>
     */
    public static boolean isBond(String securityId) {
        return securityId != null
                && (securityId.contains("%") || securityId.toUpperCase().startsWith("GILT"));
    }

    /**
     * The one place a gilt's internal id is built: {@code "GILT {coupon}% {year}"}, with the
     * coupon in its <b>canonical</b> form — trailing zeros trimmed, so 4.50, 4.500 and 4.5 all
     * produce {@code "GILT 4.5% 2028"}.
     *
     * <p>That trim is the whole reason this exists. The id is a join key between four writers
     * that each read the coupon out of different text, and the sources disagree about trailing
     * zeros: HL pads ({@code "4.750"}), Tradeweb pads ({@code "3.000"}), AJ Bell's <em>holdings</em>
     * export doesn't ({@code "4.75%"}) — and AJ Bell's <em>cash statement</em> writes
     * {@code "GILT 4.50%"}. Each writer used to normalise for itself, or not at all, so a coupon
     * a broker happened to spell with a trailing zero produced a position under one string and its
     * prices under another. A £50,597 gilt bought 2026-08-21 landed in the ledger as
     * {@code "GILT 4.50% 2028"} while HL priced it all along as {@code "GILT 4.5% 2028"}: the
     * cash-ledger view could join neither the intraday quote nor the daily close, so it reported
     * the position as unpriced and dropped it from the totals (2026-08-24). {@code GILT 1.00% 2024}
     * and {@code GILT 5.00% 2025} carry the same defect and were only ever invisible because both
     * matured before anyone read a total that was missing them.
     *
     * <p>Building the whole id here rather than exposing a coupon-trimming helper is deliberate:
     * a caller cannot then assemble the string correctly and forget to canonicalise it.
     */
    public static String giltId(String coupon, int year) {
        return "GILT " + canonicalCoupon(coupon) + "% " + year;
    }

    /**
     * Re-canonicalises an id that was built before {@link #giltId} was the only way to build one.
     * Returns non-gilt ids, and ids already canonical, unchanged — so it is safe to run over every
     * stored symbol, which is what the repository migrations do.
     */
    public static String canonicalGiltId(String securityId) {
        if (securityId == null) return null;
        Matcher m = GILT_ID.matcher(securityId.trim());
        if (!m.matches()) return securityId;
        return giltId(m.group(1), Integer.parseInt(m.group(2)));
    }

    /**
     * The annual coupon embedded in a bond id, as a <b>fraction</b> — {@code "GILT 4.75% 2038"}
     * → {@code 0.0475}. Returns null when the id carries no readable coupon.
     *
     * <p>Reading a coupon and <em>building</em> an id are different concerns, which is why this
     * sits beside {@link #giltId} rather than contradicting its javadoc: {@code giltId} refuses to
     * expose a coupon helper because a caller could then assemble the join key and forget to
     * canonicalise it. Nothing here produces an id. What it produces is the numerator of a gilt's
     * running yield, and having that in one place matters for the same reason {@link #isBond} does
     * — the alternative is the regex living in every service that needs an income figure.
     *
     * <p>A zero coupon is a real answer ({@code BigDecimal.ZERO}), not a missing one: a strip
     * genuinely pays nothing, and a caller must be able to tell that from an unparseable id.
     */
    public static BigDecimal couponOf(String securityId) {
        if (securityId == null) return null;
        Matcher m = COUPON.matcher(securityId);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code "4.50"} → {@code "4.5"}; {@code "3.000"} → {@code "3"}; {@code "0.875"} unchanged. */
    private static String canonicalCoupon(String coupon) {
        String c = coupon == null ? "" : coupon.trim();
        if (!c.contains(".")) return c;
        c = c.replaceAll("0+$", "");
        if (c.endsWith(".")) c = c.substring(0, c.length() - 1);
        return c.isEmpty() ? coupon.trim() : c;
    }

    /**
     * The canonical form of a broker's instrument description for keyword matching: lowercase,
     * with every run of non-alphanumerics collapsed to a single space.
     *
     * <p><b>Both sides of the comparison must go through this</b>, which is the whole point of it
     * living here rather than in either parser. The dashboard suggests a match phrase by stripping
     * punctuation out of the name, so a save of "Legal &amp; General Group PLC" stored the keyword
     * {@code legal general group plc} — while the parsers tested it against the raw lowercased name
     * {@code "legal & general group plc"}, which does not contain it. The mapping saved, reported
     * success, and could never fire; every instrument whose name carries an ampersand, a hyphen or
     * a dot was un-mappable from the dashboard (2026-08-24).
     *
     * <p>Normalising rather than preserving punctuation is also what lets one keyword span the
     * brokers, which is the reason {@code instrument_aliases} is a single table: the same
     * instrument reaches the orders export and the cash statement punctuated differently.
     */
    public static String matchKey(String description) {
        if (description == null) return "";
        return description.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
