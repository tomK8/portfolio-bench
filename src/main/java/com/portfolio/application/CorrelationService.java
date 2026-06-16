package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Trailing correlation matrix + rolling vols across the held universe.
 *
 * <p><b>Returns</b> are daily log returns on native-currency total-return prices
 * ({@code adj_close × splitFactor} for equities, {@code close} for bonds). Using native
 * isolates asset behaviour from FX moves — DR and pairwise correlations describe the
 * underlying instruments, not the user's GBP P&L (which gets FX layered on top).
 *
 * <p><b>Cash</b> is folded in as a synthetic asset with σ=0 and ρ=0 vs everything.
 * Its weight = total GBP cash / (invested GBP + cash GBP) at current intraday prices.
 * DR is invariant to adding a 0-vol uncorrelated asset (algebraically: both numerator
 * and denominator scale by {@code (1-w_cash)}), so it stays a clean "diversification of
 * the risky book" measure even with cash included. Portfolio vol, in contrast, drops
 * proportionally to the equity share — which reflects reality.
 *
 * <p><b>Cluster order</b> is agglomerative average-linkage on {@code (1 − ρ)} distance.
 * Reorders rows/columns so high-correlation blocks sit on the diagonal — for ~30-40
 * symbols the O(N³) loop is trivially fast.
 *
 * <p><b>Window selection</b>: we take the latest N trading days where every kept symbol
 * has a return. Symbols missing more than 30% of the recent {@code 1.5 × window} dates
 * are dropped and listed under {@code missingSymbols} so the UI can flag them — newly
 * added names without enough history would otherwise force the common window down.
 */
