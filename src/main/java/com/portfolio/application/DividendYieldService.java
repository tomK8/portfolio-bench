package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.DividendYield;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.DividendHistoryRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a dividend yield from stored payment history for symbols Yahoo's {@code summaryDetail}
 * says nothing about — the single implementation shared by the income overlay
 * ({@link StyleBreakdownService}) and the open-orders drift netting, so the two panels cannot
 * disagree about what a name yields.
 *
 * <p>Keyed on the <b>Yahoo ticker</b>, like {@code price_history} itself, because the divisor is a
 * stored close from that same table: dividend amounts and the close come out of one chart response,
 * so they share a currency and a split basis and the quotient is a pure ratio. Callers pass
 * internal symbols and the mapping happens here once.
 *
 * <p><b>Absent is not zero.</b> A symbol whose full history has never been scanned is left out of
 * the map entirely, exactly as {@code FundamentalsRepository.loadForwardYields} leaves out a name
 * with no published yield — the caller has to be able to tell an accumulating ETF that really
 * distributes nothing from one nobody has fetched yet.
 */
public class DividendYieldService {

    private static final Logger log = LoggerFactory.getLogger(DividendYieldService.class);

    private final DividendHistoryRepository divRepo;
    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickers;

    public DividendYieldService(DividendHistoryRepository divRepo, PriceHistoryRepository priceRepo,
                                YahooTickerMap tickers) {
        this.divRepo = divRepo;
        this.priceRepo = priceRepo;
        this.tickers = tickers;
    }

    /**
     * The derived yield plus the provenance behind it.
     *
     * @param yield  annual dividend ÷ latest close, as a fraction
     * @param detail payment count, last ex-date and whether the window had to be widened
     */
    public record Derived(BigDecimal yield, DividendYield.Result detail) {
        public String explain() {
            return detail.explain();
        }
    }

    /**
     * Derived yields for the given internal symbols, keyed by internal symbol.
     *
     * <p>Bonds are skipped — a gilt's running yield comes from the coupon in its own id
     * ({@code StyleClassifier.fixedIncome}), which is exact and needs no feed.
     */
    public Map<String, Derived> yieldsFor(Collection<String> internalSymbols) {
        Map<String, Derived> out = new HashMap<>();
        if (internalSymbols == null || internalSymbols.isEmpty()) return out;

        Map<String, String> tickerBySymbol = new HashMap<>();
        Set<String> tickerSet = new LinkedHashSet<>();
        for (String sym : internalSymbols) {
            if (sym == null || sym.isBlank() || Instruments.isBond(sym)) continue;
            String tk = tickers.tickerFor(sym);
            if (tk == null || tk.isBlank()) continue;
            tickerBySymbol.put(sym, tk);
            tickerSet.add(tk);
        }
        if (tickerSet.isEmpty()) return out;

        try {
            Set<String> scanned = divRepo.scannedSymbols();
            Map<String, List<DividendYield.Payment>> payments = divRepo.loadPayments(tickerSet);
            Map<String, PriceBar> prices = priceRepo.loadPricesAsOf(tickerSet, LocalDate.now());
            LocalDate today = LocalDate.now();

            for (Map.Entry<String, String> e : tickerBySymbol.entrySet()) {
                String tk = e.getValue();
                if (!scanned.contains(tk)) continue;      // never read in full — cannot say
                PriceBar bar = prices.get(tk);
                if (bar == null || bar.close() <= 0) continue;
                DividendYield.Result r = DividendYield.derive(
                        payments.getOrDefault(tk, List.of()),
                        BigDecimal.valueOf(bar.close()), today);
                if (r != null) out.put(e.getKey(), new Derived(r.yield(), r));
            }
        } catch (Exception e) {
            log.warn("Could not derive dividend yields", e);
        }
        return out;
    }

    /** Internal symbols whose history has never been fully scanned — handed to a warmer. */
    public Set<String> unscanned(Collection<String> internalSymbols) {
        Set<String> out = new LinkedHashSet<>();
        if (internalSymbols == null || internalSymbols.isEmpty()) return out;
        try {
            Set<String> scanned = divRepo.scannedSymbols();
            for (String sym : internalSymbols) {
                if (sym == null || sym.isBlank() || Instruments.isBond(sym)) continue;
                String tk = tickers.tickerFor(sym);
                if (tk != null && !tk.isBlank() && !scanned.contains(tk)) out.add(sym);
            }
        } catch (Exception e) {
            log.warn("Could not determine unscanned dividend symbols", e);
        }
        return out;
    }
}
