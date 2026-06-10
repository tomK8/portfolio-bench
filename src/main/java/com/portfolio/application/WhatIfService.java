package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * "What if I'd just bought ETFs?" simulator. Replays every {@code CONTRIBUTION} row (and the
 * Roth USD seed at its earliest ledger date) and, on each contribution date, splits the GBP
 * amount across the supplied basket by weight, buying fractional shares at that day's
 * {@code adj_close} — total-return mode, so dividends paid by the basket are reinvested.
 *
 * <p>Then values the running basket at each month-end (plus a "today" point) using the same
 * floor-fill price lookup {@link PortfolioValueService} uses. The output shape matches
 * {@link PortfolioValueService.ValueTimeline} so the dashboard can overlay it on the actual
 * portfolio line without bespoke chart wiring.
 *
 * <p>Known approximations (same caveats as the actual-value chart, plus one):
 * <ul>
 *   <li>If a basket symbol has no price ≤ the contribution date we fall back to its earliest
 *       known close (ceil-fill). The synthetic buy "lands" at that price — accurate-ish for
 *       a backtest, but biases against the basket when the earliest close is well after the
 *       contribution.</li>
 *   <li>If a basket symbol has no rows in {@code price_history} at all, the run still
 *       returns — its allocation is treated as held cash (i.e. it stays in GBP). The symbol
 *       shows up in {@code missingPrices} so the UI can warn the user.</li>
 * </ul>
 */
public class WhatIfService {

    private static final Logger log = LoggerFactory.getLogger(WhatIfService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.0005");

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final HistoricalFxRateProvider fxProvider;
    private final YahooTickerMap tickerMap;
    private final KeyValueStore settings;

    public WhatIfService(CashTransactionRepository cashRepo,
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

    /**
     * @param basket up to 7 entries, weights in [0,1] summing to 1.0 (±0.0005 tolerance).
     */
    public PortfolioValueService.ValueTimeline timeline(List<Weight> basket) {
        validate(basket);

        List<Contribution> events = loadContributionEvents();
        if (events.isEmpty()) return new PortfolioValueService.ValueTimeline(List.of(), List.of());

        LocalDate start = events.get(0).date;
        LocalDate end = LocalDate.now();

        Map<String, NavigableMap<LocalDate, PricePoint>> prices = loadPrices(basket);
        Map<String, NavigableMap<LocalDate, BigDecimal>> fx = preloadFx(start, end);

        Map<String, BigDecimal> shares = new HashMap<>();   // symbol → fractional shares accumulated
        BigDecimal residualGbp = BigDecimal.ZERO;            // allocations that couldn't be priced

        List<PortfolioValueService.DataPoint> points = new ArrayList<>();
        int idx = 0;
        LocalDate sample = start;
        while (!sample.isAfter(end)) {
            while (idx < events.size() && !events.get(idx).date.isAfter(sample)) {
                residualGbp = residualGbp.add(applyContribution(events.get(idx), basket, shares, prices, fx));
                idx++;
            }
            BigDecimal v = valueAt(sample, shares, prices, fx).add(residualGbp);
            points.add(new PortfolioValueService.DataPoint(sample.toString(),
                    v.setScale(2, RoundingMode.HALF_UP), null));
            sample = sample.plusDays(1);
        }

        List<PortfolioValueService.MissingPrice> missing = new ArrayList<>();
        for (Weight w : basket) {
            String sym = w.symbol.toUpperCase();
            if (!prices.containsKey(sym)) {
                missing.add(new PortfolioValueService.MissingPrice(
                        sym, start.toString(), end.toString()));
                log.warn("What-if: no price_history rows for {} — its {}% allocation stays as GBP cash. "
                        + "Backfill with the daily price job or a Tradeweb file.",
                        sym, w.weight.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP));
            }
        }
        return new PortfolioValueService.ValueTimeline(points, missing);
    }

    // ---- Contribution stream --------------------------------------------

    /**
     * One ordered stream of GBP-denominated contribution events, mirroring
     * {@link ContributionService}: every CONTRIBUTION row plus the Roth USD seed on Roth's
     * earliest ledger date (FX'd to GBP at that date).
     */
    private List<Contribution> loadContributionEvents() {
        List<Contribution> events = new ArrayList<>();
        for (CashTransaction t : cashRepo.loadContributions()) {
            events.add(new Contribution(LocalDate.parse(t.transactionDate()),
                    BigDecimal.valueOf(t.amountGbp())));
        }
        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        if (rothSeedUsd.signum() > 0 && rothStartStr != null) {
            LocalDate rothStart = LocalDate.parse(rothStartStr);
            BigDecimal seedGbp = convertSeedToGbp(rothSeedUsd, rothStart);
            if (seedGbp.signum() > 0) events.add(new Contribution(rothStart, seedGbp));
        }
        events.sort(java.util.Comparator.comparing(c -> c.date));
        return events;
    }

    private BigDecimal convertSeedToGbp(BigDecimal seedUsd, LocalDate date) {
        try {
            Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(
                    "USD", date.minusDays(14), date.plusDays(1));
            BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(new TreeMap<>(series), date);
            if (rate == null || rate.signum() == 0) return BigDecimal.ZERO;
            return seedUsd.divide(rate, 10, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Could not convert Roth USD seed to GBP for what-if", e);
            return BigDecimal.ZERO;
        }
    }

    // ---- Per-event allocation -------------------------------------------

    /**
     * Split this contribution across the basket, accumulating fractional shares. Returns the
     * portion of the contribution that stayed as GBP cash: either because the basket weights
     * don't sum to 100% (intentional cash allocation) or because a basket symbol has no
     * price series we could buy at (unintentional residual). Both end up in the same bucket
     * because they behave identically — flat in GBP, no return.
     */
    private BigDecimal applyContribution(Contribution c, List<Weight> basket,
                                         Map<String, BigDecimal> shares,
                                         Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                         Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        BigDecimal cashGbp = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (Weight w : basket) {
            String sym = w.symbol.toUpperCase();
            BigDecimal allocGbp = c.amountGbp.multiply(w.weight);
            weightSum = weightSum.add(w.weight);
            BigDecimal priceGbp = priceGbpAt(sym, c.date, prices, fx);
            if (priceGbp == null || priceGbp.signum() <= 0) {
                cashGbp = cashGbp.add(allocGbp);
                continue;
            }
            BigDecimal addedShares = Instruments.isBond(sym)
                    ? allocGbp.multiply(HUNDRED).divide(priceGbp, 10, RoundingMode.HALF_UP)
                    : allocGbp.divide(priceGbp, 10, RoundingMode.HALF_UP);
            shares.merge(sym, addedShares, BigDecimal::add);
        }
        BigDecimal unallocated = BigDecimal.ONE.subtract(weightSum);
        if (unallocated.signum() > 0) {
            cashGbp = cashGbp.add(c.amountGbp.multiply(unallocated));
        }
        return cashGbp;
    }

    // ---- Valuation ------------------------------------------------------

    private static BigDecimal valueAt(LocalDate sample, Map<String, BigDecimal> shares,
                                      Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                      Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : shares.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty.signum() <= 0) continue;
            BigDecimal priceGbp = priceGbpAt(e.getKey(), sample, prices, fx);
            if (priceGbp == null) continue;
            BigDecimal native_ = Instruments.isBond(e.getKey())
                    ? qty.multiply(priceGbp).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                    : qty.multiply(priceGbp);
            total = total.add(native_);
        }
        return total;
    }

