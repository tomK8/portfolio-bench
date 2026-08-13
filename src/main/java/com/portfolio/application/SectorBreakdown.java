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
                      BigDecimal directGbp, BigDecimal viaFundGbp) {
    }

    /**
     * @param lowerBound true when any fund contributed, meaning the real figure is at least this.
     *                   Yahoo names only a fund's top 10 holdings, so qualifying names in the
     *                   unnamed tail cannot be counted — the UI renders this as "≥".
     */
    public record AiTech(BigDecimal gbp,
                         BigDecimal pctOfTotal,
                         BigDecimal pctOfInvested,
                         BigDecimal directGbp,
                         BigDecimal viaFundGbp,
                         boolean lowerBound,
                         List<Contributor> contributors) {
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
