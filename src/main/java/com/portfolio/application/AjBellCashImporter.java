package com.portfolio.application;

import com.portfolio.parser.AJBellCashStatementParser;
import com.portfolio.persistence.CashTransactionRepository;

import java.nio.file.Path;
import java.util.List;

/** AJBell {@code cashstatements*.csv}; picks the most recent matching file. */
public class AjBellCashImporter extends AbstractCashImporter {

    private static final String SOURCE = "AJBell";
    private static final String GLOB = "cashstatements*.csv";
    private static final String ARCHIVE_PREFIX = "cashstatements";

    private final AJBellCashStatementParser parser = new AJBellCashStatementParser();

    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo) {
        super(archiveDir, repo);
    }

    @Override
    public List<ImportCashResult> importFrom(Path inputDir) {
        return mostRecent(inputDir, GLOB)
                .map(file -> archiveOrDelete(SOURCE, file, repo.saveAjBell(parse(parser, file)), ARCHIVE_PREFIX))
                .map(List::of)
                .orElse(List.of(ImportCashResult.notFound(SOURCE)));
    }
}
