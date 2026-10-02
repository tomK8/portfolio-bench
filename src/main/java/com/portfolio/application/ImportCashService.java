package com.portfolio.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Dispatches the configured {@link CashImporter}s in order and concatenates their results.
 *
 * <p>One {@link ImportCashResult} per file actually processed; an importer that finds nothing
 * emits a single {@code NOT_FOUND} row so the UI shows what was attempted.
 *
 * <p>Adding a broker: write a new {@code CashImporter}, register it as a bean. No edit here.
 */
public class ImportCashService {

    private static final Logger log = LoggerFactory.getLogger(ImportCashService.class);

    private final Path inputDir;
    private final List<CashImporter> importers;
    private final OrderFillReconciler fillReconciler;

    public ImportCashService(Path inputDir, List<CashImporter> importers) {
        this(inputDir, importers, null);
    }

    /**
     * @param fillReconciler run once after the import, against the ledger it just grew. A trade is
     *                       the only record a fill leaves when the order export was downloaded too
     *                       late to catch it, so the arrival of new statement rows is precisely
     *                       when an order filed as "No longer listed" becomes explainable. Null
     *                       skips it; the Open-orders panel asks the same question on its own.
     */
    public ImportCashService(Path inputDir, List<CashImporter> importers,
                             OrderFillReconciler fillReconciler) {
        this.inputDir = inputDir;
        this.importers = importers;
        this.fillReconciler = fillReconciler;
    }

    public List<ImportCashResult> importCash() {
        List<ImportCashResult> results = importers.stream()
                .flatMap(i -> i.importFrom(inputDir).stream())
                .toList();
        settleOrders(results);
        return results;
    }

    /**
     * Account for any order the newly imported rows can explain.
     *
     * <p>Only when something was actually inserted — an import that found no new data has not moved
     * the ledger, so it cannot have changed an answer. Failure is logged and swallowed: the cash
     * rows are in and reported either way, and an order left unexplained is the state it was
     * already in, not a lost import.
     */
    private void settleOrders(List<ImportCashResult> results) {
        if (fillReconciler == null) return;
        if (results.stream().noneMatch(r -> r.inserted() > 0)) return;
        try {
            int settled = fillReconciler.reconcile();
            if (settled > 0) {
                log.info("Cash import explained {} order(s) the broker had stopped listing", settled);
            }
        } catch (Exception e) {
            log.warn("Could not settle orders against the imported cash ledger", e);
        }
    }
}
