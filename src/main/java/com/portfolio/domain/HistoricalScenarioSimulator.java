package com.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * "What if next year played out like &lt;past period&gt;?" — takes today's GBP-valued positions
 * and projects their values day-by-day through a historical window {@code [from, to]} by
 * applying each symbol's actual return curve from that window.
 *
 * <p>Inputs are GBP-denominated at the window-start anchor: the simulator scales each
 * position by {@code price(d) / price(from)} from the supplied price series, so currency is
 * irrelevant as long as each series uses one consistent unit. Pass {@code adj_close} (total
 * return) for equities; pass clean {@code close} for bonds and let {@code couponRate}
 * accrue the income side separately.
 *
 * <p>Substitutes: a {@code substitutes} mapping replaces a symbol's return curve with another's
 * (e.g. {@code NVDA → EQQQ} so the projection doesn't extrapolate a single name's outlier
 * decade onto the next one). The original GBP value stays — only the return path changes.
 * A substituted equity does <em>not</em> accrue bond coupon even if the original was a bond,
 * since the projection inherits the substitute's nature.
 *
 * <p>Missing data: if neither the original nor its substitute have any rows in the supplied
 * price series, the position is held flat in GBP across the window and {@code missing} is set
 * on its result row. Partial coverage (some days missing within the window) is filled by
 * floor-fill, falling back to the earliest known close (ceil-fill) for dates before any data —
 * same convention used by {@link com.portfolio.application.PortfolioValueService}.
 *
 * <p>Cash compounds at a flat annual rate per currency via continuous-day compounding
 * ({@code V × (1 + r)^(days/365.25)}). Stage 2 will swap this for a time-varying historical
 * rate series; the math here is unchanged, only the rate lookup moves.
 */
public class HistoricalScenarioSimulator {

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");
    private static final int SCALE = 10;

