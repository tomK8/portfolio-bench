package com.portfolio.application;

import com.portfolio.parser.AJBellCashStatementParser;
import com.portfolio.persistence.CashTransactionRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** AJBell {@code cashstatements*.csv}; picks the most recent matching file. */
public class AjBellCashImporter extends AbstractCashImporter {

    private static final String SOURCE = "AJBell";
    private static final String GLOB = "cashstatements*.csv";
    private static final String ARCHIVE_PREFIX = "cashstatements";

    private final AJBellCashStatementParser parser;

    /** Committed symbol rules only. */
    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo) {
        this(archiveDir, repo, Map::of);
    }

    /**
     * @param aliasSource user-added description-keyword → symbol mappings, consulted before the
     *                    parser's committed rules so a name mapped on the dashboard is picked up by
     *                    the next import.
     */
    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo,
                              Supplier<Map<String, String>> aliasSource) {
        super(archiveDir, repo);
        this.parser = new AJBellCashStatementParser(aliasSource);
    }

    @Override
    public List<ImportCashResult> importFrom(Path inputDir) {
        return mostRecent(inputDir, GLOB)
                .map(file -> archiveOrDelete(SOURCE, file, repo.saveAjBell(parse(parser, file)), ARCHIVE_PREFIX))
                .map(List::of)
                .orElse(List.of(ImportCashResult.notFound(SOURCE)));
    }
}
