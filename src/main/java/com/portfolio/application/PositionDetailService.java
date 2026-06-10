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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-symbol drill-down: FIFO lots (open + closed), dividends, total return, price chart
 * data with buy/sell/dividend markers.
 *
 * <p>FIFO is run per {@code (account, symbol)} timeline and then merged for display.
 * Every buy opens a lot; every sell consumes lots oldest-first and emits one {@link ClosedLot}
 * per piece consumed (so a single sell against three earlier buys produces three closed lots,
 * preserving the open-date of each underlying piece). Splits scale every surviving lot's
 * quantity and inverse-scale its cost-per-share so cost basis is invariant — same model as
 * {@link com.portfolio.domain.CashLedgerReconstructor}.
 *
 * <p>The price chart series shows <b>raw close in the listing currency</b>:
 * {@code priceBar.close × priceBar.splitFactor}. Yahoo's stored close is split-adjusted to
 * today's basis; multiplying by {@code splitFactor} recovers the close the user actually saw
 * on that day. Buy / sell markers sit at that price so they line up with the chart visually;
 * tooltips carry the GBP cost / proceeds from the ledger.
 *
 * <p>Total return = realized + unrealized + dividends, all GBP. Unrealized uses live intraday
 * + live FX (same path as the cash-ledger sync view). If there's no intraday price the
 * unrealized component is {@code null} and {@code totalReturnGbp} falls through as {@code null}
 * rather than being silently wrong.
 */
public class PositionDetailService {

    private static final Logger log = LoggerFactory.getLogger(PositionDetailService.class);
    private static final int SCALE = 10;
    private static final int GBP_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final IntradayPriceRepository intradayRepo;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    public PositionDetailService(CashTransactionRepository cashRepo,
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

    public PositionDetail detail(String symbolRaw) {
        if (symbolRaw == null || symbolRaw.isBlank()) return PositionDetail.empty("");
        String symbol = symbolRaw.trim().toUpperCase();

        List<CashTransaction> all = cashRepo.loadDividendTransactions();
        List<CashTransaction> rows = new ArrayList<>();
        for (CashTransaction t : all) {
            if (t.symbol() != null && symbol.equals(t.symbol().toUpperCase())) rows.add(t);
        }
        if (rows.isEmpty()) return PositionDetail.empty(symbol);

        Lots lots = reconstruct(rows);

        List<DividendPayment> dividends = new ArrayList<>();
        BigDecimal lifetimeDivs = BigDecimal.ZERO;
        for (CashTransaction t : rows) {
            if (t.type() != TransactionType.DIVIDEND) continue;
            BigDecimal amt = BigDecimal.valueOf(t.amountGbp());
            dividends.add(new DividendPayment(t.transactionDate(),
                    amt.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                    t.account().dbValue()));
            lifetimeDivs = lifetimeDivs.add(amt);
        }

        BigDecimal shares = lots.totalOpenShares();
        BigDecimal costBasis = lots.totalOpenCostGbp();
        BigDecimal realized = lots.totalRealizedGbp();

        String ticker = Instruments.isBond(symbol) ? symbol : tickerMap.tickerFor(symbol);
        List<PriceBar> bars = priceRepo.getPriceHistory(ticker,
                LocalDate.parse(rows.get(0).transactionDate()).minusMonths(3),
                LocalDate.now());

        IntradayPrice ip = intradayRepo.loadLatestIntradayPrices(List.of(ticker)).get(ticker);
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            log.warn("Could not fetch FX rates for position detail; unrealized will be null", e);
            rates = Map.of();
        }

        BigDecimal currentPriceNative = ip == null ? null : BigDecimal.valueOf(ip.close());
        String priceCcy = ip == null ? null : ip.currency();
        BigDecimal marketValueGbp = positionValueGbp(symbol, shares, ip, rates);

        BigDecimal unrealized = (marketValueGbp == null || costBasis.signum() == 0)
                ? null : marketValueGbp.subtract(costBasis);
        BigDecimal totalReturn = (unrealized == null)
                ? null : realized.add(unrealized).add(lifetimeDivs);

        List<PricePoint> priceSeries = new ArrayList<>();
        for (PriceBar b : bars) {
            // Raw close on that day's basis. Yahoo stores split-adjusted close; multiplying
            // by splitFactor undoes the adjustment so the marker for a pre-split buy sits at
            // the price the user actually paid.
            priceSeries.add(new PricePoint(b.date().toString(),
                    BigDecimal.valueOf(b.close() * b.splitFactor())
                            .setScale(6, RoundingMode.HALF_UP)));
        }

        Map<String, BigDecimal> closeByDate = new LinkedHashMap<>();
        for (PriceBar b : bars) {
            closeByDate.put(b.date().toString(),
                    BigDecimal.valueOf(b.close() * b.splitFactor()));
        }

        List<Marker> markers = new ArrayList<>();
        for (CashTransaction t : rows) {
            BigDecimal yPrice = nearestClose(closeByDate, t.transactionDate());
            if (t.type() == TransactionType.TRANSACTION) {
                BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
                if (qty.signum() == 0) continue;
                BigDecimal gbp = BigDecimal.valueOf(Math.abs(t.amountGbp()))
                        .setScale(GBP_SCALE, RoundingMode.HALF_UP);
                if (t.amount() < 0) {
                    markers.add(new Marker(t.transactionDate(), "buy", yPrice,
                            qty, gbp, t.account().dbValue()));
                } else if (t.amount() > 0) {
                    markers.add(new Marker(t.transactionDate(), "sell", yPrice,
                            qty, gbp, t.account().dbValue()));
                }
            } else if (t.type() == TransactionType.DIVIDEND) {
                BigDecimal gbp = BigDecimal.valueOf(t.amountGbp())
                        .setScale(GBP_SCALE, RoundingMode.HALF_UP);
                markers.add(new Marker(t.transactionDate(), "div", yPrice,
                        BigDecimal.ZERO, gbp, t.account().dbValue()));
            }
        }

        Summary summary = new Summary(
                shares.setScale(6, RoundingMode.HALF_UP),
                money(costBasis),
                marketValueGbp == null ? null : money(marketValueGbp),
                money(realized),
                unrealized == null ? null : money(unrealized),
                money(lifetimeDivs),
                totalReturn == null ? null : money(totalReturn),
                currentPriceNative,
                priceCcy);
        return new PositionDetail(symbol, summary,
                lots.openForDisplay(), lots.closedForDisplay(),
                dividends, priceSeries, markers, bars.isEmpty());
    }