    /**
     * GBP-per-share price for {@code symbol} as of {@code date}: floor-fill the price series,
     * fall back to earliest known close (matching {@link PortfolioValueService}), convert
     * pence→pounds for {@code GBp} listings, then FX to GBP at the historical rate.
     */
    private static BigDecimal priceGbpAt(String symbol, LocalDate date,
                                         Map<String, NavigableMap<LocalDate, PricePoint>> prices,
                                         Map<String, NavigableMap<LocalDate, BigDecimal>> fx) {
        NavigableMap<LocalDate, PricePoint> series = prices.get(symbol);
        if (series == null || series.isEmpty()) return null;
        Map.Entry<LocalDate, PricePoint> entry = series.floorEntry(date);
        if (entry == null) entry = series.firstEntry();
        PricePoint pp = entry.getValue();
        BigDecimal price = BigDecimal.valueOf(pp.adjClose);
        String currency = pp.currency;
        if ("GBp".equals(currency)) {
            price = price.movePointLeft(2);
            currency = "GBP";
        }
        if ("GBP".equals(currency)) return price;
        NavigableMap<LocalDate, BigDecimal> series2 = fx.get(currency);
        if (series2 == null) return null;
        BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series2, date);
        if (rate == null || rate.signum() == 0) return null;
        return price.divide(rate, 10, RoundingMode.HALF_UP);
    }

    // ---- Preloads -------------------------------------------------------

    private Map<String, NavigableMap<LocalDate, PricePoint>> loadPrices(List<Weight> basket) {
        Map<String, NavigableMap<LocalDate, PricePoint>> out = new HashMap<>();
        LocalDate earliest = LocalDate.of(2000, 1, 1);
        LocalDate latest = LocalDate.now();
        for (Weight w : basket) {
            String sym = w.symbol.toUpperCase();
            if (out.containsKey(sym)) continue;
            String ticker = Instruments.isBond(sym) ? sym : tickerMap.tickerFor(sym);
            List<PriceBar> bars = priceRepo.getPriceHistory(ticker, earliest, latest);
            if (bars.isEmpty()) continue;
            NavigableMap<LocalDate, PricePoint> series = new TreeMap<>();
            for (PriceBar b : bars) series.put(b.date(), new PricePoint(b.adjClose(), b.currency()));
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

    // ---- Validation -----------------------------------------------------

    private static void validate(List<Weight> basket) {
        if (basket == null || basket.isEmpty()) {
            throw new IllegalArgumentException("Basket is empty — pick at least one instrument.");
        }
        if (basket.size() > 7) {
            throw new IllegalArgumentException("Basket may contain at most 7 instruments.");
        }
        BigDecimal sum = BigDecimal.ZERO;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Weight w : basket) {
            if (w.symbol == null || w.symbol.isBlank()) {
                throw new IllegalArgumentException("Basket entry has no symbol.");
            }
            if (!seen.add(w.symbol.toUpperCase())) {
                throw new IllegalArgumentException("Duplicate symbol in basket: " + w.symbol);
            }
            if (w.weight.signum() <= 0) {
                throw new IllegalArgumentException("Weight for " + w.symbol + " must be positive.");
            }
            sum = sum.add(w.weight);
        }
        // Allow under-allocation (the rest stays as GBP cash, modelled in applyContribution).
        // Over-allocation is still an error — it would synthesize money that wasn't contributed.
        if (sum.subtract(BigDecimal.ONE).compareTo(WEIGHT_TOLERANCE) > 0) {
            throw new IllegalArgumentException("Weights must sum to 100% or less (got "
                    + sum.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP) + "%).");
        }
    }

    // ---- DTOs -----------------------------------------------------------

    /** One basket entry. Weight is a fraction in [0,1] — caller converts from percent. */
    public record Weight(String symbol, BigDecimal weight) {
    }

    private record Contribution(LocalDate date, BigDecimal amountGbp) {
    }

    private record PricePoint(double adjClose, String currency) {
    }
}
