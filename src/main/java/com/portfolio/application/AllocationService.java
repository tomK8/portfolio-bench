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
 * Time series of portfolio composition: GBP per held symbol plus aggregated cash, at weekly
 * samples from the first ledger date through today. Feeds three views on the dashboard:
 * a cash-vs-invested stacked area, a per-symbol stacked area (top-N + Other client-side),
 * and a date-picker snapshot showing the full breakdown on the chosen day.
 *
 * <p>Same forward-replay engine as {@link PortfolioValueService} — duplicated rather than
 * shared because the consumer-specific bits (snapshot shape vs. single total) make the
 * common kernel small. If a fourth caller appears, lift it into a {@code PortfolioReplay}
 * helper at that point.
 *
 * <p>Weekly cadence is the trade-off: positions don't change minute-to-minute, daily would
 * 7× the payload for no readable benefit on a multi-year chart, and the trailing "today"
 * sample is always appended so the latest state is reflected even mid-week.
 */
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SAMPLE_INTERVAL_DAYS = 7;

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final HistoricalFxRateProvider fxProvider;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore settings;

    public AllocationService(CashTransactionRepository cashRepo,
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

    public AllocationTimeline timeline() {
        List<CashTransaction> txs = cashRepo.loadAllTransactions();
        if (txs.isEmpty()) return new AllocationTimeline(List.of());

        LocalDate start = LocalDate.parse(txs.get(0).transactionDate());
        LocalDate today = LocalDate.now();

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPriceSeries(txs);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(start, today);

        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        LocalDate rothStart = rothStartStr == null ? null : LocalDate.parse(rothStartStr);
        boolean rothSeeded = false;

        Map<String, BigDecimal> qty = new HashMap<>();
        Map<String, BigDecimal> cashByAcctCcy = new HashMap<>();

        List<AllocationPoint> points = new ArrayList<>();
        int idx = 0;
        LocalDate sample = start;
        while (!sample.isAfter(today)) {
            while (idx < txs.size() && !LocalDate.parse(txs.get(idx).transactionDate()).isAfter(sample)) {
                apply(qty, cashByAcctCcy, txs.get(idx));
                idx++;
            }
            if (!rothSeeded && rothStart != null && !sample.isBefore(rothStart)) {
                cashByAcctCcy.merge("RothIRA|USD", rothSeedUsd, BigDecimal::add);
                rothSeeded = true;
            }
            points.add(snapshot(sample, qty, cashByAcctCcy, prices, fx));
            sample = sample.plusDays(SAMPLE_INTERVAL_DAYS);
        }
        // Replay remaining transactions through today and append a final sample so the latest
        // composition is in the series even if it's mid-week (the user opening the snapshot
        // panel without a date selection sees the current state, not last Sunday's).
        while (idx < txs.size() && !LocalDate.parse(txs.get(idx).transactionDate()).isAfter(today)) {
            apply(qty, cashByAcctCcy, txs.get(idx));
            idx++;
        }
        if (points.isEmpty() || !points.get(points.size() - 1).date().equals(today.toString())) {
            points.add(snapshot(today, qty, cashByAcctCcy, prices, fx));
        }
        return new AllocationTimeline(points);
    }

    private AllocationPoint snapshot(LocalDate sample,
                                     Map<String, BigDecimal> qty,
                                     Map<String, BigDecimal> cashByAcctCcy,
                                     Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                     Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        Map<String, BigDecimal> symbolGbp = new LinkedHashMap<>();
        BigDecimal investedGbp = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : qty.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            BigDecimal v = positionGbp(e.getKey(), e.getValue(), sample, prices, fx);
            if (v == null || v.signum() == 0) continue;
            BigDecimal rounded = v.setScale(2, RoundingMode.HALF_UP);
            symbolGbp.put(e.getKey(), rounded);
            investedGbp = investedGbp.add(rounded);
        }
        BigDecimal cashGbp = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : cashByAcctCcy.entrySet()) {
            BigDecimal n = e.getValue();
            if (n.signum() == 0) continue;
            String ccy = e.getKey().substring(e.getKey().indexOf('|') + 1);
            BigDecimal g = toGbp(n, ccy, sample, fx);
            if (g != null) cashGbp = cashGbp.add(g);
        }
        cashGbp = cashGbp.setScale(2, RoundingMode.HALF_UP);
        return new AllocationPoint(sample.toString(), cashGbp,
                investedGbp.setScale(2, RoundingMode.HALF_UP),
                cashGbp.add(investedGbp).setScale(2, RoundingMode.HALF_UP),
                symbolGbp);
    }

    // ---- replay -------------------------------------------------------------

    private static void apply(Map<String, BigDecimal> qtyBySymbol,
                              Map<String, BigDecimal> cashByAccountCcy,
                              CashTransaction t) {
        String acctCcy = t.account().dbValue() + "|" + t.currency();
        cashByAccountCcy.merge(acctCcy, BigDecimal.valueOf(t.amount()), BigDecimal::add);
        if (t.type() != TransactionType.TRANSACTION) return;
        String sym = t.symbol();
        if (sym == null || sym.isEmpty() || sym.equals("GBP") || sym.equals("CASH")) return;
        BigDecimal q;
        if (t.amount() < 0) q = BigDecimal.valueOf(Math.abs(t.quantity()));
        else if (t.amount() > 0) q = BigDecimal.valueOf(-Math.abs(t.quantity()));
        else q = BigDecimal.valueOf(t.quantity());
        qtyBySymbol.merge(sym.toUpperCase(), q, BigDecimal::add);
    }

    // ---- valuation ----------------------------------------------------------

    private static BigDecimal positionGbp(String symbol, BigDecimal qty, LocalDate sample,
                                          Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                          Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        NavigableMap<LocalDate, PricePoint> series = prices.get(symbol);
        if (series == null) return null;
        Map.Entry<LocalDate, PricePoint> entry = series.floorEntry(sample);
        if (entry == null) entry = series.firstEntry();
        PricePoint pp = entry.getValue();
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

    // ---- preloads -----------------------------------------------------------

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

    // ---- DTOs ---------------------------------------------------------------

    private record PricePoint(double close, double splitFactor, String currency) {
    }

    /**
     * Composition at one sample. {@code symbolGbp} is keyed by internal symbol and only
     * carries non-zero positions, so payload stays compact for the multi-year timeline.
     * {@code cashGbp + investedGbp = totalGbp} by construction.
     */
    public record AllocationPoint(String date,
                                  BigDecimal cashGbp,
                                  BigDecimal investedGbp,
                                  BigDecimal totalGbp,
                                  Map<String, BigDecimal> symbolGbp) {
    }

    public record AllocationTimeline(List<AllocationPoint> points) {
    }
}
