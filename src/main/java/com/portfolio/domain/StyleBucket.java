package com.portfolio.domain;

/**
 * The investment-style buckets the Allocation → Breakdown panel reports, above the sector block.
 *
 * <p>Style is a <b>2×2, not a spectrum</b>. Cheapness and growth are separate measurements, and a
 * position can score high on both ({@link #VALUE_GROWTH} — a cheap compounder) or neither
 * ({@link #NEITHER} — expensive without the growth to justify it). Collapsing them onto one
 * value↔growth line forces a third of a real portfolio into whichever end it is nearest, which is
 * how a loss-making biotech on a negative earnings multiple ends up labelled "value".
 *
 * <p>Funds get their own three buckets rather than joining the 2×2. Yahoo publishes a fund's
 * aggregate price multiples but returns null for its earnings growth, so the growth axis genuinely
 * cannot be measured for them — and inferring "expensive therefore growth" would quietly convert
 * an absence of data into a finding. Keeping them separate makes the gap visible in the table.
 *
 * <p>{@link #UNKNOWN} is load-bearing in the same way {@code SectorBucket.UNCLASSIFIED} is:
 * anything with too little data to score is surfaced by name, never folded into a neighbour.
 */
public enum StyleBucket {

    VALUE("Value", true),
    GROWTH("Growth", true),
    VALUE_GROWTH("Value + Growth", true),
    NEITHER("Neither", true),
    FUND_VALUE("Fund — value-tilted", false),
    FUND_BLEND("Fund — blend", false),
    FUND_GROWTH("Fund — growth-tilted", false),
    FIXED_INCOME("Fixed Income", false),
    // Bullion and the like: no earnings, no sales, no yield, so every axis abstains. That is a
    // property of the asset, not a gap in the data, and it must not read as UNKNOWN — which the
    // UI presents as "go and fix this".
    NON_EQUITY("Non-equity (bullion etc.)", false),
    CASH("Cash", false),
    // Money-market and ultra-short-dated government ETFs. Structural for the same reason bullion
    // is: there are no earnings, sales or growth to score, which is a property of the instrument
    // and not a gap in the feed. Kept apart from CASH so the "% of invested" denominator stays
    // true — these are held positions, unlike a ledger balance.
    CASH_EQUIVALENTS("Cash equivalents", false),
    UNKNOWN("Unclassified", false);

    private final String label;
    private final boolean scored;

    StyleBucket(String label, boolean scored) {
        this.label = label;
        this.scored = scored;
    }

    public String label() {
        return label;
    }

    /** Whether this bucket came out of the two-axis score, as opposed to being structural. */
    public boolean scored() {
        return scored;
    }

    /** The 2×2 cell for a directly-held equity. */
    public static StyleBucket of(boolean cheap, boolean growing) {
        if (cheap && growing) return VALUE_GROWTH;
        if (cheap) return VALUE;
        if (growing) return GROWTH;
        return NEITHER;
    }
}
