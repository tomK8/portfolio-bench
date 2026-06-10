package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-symbol and per-year dividend income, plus yield-on-cost and trailing yield against
 * current market value.
 *
 * <p>Source: {@code cash_transactions WHERE type='DIVIDEND'} (already loaded together with
 * TRANSACTION rows by {@link CashTransactionRepository#loadDividendTransactions()}).
 * Current shares and cost basis come from running the TRANSACTION rows back through
 * {@link CashLedgerReconstructor} — same engine the cash-ledger view uses. Current market
 * value uses live intraday prices ({@link IntradayPriceRepository#loadLatestIntradayPrices})
 * and live FX.
 *
 * <p>Two yields per symbol:
 * <ul>
 *   <li><b>Yield-on-cost</b> = {@code trailing_12mo_dividends / cost_basis}. "What I earn
 *       in income each year against what I paid." Independent of today's price — moves
 *       only when the company changes its dividend or I add to / trim the position.</li>
 *   <li><b>Trailing yield</b> = {@code trailing_12mo_dividends / current_market_value}.
 *       Tracks the broker-screen forward yield. Comparable across positions and against the
 *       company's advertised number.</li>
 * </ul>
 *
 * <p>The per-symbol table shows currently-held positions; sold positions with historical
 * dividends are appended at the bottom (shares=0, cost basis=0, no yields) so the totals
 * tab match the annual chart.
 *
 * <p>The annual chart stacks per-symbol bars: top {@link #ANNUAL_TOP_N} symbols by lifetime
 * income each get their own band; the rest are bucketed into "Other" to keep the legend
 * legible.
 */
public class DividendIncomeService {

    private static final Logger log = LoggerFactory.getLogger(DividendIncomeService.class);
    private static final int ANNUAL_TOP_N = 8;
    private static final int YIELD_SCALE = 6;
    private static final int GBP_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public DividendIncomeService(CashTransactionRepository cashRepo,
                                 IntradayPriceRepository intradayRepo,
                                 FxRateProvider fxRateProvider,
                                 YahooTickerMap tickerMap) {
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public DividendIncome summary() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();
        if (rows.isEmpty()) return DividendIncome.empty();

        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            // Charts and YoC don't depend on FX; only current-market-value lookups do.
            // Degrade rather than fail — log + carry on with an empty rate map so GBP rows
            // value cleanly and USD/EUR rows fall back to cost basis.
            log.warn("Could not fetch FX rates for dividend tab; non-GBP market values will fall back to cost basis", e);
            rates = Map.of();
        }

        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> latestBySymbol = latestPricesBySymbol(positions);

        return build(rows, positions, latestBySymbol, rates, LocalDate.now());
    }

    /** Visible for testing — feed synthetic rows and a fixed "today". */
    static DividendIncome build(List<CashTransaction> rows,
                                List<Position> positions,
                                Map<String, IntradayPrice> latestBySymbol,
                                Map<String, BigDecimal> rates,
                                LocalDate today) {
        LocalDate trailingCutoff = today.minusYears(1);

        Map<String, BigDecimal> lifetimeBySymbol = new LinkedHashMap<>();
        Map<String, BigDecimal> trailingBySymbol = new HashMap<>();
        Map<Integer, BigDecimal> totalByYear = new TreeMap<>();
        Map<String, Map<Integer, BigDecimal>> bySymbolYear = new LinkedHashMap<>();
        BigDecimal lifetimeTotal = BigDecimal.ZERO;
        BigDecimal trailingTotal = BigDecimal.ZERO;

        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.DIVIDEND) continue;
            if (t.symbol() == null || t.symbol().isBlank()) continue;
            String sym = t.symbol().toUpperCase();
            BigDecimal amt = BigDecimal.valueOf(t.amountGbp());
            LocalDate d = LocalDate.parse(t.transactionDate());
            int year = d.getYear();
            lifetimeBySymbol.merge(sym, amt, BigDecimal::add);
            lifetimeTotal = lifetimeTotal.add(amt);
            if (d.isAfter(trailingCutoff)) {
                trailingBySymbol.merge(sym, amt, BigDecimal::add);
                trailingTotal = trailingTotal.add(amt);
            }
            totalByYear.merge(year, amt, BigDecimal::add);
            bySymbolYear.computeIfAbsent(sym, k -> new TreeMap<>())
                    .merge(year, amt, BigDecimal::add);
        }

        Map<String, Position> heldBySymbol = new LinkedHashMap<>();
        for (Position p : positions) heldBySymbol.put(p.securityId().toUpperCase(), p);

        List<SymbolRow> heldRows = new ArrayList<>();
        BigDecimal portfolioCostBasis = BigDecimal.ZERO;
        BigDecimal portfolioMarketValue = BigDecimal.ZERO;
        for (Position p : positions) {
            String sym = p.securityId().toUpperCase();
            IntradayPrice ip = latestBySymbol.get(sym);
            BigDecimal marketValue = positionGbp(p, ip, rates);
            BigDecimal lifetime = lifetimeBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal trailing = trailingBySymbol.getOrDefault(sym, BigDecimal.ZERO);

            BigDecimal yoc = (p.costGbp().signum() > 0)
                    ? trailing.divide(p.costGbp(), YIELD_SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal trailingYield = (marketValue != null && marketValue.signum() > 0)
                    ? trailing.divide(marketValue, YIELD_SCALE, RoundingMode.HALF_UP) : null;

            portfolioCostBasis = portfolioCostBasis.add(p.costGbp());
            if (marketValue != null) portfolioMarketValue = portfolioMarketValue.add(marketValue);

            heldRows.add(new SymbolRow(sym,
                    p.quantity().setScale(4, RoundingMode.HALF_UP),
                    money(p.costGbp()),
                    marketValue == null ? null : money(marketValue),
                    money(lifetime), money(trailing),
                    yoc, trailingYield,
                    String.join(", ", p.accounts()),
                    true));
        }
        heldRows.sort(Comparator.comparing(
                SymbolRow::trailingIncomeGbp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // Closed positions: lifetime divs received but no current holding.
        List<SymbolRow> closedRows = new ArrayList<>();
        for (var e : lifetimeBySymbol.entrySet()) {
            if (heldBySymbol.containsKey(e.getKey())) continue;
            if (e.getValue().signum() == 0) continue;
            closedRows.add(new SymbolRow(e.getKey(),
                    BigDecimal.ZERO, BigDecimal.ZERO, null,
                    money(e.getValue()),
                    money(trailingBySymbol.getOrDefault(e.getKey(), BigDecimal.ZERO)),
                    null, null, "", false));
        }
        closedRows.sort(Comparator.comparing(
                SymbolRow::lifetimeGbp, Comparator.reverseOrder()));

        List<SymbolRow> allRows = new ArrayList<>(heldRows);
        allRows.addAll(closedRows);

        // Annual stacked bars — top-N by lifetime, "Other" lumps the long tail.
        List<String> topSymbols = topNByLifetime(lifetimeBySymbol, ANNUAL_TOP_N);
        List<AnnualBucket> annual = buildAnnualBuckets(totalByYear, bySymbolYear, topSymbols);

        BigDecimal portfolioYoc = (portfolioCostBasis.signum() > 0)
                ? trailingTotal.divide(portfolioCostBasis, YIELD_SCALE, RoundingMode.HALF_UP)
                : null;
        BigDecimal portfolioYield = (portfolioMarketValue.signum() > 0)
                ? trailingTotal.divide(portfolioMarketValue, YIELD_SCALE, RoundingMode.HALF_UP)
                : null;

        // Forward income = same 12mo total. A more sophisticated estimator could project
        // declared next-payment dates; rolling the trailing window forward is a defensible,
        // assumption-free default and matches what most platforms display.
        Summary summary = new Summary(
                money(lifetimeTotal), money(trailingTotal), money(trailingTotal),
                money(portfolioCostBasis), money(portfolioMarketValue),
                portfolioYoc, portfolioYield);
        return new DividendIncome(summary, allRows, annual, topSymbols);
    }

    private static List<String> topNByLifetime(Map<String, BigDecimal> lifetimeBySymbol, int n) {
        return lifetimeBySymbol.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<AnnualBucket> buildAnnualBuckets(Map<Integer, BigDecimal> totalByYear,
                                                         Map<String, Map<Integer, BigDecimal>> bySymbolYear,
                                                         List<String> topSymbols) {
        if (totalByYear.isEmpty()) return List.of();
        int firstYear = totalByYear.keySet().iterator().next();
        int lastYear = LocalDate.now().getYear();
        for (Integer y : totalByYear.keySet()) {
            if (y > lastYear) lastYear = y;
        }
        Set<String> topSet = Set.copyOf(topSymbols);
        List<AnnualBucket> out = new ArrayList<>();
        for (int year = firstYear; year <= lastYear; year++) {
            Map<String, BigDecimal> perSymbol = new LinkedHashMap<>();
            BigDecimal other = BigDecimal.ZERO;
            for (var e : bySymbolYear.entrySet()) {
                BigDecimal v = e.getValue().getOrDefault(year, BigDecimal.ZERO);
                if (v.signum() == 0) continue;
                if (topSet.contains(e.getKey())) perSymbol.put(e.getKey(), money(v));
                else other = other.add(v);
            }
            if (other.signum() != 0) perSymbol.put("Other", money(other));
            BigDecimal total = totalByYear.getOrDefault(year, BigDecimal.ZERO);
            out.add(new AnnualBucket(year, money(total), perSymbol));
        }
        return out;
    }

    private static BigDecimal positionGbp(Position p, IntradayPrice ip,
                                          Map<String, BigDecimal> rates) {
        if (ip == null) return null;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        String priceCcy = ip.currency();
        if ("GBp".equals(priceCcy)) {
            price = price.movePointLeft(2);
            priceCcy = "GBP";
        }
        BigDecimal native_ = Instruments.isBond(p.securityId())
                ? price.multiply(p.quantity()).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : price.multiply(p.quantity());
        if ("GBP".equals(priceCcy)) return native_;
        BigDecimal rate = rates.get(priceCcy);
        if (rate == null || rate.signum() == 0) return null;
        return native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private Map<String, IntradayPrice> latestPricesBySymbol(List<Position> positions) {
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (Position p : positions) {
            String sym = p.securityId();
            if (sym == null || sym.isBlank()) continue;
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(upper, ticker);
        }
        Map<String, IntradayPrice> byTicker =
                intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, IntradayPrice> bySymbol = new HashMap<>();
        for (var e : tickerBySymbol.entrySet()) {
            IntradayPrice p = byTicker.get(e.getValue());
            if (p != null) bySymbol.put(e.getKey(), p);
        }
        return bySymbol;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs ----------------------------------------------------------------

    /**
     * One row in the per-symbol table. {@code currentlyHeld=true} → live position with cost
     * basis and (when prices available) market value, YoC, trailing yield. {@code false} →
     * closed position kept around so the historical income still ties to the annual chart;
     * shares/cost-basis are zero, yields null.
     */
    public record SymbolRow(String symbol,
                            BigDecimal shares,
                            BigDecimal costBasisGbp,
                            BigDecimal marketValueGbp,
                            BigDecimal lifetimeGbp,
                            BigDecimal trailingIncomeGbp,
                            BigDecimal yieldOnCost,
                            BigDecimal trailingYield,
                            String accounts,
                            boolean currentlyHeld) {
    }

    public record AnnualBucket(int year, BigDecimal totalGbp, Map<String, BigDecimal> perSymbolGbp) {
    }

    public record Summary(BigDecimal lifetimeIncomeGbp,
                          BigDecimal trailing12mIncomeGbp,
                          BigDecimal forwardIncomeGbp,
                          BigDecimal totalCostBasisGbp,
                          BigDecimal totalMarketValueGbp,
                          BigDecimal portfolioYieldOnCost,
                          BigDecimal portfolioTrailingYield) {

        public static Summary empty() {
            return new Summary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        }
    }

    public record DividendIncome(Summary summary,
                                 List<SymbolRow> rows,
                                 List<AnnualBucket> annual,
                                 List<String> stackedSymbolOrder) {

        public static DividendIncome empty() {
            return new DividendIncome(Summary.empty(), List.of(), List.of(), List.of());
        }
    }
}
