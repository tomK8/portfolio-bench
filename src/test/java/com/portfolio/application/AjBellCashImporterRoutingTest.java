package com.portfolio.application;

import com.portfolio.adapter.AjBellFileRouter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.domain.model.Account;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both AJ Bell accounts export {@code cashstatements.csv}; the browser suffixes the second. Which
 * ledger each belongs to is decided on the instruments it names, and a statement that cannot be
 * attributed is left alone — misfiling one corrupts two ledgers at once, and the dedup key is
 * scoped per account so no later import would repair it.
 */
class AjBellCashImporterRoutingTest {

    private static final String HOLDINGS_HEADER = "\"Investment\",\"Quantity\",\"Price\","
            + "\"Value (£)\",\"Cost (£)\",\"Change (£)\",\"Change (%)\",\"Price +/- today (%)\","
            + "\"Valuation currency\",\"Market currency\",\"Exchange rate\",\"Date\",\"Time\","
            + "\"Portfolio\",\"Ticker\"\n";

    private static final String CASH_HEADER =
            "Date,Description,Reference,Settlement date,Receipt (GBP),Payment (GBP),Balance (GBP)\n";

    private static final String SIPP_STATEMENT = CASH_HEADER
            + "16/09/2026,\"Sale 7605 Legal & General Group PLC\",REF1,18/09/2026,22664.14,0,32813.73\n"
            + "01/09/2026,\"* BALANCE B/F *\",-,-,0,0,10149.59\n";

    private static final String DEALING_STATEMENT = CASH_HEADER
            + "24/09/2026,\"Purchase 7133 Barclays PLC\",REF2,26/09/2026,0,-26477.06,0.00\n"
            + "01/09/2026,\"* BALANCE B/F *\",-,-,0,0,26477.06\n";

    @TempDir
    Path inputDir;
    @TempDir
    Path dbDir;

    private CashTransactionRepository repo;
    private AjBellCashImporter importer;

    @BeforeEach
    void setUp() throws IOException {
        repo = new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
        importer = new AjBellCashImporter(dbDir, repo, java.util.Map::of,
                new AjBellFileRouter(new HoldingFileLocator(inputDir)));
        holdings("portfolio-A50742S-SIPP.csv", "SIPP", "LGEN");
        holdings("portfolio-A50742D-Dealing account.csv", "Dealing account", "BARC");
    }

    private void holdings(String name, String portfolio, String... tickers) throws IOException {
        StringBuilder sb = new StringBuilder(HOLDINGS_HEADER);
        for (String t : tickers) {
            sb.append("\"").append(t).append(" PLC (LSE:").append(t).append(")\",\"100\",\"1\","
                    + "\"100\",\"90\",\"10\",\"1\",\"0\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\","
                    + "\"13:36\",\"").append(portfolio).append("\",\"").append(t).append("\"\n");
        }
        Files.writeString(inputDir.resolve(name), sb.toString());
    }

    private Path statement(String name, String content, long mtime) throws IOException {
        Path file = inputDir.resolve(name);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
        return file;
    }

    private ImportCashResult resultFor(List<ImportCashResult> results, Account account) {
        return results.stream().filter(r -> account.displayName().equals(r.source()))
                .findFirst().orElseThrow();
    }

    @Test
    void bothAccountsStatementsAreImportedIntoTheirOwnLedgers() throws IOException {
        Path sipp = statement("cashstatements.csv", SIPP_STATEMENT, 1_000_000L);
        Path dealing = statement("cashstatements (1).csv", DEALING_STATEMENT, 2_000_000L);

        List<ImportCashResult> results = importer.importFrom(inputDir);

        assertEquals(ImportCashResult.Status.IMPORTED, resultFor(results, Account.AJBELL).status());
        assertEquals(ImportCashResult.Status.IMPORTED,
                resultFor(results, Account.AJBELL_DEALING).status());
        assertFalse(Files.exists(sipp), "an imported statement is archived out of the input dir");
        assertFalse(Files.exists(dealing));

        assertTrue(repo.loadDividendTransactions().stream().anyMatch(t ->
                t.account() == Account.AJBELL && "LGEN".equals(t.symbol())));
        assertTrue(repo.loadDividendTransactions().stream().anyMatch(t ->
                t.account() == Account.AJBELL_DEALING && "BARC".equals(t.symbol())));
    }

    /** Two statements archived the same day must not overwrite one another. */
    @Test
    void eachAccountArchivesUnderItsOwnName() throws IOException {
        statement("cashstatements.csv", SIPP_STATEMENT, 1_000_000L);
        statement("cashstatements (1).csv", DEALING_STATEMENT, 2_000_000L);

        List<ImportCashResult> results = importer.importFrom(inputDir);
        String sippArchive = resultFor(results, Account.AJBELL).detail();
        String dealingArchive = resultFor(results, Account.AJBELL_DEALING).detail();

        assertTrue(Files.exists(Path.of(sippArchive)));
        assertTrue(Files.exists(Path.of(dealingArchive)));
        assertFalse(sippArchive.equals(dealingArchive));
        assertTrue(Path.of(sippArchive).getFileName().toString().startsWith("cashstatements_"),
                "the SIPP keeps the archive name years of statements are already under");
    }

    @Test
    void anAccountWithNoStatementIsReportedAsNotFoundRatherThanFedTheOthers() throws IOException {
        statement("cashstatements.csv", SIPP_STATEMENT, 1_000_000L);

        List<ImportCashResult> results = importer.importFrom(inputDir);

        assertEquals(ImportCashResult.Status.IMPORTED, resultFor(results, Account.AJBELL).status());
        assertEquals(ImportCashResult.Status.NOT_FOUND,
                resultFor(results, Account.AJBELL_DEALING).status());
    }

    /**
     * The one unacceptable outcome is a guess. A statement naming nothing either account holds is
     * left on disk, reported, and read by nobody.
     */
    @Test
    void anUnattributableStatementIsRefusedAndLeftOnDisk() throws IOException {
        Path odd = statement("cashstatements.csv", CASH_HEADER
                + "16/09/2026,\"Sale 10 Tesla Inc\",REF9,18/09/2026,100.00,0,100.00\n"
                + "01/09/2026,\"* BALANCE B/F *\",-,-,0,0,0.00\n", 1_000_000L);

        List<ImportCashResult> results = importer.importFrom(inputDir);

        ImportCashResult refused = results.stream()
                .filter(r -> r.status() == ImportCashResult.Status.REFUSED).findFirst().orElseThrow();
        assertEquals("cashstatements.csv", refused.sourceFile());
        assertTrue(refused.detail().contains("cashstatements.csv"));
        assertTrue(Files.exists(odd), "a refused statement stays put — nothing was read from it");
        assertTrue(repo.loadDividendTransactions().isEmpty());
    }
}
