package com.portfolio.domain;

import java.util.Set;

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

    private Instruments() {
    }

    /**
     * The instrument's asset class, for the drift term in the open-orders touch model. Resolution
     * order: direct bond → bond fund → bullion ETC → equity.
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
}
