package com.portfolio.application;

import java.nio.file.Path;
import java.util.List;

/**
 * One broker's cash-statement ingestion. An importer is responsible for finding its source
 * file(s) in the input directory, parsing them, saving to the cash ledger and archiving (or
 * deleting on duplicate) the consumed files. Returns one {@link ImportCashResult} per file
 * actually processed; a result with status {@code NOT_FOUND} when no file matched.
 *
 * <p>{@link ImportCashService} composes a list of importers and concatenates their results.
 * Adding a new broker is a matter of writing a new {@code CashImporter} impl and registering
 * it as a bean — no edit to the dispatching service.
 */
public interface CashImporter {

    List<ImportCashResult> importFrom(Path inputDir);
}
