package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.ShareClasses;
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
    /**
     * The listings declared to be one fund. Two lines of one share class are one holding, and every
     * consumer of these points — the composition chart, concentration, currency, sector and style —
     * inherits that by the points being folded here rather than each folding them again.
     */
    private final ShareClassRegistry shareClasses;

    public AllocationService(CashTransactionRepository cashRepo,
                             PriceHistoryRepository priceRepo,
                             HistoricalFxRateProvider fxProvider,
                             YahooTickerMap tickerMap,
                             KeyValueStore settings) {
        this(cashRepo, priceRepo, fxProvider, tickerMap, settings, ShareClassRegistry.NONE);
    }

    public AllocationService(CashTransactionRepository cashRepo,
                             PriceHistoryRepository priceRepo,
                             HistoricalFxRateProvider fxProvider,
                             YahooTickerMap tickerMap,
                             KeyValueStore settings,
                             ShareClassRegistry shareClasses) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fxProvider = fxProvider;
        this.tickerMap = tickerMap;
        this.settings = settings;
        this.shareClasses = shareClasses == null ? ShareClassRegistry.NONE : shareClasses;
    }

    public AllocationTimeline timeline() {
        return new AllocationTimeline(replay(true).points());
    }

    /**
     * Today's composition plus the two things behind it the replay already tracks and otherwise
     * discards: the per-{@code (account, currency)} cash balances, and the held quantity per
     * symbol.
     *
     * <p>Kept off {@link AllocationPoint} deliberately: that record is serialised ~550 times by
     * {@code GET /allocation}, and hanging two maps off every point would bloat the chart payload
     * for data only the drill-downs need.
     *
     * <p>The quantities exist for the style block's fixed-income yield. A gilt's annual coupon is
     * {@code nominal × coupon} exactly — no price and no FX — and its running yield then falls out
     * as {@code income ÷ value}, which is {@code coupon ÷ (price/100)} computed against the very
     * price this replay valued the position at. Re-reading a price from the repositories would be
     * a second valuation path that could disagree with the number printed beside it.
     *
     * @return the current snapshot, or {@code null} when the ledger is empty
     */
    public CurrentSnapshot currentSnapshot() {
        Replay r = replay(false);
        if (r.points().isEmpty()) return null;
        return new CurrentSnapshot(r.points().get(r.points().size() - 1),
                r.cashGbpByAccountCcy(), r.quantityBySymbol());
    }

    /**
     * Today's composition only — the single-sample form of {@link #timeline()}, in the same spirit
     * as {@code PortfolioValueService.dailyValuesIn}. The ledger still replays from its first row
     * (quantities and cash have to be caught up), but none of the ~550 weekly snapshots are valued,
     * so callers that only want the current breakdown don't pay for a decade of history.
     *
     * <p>Shares one replay path with {@link #timeline()} so the sector panel and the chart beneath
     * it cannot disagree about what is held today.
     *
     * @return the current point, or {@code null} when the ledger is empty
     */
    public AllocationPoint currentPoint() {
        CurrentSnapshot snapshot = currentSnapshot();
        return snapshot == null ? null : snapshot.point();
    }

    private Replay replay(boolean weeklySamples) {
        List<CashTransaction> txs = cashRepo.loadAllTransactions();
        if (txs.isEmpty()) return new Replay(List.of(), Map.of(), Map.of());

        LocalDate start = LocalDate.parse(txs.get(0).transactionDate());
        LocalDate today = LocalDate.now();

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPriceSeries(txs);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(start, today);
        // Read once for the whole replay: ~550 samples must not each ask the same question, and a
        // declaration that changed mid-replay would draw a series that folds only part of itself.
        ShareClasses holdings = shareClasses.declared();

        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        LocalDate rothStart = rothStartStr == null ? null : LocalDate.parse(rothStartStr);
        boolean rothSeeded = false;

        Map<String, BigDecimal> qty = new HashMap<>();
        Map<String, BigDecimal> cashByAcctCcy = new HashMap<>();

        List<AllocationPoint> points = new ArrayList<>();
        int idx = 0;
        if (weeklySamples) {
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
                points.add(snapshot(sample, qty, cashByAcctCcy, prices, fx, holdings));
                sample = sample.plusDays(SAMPLE_INTERVAL_DAYS);
            }
        }
        // Replay remaining transactions through today and append a final sample so the latest
        // composition is in the series even if it's mid-week (the user opening the snapshot
        // panel without a date selection sees the current state, not last Sunday's).
        while (idx < txs.size() && !LocalDate.parse(txs.get(idx).transactionDate()).isAfter(today)) {
            apply(qty, cashByAcctCcy, txs.get(idx));
            idx++;
        }
        // Seed here too: the weekly loop only sees sample dates, so a Roth start falling between
        // the last sample and today would otherwise miss its seed — and on the currentPoint path
        // there is no weekly loop to have applied it at all.
        if (!rothSeeded && rothStart != null && !rothStart.isAfter(today)) {
            cashByAcctCcy.merge("RothIRA|USD", rothSeedUsd, BigDecimal::add);
        }
        if (points.isEmpty() || !points.get(points.size() - 1).date().equals(today.toString())) {
            points.add(snapshot(today, qty, cashByAcctCcy, prices, fx, holdings));
        }
        return new Replay(points, cashDetailGbp(cashByAcctCcy, today, fx), heldQuantities(qty));
    }

    /**
     * Per-{@code (account, currency)} cash in GBP at {@code sample}. Uses the same {@link #toGbp}
     * conversion {@link #snapshot} applies to the aggregate, so the parts reconcile to the
     * {@code cashGbp} the point reports rather than drifting on a second rounding path.
     */
    private Map<String, BigDecimal> cashDetailGbp(Map<String, BigDecimal> cashByAcctCcy,
                                                  LocalDate sample,
                                                  Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : cashByAcctCcy.entrySet()) {
            BigDecimal n = e.getValue();
            if (n.signum() == 0) continue;
            String ccy = e.getKey().substring(e.getKey().indexOf('|') + 1);
            BigDecimal g = toGbp(n, ccy, sample, fx);
            if (g != null && g.signum() != 0) {
                out.put(e.getKey(), g.setScale(2, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    /**
     * Open positions only, matching {@link #snapshot}'s own {@code signum() <= 0} filter — a
     * symbol sold out sits in the replay map at zero and is not a holding.
     */
    private static Map<String, BigDecimal> heldQuantities(Map<String, BigDecimal> qty) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : qty.entrySet()) {
            if (e.getValue().signum() > 0) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Composition at one sample.
     *
     * <p>Each listing is valued on <em>its own</em> quote in its own currency, and only then are the
     * declared share-class pairs folded together — so nothing here assumes one unit of a fund's
     * sterling line equals one unit of its dollar line, which is an assumption about units rather
     * than about money. Folding after valuation also means the totals are identical either way: the
     * fold moves value between rows, never into or out of the chart.
     */
    private AllocationPoint snapshot(LocalDate sample,
                                     Map<String, BigDecimal> qty,
                                     Map<String, BigDecimal> cashByAcctCcy,
                                     Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                     Map<String, NavigableMap<LocalDate, BigDecimal>> fx,
                                     ShareClasses holdings) {
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
                holdings.fold(symbolGbp));
    }

    // ---- replay -------------------------------------------------------------

    private static void apply(Map<String, BigDecimal> qtyBySymbol,
                              Map<String, BigDecimal> cashByAccountCcy,
                              CashTransaction t) {
        // An in-specie transfer is inert here, and skipping the pair is not a shortcut but the
        // only correct handling. This replay tracks quantity (and cost) *globally per symbol* and
        // cash per (account, currency): the two legs move the same shares and the same cost in
        // opposite directions, so they necessarily cancel in the symbol totals, while their
        // amounts are a cost basis rather than money and must never touch cash. Processing them
        // could only introduce error — the weighted-average cost released by the outgoing leg is
        // taken against the *global* pool, which for a name held in several accounts is not the
        // cost the losing account actually carried, so the pair would fail to cancel.
        if (t.type() == TransactionType.TRANSFER) return;
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
     *
     * <p>Keyed by <b>holding</b>, not by listing: two lines of one share class the user has declared
     * a switch between are one entry, under the line the switch is leaving. A half-finished switch
     * would otherwise draw one series falling as another rises while nothing owned had changed.
     */
    public record AllocationPoint(String date,
                                  BigDecimal cashGbp,
                                  BigDecimal investedGbp,
                                  BigDecimal totalGbp,
                                  Map<String, BigDecimal> symbolGbp) {
    }

    public record AllocationTimeline(List<AllocationPoint> points) {
    }

    /**
     * Today's point plus the two details behind it: cash keyed
     * {@code "<Account.dbValue()>|<CCY>"} (e.g. {@code "AJBell|GBP"}), and the held quantity per
     * internal symbol — nominal for a bond, shares for anything else, in the same units every
     * {@code qty × price / 100} valuation in the codebase assumes.
     *
     * <p>Quantities stay keyed by <b>listing</b> while the point's values are keyed by holding. They
     * are a unit count, and adding two listings' units together would assert they are interchangeable
     * — a claim about share classes this map has no business making. Its one consumer is the fixed-
     * income yield, which needs a gilt's nominal, and a gilt has no second listing to be folded with.
     */
    public record CurrentSnapshot(AllocationPoint point,
                                  Map<String, BigDecimal> cashGbpByAccountCcy,
                                  Map<String, BigDecimal> quantityBySymbol) {
    }

    /** Internal replay result — the samples plus the two details the points drop. */
    private record Replay(List<AllocationPoint> points,
                          Map<String, BigDecimal> cashGbpByAccountCcy,
                          Map<String, BigDecimal> quantityBySymbol) {
    }
}