    public Result simulate(List<Position> positions,
                           List<CashBucket> cash,
                           LocalDate from, LocalDate to,
                           Map<String, String> substitutes,
                           Map<String, NavigableMap<LocalDate, BigDecimal>> priceSeries) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to must be non-null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to' (got " + from + " > " + to + ")");
        }
        if (positions == null) positions = List.of();
        if (cash == null) cash = List.of();
        if (substitutes == null) substitutes = Map.of();
        if (priceSeries == null) priceSeries = Map.of();

        Set<String> seen = new HashSet<>();
        for (Position p : positions) {
            if (!seen.add(p.symbol().toUpperCase())) {
                throw new IllegalArgumentException("Duplicate position symbol: " + p.symbol());
            }
        }

        List<Resolved> resolved = new ArrayList<>();
        for (Position p : positions) {
            String sub = substitutes.get(p.symbol().toUpperCase());
            String effective = (sub != null && !sub.isBlank()) ? sub.toUpperCase() : p.symbol().toUpperCase();
            NavigableMap<LocalDate, BigDecimal> series = priceSeries.get(effective);
            BigDecimal startPrice = series == null ? null : priceOn(series, from);
            // If the chosen series has no data at all, try falling back to the original when a
            // substitute was supplied — the user may have asked for EQQQ but EQQQ's series might
            // not yet be loaded; better to project the original than to give up.
            if ((series == null || startPrice == null) && sub != null && !sub.isBlank()) {
                NavigableMap<LocalDate, BigDecimal> originalSeries = priceSeries.get(p.symbol().toUpperCase());
                BigDecimal originalStart = originalSeries == null ? null : priceOn(originalSeries, from);
                if (originalStart != null) {
                    effective = p.symbol().toUpperCase();
                    series = originalSeries;
                    startPrice = originalStart;
                    sub = null;
                }
            }
            boolean missing = (series == null || startPrice == null);
            resolved.add(new Resolved(p, effective, series, startPrice, sub != null && !sub.isBlank(), missing));
        }

        List<DataPoint> timeline = new ArrayList<>();
        LocalDate sample = from;
        while (!sample.isAfter(to)) {
            BigDecimal total = BigDecimal.ZERO;
            long days = ChronoUnit.DAYS.between(from, sample);
            for (Resolved r : resolved) {
                total = total.add(valueAt(r, sample, days));
            }
            for (CashBucket cb : cash) {
                total = total.add(cashValueAt(cb, days));
            }
            timeline.add(new DataPoint(sample, total.setScale(2, RoundingMode.HALF_UP)));
            sample = sample.plusDays(1);
        }

        long totalDays = ChronoUnit.DAYS.between(from, to);
        List<SymbolResult> perSymbol = new ArrayList<>();
        for (Resolved r : resolved) {
            BigDecimal endVal = valueAt(r, to, totalDays);
            BigDecimal start = r.position.valueGbp();
            BigDecimal pnl = endVal.subtract(start);
            BigDecimal ret = start.signum() == 0 ? null
                    : pnl.divide(start, 6, RoundingMode.HALF_UP);
            perSymbol.add(new SymbolResult(
                    r.position.symbol(),
                    r.effectiveSymbol,
                    start.setScale(2, RoundingMode.HALF_UP),
                    endVal.setScale(2, RoundingMode.HALF_UP),
                    pnl.setScale(2, RoundingMode.HALF_UP),
                    ret,
                    r.substituted,
                    r.missing));
        }

        List<CashResult> perCash = new ArrayList<>();
        for (CashBucket cb : cash) {
            BigDecimal endVal = cashValueAt(cb, totalDays);
            BigDecimal pnl = endVal.subtract(cb.valueGbp());
            BigDecimal ret = cb.valueGbp().signum() == 0 ? null
                    : pnl.divide(cb.valueGbp(), 6, RoundingMode.HALF_UP);
            perCash.add(new CashResult(
                    cb.currency(),
                    cb.valueGbp().setScale(2, RoundingMode.HALF_UP),
                    endVal.setScale(2, RoundingMode.HALF_UP),
                    pnl.setScale(2, RoundingMode.HALF_UP),
                    ret));
        }

        BigDecimal startTotal = BigDecimal.ZERO;
        for (Position p : positions) startTotal = startTotal.add(p.valueGbp());
        for (CashBucket cb : cash) startTotal = startTotal.add(cb.valueGbp());
        BigDecimal endTotal = timeline.isEmpty() ? startTotal
                : timeline.get(timeline.size() - 1).totalValueGbp();
        BigDecimal totalPnl = endTotal.subtract(startTotal);
        BigDecimal totalReturn = startTotal.signum() == 0 ? null
                : totalPnl.divide(startTotal, 6, RoundingMode.HALF_UP);

        return new Result(from, to, timeline, perSymbol, perCash,
                startTotal.setScale(2, RoundingMode.HALF_UP),
                endTotal.setScale(2, RoundingMode.HALF_UP),
                totalPnl.setScale(2, RoundingMode.HALF_UP),
                totalReturn);
    }

    /** GBP value of position {@code r} on {@code date}, {@code daysElapsed} days into the window. */
    private static BigDecimal valueAt(Resolved r, LocalDate date, long daysElapsed) {
        BigDecimal start = r.position.valueGbp();
        if (r.missing) return start;
        BigDecimal price = priceOn(r.series, date);
        if (price == null || price.signum() == 0) return start;
        BigDecimal priceReturn = price.divide(r.startPrice, SCALE, RoundingMode.HALF_UP);
        BigDecimal value = start.multiply(priceReturn);
        // Coupon only when the original is a bond AND no substitute is taking over the return path.
        if (!r.substituted && r.position.isBond() && r.position.couponRate() != null
                && r.position.couponRate().signum() > 0 && daysElapsed > 0) {
            BigDecimal yearsElapsed = BigDecimal.valueOf(daysElapsed)
                    .divide(DAYS_PER_YEAR, SCALE, RoundingMode.HALF_UP);
            BigDecimal coupon = start.multiply(r.position.couponRate()).multiply(yearsElapsed);
            value = value.add(coupon);
        }
        return value;
    }

    private static BigDecimal cashValueAt(CashBucket cb, long daysElapsed) {
        BigDecimal v = cb.valueGbp();
        if (daysElapsed == 0 || cb.annualRate() == null || cb.annualRate().signum() == 0) return v;
        double r = cb.annualRate().doubleValue();
        double years = daysElapsed / 365.25;
        double factor = Math.pow(1.0 + r, years);
        return v.multiply(BigDecimal.valueOf(factor));
    }

    /** Floor-fill price lookup with ceil-fill fallback for dates before any data. */
    private static BigDecimal priceOn(NavigableMap<LocalDate, BigDecimal> series, LocalDate date) {
        if (series == null || series.isEmpty()) return null;
        Map.Entry<LocalDate, BigDecimal> e = series.floorEntry(date);
        if (e == null) e = series.firstEntry();
        return e.getValue();
    }

    // ---- DTOs --------------------------------------------------------------

    /**
     * Today's GBP-valued position to project forward.
     *
     * @param couponRate annual coupon as a fraction (e.g. 0.045 for 4.5%) — used only when
     *                   {@code isBond} and the position is not being substituted. {@code null}
     *                   or zero disables coupon accrual.
     */
    public record Position(String symbol, BigDecimal valueGbp, boolean isBond, BigDecimal couponRate) {
        public Position {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol required");
            }
            if (valueGbp == null) throw new IllegalArgumentException("valueGbp required");
        }
    }

    /**
     * Cash bucket compounding at a flat annual rate.
     *
     * @param annualRate fractional, e.g. 0.05 for 5%. {@code null} or zero → cash stays flat.
     */
    public record CashBucket(String currency, BigDecimal valueGbp, BigDecimal annualRate) {
        public CashBucket {
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency required");
            }
            if (valueGbp == null) throw new IllegalArgumentException("valueGbp required");
        }
    }

    public record DataPoint(LocalDate date, BigDecimal totalValueGbp) {
    }

    /**
     * Per-position outcome.
     *
     * @param effectiveSymbol the symbol whose return curve was actually applied — equals
     *                        {@code symbol} unless {@code substituted}.
     * @param substituted     true when a user-supplied substitute was used.
     * @param missing         true when neither the original nor the substitute had any price
     *                        rows in the window; the row is held flat at {@code startValueGbp}.
     */
    public record SymbolResult(String symbol, String effectiveSymbol,
                               BigDecimal startValueGbp, BigDecimal endValueGbp,
                               BigDecimal pnlGbp, BigDecimal periodReturn,
                               boolean substituted, boolean missing) {
    }

    public record CashResult(String currency,
                             BigDecimal startValueGbp, BigDecimal endValueGbp,
                             BigDecimal pnlGbp, BigDecimal periodReturn) {
    }

    public record Result(LocalDate from, LocalDate to,
                         List<DataPoint> timeline,
                         List<SymbolResult> perSymbol,
                         List<CashResult> perCash,
                         BigDecimal startTotalGbp, BigDecimal endTotalGbp,
                         BigDecimal pnlGbp, BigDecimal periodReturn) {
    }

    private record Resolved(Position position, String effectiveSymbol,
                            NavigableMap<LocalDate, BigDecimal> series,
                            BigDecimal startPrice, boolean substituted, boolean missing) {
    }
}
