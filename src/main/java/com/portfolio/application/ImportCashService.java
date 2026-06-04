package com.portfolio.application;

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

    private final Path inputDir;
    private final List<CashImporter> importers;

    public ImportCashService(Path inputDir, List<CashImporter> importers) {
        this.inputDir = inputDir;
        this.importers = importers;
    }

    public List<ImportCashResult> importCash() {
        return importers.stream()
                .flatMap(i -> i.importFrom(inputDir).stream())
                .toList();
    }
}
