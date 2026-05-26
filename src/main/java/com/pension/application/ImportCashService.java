package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.model.CashTransaction;
import com.pension.parser.AJBellCashStatementParser;
import com.pension.parser.CashTransactionParser;
import com.pension.parser.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

/**
 * Imports the AJ Bell cash statement as one cohesive operation: parse the file,
 * dedup-save its rows, then archive the file if anything new was written or
 * delete it if it was a duplicate. The dedup/integrity logic stays in
 * {@link PortfolioDatabase} (it needs the existing-keys query and inserts in the
 * same connection), and the parsed rows are passed through in file order so the
 * statement's gap detection still holds.
 */
public class ImportCashService {

    private static final String CASH_FILE = "cashstatements.csv";

    private final Path inputDir;
    private final PortfolioDatabase db;
    private final CashTransactionParser parser = new AJBellCashStatementParser();

    public ImportCashService(Path inputDir, PortfolioDatabase db) {
        this.inputDir = inputDir;
        this.db = db;
    }

    public ImportCashResult importCash() {
        Path file = inputDir.resolve(CASH_FILE);
        if (!Files.exists(file)) {
            return ImportCashResult.notFound();
        }

        List<CashTransaction> transactions;
        try {
            transactions = parser.parse(file);
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to parse cash statement " + file, e);
        }

        int inserted = db.saveCashTransactions(transactions);

        try {
            if (inserted > 0) {
                Path archived = db.dbDir.resolve("cashstatements_" + LocalDate.now() + ".csv");
                Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING);
                return ImportCashResult.imported(inserted, archived.toString());
            }
            Files.delete(file);
            return ImportCashResult.noNewData();
        } catch (IOException e) {
            throw new IllegalStateException("Imported rows but could not archive/remove " + file, e);
        }
    }
}
