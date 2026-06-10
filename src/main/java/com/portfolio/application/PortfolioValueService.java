package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the historical GBP value timeline of the portfolio for the chart.
 *
 * <p>Forward-replays the entire cash ledger once, maintaining running per-symbol quantity and
 * per-{@code (account, currency)} native cash. At each calendar day the running state is valued:
 * positions at the most recent close ≤ that date (forward-fill, like a finance "as-of" lookup);
 * cash at the historical USD/EUR FX. Weekends and holidays inherit the prior trading day's
 * close via the floor-fill lookup. The Roth IRA brought-forward USD seed (from the
 * {@code roth_balance_brought_forward} KV file) is folded into Roth's USD cash on its
 * earliest ledger date — without it the chart would understate Roth by the seed.
 *
 * <p>Known approximations (good enough for a high-level view, not for accounting):
 * <ul>
 *   <li>Gilts have spotty pre-Tradeweb coverage; for dates earlier than a gilt's first known
 *       close it's valued at zero rather than its cost basis.</li>
 *   <li>Yahoo's stored close is split-adjusted to today's basis; we multiply by each bar's
 *       {@code split_factor} to recover the raw close so historical {@code qty × price}
 *       matches the user's basis at the time of trade. Requires the price-fetch path to
 *       populate {@code split_factor} (see {@code YahooPriceFetcher}); pre-migration rows
 *       default to 1.0, meaning a "Rebuild adj_close" is needed once after deploy to
 *       backfill split factors for any pre-split holdings.</li>
 *   <li>FX outside Frankfurter's range falls back to the nearest earlier rate.</li>
 * </ul>
 */
