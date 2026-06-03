package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.model.CashTransaction;
import com.pension.parser.AJBellCashStatementParser;
import com.pension.parser.CashTransactionParser;
import com.pension.parser.ParseException;
import com.pension.parser.RothIraCashStatementParser;
import com.pension.port.HistoricalFxRateProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Imports the cash statements as one cohesive operation: for each known source it
 * picks the most-recent matching file in the input dir (if any), parses it, dedup-saves
 * its rows, then archives the file to the Investing folder if anything new was written
 * or deletes it if it was a duplicate.
 *
 * <p>Sources match a glob so re-downloads like {@code cashstatements (1).csv} are picked
 * up; the newest by modification time wins. Missing files produce no result row.
 *
 * <p>Two sources are handled, each independently:
 * <ul>
 *   <li>AJBell — {@code cashstatements*.csv}, GBP, dedup by (date, balance) inside
 *       {@link PortfolioDatabase#saveCashTransactions};</li>
 *   <li>RothIRA — {@code History*.xlsx}, native USD with historical-FX conversion,
 *       dedup by (date, symbol, qty, type, amount) and running-balance derivation
 *       inside {@link PortfolioDatabase#saveRothIraCashTransactions}.</li>
 * </ul>
 */
public class ImportCashService {

    private static final String AJBELL_GLOB = "cashstatements*.csv";
    private static final String ROTH_GLOB = "History*.xlsx";

    /**
     * RothIRA opening balance before the earliest transaction; only used to seed an empty account.
     */
    private static final BigDecimal ROTH_BALANCE_BROUGHT_FORWARD = new BigDecimal("0");

    private final Path inputDir;
    private final PortfolioDatabase db;
    private final AJBellCashStatementParser ajBellParser = new AJBellCashStatementParser();
    private final RothIraCashStatementParser rothParser;

    public ImportCashService(Path inputDir, PortfolioDatabase db, HistoricalFxRateProvider fxProvider) {
        this.inputDir = inputDir;
        this.db = db;
        this.rothParser = new RothIraCashStatementParser(fxProvider);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    /**
     * One result per source that was actually processed; sources whose file is absent
     * produce no entry. Stable order: AJBell, RothIRA.
     */
    public List<ImportCashResult> importCash() {
        List<ImportCashResult> results = new ArrayList<>();
        importAjBell().ifPresent(results::add);
        importRothIra().ifPresent(results::add);
        return results;
    }

    private Optional<ImportCashResult> importAjBell() {
        return mostRecent(AJBELL_GLOB).map(file -> {
            int inserted = db.saveCashTransactions(parse(ajBellParser, file));
            return archiveOrDelete("AJBell", file, inserted, "cashstatements");
        });
    }

    private Optional<ImportCashResult> importRothIra() {
        return mostRecent(ROTH_GLOB).map(file -> {
            int inserted = db.saveRothIraCashTransactions(parse(rothParser, file), ROTH_BALANCE_BROUGHT_FORWARD);
            return archiveOrDelete("RothIRA", file, inserted, "History");
        });
    }

    private Optional<Path> mostRecent(String glob) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, glob)) {
            Path newest = null;
            long newestMtime = Long.MIN_VALUE;
            for (Path p : stream) {
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m > newestMtime) {
                    newestMtime = m;
                    newest = p;
                }
            }
            return Optional.ofNullable(newest);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan input directory " + inputDir, e);
        }
    }

    private List<CashTransaction> parse(CashTransactionParser parser, Path file) {
        try {
            return parser.parse(file);
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to parse cash statement " + file, e);
        }
    }

    private ImportCashResult archiveOrDelete(String source, Path file, int inserted, String archivePrefix) {
        try {
            if (inserted > 0) {
                Path archived = db.dbDir.resolve(archivePrefix + "_" + LocalDate.now() + extension(file));
                Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING);
                return ImportCashResult.imported(source, inserted, archived.toString());
            }
            Files.delete(file);
            return ImportCashResult.noNewData(source);
        } catch (IOException e) {
            throw new IllegalStateException("Imported rows but could not archive/remove " + file, e);
        }
    }
}
