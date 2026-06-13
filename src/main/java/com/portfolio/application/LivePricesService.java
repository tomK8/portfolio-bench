package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.CashBalance;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot for the Live tab: per-position last intraday quote, day change vs the prior
 * stored daily close, and aggregate totals. Stateless apart from a short FX rate cache
 * — the SSE controller calls {@link #snapshot()} every {@code TICK_SECONDS}, and
 * the FX endpoint shouldn't be poked that often.
 *
 * <p>Day change uses {@code price_history.close} for the day strictly before today, so
 * the figure represents "move since yesterday's close" regardless of whether the daily
 * Yahoo job has already written today's row. Prev-close currency matches the listing
 * currency in the intraday feed, so {@code GBp} pence prices cancel cleanly when divided.
 */
public class LivePricesService {

    private static final Logger log = LoggerFactory.getLogger(LivePricesService.class);
    private static final Duration FX_TTL = Duration.ofMinutes(5);

    private static final Map<String, String> ACCOUNT_LABELS = Map.of(
            "AJBell", "AJ Bell SIPP",
            "RothIRA", "Roth IRA",
            "II", "II SIPP");

    private final CashTransactionRepository repo;
    private final IntradayPriceRepository intraday;
    private final PriceHistoryRepository history;
    private final FxRateProvider fxRateProvider;
    private final YahooTickerMap tickerMap;

    private volatile Map<String, BigDecimal> cachedRates = Map.of();
    private volatile Instant cachedRatesAt = Instant.EPOCH;

    public LivePricesService(CashTransactionRepository repo,
                             IntradayPriceRepository intraday,
                             PriceHistoryRepository history,
                             FxRateProvider fxRateProvider,
                             YahooTickerMap tickerMap) {
        this.repo = repo;
        this.intraday = intraday;
        this.history = history;
        this.fxRateProvider = fxRateProvider;
        this.tickerMap = tickerMap;
    }

    public Snapshot snapshot() {
        Map<String, BigDecimal> rates = rates();

        List<CashTransaction> rows = repo.loadDividendTransactions();
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        List<CashBalance> cashBalances = repo.latestCashBalances();

        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (Position p : positions) {
            String sym = p.securityId();
            if (sym == null || "CASH".equals(sym)) continue;
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.putIfAbsent(upper, ticker);
        }
        Map<String, IntradayPrice> latestByTicker =
                intraday.loadLatestIntradayPrices(tickerBySymbol.values());

        LocalDate yesterday = LocalDate.now().minusDays(1);
        Map<String, PriceBar> prevByTicker = new HashMap<>();
        for (String tk : new HashSet<>(tickerBySymbol.values())) {
            PriceBar bar = history.getPriceOn(tk, yesterday);
            if (bar != null) prevByTicker.put(tk, bar);
        }

        BigDecimal totalPosGbp = BigDecimal.ZERO;
        BigDecimal totalDayGbp = BigDecimal.ZERO;
        BigDecimal prevPosTotalGbp = BigDecimal.ZERO;
        List<Row> outRows = new ArrayList<>();
        Instant latestTs = Instant.EPOCH;

        for (Position p : positions) {
            String upper = p.securityId().toUpperCase();
            String ticker = tickerBySymbol.get(upper);
            if (ticker == null) continue;
            IntradayPrice ip = latestByTicker.get(ticker);
            if (ip == null) continue;
            PriceBar prev = prevByTicker.get(ticker);

            Currency ccy = resolveCurrency(p, ip);
            BigDecimal lastGbp = realtimeGbp(p.securityId(), ccy, p.quantity(),
                    BigDecimal.valueOf(ip.close()), ip.currency(), rates);
            BigDecimal prevGbp = (prev != null) ? realtimeGbp(p.securityId(), ccy, p.quantity(),
                    BigDecimal.valueOf(prev.close()), prev.currency(), rates) : null;
            BigDecimal lastPriceDisplay = priceForDisplay(
                    BigDecimal.valueOf(ip.close()), ip.currency(), ccy);

            BigDecimal dayChangeGbp = null;
            BigDecimal dayChangePct = null;
            if (prevGbp != null && prevGbp.signum() != 0 && lastGbp != null) {
                dayChangeGbp = lastGbp.subtract(prevGbp);
                dayChangePct = dayChangeGbp.divide(prevGbp, 6, RoundingMode.HALF_UP);
            }

            outRows.add(new Row(
                    p.securityId(),
                    ccy.getCurrencyCode(),
                    p.quantity().setScale(4, RoundingMode.HALF_UP),
                    scaleOrNull(lastPriceDisplay, 4),
                    scaleOrNull(dayChangePct, 6),
                    scaleOrNull(dayChangeGbp, 2),
                    scaleOrNull(lastGbp, 2),
                    formatAccounts(p.accounts()),
                    ip.ts()));
            if (lastGbp != null) totalPosGbp = totalPosGbp.add(lastGbp);
            if (prevGbp != null) prevPosTotalGbp = prevPosTotalGbp.add(prevGbp);
            if (dayChangeGbp != null) totalDayGbp = totalDayGbp.add(dayChangeGbp);
            if (ip.ts().isAfter(latestTs)) latestTs = ip.ts();
        }

        BigDecimal cashGbp = BigDecimal.ZERO;
        for (CashBalance cb : cashBalances) {
            cashGbp = cashGbp.add(cashToGbp(cb, rates));
        }

        outRows.sort(Comparator.comparing(
                (Row r) -> r.positionGbp() == null ? BigDecimal.ZERO : r.positionGbp()).reversed());

        BigDecimal totalGbp = totalPosGbp.add(cashGbp);
        BigDecimal dayChangePct = (prevPosTotalGbp.signum() != 0)
                ? totalDayGbp.divide(prevPosTotalGbp, 6, RoundingMode.HALF_UP) : null;
        Totals totals = new Totals(
                totalPosGbp.setScale(2, RoundingMode.HALF_UP),
                cashGbp.setScale(2, RoundingMode.HALF_UP),
                totalGbp.setScale(2, RoundingMode.HALF_UP),
                totalDayGbp.setScale(2, RoundingMode.HALF_UP),
                scaleOrNull(dayChangePct, 6));

        return new Snapshot(outRows, totals, Instant.now(),
                latestTs == Instant.EPOCH ? null : latestTs);
    }

    private synchronized Map<String, BigDecimal> rates() {
        if (!cachedRates.isEmpty()
                && Duration.between(cachedRatesAt, Instant.now()).compareTo(FX_TTL) < 0) {
            return cachedRates;
        }
        try {
            cachedRates = fxRateProvider.fetchRates();
            cachedRatesAt = Instant.now();
        } catch (Exception e) {
            log.warn("Live: FX fetch failed, using cached rates", e);
        }
        return cachedRates;
    }

    private static BigDecimal scaleOrNull(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    private static Currency resolveCurrency(Position p, IntradayPrice ip) {
        String c = ip.currency();
        if ("GBp".equals(c)) return Currency.getInstance("GBP");
        try {
            return Currency.getInstance(c);
        } catch (Exception ignored) {
            return Currency.getInstance(p.tradeCurrency());
        }
    }

    private static BigDecimal priceForDisplay(BigDecimal raw, String rawCcy, Currency displayCcy) {
        if ("GBp".equals(rawCcy) && "GBP".equals(displayCcy.getCurrencyCode())) {
            return raw.movePointLeft(2);
        }
        return raw;
    }

    private static BigDecimal realtimeGbp(String securityId, Currency ccy, BigDecimal qty,
                                          BigDecimal rawPrice, String rawCcy,
                                          Map<String, BigDecimal> rates) {
        BigDecimal price = priceForDisplay(rawPrice, rawCcy, ccy);
        BigDecimal nativeAmt = Instruments.isBond(securityId)
                ? price.multiply(qty).movePointLeft(2)
                : price.multiply(qty);
        if ("GBP".equals(ccy.getCurrencyCode())) return nativeAmt;
        BigDecimal rate = rates.get(ccy.getCurrencyCode());
        return (rate == null || rate.signum() == 0)
                ? null : nativeAmt.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal cashToGbp(CashBalance cb, Map<String, BigDecimal> rates) {
        if ("GBP".equals(cb.currency()) || cb.cashNative() == null) {
            return BigDecimal.valueOf(cb.cashGbp());
        }
        BigDecimal rate = rates.get(cb.currency());
        return (rate != null && rate.signum() != 0)
                ? BigDecimal.valueOf(cb.cashNative()).divide(rate, 10, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(cb.cashGbp());
    }

    private static String formatAccounts(Collection<String> accounts) {
        StringBuilder sb = new StringBuilder();
        for (String a : accounts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ACCOUNT_LABELS.getOrDefault(a, a));
        }
        return sb.toString();
    }

    public record Snapshot(
            List<Row> rows,
            Totals totals,
            Instant generatedAt,
            Instant latestPriceAt) {
    }

    public record Row(
            String symbol,
            String currency,
            BigDecimal quantity,
            BigDecimal lastPrice,
            BigDecimal dayChangePct,
            BigDecimal dayChangeGbp,
            BigDecimal positionGbp,
            String accounts,
            Instant priceTs) {
    }

    public record Totals(
            BigDecimal positionsGbp,
            BigDecimal cashGbp,
            BigDecimal totalGbp,
            BigDecimal dayChangeGbp,
            BigDecimal dayChangePct) {
    }
}
