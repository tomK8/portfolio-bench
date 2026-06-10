package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Money-weighted GBP P&amp;L attribution per symbol over a date window.
 *
 * <p>For each symbol ever held during {@code [from, to]}:
 * <pre>
 *   pnl = end_value − start_value + sum(window_cash_flows_gbp)
 * </pre>
 * where {@code start_value}/{@code end_value} are GBP-valued positions at the window
 * boundaries and {@code window_cash_flows_gbp} sums every ledger row of that symbol
 * (buys negative, sells positive, dividends positive, baked-in trade-date FX) within
 * the window. The identity {@code Δposition + cash_flow = P&L} holds: a trade at fair value
 * contributes 0 to P&amp;L instantaneously; only subsequent market moves and dividends do.
 *
 * <p>FX convention: cash-flow GBP equivalents use the FX at trade date
 * ({@code CashTransaction.amountGbp()}). Position valuations use the historical FX on the
 * window boundaries. That mismatch is intentional — it captures actual £ realised/locked
 * in rather than a hypothetical re-conversion.
 *
 * <p>Bonds value at {@code qty × price / 100} (per-£100 nominal), matching the convention
 * used by {@link PortfolioValueService} and {@link com.portfolio.domain.PortfolioAggregator}.
 */
public class AttributionService {

    private static final Logger log = LoggerFactory.getLogger(AttributionService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final IntradayPriceRepository intradayRepo;
    private final HistoricalFxRateProvider fxProvider;
    private final FxRateProvider liveFxProvider;
    private final YahooTickerMap tickerMap;

    public AttributionService(CashTransactionRepository cashRepo,
                              PriceHistoryRepository priceRepo,
                              IntradayPriceRepository intradayRepo,
                              HistoricalFxRateProvider fxProvider,
                              FxRateProvider liveFxProvider,
                              YahooTickerMap tickerMap) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.intradayRepo = intradayRepo;
        this.fxProvider = fxProvider;
        this.liveFxProvider = liveFxProvider;
        this.tickerMap = tickerMap;
    }