public class CorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);
    private static final int DEFAULT_WINDOW = 90;
    private static final int MIN_WINDOW = 20;
    private static final int MAX_WINDOW = 800;
    private static final double TRADING_DAYS_PER_YEAR = 252.0;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 10;
    /** Synthetic asset symbol for folded-in cash. */
    public static final String CASH_SYMBOL = "Cash";

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public CorrelationService(CashTransactionRepository cashRepo,
                              PriceHistoryRepository priceRepo,
                              IntradayPriceRepository intradayRepo,
                              FxRateProvider fxRateProvider,
                              YahooTickerMap tickerMap) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.intradayRepo = intradayRepo;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public CorrelationReport compute(int requestedWindow) {
        int window = clampWindow(requestedWindow);

        Weights weights = currentWeights();
        if (weights.symbolWeights.isEmpty()) {
            return CorrelationReport.empty(window);
        }

        Map<String, NavigableMap<LocalDate, Double>> returns = loadReturns(weights.symbolWeights.keySet());
        Universe universe = pickUniverse(returns, window);
        if (universe.symbols.isEmpty()) {
            return CorrelationReport.empty(window);
        }

        // Latest-window correlation matrix on the common-date intersection.
        double[][] retMatrix = buildReturnMatrix(returns, universe);
        double[] vols = annualisedVols(retMatrix);
        double[][] corr = correlationMatrix(retMatrix);

        // Cluster reorder.
        int[] order = clusterOrder(corr);
        List<String> orderedSymbols = new ArrayList<>(universe.symbols.size());
        double[][] orderedCorr = new double[order.length][order.length];
        double[] orderedVols = new double[order.length];
        for (int i = 0; i < order.length; i++) {
            orderedSymbols.add(universe.symbols.get(order[i]));
            orderedVols[i] = vols[order[i]];
            for (int j = 0; j < order.length; j++) {
                orderedCorr[i][j] = corr[order[i]][order[j]];
            }
        }

        // Append synthetic cash row/col (0 vol, 0 correlation) if cash has weight.
        boolean withCash = weights.cashGbp.signum() > 0;
        int n = orderedSymbols.size();
        int nWithCash = withCash ? n + 1 : n;
        List<String> allSymbols = new ArrayList<>(orderedSymbols);
        double[] allVols = new double[nWithCash];
        double[][] allCorr = new double[nWithCash][nWithCash];
        for (int i = 0; i < n; i++) {
            allVols[i] = orderedVols[i];
            System.arraycopy(orderedCorr[i], 0, allCorr[i], 0, n);
        }
        if (withCash) {
            allSymbols.add(CASH_SYMBOL);
            allVols[n] = 0.0;
            allCorr[n][n] = 1.0;
        }

        // Normalised weights across kept symbols + cash. Symbols dropped from the universe
        // (no price history, no overlap) have their weight redistributed pro-rata so
        // remaining weights sum to 1.
        double[] w = normalisedWeights(allSymbols, weights);

        double weightedAvgVol = 0.0;
        for (int i = 0; i < nWithCash; i++) weightedAvgVol += w[i] * allVols[i];
        double portfolioVol = portfolioVol(w, allVols, allCorr);
        double dr = portfolioVol > 0 ? weightedAvgVol / portfolioVol : 0.0;
        double effectiveBets = dr > 0 ? dr * dr : 0.0;

        // Rolling per-symbol vol & DR timeline — weekly samples over available history.
        Timelines timelines = buildTimelines(returns, universe.symbols, window, weights);

        // Top / bottom pairs from the equity-only block (cash row is uninteresting).
        Pairs pairs = topPairs(orderedSymbols, orderedCorr);

        // Flatten matrices to BigDecimal for clean JSON.
        List<List<BigDecimal>> matrixOut = new ArrayList<>(nWithCash);
        for (int i = 0; i < nWithCash; i++) {
            List<BigDecimal> row = new ArrayList<>(nWithCash);
            for (int j = 0; j < nWithCash; j++) {
                row.add(round(allCorr[i][j], 4));
            }
            matrixOut.add(row);
        }

        Map<String, BigDecimal> volsMap = new LinkedHashMap<>();
        for (int i = 0; i < nWithCash; i++) volsMap.put(allSymbols.get(i), round(allVols[i], 4));

        Map<String, BigDecimal> weightsMap = new LinkedHashMap<>();
        for (int i = 0; i < nWithCash; i++) weightsMap.put(allSymbols.get(i), round(w[i], 6));

        return new CorrelationReport(
                window,
                LocalDate.now().toString(),
                allSymbols,
                weightsMap,
                matrixOut,
                volsMap,
                round(portfolioVol, 4),
                round(weightedAvgVol, 4),
                round(dr, 4),
                round(effectiveBets, 4),
                timelines.rollingVols,
                timelines.divRatio,
                pairs.top,
                pairs.bottom,
                new ArrayList<>(universe.dropped));
    }

    private static int clampWindow(int w) {
        if (w <= 0) return DEFAULT_WINDOW;
        return Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, w));
    }

    // ---- Universe & weights ------------------------------------------------

    /**
     * Per-symbol GBP value (live intraday + live FX) + total cash GBP — same engine as
     * ConcentrationService.buildCurrentSnapshot, just packaged for weight calc here.
     */
    Weights currentWeights() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            log.warn("Could not fetch FX rates; some positions may be excluded", e);
            rates = Map.of();
        }

        Map<String, BigDecimal> sharesBySymbol = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.TRANSACTION) continue;
            String sym = t.symbol();
            if (sym == null || sym.isBlank()) continue;
            String key = sym.toUpperCase();
            BigDecimal qty = BigDecimal.valueOf(t.quantity());
            if (t.amount() < 0) {
                sharesBySymbol.merge(key, qty.abs(), BigDecimal::add);
            } else if (t.amount() > 0) {
                sharesBySymbol.merge(key, qty.abs().negate(), BigDecimal::add);
            } else {
                sharesBySymbol.merge(key, qty, BigDecimal::add);
            }
        }

        List<String> live = new ArrayList<>();
        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (var e : sharesBySymbol.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            live.add(e.getKey());
            tickerBySymbol.put(e.getKey(),
                    Instruments.isBond(e.getKey()) ? e.getKey() : tickerMap.tickerFor(e.getKey()));
        }
        Map<String, IntradayPrice> byTicker =
                intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());

        Map<String, BigDecimal> symbolWeights = new LinkedHashMap<>();
        for (String sym : live) {
            IntradayPrice ip = byTicker.get(tickerBySymbol.get(sym));
            BigDecimal gbp = valueGbp(sym, sharesBySymbol.get(sym), ip, rates);
            if (gbp == null || gbp.signum() <= 0) continue;
            symbolWeights.put(sym, gbp);
        }

        BigDecimal cashGbp = BigDecimal.ZERO;
        for (CashBalance cb : cashRepo.latestCashBalances()) {
            cashGbp = cashGbp.add(BigDecimal.valueOf(cb.cashGbp()));
        }
        if (cashGbp.signum() < 0) cashGbp = BigDecimal.ZERO;

        return new Weights(symbolWeights, cashGbp);
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
                ? price.multiply(shares).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
                : price.multiply(shares);
        if ("GBP".equals(ccy)) return native_;
        BigDecimal rate = rates.get(ccy);
        if (rate == null || rate.signum() == 0) return null;
        return native_.divide(rate, SCALE, RoundingMode.HALF_UP);
    }

    private double[] normalisedWeights(List<String> orderedSymbols, Weights w) {
        BigDecimal investedTotal = BigDecimal.ZERO;
        for (String s : orderedSymbols) {
            if (CASH_SYMBOL.equals(s)) continue;
            BigDecimal v = w.symbolWeights.get(s);
            if (v != null) investedTotal = investedTotal.add(v);
        }
        BigDecimal total = investedTotal.add(w.cashGbp);
        double[] out = new double[orderedSymbols.size()];
        if (total.signum() <= 0) return out;
        for (int i = 0; i < orderedSymbols.size(); i++) {
            String s = orderedSymbols.get(i);
            if (CASH_SYMBOL.equals(s)) {
                out[i] = w.cashGbp.divide(total, SCALE, RoundingMode.HALF_UP).doubleValue();
            } else {
                BigDecimal v = w.symbolWeights.get(s);
                out[i] = v == null ? 0.0
                        : v.divide(total, SCALE, RoundingMode.HALF_UP).doubleValue();
            }
        }
        return out;
    }

    // ---- Returns loading ---------------------------------------------------

    private Map<String, NavigableMap<LocalDate, Double>> loadReturns(java.util.Collection<String> symbols) {
        Map<String, NavigableMap<LocalDate, Double>> out = new LinkedHashMap<>();
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(11);
        for (String sym : symbols) {
            String ticker = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
            List<PriceBar> bars = priceRepo.getPriceHistory(ticker, from, to);
            if (bars.size() < 2) continue;
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            Double prev = null;
            boolean isBond = Instruments.isBond(sym);
            for (PriceBar b : bars) {
                double raw = isBond
                        ? b.close() * b.splitFactor()
                        : b.adjClose() * b.splitFactor();
                if (raw <= 0) continue;
                if (prev != null && prev > 0) {
                    series.put(b.date(), Math.log(raw / prev));
                }
                prev = raw;
            }
            if (!series.isEmpty()) out.put(sym, series);
        }
        return out;
    }

    static Universe pickUniverse(Map<String, NavigableMap<LocalDate, Double>> returns,
                                 int window) {
        if (returns.isEmpty()) return new Universe(List.of(), List.of(), List.of());

        // Build the union of dates ending at the most recent date, take latest 1.5×window.
        TreeMap<LocalDate, Integer> dateCounts = new TreeMap<>();
        for (var e : returns.entrySet()) {
            for (LocalDate d : e.getValue().keySet()) {
                dateCounts.merge(d, 1, Integer::sum);
            }
        }
        if (dateCounts.isEmpty()) return new Universe(List.of(), List.of(), List.of());

        int lookback = Math.max(window, (window * 3) / 2);
        List<LocalDate> recent = new ArrayList<>(dateCounts.descendingKeySet());
        if (recent.size() > lookback) recent = recent.subList(0, lookback);
        java.util.Collections.reverse(recent); // ascending order again

        // Drop symbols missing > 30% of the recent dates.
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int minPresent = (int) Math.ceil(recent.size() * 0.7);
        for (var e : returns.entrySet()) {
            int present = 0;
            for (LocalDate d : recent) if (e.getValue().containsKey(d)) present++;
            if (present >= minPresent) kept.add(e.getKey());
            else dropped.add(e.getKey());
        }
        if (kept.isEmpty()) return new Universe(List.of(), List.of(), dropped);

        // Common-date intersection across kept symbols, take latest `window`.
        List<LocalDate> common = new ArrayList<>();
        for (LocalDate d : recent) {
            boolean all = true;
            for (String s : kept) {
                if (!returns.get(s).containsKey(d)) { all = false; break; }
            }
            if (all) common.add(d);
        }
        if (common.size() < Math.max(MIN_WINDOW, window / 2)) {
            return new Universe(List.of(), List.of(), dropped);
        }
        if (common.size() > window) common = common.subList(common.size() - window, common.size());

        java.util.Collections.sort(kept);
        return new Universe(kept, common, dropped);
    }

    static double[][] buildReturnMatrix(Map<String, NavigableMap<LocalDate, Double>> returns,
                                        Universe u) {
        int n = u.symbols.size();
        int t = u.dates.size();
        double[][] m = new double[n][t];
        for (int i = 0; i < n; i++) {
            NavigableMap<LocalDate, Double> s = returns.get(u.symbols.get(i));
            for (int k = 0; k < t; k++) m[i][k] = s.get(u.dates.get(k));
        }
        return m;
    }

    // ---- Stats -------------------------------------------------------------

    static double[] annualisedVols(double[][] retMatrix) {
        int n = retMatrix.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = stdev(retMatrix[i]) * Math.sqrt(TRADING_DAYS_PER_YEAR);
        return out;
    }

    static double[][] correlationMatrix(double[][] retMatrix) {
        int n = retMatrix.length;
        int t = n == 0 ? 0 : retMatrix[0].length;
        double[] means = new double[n];
        double[] stds = new double[n];
        for (int i = 0; i < n; i++) {
            means[i] = mean(retMatrix[i]);
            stds[i] = stdev(retMatrix[i]);
        }
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            m[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                if (stds[i] == 0 || stds[j] == 0) {
                    m[i][j] = m[j][i] = 0.0;
                    continue;
                }
                double cov = 0;
                for (int k = 0; k < t; k++) {
                    cov += (retMatrix[i][k] - means[i]) * (retMatrix[j][k] - means[j]);
                }
                cov /= (t - 1);
                double rho = cov / (stds[i] * stds[j]);
                if (rho > 1) rho = 1;
                if (rho < -1) rho = -1;
                m[i][j] = m[j][i] = rho;
            }
        }
        return m;
    }

    static double portfolioVol(double[] w, double[] vols, double[][] corr) {
        int n = w.length;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                sum += w[i] * w[j] * vols[i] * vols[j] * corr[i][j];
            }
        }
        return sum > 0 ? Math.sqrt(sum) : 0;
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return x.length == 0 ? 0 : s / x.length;
    }

    private static double stdev(double[] x) {
        if (x.length < 2) return 0;
        double m = mean(x);
        double s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - 1));
    }

    // ---- Hierarchical clustering (average linkage) ------------------------

    static int[] clusterOrder(double[][] corr) {
        int n = corr.length;
        if (n <= 1) return new int[]{ 0 };
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = 1.0 - corr[i][j];
            }
        }
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> c = new ArrayList<>();
            c.add(i);
            clusters.add(c);
        }
        while (clusters.size() > 1) {
            int bestA = 0, bestB = 1;
            double bestD = Double.POSITIVE_INFINITY;
            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double d = avgLinkage(clusters.get(a), clusters.get(b), dist);
                    if (d < bestD) { bestD = d; bestA = a; bestB = b; }
                }
            }
            List<Integer> merged = new ArrayList<>(clusters.get(bestA));
            merged.addAll(clusters.get(bestB));
            clusters.remove(bestB);
            clusters.remove(bestA);
            clusters.add(merged);
        }
        int[] out = new int[n];
        List<Integer> leaves = clusters.get(0);
        for (int i = 0; i < n; i++) out[i] = leaves.get(i);
        return out;
    }

    private static double avgLinkage(List<Integer> a, List<Integer> b, double[][] dist) {
        double s = 0;
        int n = 0;
        for (int i : a) for (int j : b) { s += dist[i][j]; n++; }
        return s / n;
    }

    // ---- Rolling timelines (weekly samples) -------------------------------

    private Timelines buildTimelines(Map<String, NavigableMap<LocalDate, Double>> returns,
                                     List<String> symbols, int window, Weights weights) {
        List<RollingVolPoint> rollingVols = new ArrayList<>();
        List<DrPoint> drPoints = new ArrayList<>();

        // Union of all dates, ascending.
        TreeMap<LocalDate, Integer> dateCounts = new TreeMap<>();
        for (String s : symbols) {
            for (LocalDate d : returns.get(s).keySet()) dateCounts.merge(d, 1, Integer::sum);
        }
        List<LocalDate> allDates = new ArrayList<>(dateCounts.keySet());
        if (allDates.size() <= window) return new Timelines(rollingVols, drPoints);

        // Sample every 5 trading days (≈ weekly).
        int step = 5;
        // Weights for DR timeline use current equity weights (cash-invariant for DR).
        // Re-using current snapshot weights makes the timeline a "what if my book stayed
        // constant" view, which is the clean reading for a structural diversification metric.
        double[] currentW = equityOnlyWeights(symbols, weights);

        for (int idx = window; idx < allDates.size(); idx += step) {
            LocalDate end = allDates.get(idx);
            // Slice the last `window` dates for which all symbols have returns.
            int start = idx - window + 1;
            List<LocalDate> sample = new ArrayList<>();
            for (int k = start; k <= idx; k++) {
                LocalDate d = allDates.get(k);
                boolean all = true;
                for (String s : symbols) {
                    if (!returns.get(s).containsKey(d)) { all = false; break; }
                }
                if (all) sample.add(d);
            }
            if (sample.size() < window / 2) continue;
            double[][] m = sliceReturns(returns, symbols, sample);
            double[] vols = annualisedVols(m);
            double[][] corr = correlationMatrix(m);

            Map<String, BigDecimal> volsAt = new LinkedHashMap<>();
            for (int i = 0; i < symbols.size(); i++) {
                volsAt.put(symbols.get(i), round(vols[i], 4));
            }
            rollingVols.add(new RollingVolPoint(end.toString(), volsAt));

            double pvol = portfolioVol(currentW, vols, corr);
            double wAvg = 0;
            for (int i = 0; i < currentW.length; i++) wAvg += currentW[i] * vols[i];
            double dr = pvol > 0 ? wAvg / pvol : 0;
            drPoints.add(new DrPoint(end.toString(), round(dr, 4)));
        }
        return new Timelines(rollingVols, drPoints);
    }

    private double[] equityOnlyWeights(List<String> symbols, Weights w) {
        double[] out = new double[symbols.size()];
        BigDecimal total = BigDecimal.ZERO;
        for (String s : symbols) {
            BigDecimal v = w.symbolWeights.get(s);
            if (v != null) total = total.add(v);
        }
        if (total.signum() <= 0) return out;
        for (int i = 0; i < symbols.size(); i++) {
            BigDecimal v = w.symbolWeights.get(symbols.get(i));
            out[i] = v == null ? 0.0
                    : v.divide(total, SCALE, RoundingMode.HALF_UP).doubleValue();
        }
        return out;
    }

    private static double[][] sliceReturns(Map<String, NavigableMap<LocalDate, Double>> returns,
                                           List<String> symbols, List<LocalDate> dates) {
        double[][] m = new double[symbols.size()][dates.size()];
        for (int i = 0; i < symbols.size(); i++) {
            NavigableMap<LocalDate, Double> s = returns.get(symbols.get(i));
            for (int k = 0; k < dates.size(); k++) m[i][k] = s.get(dates.get(k));
        }
        return m;
    }

    // ---- Top / bottom pairs ------------------------------------------------

    static Pairs topPairs(List<String> symbols, double[][] corr) {
        List<Pair> all = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            for (int j = i + 1; j < symbols.size(); j++) {
                all.add(new Pair(symbols.get(i), symbols.get(j),
                        BigDecimal.valueOf(corr[i][j]).setScale(4, RoundingMode.HALF_UP)));
            }
        }
        all.sort(Comparator.comparing(Pair::corr).reversed());
        int k = Math.min(5, all.size());
        List<Pair> top = new ArrayList<>(all.subList(0, k));
        List<Pair> bottom = new ArrayList<>(all.subList(all.size() - k, all.size()));
        java.util.Collections.reverse(bottom);
        return new Pairs(top, bottom);
    }

    // ---- Helpers -----------------------------------------------------------

    private static BigDecimal round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    // ---- Internal types ----------------------------------------------------

    record Weights(Map<String, BigDecimal> symbolWeights, BigDecimal cashGbp) {
    }

    record Universe(List<String> symbols, List<LocalDate> dates, List<String> dropped) {
    }

    record Timelines(List<RollingVolPoint> rollingVols, List<DrPoint> divRatio) {
    }

    // ---- DTOs --------------------------------------------------------------

    public record Pair(String a, String b, BigDecimal corr) {
    }

    public record Pairs(List<Pair> top, List<Pair> bottom) {
    }

    public record RollingVolPoint(String date, Map<String, BigDecimal> values) {
    }

    public record DrPoint(String date, BigDecimal dr) {
    }

    public record CorrelationReport(int window,
                                    String asOf,
                                    List<String> symbols,
                                    Map<String, BigDecimal> weights,
                                    List<List<BigDecimal>> matrix,
                                    Map<String, BigDecimal> vols,
                                    BigDecimal portfolioVol,
                                    BigDecimal weightedAvgVol,
                                    BigDecimal diversificationRatio,
                                    BigDecimal effectiveBets,
                                    List<RollingVolPoint> rollingVols,
                                    List<DrPoint> divRatioTimeline,
                                    List<Pair> topPairs,
                                    List<Pair> bottomPairs,
                                    List<String> missingSymbols) {

        public static CorrelationReport empty(int window) {
            return new CorrelationReport(window, LocalDate.now().toString(),
                    List.of(), Map.of(), List.of(), Map.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