    private BigDecimal positionValueGbp(String symbol, BigDecimal shares, IntradayPrice ip,
                                        Map<String, BigDecimal> rates) {
        if (ip == null || shares.signum() == 0) return null;
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

    private static BigDecimal nearestClose(Map<String, BigDecimal> closeByDate, String date) {
        // Exact match → use it; otherwise the marker is left at zero and the chart still
        // renders it. Tooltips carry the GBP context regardless, so the marker's exact y
        // position is decorative.
        BigDecimal v = closeByDate.get(date);
        return v == null ? BigDecimal.ZERO
                : v.setScale(6, RoundingMode.HALF_UP);
    }

    /** Visible for testing. */
    static Lots reconstruct(List<CashTransaction> rows) {
        Map<String, LotEngine> byAccount = new LinkedHashMap<>();
        for (CashTransaction t : rows) {
            byAccount.computeIfAbsent(t.account().dbValue(), k -> new LotEngine(k)).accept(t);
        }
        return new Lots(byAccount);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }

    // ---- Lot engine ---------------------------------------------------------

    static final class LotEngine {
        private final String account;
        private final Deque<OpenLotState> open = new ArrayDeque<>();
        private final List<ClosedLot> closed = new ArrayList<>();

        LotEngine(String account) {
            this.account = account;
        }

        void accept(CashTransaction t) {
            if (t.type() != TransactionType.TRANSACTION) return;
            BigDecimal qty = BigDecimal.valueOf(Math.abs(t.quantity()));
            if (qty.signum() == 0) {
                // Treat as a split — signed quantity delta with zero cash.
                if (t.amount() == 0) split(BigDecimal.valueOf(t.quantity()));
                return;
            }
            BigDecimal absCostGbp = BigDecimal.valueOf(Math.abs(t.amountGbp()));
            if (t.amount() < 0) {
                BigDecimal cps = absCostGbp.divide(qty, SCALE, RoundingMode.HALF_UP);
                open.addLast(new OpenLotState(t.transactionDate(), qty, cps));
            } else if (t.amount() > 0) {
                BigDecimal pps = absCostGbp.divide(qty, SCALE, RoundingMode.HALF_UP);
                sell(t.transactionDate(), qty, pps);
            } else {
                split(BigDecimal.valueOf(t.quantity()));
            }
        }

        private void sell(String date, BigDecimal qty, BigDecimal pricePerShareGbp) {
            BigDecimal remaining = qty;
            while (remaining.signum() > 0 && !open.isEmpty()) {
                OpenLotState head = open.peekFirst();
                BigDecimal use = head.qty.min(remaining);
                BigDecimal cost = use.multiply(head.costPerShareGbp);
                BigDecimal proceeds = use.multiply(pricePerShareGbp);
                BigDecimal realized = proceeds.subtract(cost);
                closed.add(new ClosedLot(head.openDate, date,
                        use.setScale(6, RoundingMode.HALF_UP),
                        cost.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                        proceeds.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                        realized.setScale(GBP_SCALE, RoundingMode.HALF_UP),
                        account));
                head.qty = head.qty.subtract(use);
                remaining = remaining.subtract(use);
                if (head.qty.signum() == 0) open.removeFirst();
            }
        }

        private void split(BigDecimal delta) {
            BigDecimal current = BigDecimal.ZERO;
            for (OpenLotState l : open) current = current.add(l.qty);
            if (current.signum() <= 0) return;
            BigDecimal newTotal = current.add(delta);
            if (newTotal.signum() <= 0) return;
            BigDecimal ratio = newTotal.divide(current, SCALE, RoundingMode.HALF_UP);
            for (OpenLotState l : open) {
                l.qty = l.qty.multiply(ratio);
                l.costPerShareGbp = l.costPerShareGbp.divide(ratio, SCALE, RoundingMode.HALF_UP);
            }
        }
    }

    static final class OpenLotState {
        final String openDate;
        BigDecimal qty;
        BigDecimal costPerShareGbp;

        OpenLotState(String openDate, BigDecimal qty, BigDecimal costPerShareGbp) {
            this.openDate = openDate;
            this.qty = qty;
            this.costPerShareGbp = costPerShareGbp;
        }
    }

    /** Visible for testing — collects per-account open/closed lots and aggregates totals. */
    static final class Lots {
        private final Map<String, LotEngine> byAccount;

        Lots(Map<String, LotEngine> byAccount) {
            this.byAccount = byAccount;
        }

        BigDecimal totalOpenShares() {
            BigDecimal s = BigDecimal.ZERO;
            for (LotEngine e : byAccount.values())
                for (OpenLotState l : e.open) s = s.add(l.qty);
            return s;
        }

        BigDecimal totalOpenCostGbp() {
            BigDecimal s = BigDecimal.ZERO;
            for (LotEngine e : byAccount.values())
                for (OpenLotState l : e.open) s = s.add(l.qty.multiply(l.costPerShareGbp));
            return s;
        }

        BigDecimal totalRealizedGbp() {
            BigDecimal s = BigDecimal.ZERO;
            for (LotEngine e : byAccount.values())
                for (ClosedLot c : e.closed) s = s.add(c.realizedGbp());
            return s;
        }

        List<OpenLot> openForDisplay() {
            List<OpenLot> out = new ArrayList<>();
            for (LotEngine e : byAccount.values()) {
                for (OpenLotState l : e.open) {
                    out.add(new OpenLot(l.openDate,
                            l.qty.setScale(6, RoundingMode.HALF_UP),
                            l.costPerShareGbp.setScale(6, RoundingMode.HALF_UP),
                            l.qty.multiply(l.costPerShareGbp).setScale(GBP_SCALE, RoundingMode.HALF_UP),
                            e.account));
                }
            }
            out.sort(Comparator.comparing(OpenLot::openDate));
            return out;
        }

        List<ClosedLot> closedForDisplay() {
            List<ClosedLot> out = new ArrayList<>();
            for (LotEngine e : byAccount.values()) out.addAll(e.closed);
            out.sort(Comparator.comparing(ClosedLot::closeDate));
            return out;
        }
    }

    // ---- DTOs ---------------------------------------------------------------

    public record OpenLot(String openDate, BigDecimal shares,
                          BigDecimal costPerShareGbp, BigDecimal costGbp,
                          String account) {
    }

    public record ClosedLot(String openDate, String closeDate, BigDecimal shares,
                            BigDecimal costGbp, BigDecimal proceedsGbp, BigDecimal realizedGbp,
                            String account) {
    }

    public record DividendPayment(String date, BigDecimal amountGbp, String account) {
    }

    public record PricePoint(String date, BigDecimal price) {
    }

    /**
     * Annotation on the chart. {@code kind} is one of {@code "buy"}, {@code "sell"},
     * {@code "div"}. {@code priceAtMarker} is the chart-line y-coordinate where the icon
     * should sit (close on that date, native ccy). {@code shares} is zero for dividend
     * markers. {@code gbpAmount} is the GBP cost (buy), GBP proceeds (sell), or GBP
     * dividend (div).
     */
    public record Marker(String date, String kind, BigDecimal priceAtMarker,
                         BigDecimal shares, BigDecimal gbpAmount, String account) {
    }

    public record Summary(BigDecimal shares,
                          BigDecimal costBasisGbp,
                          BigDecimal marketValueGbp,
                          BigDecimal realizedGbp,
                          BigDecimal unrealizedGbp,
                          BigDecimal dividendsGbp,
                          BigDecimal totalReturnGbp,
                          BigDecimal currentPrice,
                          String currentPriceCurrency) {

        public static Summary empty() {
            return new Summary(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO,
                    null, BigDecimal.ZERO, null, null, null);
        }
    }

    public record PositionDetail(String symbol, Summary summary,
                                 List<OpenLot> openLots,
                                 List<ClosedLot> closedLots,
                                 List<DividendPayment> dividends,
                                 List<PricePoint> priceHistory,
                                 List<Marker> markers,
                                 boolean missingPriceHistory) {

        public static PositionDetail empty(String symbol) {
            return new PositionDetail(symbol, Summary.empty(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), false);
        }
    }
}
