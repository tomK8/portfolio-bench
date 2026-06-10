package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Currency exposure of the portfolio — by <b>listing currency</b>, the practical proxy for
 * "what currency is this position denominated in?".
 *
 * <p>Per position the listing currency comes from its live {@link IntradayPrice}; for the
 * historical trend (which includes long-closed positions with no intraday rows), we cache
 * the listing currency from the latest intraday + previously-held bookkeeping. Unknown
 * symbols default to GBP — a defensible default since UK-listed holdings dominate and the
 * worst case is under-counting USD exposure by a few hundred basis points in the early
 * years.
 *
 * <p>{@code GBp} (London pence) normalises to {@code GBP} for bucketing — pence and pounds
 * are the same currency for exposure purposes.
 *
 * <p>The <b>12-month FX impact</b> is a what-if number: holding the <i>current</i> non-GBP
 * book at 12-month-ago FX would have produced a different GBP value. The signed difference
 * is the cumulative FX swing on today's positions over the year. It deliberately ignores
 * positions added/sold within the window — that requires per-lot FX attribution, an
 * order-of-magnitude bigger build.
 */
public class CurrencyExposureService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyExposureService.class);
    private static final int WEIGHT_SCALE = 6;
    private static final int GBP_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AllocationService allocationService;
    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FxRateProvider fxRateProvider;
    private final HistoricalFxRateProvider historicalFxProvider;
    private final YahooTickerMap tickerMap;

    public CurrencyExposureService(AllocationService allocationService,
                                   CashTransactionRepository cashRepo,
                                   IntradayPriceRepository intradayRepo,
                                   FxRateProvider fxRateProvider,
                                   HistoricalFxRateProvider historicalFxProvider,
                                   YahooTickerMap tickerMap) {
        this.allocationService = allocationService;
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.fxRateProvider = fxRateProvider;
        this.historicalFxProvider = historicalFxProvider;
        this.tickerMap = tickerMap;
    }

    public CurrencyExposure summary() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();

        Map<String, BigDecimal> currentRates;
        try {
            currentRates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            log.warn("Could not fetch live FX rates; non-GBP positions will fall back to null GBP", e);
            currentRates = Map.of();
        }

        // Per (account, symbol) → net shares (FIFO not needed here; only current net qty matters).
        Map<String, BigDecimal> sharesByKey = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.TRANSACTION) continue;
            String sym = t.symbol();
            if (sym == null || sym.isBlank()) continue;
            String key = t.account().dbValue() + "|" + sym.toUpperCase();
            BigDecimal qty = BigDecimal.valueOf(t.quantity());
            if (t.amount() < 0) sharesByKey.merge(key, qty.abs(), BigDecimal::add);
            else if (t.amount() > 0) sharesByKey.merge(key, qty.abs().negate(), BigDecimal::add);
            else sharesByKey.merge(key, qty, BigDecimal::add);
        }

        // Map currently-held symbols → ticker → intraday → listing currency.
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (var e : sharesByKey.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            String sym = keySymbol(e.getKey());
            String ticker = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(sym, ticker);
        }
        Map<String, IntradayPrice> byTicker =
                intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, IntradayPrice> bySymbol = new LinkedHashMap<>();
        for (var e : tickerBySymbol.entrySet()) {
            IntradayPrice p = byTicker.get(e.getValue());
            if (p != null) bySymbol.put(e.getKey(), p);
        }

        // Currency aggregates: invested per currency, position count per currency.
        Map<String, BigDecimal> investedByCcy = new LinkedHashMap<>();
        Map<String, Integer> positionsByCcy = new LinkedHashMap<>();
        BigDecimal investedTotal = BigDecimal.ZERO;
        Set<String> seenSymbols = new LinkedHashSet<>();
        for (var e : sharesByKey.entrySet()) {
            BigDecimal shares = e.getValue();
            if (shares.signum() <= 0) continue;
            String sym = keySymbol(e.getKey());
            IntradayPrice ip = bySymbol.get(sym);
            String ccy = normalise(ip == null ? "GBP" : ip.currency());
            BigDecimal gbp = valueGbp(sym, shares, ip, currentRates);
            if (gbp == null) continue;
            investedByCcy.merge(ccy, gbp, BigDecimal::add);
            investedTotal = investedTotal.add(gbp);
            if (seenSymbols.add(sym)) {
                positionsByCcy.merge(ccy, 1, Integer::sum);
            }
        }

        // Cash per currency.
        Map<String, BigDecimal> cashByCcy = new LinkedHashMap<>();
        BigDecimal cashTotal = BigDecimal.ZERO;
        for (CashBalance cb : cashRepo.latestCashBalances()) {
            String ccy = normalise(cb.currency());
            BigDecimal gbp = BigDecimal.valueOf(cb.cashGbp());
            cashByCcy.merge(ccy, gbp, BigDecimal::add);
            cashTotal = cashTotal.add(gbp);
        }

        BigDecimal total = investedTotal.add(cashTotal);
        if (total.signum() <= 0) return CurrencyExposure.empty();

        // Build per-currency rows: union of currencies seen in either invested or cash.
        Set<String> allCcy = new LinkedHashSet<>(investedByCcy.keySet());
        allCcy.addAll(cashByCcy.keySet());
        List<CurrencyRow> rowsOut = new ArrayList<>();
        for (String ccy : allCcy) {
            BigDecimal inv = investedByCcy.getOrDefault(ccy, BigDecimal.ZERO);
            BigDecimal cash = cashByCcy.getOrDefault(ccy, BigDecimal.ZERO);
            BigDecimal tot = inv.add(cash);
            BigDecimal weight = total.signum() > 0
                    ? tot.divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            rowsOut.add(new CurrencyRow(ccy, money(inv), money(cash), money(tot), weight,
                    positionsByCcy.getOrDefault(ccy, 0)));
        }
        rowsOut.sort(Comparator.comparing(CurrencyRow::totalGbp).reversed());

        BigDecimal gbpExposure = investedByCcy.getOrDefault("GBP", BigDecimal.ZERO)
                .add(cashByCcy.getOrDefault("GBP", BigDecimal.ZERO));
        BigDecimal nonGbpExposure = total.subtract(gbpExposure);
        BigDecimal nonGbpShare = total.signum() > 0
                ? nonGbpExposure.divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // FX impact on the current non-GBP book over the trailing 12 months.
        BigDecimal fxImpact12m = computeFxImpact12m(investedByCcy, cashByCcy, currentRates);

        // Build per-symbol currency lookup for trend, including currencies for closed/never-priced
        // symbols (default GBP).
        Map<String, String> currencyBySymbol = new HashMap<>();
        for (var e : bySymbol.entrySet()) {
            currencyBySymbol.put(e.getKey(), normalise(e.getValue().currency()));
        }
        List<TrendPoint> trend = computeTrend(allocationService.timeline().points(), currencyBySymbol);

        Snapshot snapshot = new Snapshot(money(total),
                money(investedTotal), money(cashTotal),
                money(gbpExposure), money(nonGbpExposure),
                nonGbpShare, money(fxImpact12m),
                rowsOut.size(), rowsOut);
        return new CurrencyExposure(snapshot, trend);
    }

    /**
     * Sum over non-GBP currencies of {@code (native_total) × (1/current_rate − 1/rate_12m_ago)},
     * where {@code native_total = gbp_now × current_rate} reconstructs the implied native
     * holding. The sign reads as "what GBP value the FX swing added (positive) or subtracted
     * (negative) over the year, holding the current book constant". Returns {@code null} when
     * historical rates can't be fetched.
     */
    private BigDecimal computeFxImpact12m(Map<String, BigDecimal> investedByCcy,
                                          Map<String, BigDecimal> cashByCcy,
                                          Map<String, BigDecimal> currentRates) {
        LocalDate today = LocalDate.now();
        LocalDate yearAgo = today.minusYears(1);

        BigDecimal impact = BigDecimal.ZERO;
        Set<String> ccys = new LinkedHashSet<>(investedByCcy.keySet());
        ccys.addAll(cashByCcy.keySet());
        for (String ccy : ccys) {
            if ("GBP".equals(ccy)) continue;
            BigDecimal currentRate = currentRates.get(ccy);
            if (currentRate == null || currentRate.signum() == 0) continue;

            BigDecimal pastRate;
            try {
                Map<LocalDate, BigDecimal> series = historicalFxProvider.fetchRateSeries(
                        ccy, yearAgo.minusDays(14), yearAgo.plusDays(1));
                pastRate = HistoricalFxRateProvider.rateOnOrBefore(series, yearAgo);
            } catch (Exception e) {
                log.warn("Could not fetch historical FX for {} — skipping from FX impact", ccy, e);
                continue;
            }
            if (pastRate == null || pastRate.signum() == 0) continue;

            BigDecimal gbpNow = investedByCcy.getOrDefault(ccy, BigDecimal.ZERO)
                    .add(cashByCcy.getOrDefault(ccy, BigDecimal.ZERO));
            BigDecimal nativeTotal = gbpNow.multiply(currentRate);
            // If today's GBP value is X, native = X × currentRate; what would the same native
            // hold be worth at the older rate? native / pastRate. Difference = today's GBP −
            // that hypothetical. Positive = FX moved in the user's favour (foreign currency
            // strengthened against GBP).
            BigDecimal hypothetical = nativeTotal.divide(pastRate, 10, RoundingMode.HALF_UP);
            impact = impact.add(gbpNow.subtract(hypothetical));
        }
        return impact;
    }

    /**
     * Per allocation sample, bucket the symbol GBP values by listing currency. Closed
     * positions whose currency we don't know default to GBP — the snapshot's own
     * {@code currencyBySymbol} cache feeds this lookup, so a UK-listed name held only
     * historically still gets the right bucket.
     */
    static List<TrendPoint> computeTrend(List<AllocationPoint> points,
                                         Map<String, String> currencyBySymbol) {
        List<TrendPoint> out = new ArrayList<>();
        for (AllocationPoint p : points) {
            Map<String, BigDecimal> byCcy = new TreeMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (var e : p.symbolGbp().entrySet()) {
                BigDecimal v = e.getValue();
                if (v.signum() <= 0) continue;
                String ccy = currencyBySymbol.getOrDefault(e.getKey().toUpperCase(), "GBP");
                byCcy.merge(ccy, v, BigDecimal::add);
                total = total.add(v);
            }
            Map<String, BigDecimal> weights = new TreeMap<>();
            for (var e : byCcy.entrySet()) {
                BigDecimal w = total.signum() > 0
                        ? e.getValue().divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                weights.put(e.getKey(), w);
            }
            out.add(new TrendPoint(p.date(), weights));
        }
        return out;
    }

    private BigDecimal valueGbp(String symbol, BigDecimal shares, IntradayPrice ip,
                                Map<String, BigDecimal> rates) {
        if (ip == null) return null;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        String ccy = ip.currency();
        if ("GBp".equals(ccy)) {
            price = price.movePointLeft(2);
            ccy = "GBP";
        }
        BigDecimal native_ = Instruments.isBond(symbol)
                ? price.multiply(shares).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : price.multiply(shares);
        if ("GBP".equals(ccy)) return native_;
        BigDecimal rate = rates.get(ccy);
        if (rate == null || rate.signum() == 0) return null;
        return native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static String normalise(String ccy) {
        if (ccy == null || ccy.isBlank()) return "GBP";
        return "GBp".equals(ccy) ? "GBP" : ccy;
    }

    private static String keyAccount(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static String keySymbol(String key) {
        return key.substring(key.indexOf('|') + 1);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    // ---- DTOs --------------------------------------------------------------

    public record CurrencyRow(String currency, BigDecimal investedGbp, BigDecimal cashGbp,
                              BigDecimal totalGbp, BigDecimal weight, int positions) {
    }

    public record TrendPoint(String date, Map<String, BigDecimal> weightByCurrency) {
    }

    /**
     * Snapshot of currency exposure right now. {@code nonGbpExposureGbp} is the GBP-valued
     * sum of everything not in GBP — the headline number for "FX risk I'm carrying".
     * {@code fxImpact12mGbp} is the cumulative effect of the past year's FX moves on the
     * current book; positive means FX added to your GBP value, negative means it subtracted.
     */
    public record Snapshot(BigDecimal totalGbp,
                           BigDecimal investedGbp,
                           BigDecimal cashGbp,
                           BigDecimal gbpExposureGbp,
                           BigDecimal nonGbpExposureGbp,
                           BigDecimal nonGbpShare,
                           BigDecimal fxImpact12mGbp,
                           int currencyCount,
                           List<CurrencyRow> rows) {

        public static Snapshot empty() {
            return new Snapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, List.of());
        }
    }

    public record CurrencyExposure(Snapshot snapshot, List<TrendPoint> trend) {

        public static CurrencyExposure empty() {
            return new CurrencyExposure(Snapshot.empty(), List.of());
        }
    }
}