public class PortfolioValueService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioValueService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final HistoricalFxRateProvider fxProvider;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore settings;

    public PortfolioValueService(CashTransactionRepository cashRepo,
                                 PriceHistoryRepository priceRepo,
                                 HistoricalFxRateProvider fxProvider,
                                 YahooTickerMap tickerMap,
                                 KeyValueStore settings) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fxProvider = fxProvider;
        this.tickerMap = tickerMap;
        this.settings = settings;
    }

    public ValueTimeline timeline() {
        List<CashTransaction> txs = cashRepo.loadAllTransactions();
        if (txs.isEmpty()) return new ValueTimeline(List.of(), List.of());

        LocalDate start = LocalDate.parse(txs.get(0).transactionDate());
        LocalDate end = LocalDate.now();

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPriceSeries(txs);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(start, end);

        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        LocalDate rothStart = rothStartStr == null ? null : LocalDate.parse(rothStartStr);
        boolean rothSeeded = false;

        Map<String, BigDecimal> qtyBySymbol = new HashMap<>();      // symbol → net qty
        Map<String, BigDecimal> costBySymbol = new HashMap<>();     // symbol → running GBP cost basis (WAC)
        Map<String, BigDecimal> cashByAccountCcy = new HashMap<>(); // "account|ccy" → native cash
        Map<String, HeldRange> heldRange = new LinkedHashMap<>();   // first/last date qty > 0, per symbol

        List<DataPoint> points = new ArrayList<>();
        int idx = 0;
        LocalDate sample = start;
        while (!sample.isAfter(end)) {
            while (idx < txs.size() && !LocalDate.parse(txs.get(idx).transactionDate()).isAfter(sample)) {
                applyWithCost(qtyBySymbol, costBySymbol, cashByAccountCcy, txs.get(idx));
                idx++;
            }
            if (!rothSeeded && rothStart != null && !sample.isBefore(rothStart)) {
                cashByAccountCcy.merge("RothIRA|USD", rothSeedUsd, BigDecimal::add);
                rothSeeded = true;
            }
            recordHeld(qtyBySymbol, heldRange, sample);
            BigDecimal v = valueAt(sample, qtyBySymbol, cashByAccountCcy, prices, fx);
            // Cost basis line uses weighted-average cost per symbol — simpler than FIFO and
            // adequate for the visual "where did gains come from" read. Sells release a
            // proportional slice of running cost; splits scale qty but leave cost basis
            // invariant.
            BigDecimal costBasis = BigDecimal.ZERO;
            for (BigDecimal c : costBySymbol.values()) costBasis = costBasis.add(c);
            points.add(new DataPoint(sample.toString(),
                    v.setScale(2, RoundingMode.HALF_UP),
                    costBasis.setScale(2, RoundingMode.HALF_UP)));
            sample = sample.plusDays(1);
        }

        List<MissingPrice> missing = new ArrayList<>();
        for (Map.Entry<String, HeldRange> e : heldRange.entrySet()) {
            if (!prices.containsKey(e.getKey())) {
                HeldRange r = e.getValue();
                missing.add(new MissingPrice(e.getKey(), r.from.toString(), r.to.toString()));
                log.warn("Portfolio value chart: no price_history rows for {} (held {} to {}) — "
                        + "valued at zero across the held range. Add a Tradeweb file or a daily "
                        + "Yahoo entry to remove the dip.", e.getKey(), r.from, r.to);
            }
        }
        return new ValueTimeline(points, missing);
    }

    /**
     * Daily GBP portfolio value from the first ledger date through today. Same replay engine
     * as {@link #timeline()} but stepped one calendar day at a time — used by
     * {@link PortfolioReturnService} to chain-link daily time-weighted returns. Weekends and
     * holidays inherit the previous trading day's price via the floor-fill lookup in
     * {@link #positionGbp}, so the series is dense and gap-free.
     */
    public List<DailyValue> dailyValues() {
        List<CashTransaction> txs = cashRepo.loadAllTransactions();
        if (txs.isEmpty()) return List.of();

        LocalDate start = LocalDate.parse(txs.get(0).transactionDate());
        LocalDate end = LocalDate.now();

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPriceSeries(txs);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(start, end);

        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        LocalDate rothStart = rothStartStr == null ? null : LocalDate.parse(rothStartStr);
        boolean rothSeeded = false;

        Map<String, BigDecimal> qtyBySymbol = new HashMap<>();
        Map<String, BigDecimal> cashByAccountCcy = new HashMap<>();

        List<DailyValue> points = new ArrayList<>();
        int idx = 0;
        LocalDate sample = start;
        while (!sample.isAfter(end)) {
            while (idx < txs.size() && !LocalDate.parse(txs.get(idx).transactionDate()).isAfter(sample)) {
                apply(qtyBySymbol, cashByAccountCcy, txs.get(idx));
                idx++;
            }
            if (!rothSeeded && rothStart != null && !sample.isBefore(rothStart)) {
                cashByAccountCcy.merge("RothIRA|USD", rothSeedUsd, BigDecimal::add);
                rothSeeded = true;
            }
            BigDecimal invested = investedAt(sample, qtyBySymbol, prices, fx);
            BigDecimal cash = cashAt(sample, cashByAccountCcy, fx);
            points.add(new DailyValue(sample, invested.add(cash), invested));
            sample = sample.plusDays(1);
        }
        return points;
    }

    /** Position-only GBP value at {@code sample} — same formula as {@link #valueAt} minus the cash legs. */
    private static BigDecimal investedAt(LocalDate sample,
                                         Map<String, BigDecimal> qtyBySymbol,
                                         Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                         Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : qtyBySymbol.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty.signum() <= 0) continue;
            BigDecimal v = positionGbp(e.getKey(), qty, sample, prices, fx);
            if (v != null) total = total.add(v);
        }
        return total;
    }

    private static BigDecimal cashAt(LocalDate sample,
                                     Map<String, BigDecimal> cashByAccountCcy,
                                     Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : cashByAccountCcy.entrySet()) {
            BigDecimal native_ = e.getValue();
            if (native_.signum() == 0) continue;
            String currency = e.getKey().substring(e.getKey().indexOf('|') + 1);
            BigDecimal gbp = toGbp(native_, currency, sample, fx);
            if (gbp != null) total = total.add(gbp);
        }
        return total;
    }

    /** Track the first and last sample dates at which {@code qty > 0} for each symbol. */
    private static void recordHeld(Map<String, BigDecimal> qtyBySymbol,
                                   Map<String, HeldRange> heldRange, LocalDate sample) {
        for (Map.Entry<String, BigDecimal> e : qtyBySymbol.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            heldRange.compute(e.getKey(), (sym, prior) -> prior == null
                    ? new HeldRange(sample, sample) : new HeldRange(prior.from, sample));
        }
    }

    // ---- Replay ----------------------------------------------------------

    private static void apply(Map<String, BigDecimal> qtyBySymbol,
                              Map<String, BigDecimal> cashByAccountCcy,
                              CashTransaction t) {
        String acctCcy = t.account().dbValue() + "|" + t.currency();
        cashByAccountCcy.merge(acctCcy, BigDecimal.valueOf(t.amount()), BigDecimal::add);
        if (t.type() != TransactionType.TRANSACTION) return;
        String sym = t.symbol();
        if (sym == null || sym.isEmpty() || sym.equals("GBP") || sym.equals("CASH")) return;
        BigDecimal q;
        if (t.amount() < 0) q = BigDecimal.valueOf(Math.abs(t.quantity()));        // buy
        else if (t.amount() > 0) q = BigDecimal.valueOf(-Math.abs(t.quantity()));  // sell
        else q = BigDecimal.valueOf(t.quantity());                                 // split (signed)
        qtyBySymbol.merge(sym.toUpperCase(), q, BigDecimal::add);
    }

    /**
     * Same as {@link #apply} but additionally maintains a weighted-average GBP cost basis per
     * symbol. Buy → add the row's amountGbp to running cost; sell → release a proportional
     * slice of running cost (so a half-sell halves the remaining cost basis); split → leave
     * cost untouched (qty scales, total cost is invariant under splits, cost-per-share
     * scales accordingly). Drives the cost-basis overlay on the Value-over-time chart.
     */
    private static void applyWithCost(Map<String, BigDecimal> qtyBySymbol,
                                      Map<String, BigDecimal> costBySymbol,
                                      Map<String, BigDecimal> cashByAccountCcy,
                                      CashTransaction t) {
        String acctCcy = t.account().dbValue() + "|" + t.currency();
        cashByAccountCcy.merge(acctCcy, BigDecimal.valueOf(t.amount()), BigDecimal::add);
        if (t.type() != TransactionType.TRANSACTION) return;
        String sym = t.symbol();
        if (sym == null || sym.isEmpty() || sym.equals("GBP") || sym.equals("CASH")) return;
        String upper = sym.toUpperCase();

        BigDecimal qtyPrior = qtyBySymbol.getOrDefault(upper, BigDecimal.ZERO);
        BigDecimal costPrior = costBySymbol.getOrDefault(upper, BigDecimal.ZERO);

        if (t.amount() < 0) {
            // Buy: add this lot's GBP cost and shares.
            BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
            BigDecimal cost = BigDecimal.valueOf(Math.abs(t.amountGbp()));
            qtyBySymbol.put(upper, qtyPrior.add(qty));
            costBySymbol.put(upper, costPrior.add(cost));
        } else if (t.amount() > 0) {
            // Sell: release proportional cost. share = (sellQty / priorQty).
            BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
            BigDecimal newQty = qtyPrior.subtract(qty);
            if (qtyPrior.signum() > 0) {
                BigDecimal frac = qty.divide(qtyPrior, 10, RoundingMode.HALF_UP);
                BigDecimal costReleased = costPrior.multiply(frac);
                BigDecimal costRemaining = costPrior.subtract(costReleased);
                if (newQty.signum() <= 0) {
                    // Fully closed → drop cost regardless of FP residual.
                    costBySymbol.remove(upper);
                } else {
                    costBySymbol.put(upper, costRemaining);
                }
            }
            if (newQty.signum() <= 0) qtyBySymbol.remove(upper);
            else qtyBySymbol.put(upper, newQty);
        } else {
            // Split: signed qty delta, cost basis invariant.
            qtyBySymbol.put(upper, qtyPrior.add(BigDecimal.valueOf(t.quantity())));
        }
    }

    // ---- Valuation -------------------------------------------------------

    private static BigDecimal valueAt(LocalDate sample,
                                      Map<String, BigDecimal> qtyBySymbol,
                                      Map<String, BigDecimal> cashByAccountCcy,
                                      Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                      Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : qtyBySymbol.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty.signum() <= 0) continue;
            BigDecimal valueGbp = positionGbp(e.getKey(), qty, sample, prices, fx);
            if (valueGbp != null) total = total.add(valueGbp);
        }
        for (Map.Entry<String, BigDecimal> e : cashByAccountCcy.entrySet()) {
            BigDecimal native_ = e.getValue();
            if (native_.signum() == 0) continue;
            String currency = e.getKey().substring(e.getKey().indexOf('|') + 1);
            BigDecimal gbp = toGbp(native_, currency, sample, fx);
            if (gbp != null) total = total.add(gbp);
        }
        return total;
    }

    private static BigDecimal positionGbp(String symbol, BigDecimal qty, LocalDate sample,
                                          Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                          Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        NavigableMap<LocalDate, PricePoint> series = prices.get(symbol);
        if (series == null) return null;
        // Floor-fill (latest close ≤ sample) is the right thing for "as-of" valuation. When
        // sample is older than every stored close — typically a gilt held before the user
        // dropped the first Tradeweb file for it — fall back to the earliest known close
        // (ceil-fill). The position then renders as approximately right rather than zero;
        // the symbol still needs prices added to look exact, but the chart line stays sane.
        Map.Entry<LocalDate, PricePoint> entry = series.floorEntry(sample);
        if (entry == null) entry = series.firstEntry();
        PricePoint pp = entry.getValue();
        // Yahoo's stored close is split-adjusted to today's basis; the ledger's qty is in
        // basis-at-time-of-trade. Multiply by splitFactor (cumulative ratio of splits since
        // the bar's date) to recover the raw close, so qty × price matches what the user
        // actually paid / received on that date.
        BigDecimal price = BigDecimal.valueOf(pp.close() * pp.splitFactor());
        String currency = pp.currency();
        if ("GBp".equals(currency)) {
            price = price.movePointLeft(2);
            currency = "GBP";
        }
        BigDecimal native_ = Instruments.isBond(symbol)
                ? price.multiply(qty).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : price.multiply(qty);
        return toGbp(native_, currency, sample, fx);
    }

    private static BigDecimal toGbp(BigDecimal native_, String currency, LocalDate sample,
                                    Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        if ("GBP".equals(currency)) return native_;
        NavigableMap<LocalDate, BigDecimal> series = fx.get(currency);
        if (series == null) return null;
        BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series, sample);
        if (rate == null || rate.signum() == 0) return null;
        return native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    // ---- Preloads --------------------------------------------------------

    private Map<String, NavigableMap<LocalDate, PricePoint>> loadPriceSeries(List<CashTransaction> txs) {
        Set<String> symbols = new HashSet<>();
        for (CashTransaction t : txs) {
            if (t.type() == TransactionType.TRANSACTION && t.symbol() != null
                    && !t.symbol().isEmpty() && !t.symbol().equals("GBP") && !t.symbol().equals("CASH")) {
                symbols.add(t.symbol().toUpperCase());
            }
        }
        Map<String, NavigableMap<LocalDate, PricePoint>> out = new HashMap<>();
        LocalDate earliest = LocalDate.of(2000, 1, 1);
        LocalDate latest = LocalDate.now();
        for (String sym : symbols) {
            String ticker = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
            List<PriceBar> bars = priceRepo.getPriceHistory(ticker, earliest, latest);
            if (bars.isEmpty()) continue;
            NavigableMap<LocalDate, PricePoint> series = new TreeMap<>();
            for (PriceBar b : bars) series.put(b.date(), new PricePoint(b.close(), b.splitFactor(), b.currency()));
            out.put(sym, series);
        }
        return out;
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> preloadFx(LocalDate start, LocalDate end) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> out = new HashMap<>();
        for (String ccy : List.of("USD", "EUR")) {
            try {
                Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(
                        ccy, start.minusDays(14), end);
                out.put(ccy, new TreeMap<>(series));
            } catch (Exception e) {
                log.warn("Could not fetch historical FX for {}", ccy, e);
            }
        }
        return out;
    }

    // ---- DTOs ------------------------------------------------------------

    private record PricePoint(double close, double splitFactor, String currency) {
    }

    private record HeldRange(LocalDate from, LocalDate to) {
    }

    /**
     * One day's portfolio value. {@code costBasisGbp} is the running weighted-average cost
     * basis of positions (nullable — only set by {@link #timeline()}; {@code WhatIfService}'s
     * synthetic timeline doesn't compute it).
     */
    public record DataPoint(String date, BigDecimal valueGbp, BigDecimal costBasisGbp) {
    }

    /**
     * Daily sample of portfolio GBP value. {@code valueGbp} is total (positions + cash);
     * {@code investedGbp} is the positions-only sub-total, used by
     * {@link PortfolioReturnService} to compute a cash-drag-free TWR.
     */
    public record DailyValue(LocalDate date, BigDecimal valueGbp, BigDecimal investedGbp) {
    }

    /**
     * Symbol that was held during {@code from..to} but has no rows in {@code price_history},
     * so it contributed nothing to the chart across that range. The dashboard surfaces these
     * as a warning panel — the user can drop a price file to fix the dip.
     */
    public record MissingPrice(String symbol, String from, String to) {
    }

    public record ValueTimeline(List<DataPoint> points, List<MissingPrice> missingPrices) {
    }
}
