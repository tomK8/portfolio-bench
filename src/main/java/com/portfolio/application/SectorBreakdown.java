package com.portfolio.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * JSON DTO for the sector block at the top of Allocation → Breakdown.
 *
 * <p><b>All percentages are fractions</b> (0.5782 = 57.82%), formatted by the client. Two bases
 * ship for every row because they answer different questions and neither is obviously primary:
 * {@code pctOfTotal} includes cash, {@code pctOfInvested} excludes it. Both are computed here so
 * the UI can toggle without a refetch.
 *
 * @param rows         one per non-empty bucket, largest first; {@code gbp} sums to {@code totalGbp}
 * @param aiTech       the cross-cutting theme figure — the number the panel exists to produce
 * @param unclassified positions no rule could place, listed by name rather than absorbed
 * @param lookThrough  per-fund provenance so the look-through is auditable, not implicit
 */
public record SectorBreakdown(
        String asOf,
        BigDecimal totalGbp,
        BigDecimal investedGbp,
        BigDecimal cashGbp,
        List<Row> rows,
        AiTech aiTech,
        List<Contributor> unclassified,
        List<FundNote> lookThrough,
        List<String> warnings) {

    /**
     * @param directGbp  value from positions held outright
     * @param viaFundGbp value attributed by looking through a fund — kept separate so the
     *                   inferred part of every bucket stays visible rather than baked in
     */
    public record Row(String bucket, String label, BigDecimal gbp,
                      BigDecimal pctOfTotal, BigDecimal pctOfInvested,
                      BigDecimal directGbp, BigDecimal viaFundGbp,
                      List<Constituent> constituents) {
    }

    /**
     * One position feeding a bucket, for the click-through popup. The unit is the <b>position</b>,
     * not the underlying company: a fund arrives as a single row carrying its slice, because Yahoo
     * names only a fund's top 10 holdings and several of ours resolve almost none of themselves
     * (MCTS 0%, RBTX 5.9%). Listing positions keeps every penny of the bucket accounted for.
     *
     * @param label            symbol, or {@code "AJBell · GBP"} for a cash balance
     * @param pctOfBucket      share of the bucket this row represents
     * @param shareOfPosition  for a fund slice, how much of that fund landed in this bucket
     *                         (0.178 = 17.8% of EQQQ); {@code null} for a directly-held position
     */
    public record Constituent(String label,
                              BigDecimal gbp,
                              BigDecimal pctOfBucket,
                              boolean viaFund,
                              BigDecimal shareOfPosition) {
    }

    /**
     * The cross-cutting theme figure. <b>Not a row in {@link #rows()} and must never be added to
     * it</b> — it already contains the whole Technology bucket, so summing them double-counts.
     *
     * <p>{@code gbp = fromTechSectorGbp + crossSectorGbp} exactly, and {@code fromTechSectorGbp}
     * equals the Technology row's value. That bridge is what makes the difference between this
     * number and the Technology bucket legible on screen: everything else here is filed by GICS
     * under Communication Services, Consumer Cyclical and the like.
     *
     * @param lowerBound true when any fund contributed, meaning the real figure is at least this.
     *                   Yahoo names only a fund's top 10 holdings, so qualifying names in the
     *                   unnamed tail cannot be counted — the UI renders this as "≥".
     */
    public record AiTech(BigDecimal gbp,
                         BigDecimal pctOfTotal,
                         BigDecimal pctOfInvested,
                         BigDecimal directGbp,
                         BigDecimal viaFundGbp,
                         BigDecimal fromTechSectorGbp,
                         BigDecimal crossSectorGbp,
                         boolean lowerBound,
                         List<AiContributor> contributors) {
    }

    /**
     * One position feeding the AI/Tech figure, with why it counts.
     *
     * @param source      {@code TECHNOLOGY_SECTOR} (its own sector is Technology),
     *                    {@code TAGGED} (an agreed name GICS files elsewhere) or {@code FUND}
     *                    (attributed by look-through, so typically a blend of both)
     * @param sectorLabel the position's own sector bucket — what makes a TAGGED row
     *                    self-explanatory ("Tagged — Communications/Media")
     */
    public record AiContributor(String symbol,
                                BigDecimal gbp,
                                String source,
                                String sectorLabel,
                                BigDecimal fromTechSectorGbp,
                                BigDecimal crossSectorGbp) {
    }

    public record Contributor(String symbol, BigDecimal gbp) {
    }

    /**
     * @param fetchedAt       when these weights were last refreshed from Yahoo
     * @param nameCoveragePct fraction of the fund identified by name (top 10 only)
     * @param techPct         the fund's raw technology weight
     */
    public record FundNote(String symbol, BigDecimal gbp, String fetchedAt,
                           BigDecimal nameCoveragePct, BigDecimal techPct) {
    }
}
