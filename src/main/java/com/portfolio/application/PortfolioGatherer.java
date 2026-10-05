package com.portfolio.application;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.AccountPosition;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.Holding;
import com.portfolio.parser.*;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.port.FxRateProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads everything an operation needs from disk: live FX rates plus the most
 * recent supported file for each account parser. Shared by the operations that
 * need holdings (sync, Excel export) so the gather logic lives in one place.
 */
public class PortfolioGatherer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PortfolioGatherer.class);

    /** Tolerance on the share count before the ledger's cost may stand in for a missing one. */
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.01");

    private final FxRateProvider fxRateProvider;
    private final HoldingFileLocator fileLocator;
    private final ManualPositionService manualPositions;
    private final CashTransactionRepository cashRepo;

    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator) {
        this(fxRateProvider, fileLocator, null, null);
    }

    /**
     * @param manualPositions accounts with no downloadable export, whose holdings are derived from
     *                        the cash ledger instead of a file; null when there are none
     */
    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator,
                             ManualPositionService manualPositions) {
        this(fxRateProvider, fileLocator, manualPositions, null);
    }

    /**
     * @param cashRepo the ledger, consulted only for a cost basis the broker's own export does not
     *                 state; null skips that step and leaves such a holding's cost unknown
     */
    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator,
                             ManualPositionService manualPositions,
                             CashTransactionRepository cashRepo) {
        this.fxRateProvider = fxRateProvider;
        this.fileLocator = fileLocator;
        this.manualPositions = manualPositions;
        this.cashRepo = cashRepo;
    }

    public GatheredPortfolio gather() {
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch FX rates", e);
        }

        // The AJ Bell parsers need the live rates, so parsers are built per run. The two AJ Bell
        // accounts export the same filename pattern and are told apart by the Portfolio column
        // each file carries — the one AJ Bell export that names its own account.
        List<AccountParser> parsers = List.of(
                new RothIraParser(),
                new AJBellSippParser(rates),
                new AJBellDealingParser(rates),
                new IISippParser());

        List<Holding> holdings = new ArrayList<>();
        Map<String, Path> sources = new LinkedHashMap<>();
        try {
            for (AccountParser parser : parsers) {
                Optional<Path> file = fileLocator.findMostRecent(parser);
                if (file.isPresent()) {
                    holdings.addAll(parser.parse(file.get()));
                    sources.put(parser.sourceName(), file.get());
                }
            }
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to read or parse input files", e);
        }

        // Hand-entered accounts have no file to locate, so they are appended rather than parsed.
        // A failure here must not take the whole sync down with it: the broker exports parsed
        // fine and are worth showing.
        if (manualPositions != null) {
            try {
                List<Holding> manual = manualPositions.holdings();
                if (!manual.isEmpty()) {
                    holdings.addAll(manual);
                    for (Holding h : manual) sources.putIfAbsent(h.getSource(), null);
                }
            } catch (Exception e) {
                log.warn("Could not read manually entered positions — continuing without them", e);
            }
        }

        return new GatheredPortfolio(rates, fillMissingCostFromLedger(holdings), sources);
    }

    /**
     * Give a holding whose export states no cost basis the one the ledger holds for it.
     *
     * <p>AJ Bell reports a zero cost for shares transferred in in specie — it never saw the
     * purchase, so it has nothing to state, and the parser reads that zero as "unknown" rather than
     * letting the whole market value book as gain. But unknown is not where it ends: the basis
     * travelled with the shares into the ledger, which is the entire point of recording a transfer
     * as a cost rather than a market value. Left unfilled, the position sits in the portfolio total
     * contributing value but no gain — diluting Return on invested by its whole size, quietly,
     * because a null gain is skipped from the numerator and the denominator keeps the value.
     *
     * <p><b>Only where the export says nothing, and only when the two agree on the share count.</b>
     * A broker that states a basis keeps it, divergences and all — AJ Bell pools at a Section 104
     * average while this ledger tracks FIFO lots, and silently preferring one over the other is how
     * a phantom gain gets manufactured. A disagreement about quantity means the ledger's cost is
     * for a different number of shares, so it is not this position's cost; the Reconcile tab is
     * already reporting that, and the right answer here is to leave the cost unknown.
     */
    private List<Holding> fillMissingCostFromLedger(List<Holding> holdings) {
        if (cashRepo == null) return holdings;
        boolean anyMissing = holdings.stream().anyMatch(PortfolioGatherer::costUnknown);
        if (!anyMissing) return holdings;

        Map<String, AccountPosition> ledger = new HashMap<>();
        try {
            for (AccountPosition p : new CashLedgerReconstructor()
                    .reconstructByAccount(cashRepo.loadDividendTransactions())) {
                ledger.put(p.account().dbValue() + "|" + p.securityId().toUpperCase(), p);
            }
        } catch (Exception e) {
            log.warn("Could not read the ledger for cost bases the broker exports omit", e);
            return holdings;
        }

        List<Holding> out = new ArrayList<>(holdings.size());
        for (Holding h : holdings) {
            AccountPosition p = costUnknown(h) ? ledger.get(ledgerKey(h)) : null;
            if (p == null || p.costGbp() == null
                    || p.quantity().subtract(h.getQuantity()).abs().compareTo(QTY_TOLERANCE) > 0) {
                out.add(h);
                continue;
            }
            log.info("{} at {} reports no cost basis; using the ledger's {} for {} shares",
                    h.getSecurityId(), h.getSource(), p.costGbp(), p.quantity());
            out.add(h.withCostBasisGbp(p.costGbp()));
        }
        return out;
    }

    private static boolean costUnknown(Holding h) {
        return !"CASH".equals(h.getSecurityId())
                && h.getCostBasisGbp() == null && h.getAvgPricePaid() == null;
    }

    private static String ledgerKey(Holding h) {
        Account account = Account.fromDisplayName(h.getSource());
        String key = account == null ? h.getSource() : account.dbValue();
        return key + "|" + h.getSecurityId().toUpperCase();
    }
}
