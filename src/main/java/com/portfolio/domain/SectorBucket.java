package com.portfolio.domain;

/**
 * The sector buckets the Allocation → Breakdown panel reports. Coarser than Yahoo's own sector
 * list on purpose: the two Consumer sectors are merged, and everything that is not a single-name
 * equity gets a top-level home ({@link #FIXED_INCOME}, {@link #CASH}, {@link #COMMODITIES}) so
 * equity percentages are never quietly diluted by gilts or an idle cash pile.
 *
 * <p>{@link #UNCLASSIFIED} is load-bearing, not a dumping ground: anything that lands here is
 * surfaced by name in the UI rather than folded into a neighbouring bucket. Silently absorbing an
 * unclassifiable position would understate whichever bucket it really belonged to, and the whole
 * point of the panel is a concentration figure you can trust.
 */
public enum SectorBucket {

    TECH_AI("Tech/AI"),
    COMMS_MEDIA("Communications/Media"),
    FINANCIALS("Financials"),
    HEALTHCARE("Healthcare"),
    CONSUMER("Consumer"),
    INDUSTRIALS_DEFENCE("Industrials/Defence"),
    ENERGY("Energy"),
    MATERIALS_MINING("Materials/Mining"),
    UTILITIES_INFRA("Utilities/Infrastructure"),
    REAL_ESTATE("Real Estate"),
    FIXED_INCOME("Fixed Income"),
    COMMODITIES("Commodities"),
    CASH("Cash"),
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
            case "consumer_cyclical", "consumer_defensive" -> CONSUMER;
            case "industrials" -> INDUSTRIALS_DEFENCE;
            case "energy" -> ENERGY;
            case "basic_materials" -> MATERIALS_MINING;
            case "utilities" -> UTILITIES_INFRA;
            case "realestate", "real_estate" -> REAL_ESTATE;
            default -> null;
        };
    }
}
