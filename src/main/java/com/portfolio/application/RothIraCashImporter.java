package com.portfolio.application;

import com.portfolio.parser.RothIraCashStatementParser;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.port.HistoricalFxRateProvider;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/** RothIRA {@code History*.xlsx}; picks the most recent matching file. */
public class RothIraCashImporter extends AbstractCashImporter {

    private static final String SOURCE = "RothIRA";
    private static final String GLOB = "History*.xlsx";
    private static final String ARCHIVE_PREFIX = "History";

    /** Opening balance before the earliest transaction; only used to seed an empty account. */
    private static final BigDecimal BROUGHT_FORWARD = new BigDecimal("0");

    private final RothIraCashStatementParser parser;

    public RothIraCashImporter(Path archiveDir, CashTransactionRepository repo,
                               HistoricalFxRateProvider fxProvider) {
        super(archiveDir, repo);
        this.parser = new RothIraCashStatementParser(fxProvider);
    }

    @Override
    public List<ImportCashResult> importFrom(Path inputDir) {
        return mostRecent(inputDir, GLOB)
                .map(file -> archiveOrDelete(
                        SOURCE, file,
                        repo.saveRothIra(parse(parser, file), BROUGHT_FORWARD),
                        ARCHIVE_PREFIX))
                .map(List::of)
                .orElse(List.of(ImportCashResult.notFound(SOURCE)));
    }
}