    public AttributionResult attribute(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to' (got "
                    + from + " > " + to + ")");
        }
        List<CashTransaction> txs = cashRepo.loadAllTransactions();
        if (txs.isEmpty()) {
            return new AttributionResult(from.toString(), to.toString(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPriceSeries(txs);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(from, to);

        // For the end-of-window valuation on today's date, prefer the live intraday quote +
        // live FX — matches the cash-ledger view's totals, so the two screens reconcile to
        // today's market rather than to yesterday's price_history close.
        boolean endIsToday = to.equals(LocalDate.now());
        Map<String, IntradayPrice> latestIntraday = Map.of();
        Map<String, BigDecimal> liveRates = Map.of();
        if (endIsToday) {
            try {
                liveRates = liveFxProvider.fetchRates();
            } catch (Exception e) {
                log.warn("Could not fetch live FX; end-of-window valuation falls back to historical FX", e);
            }
        }

        Map<String, BigDecimal> qty = new HashMap<>();
        Map<String, BigDecimal> qtyAtStart = null;
        Map<String, BigDecimal> cashFlowsGbp = new HashMap<>();
        // Per-symbol ordered list of signed in-window flows, used to find peak deployed capital
        // (= max running net cash the position required) for return-on-capital math.
        Map<String, List<BigDecimal>> windowFlowsBySymbol = new HashMap<>();

        for (CashTransaction t : txs) {
            LocalDate d = LocalDate.parse(t.transactionDate());
            // Snapshot the qty state just before applying the first in-window transaction.
            // Anything strictly before `from` is already folded into qty; the snapshot
            // captures exactly the start-of-window position.
            if (qtyAtStart == null && !d.isBefore(from)) {
                qtyAtStart = new HashMap<>(qty);
            }
            if (d.isAfter(to)) break;
            applyQty(qty, t);
            if (!d.isBefore(from)) {
                String sym = symbolOf(t);
                if (sym != null) {
                    BigDecimal flow = BigDecimal.valueOf(t.amountGbp());
                    cashFlowsGbp.merge(sym, flow, BigDecimal::add);
                    windowFlowsBySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(flow);
                }
            }
        }
        if (qtyAtStart == null) qtyAtStart = new HashMap<>(qty);   // window after all activity

        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(qtyAtStart.keySet());
        symbols.addAll(qty.keySet());
        symbols.addAll(cashFlowsGbp.keySet());

        if (endIsToday) {
            Set<String> endHeld = new HashSet<>();
            for (Map.Entry<String, BigDecimal> e : qty.entrySet()) {
                if (e.getValue().signum() > 0) endHeld.add(e.getKey());
            }
            latestIntraday = loadLatestIntradayBySymbol(endHeld);
        }

        long windowDays = ChronoUnit.DAYS.between(from, to);

        List<AttributionRow> rows = new ArrayList<>();
        for (String sym : symbols) {
            BigDecimal qStart = qtyAtStart.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal qEnd = qty.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal cf = cashFlowsGbp.getOrDefault(sym, BigDecimal.ZERO);
            if (qStart.signum() == 0 && qEnd.signum() == 0 && cf.signum() == 0) continue;

            BigDecimal startVal = qStart.signum() > 0
                    ? nullToZero(positionGbp(sym, qStart, from, prices, fx)) : BigDecimal.ZERO;
            BigDecimal endVal;
            if (qEnd.signum() <= 0) {
                endVal = BigDecimal.ZERO;
            } else if (endIsToday && latestIntraday.containsKey(sym)) {
                BigDecimal rt = rtPositionGbp(sym, qEnd, latestIntraday.get(sym), liveRates);
                endVal = (rt != null) ? rt : nullToZero(positionGbp(sym, qEnd, to, prices, fx));
            } else {
                endVal = nullToZero(positionGbp(sym, qEnd, to, prices, fx));
            }
            BigDecimal pnl = endVal.subtract(startVal).add(cf);
            BigDecimal peakCapital = peakCapitalDeployed(
                    startVal, windowFlowsBySymbol.getOrDefault(sym, List.of()));
            BigDecimal periodReturn = peakCapital.signum() > 0
                    ? pnl.divide(peakCapital, 6, RoundingMode.HALF_UP) : null;
            BigDecimal annualized = annualize(periodReturn, windowDays);

            rows.add(new AttributionRow(sym,
                    startVal.setScale(2, RoundingMode.HALF_UP),
                    endVal.setScale(2, RoundingMode.HALF_UP),
                    cf.setScale(2, RoundingMode.HALF_UP),
                    pnl.setScale(2, RoundingMode.HALF_UP),
                    periodReturn,
                    annualized));
        }
        rows.sort(Comparator.comparing(AttributionRow::pnlGbp).reversed());

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalStart = BigDecimal.ZERO;
        BigDecimal totalEnd = BigDecimal.ZERO;
        BigDecimal totalCashFlow = BigDecimal.ZERO;
        for (AttributionRow r : rows) {
            total = total.add(r.pnlGbp());
            totalStart = totalStart.add(r.startValueGbp());
            totalEnd = totalEnd.add(r.endValueGbp());
            totalCashFlow = totalCashFlow.add(r.cashFlowGbp());
        }
        return new AttributionResult(from.toString(), to.toString(), rows,
                totalStart.setScale(2, RoundingMode.HALF_UP),
                totalEnd.setScale(2, RoundingMode.HALF_UP),
                totalCashFlow.setScale(2, RoundingMode.HALF_UP),
                total.setScale(2, RoundingMode.HALF_UP));
    }

    // ---- replay ---------------------------------------------------------------

    /** Mirror of {@link PortfolioValueService}'s qty rule: amount sign carries buy/sell. */
    private static void applyQty(Map<String, BigDecimal> qty, CashTransaction t) {
        if (t.type() != TransactionType.TRANSACTION) return;
        String sym = symbolOf(t);
        if (sym == null) return;
        BigDecimal q;
        if (t.amount() < 0) q = BigDecimal.valueOf(Math.abs(t.quantity()));        // buy
        else if (t.amount() > 0) q = BigDecimal.valueOf(-Math.abs(t.quantity()));  // sell
        else q = BigDecimal.valueOf(t.quantity());                                 // split (signed)
        qty.merge(sym, q, BigDecimal::add);
    }

    /** Internal symbol (upper-cased) or {@code null} if this row doesn't belong to a security. */
    private static String symbolOf(CashTransaction t) {
        if (t.type() != TransactionType.TRANSACTION && t.type() != TransactionType.DIVIDEND) return null;
        String sym = t.symbol();
        if (sym == null || sym.isEmpty() || sym.equals("GBP") || sym.equals("CASH")) return null;
        return sym.toUpperCase();
    }

    // ---- valuation ------------------------------------------------------------

    private static BigDecimal positionGbp(String symbol, BigDecimal qty, LocalDate sample,
                                          Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                          Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        NavigableMap<LocalDate, PricePoint> series = prices.get(symbol);
        if (series == null) return null;
        Map.Entry<LocalDate, PricePoint> entry = series.floorEntry(sample);
        if (entry == null) entry = series.firstEntry();
        PricePoint pp = entry.getValue();
        // splitFactor unwinds Yahoo's "today's basis" close back to the raw close on `sample`,
        // so qty × price matches the user's basis at the time. PortfolioValueService does
        // the same — keep them in sync if the formula changes.
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

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Peak net capital deployed in this symbol over the window — used as the denominator for
     * return-on-capital. Starts at {@code startValue} (capital already tied up at window open),
     * then steps through signed in-window flows (buy → negative amount → adds to deployment,
     * sell/dividend → positive → reduces). The peak captures the maximum out-of-pocket
     * commitment the position required.
     */
    private static BigDecimal peakCapitalDeployed(BigDecimal startValue, List<BigDecimal> windowFlows) {
        BigDecimal running = startValue;
        BigDecimal peak = startValue;
        for (BigDecimal flow : windowFlows) {
            running = running.subtract(flow);
            if (running.compareTo(peak) > 0) peak = running;
        }
        return peak;
    }

    /**
     * Annualise a period return over {@code days}. Returns the input unchanged when the window
     * is &lt;= 0 days (annualisation undefined) and {@code null} when {@code 1 + r} would be
     * non-positive (total wipeout — annualised value is −100% in the limit, but the {@code pow}
     * call would underflow to NaN; report as null and let the UI render "—").
     */
    private static BigDecimal annualize(BigDecimal periodReturn, long days) {
        if (periodReturn == null) return null;
        if (days <= 0) return periodReturn;
        double base = 1.0 + periodReturn.doubleValue();
        if (base <= 0) return null;
        double annual = Math.pow(base, 365.25 / days) - 1.0;
        return BigDecimal.valueOf(annual).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Live-quote valuation in GBP — mirrors {@link SyncFromCashService}'s realtime formula
     * so the attribution End-value total reconciles with the cash-ledger view when {@code to}
     * is today. Returns {@code null} when the FX rate for the listing currency is missing
     * (caller falls back to the daily-close path).
     */
    private static BigDecimal rtPositionGbp(String symbol, BigDecimal qty,
                                            IntradayPrice ip, Map<String, BigDecimal> liveRates) {
        if (ip == null) return null;
        BigDecimal price = BigDecimal.valueOf(ip.close());
        String currency = ip.currency();
        if ("GBp".equals(currency)) {
            price = price.movePointLeft(2);
            currency = "GBP";
        }
        BigDecimal rtNative = Instruments.isBond(symbol)
                ? price.multiply(qty).movePointLeft(2)
                : price.multiply(qty);
        if ("GBP".equals(currency)) return rtNative;
        BigDecimal rate = liveRates.get(currency);
        if (rate == null || rate.signum() == 0) return null;
        return rtNative.divide(rate, 10, RoundingMode.HALF_UP);
    }

    /**
     * Bulk intraday lookup for a set of internal symbols, re-keyed by the symbol the caller
     * uses (upper-cased). Mirrors {@link SyncFromCashService#latestPricesBySymbol(java.util.List)}
     * — gilts use the symbol itself, stocks go through the Yahoo ticker map.
     */
    private Map<String, IntradayPrice> loadLatestIntradayBySymbol(Set<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (String sym : symbols) {
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(upper, ticker);
        }
        Map<String, IntradayPrice> byTicker = intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, IntradayPrice> bySymbol = new HashMap<>();
        for (Map.Entry<String, String> e : tickerBySymbol.entrySet()) {
            IntradayPrice p = byTicker.get(e.getValue());
            if (p != null) bySymbol.put(e.getKey(), p);
        }
        return bySymbol;
    }

    // ---- preloads -------------------------------------------------------------

    private Map<String, NavigableMap<LocalDate, PricePoint>> loadPriceSeries(List<CashTransaction> txs) {
        Set<String> symbols = new HashSet<>();
        for (CashTransaction t : txs) {
            String s = symbolOf(t);
            if (s != null && t.type() == TransactionType.TRANSACTION) symbols.add(s);
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

    private Map<String, NavigableMap<LocalDate, BigDecimal>> preloadFx(LocalDate from, LocalDate to) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> out = new HashMap<>();
        for (String ccy : List.of("USD", "EUR")) {
            try {
                Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(
                        ccy, from.minusDays(14), to.plusDays(1));
                out.put(ccy, new TreeMap<>(series));
            } catch (Exception e) {
                log.warn("Could not fetch historical FX for {}", ccy, e);
            }
        }
        return out;
    }

    // ---- DTOs -----------------------------------------------------------------

    private record PricePoint(double close, double splitFactor, String currency) {
    }

    /**
     * Per-symbol attribution: start/end GBP values, in-window cash flows (signed), the
     * period P&amp;L (= {@code endValueGbp − startValueGbp + cashFlowGbp}), and two
     * return-on-capital metrics. {@code periodReturn} is {@code pnl / peak_capital_deployed}
     * (fractional, e.g. 0.07 = +7%); {@code annualizedReturn} grosses that up to a 365.25-day
     * year. Both are {@code null} when peak capital is zero (a position the window didn't
     * fund — e.g. only dividends, no actual exposure).
     */
    public record AttributionRow(String symbol,
                                 BigDecimal startValueGbp,
                                 BigDecimal endValueGbp,
                                 BigDecimal cashFlowGbp,
                                 BigDecimal pnlGbp,
                                 BigDecimal periodReturn,
                                 BigDecimal annualizedReturn) {
    }

    public record AttributionResult(String from, String to,
                                    List<AttributionRow> rows,
                                    BigDecimal totalStartValueGbp,
                                    BigDecimal totalEndValueGbp,
                                    BigDecimal totalCashFlowGbp,
                                    BigDecimal totalPnlGbp) {
    }
}
