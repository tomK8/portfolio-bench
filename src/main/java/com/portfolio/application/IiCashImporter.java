package com.portfolio.application;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.parser.IICashStatementParser;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.port.HistoricalFxRateProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * II SIPP UUID-named CSVs. Multiple files (one per currency); each becomes its own result row
 * labelled {@code II SIPP (GBP)} / {@code II SIPP (USD)}. The parser's {@code supports} sniff
 * distinguishes cash files from holdings files (same naming scheme).
 */
public class IiCashImporter extends AbstractCashImporter {

    /** UUID-named CSV (8-4-4-4-12 hex). */
    private static final String GLOB = "????????-????-????-????-????????????.csv";
    private static final String NOT_FOUND_SOURCE = "II SIPP";
    private static final String ARCHIVE_PREFIX = "ii_cash_";

    private final IICashStatementParser parser;

    public IiCashImporter(Path archiveDir, CashTransactionRepository repo,
                          HistoricalFxRateProvider fxProvider) {
        this(archiveDir, repo, fxProvider, Map::of);
    }

    /**
     * @param giltSedols SEDOL → canonical gilt id, published by {@code GiltPriceFetchJob}. II
     *                   names a gilt by a code of its own and its description carries no coupon,
     *                   so the SEDOL is the only thing in the file that identifies one.
     */
    public IiCashImporter(Path archiveDir, CashTransactionRepository repo,
                          HistoricalFxRateProvider fxProvider,
                          Supplier<Map<String, String>> giltSedols) {
        super(archiveDir, repo);
        this.parser = new IICashStatementParser(fxProvider, giltSedols);
    }

    @Override
    public List<ImportCashResult> importFrom(Path inputDir) {
        List<Path> files = matchingFiles(inputDir, GLOB).stream()
                .filter(parser::supports)
                .sorted(Comparator.comparingLong(AbstractCashImporter::mtime))
                .toList();

        if (files.isEmpty()) return List.of(ImportCashResult.notFound(NOT_FOUND_SOURCE));

        List<ImportCashResult> results = new ArrayList<>();
        for (Path file : files) {
            List<CashTransaction> rows = parse(parser, file);
            String ccy = rows.isEmpty() ? "?" : rows.get(0).currency();
            String source = "II SIPP (" + ccy + ")";
            int inserted = repo.saveII(rows);
            results.add(archiveOrDelete(source, file, inserted, ARCHIVE_PREFIX + ccy));
        }
        return results;
    }
}
