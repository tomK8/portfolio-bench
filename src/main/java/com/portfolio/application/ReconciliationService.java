package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surfaces data-quality issues that would otherwise only live as scattered WARN logs:
 * held symbols with no price_history, stale intraday quotes, unknown listing currencies.
 *
 * <p>The page is a "right now" view, not a log. Every issue is regenerated on each request
 * by re-running the checks — no persistence layer for warnings, no risk of stale alarms
 * lingering after the underlying problem was fixed. Severity ordering: error
 * (no price history → chart breaks) → warning (stale intraday → totals are stale) →
 * info (everything else worth flagging).
 */
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Intraday rows older than this are considered stale. Set wide enough to span a normal
     *  Fri-close → Mon-open weekend gap (~64h for US, ~67h for LSE) so the rule doesn't
     *  warn on every held symbol on Sunday afternoon — still tight enough to catch a
     *  fetcher that has actually been failing for multiple sessions. */
    private static final long INTRADAY_STALE_HOURS = 72;

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final IntradayPriceRepository intradayRepo;
    private final YahooTickerMap tickerMap;

    public ReconciliationService(CashTransactionRepository cashRepo,
                                 PriceHistoryRepository priceRepo,
                                 IntradayPriceRepository intradayRepo,
                                 YahooTickerMap tickerMap) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.intradayRepo = intradayRepo;
        this.tickerMap = tickerMap;
    }

    public Report report() {
        List<CashTransaction> rows = cashRepo.loadDividendTransactions();
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);

        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (Position p : positions) {
            String sym = p.securityId();
            if (sym == null || sym.isBlank()) continue;
            String upper = sym.toUpperCase();
            String ticker = Instruments.isBond(sym) ? upper : tickerMap.tickerFor(sym);
            tickerBySymbol.put(upper, ticker);
        }
        Map<String, IntradayPrice> intraBySymbol = new LinkedHashMap<>();
        try {
            Map<String, IntradayPrice> byTicker =
                    intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
            for (var e : tickerBySymbol.entrySet()) {
                intraBySymbol.put(e.getKey(), byTicker.get(e.getValue()));
            }
        } catch (Exception e) {
            log.warn("Reconciliation: could not load intraday prices", e);
        }

        List<Issue> issues = new ArrayList<>();
        Instant now = Instant.now();
        for (Position p : positions) {
            String sym = p.securityId().toUpperCase();
            String ticker = tickerBySymbol.get(sym);

            LocalDate latestHist = null;
            try {
                latestHist = priceRepo.getLatestPriceDate(ticker);
            } catch (Exception e) {
                log.warn("Reconciliation: getLatestPriceDate failed for {}", ticker, e);
            }
            if (latestHist == null) {
                issues.add(new Issue(sym, "error", "no_price_history",
                        "No rows in price_history. Position values silently fall back to "
                                + "cost basis; the Value-over-time chart drops to zero on that "
                                + "range. Add a Tradeweb file (gilts) or trigger a Yahoo fetch."));
            } else if (latestHist.isBefore(LocalDate.now().minusDays(14))) {
                issues.add(new Issue(sym, "warning", "stale_price_history",
                        "Latest price_history bar is " + latestHist + " (more than 14 days old). "
                                + "Daily scheduler may have failed; check the log."));
            }

            IntradayPrice ip = intraBySymbol.get(sym);
            if (ip == null) {
                if (!Instruments.isBond(sym)) {
                    issues.add(new Issue(sym, "warning", "no_intraday",
                            "No intraday rows. Cash-ledger view and Concentration tab will "
                                    + "drop this position from totals."));
                }
            } else {
                long ageHours = ChronoUnit.HOURS.between(ip.ts(), now);
                if (ageHours > INTRADAY_STALE_HOURS) {
                    issues.add(new Issue(sym, "warning", "stale_intraday",
                            "Intraday quote is " + ageHours + "h old. Symbol may be delisted "
                                    + "or the Yahoo fetch is failing for it."));
                }
                String ccy = ip.currency();
                if (ccy == null || (!"GBP".equals(ccy) && !"GBp".equals(ccy)
                        && !"USD".equals(ccy) && !"EUR".equals(ccy))) {
                    issues.add(new Issue(sym, "info", "uncommon_currency",
                            "Listing currency '" + ccy + "' is outside the common set — "
                                    + "FX conversion may not be configured."));
                }
            }
        }

        issues.sort(Comparator.<Issue>comparingInt(i -> severityRank(i.severity()))
                .thenComparing(Issue::symbol));

        return new Report(positions.size(), issues);
    }

    private static int severityRank(String s) {
        return switch (s) {
            case "error" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }

    // ---- DTOs --------------------------------------------------------------

    /**
     * One reconciliation finding. {@code severity} is "error" / "warning" / "info";
     * {@code code} is a stable short identifier so the UI can colour/icon by class.
     */
    public record Issue(String symbol, String severity, String code, String detail) {
    }

    public record Report(int positionsChecked, List<Issue> issues) {
    }
}
