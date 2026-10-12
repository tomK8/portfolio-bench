package com.portfolio.domain;

/**
 * The sector buckets the Allocation → Breakdown panel reports. Coarser than Yahoo's own sector
 * list in places, but everything that is not a single-name equity gets a top-level home
 * ({@link #FIXED_INCOME}, {@link #CASH}, {@link #CASH_EQUIVALENTS}, {@link #COMMODITIES}) so
 * equity percentages are never quietly diluted by gilts or an idle cash pile.
 *
 * <p><b>The two Consumer sectors are kept apart</b>, as GICS and Yahoo both have them. They were
 * briefly merged into one "Consumer" row, which cost real information for nothing: staples and
 * discretionary are the classic defensive/cyclical pair, they behave differently in a drawdown, and
 * a single row reading ~7% hid a book that was two-thirds discretionary. Nothing was gained by the
 * merge either — Yahoo returns the two separately in <em>both</em> shapes it publishes, the display
 * form on a stock ("Consumer Cyclical" / "Consumer Defensive") and the snake_case keys inside a
 * fund's {@code sectorWeightings} — so the split needs no extra data, no extra fetch and no
 * look-through changes.
 *
 * <p>{@link #UNCLASSIFIED} is load-bearing, not a dumping ground: anything that lands here is
 * surfaced by name in the UI rather than folded into a neighbouring bucket. Silently absorbing an
 * unclassifiable position would understate whichever bucket it really belonged to, and the whole
 * point of the panel is a concentration figure you can trust.
 */
public enum SectorBucket {

    // Label is plain "Technology" because that is exactly what it is — the GICS Technology
    // sector. It was briefly "Tech/AI", which implied it captured the AI names too and led
    // straight to the misreading the AI/Tech tile exists to answer (Alphabet, Amazon and Meta
    // are AI/Tech bets but GICS files them elsewhere, so they are *not* in this bucket).
    // The constant name stays TECH_AI: it is the JSON `bucket` key and the SECTOR_COLORS key
    // in dashboard.js, and renaming it would buy nothing while risking a silent colour miss.
    TECH_AI("Technology"),
    COMMS_MEDIA("Communications/Media"),
    FINANCIALS("Financials"),
    HEALTHCARE("Healthcare"),
    // GICS names rather than Yahoo's ("Consumer Cyclical" / "Consumer Defensive"): discretionary
    // and staples is the vocabulary the split exists to express, and fromYahooSector accepts
    // Yahoo's spellings on the way in regardless.
    CONSUMER_DISCRETIONARY("Consumer Discretionary"),
    CONSUMER_STAPLES("Consumer Staples"),
    INDUSTRIALS_DEFENCE("Industrials/Defence"),
    ENERGY("Energy"),
    MATERIALS_MINING("Materials/Mining"),
    UTILITIES_INFRA("Utilities/Infrastructure"),
    REAL_ESTATE("Real Estate"),
    FIXED_INCOME("Fixed Income"),
    COMMODITIES("Commodities"),
    CASH("Cash"),
    // Money-market and ultra-short-dated government ETFs. A row of its own rather than a merge
    // into CASH, because the two are not the same figure: `cashGbp` on the point is what the
    // ledger holds as a balance, and the cash-vs-invested chart directly beneath this table is
    // drawn from it. Folding a held position into that row would make the table and the chart
    // disagree about what cash is, and would leave the £135k sitting inside `investedGbp` while
    // being reported as uninvested. Adjacent row, honest denominators.
    CASH_EQUIVALENTS("Cash equivalents"),
    UNCLASSIFIED("Unclassified");

    private final String label;

    SectorBucket(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Maps a Yahoo sector name to a bucket, accepting <b>both</b> spellings Yahoo uses: the
     * display form from {@code summaryProfile} ("Financial Services", "Consumer Cyclical") and
     * the snake_case form from {@code topHoldings} ("financial_services", "consumer_cyclical").
     * Normalising here rather than at each call site is what lets a fund's constituent sectors
     * and a directly-held stock's sector be compared at all.
     *
     * @return the bucket, or {@code null} when the sector is unknown/absent — callers decide
     *         whether that means {@link #UNCLASSIFIED} or "skip", which differ in the AI/Tech math
     */
    public static SectorBucket fromYahooSector(String sector) {
        if (sector == null || sector.isBlank()) return null;
        String key = sector.trim().toLowerCase().replace(' ', '_').replace("-", "_");
        return switch (key) {
            case "technology" -> TECH_AI;
            case "communication_services", "communications" -> COMMS_MEDIA;
            case "financial_services", "financial" -> FINANCIALS;
            case "healthcare", "health_care" -> HEALTHCARE;
            case "consumer_cyclical", "consumer_discretionary" -> CONSUMER_DISCRETIONARY;
            case "consumer_defensive", "consumer_staples" -> CONSUMER_STAPLES;
            case "industrials" -> INDUSTRIALS_DEFENCE;
            case "energy" -> ENERGY;
            case "basic_materials" -> MATERIALS_MINING;
            case "utilities" -> UTILITIES_INFRA;
            case "realestate", "real_estate" -> REAL_ESTATE;
            default -> null;
        };
    }
}
