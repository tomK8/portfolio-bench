package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rows written before {@code Instruments.giltId} was the only way to build a gilt id. Both
 * repositories re-key them in their constructor, so the test's shape is always: write the legacy
 * spelling, construct a fresh repository over the same directory, read back the canonical one.
 */
class GiltSymbolMigrationTest {

    @TempDir
    Path dbDir;

    private JdbcConnectionFactory cf() {
        return new JdbcConnectionFactory(dbDir);
    }

    private CashTransactionRepository newCashRepo() {
        return new CashTransactionRepository(cf(), new KeyValueStore(dbDir));
    }

    private PriceHistoryRepository newPriceRepo() {
        return new PriceHistoryRepository(cf());
    }

    private static CashTransaction cashRow(String date, String symbol, double amount) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, 50000.0, amount, "GBP", 1.0, amount, 1000.0, 1000.0, "Purchase " + symbol);
    }

    private static PriceBar bar(String symbol, String date, double close) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                close, close, 1.0, null, "GBP");
    }

    private List<String> symbols(String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection conn = cf().open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT symbol FROM " + table + " ORDER BY symbol")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /**
     * The case that lost £50,597: AJ Bell's cash statement wrote "GILT 4.50%", the HL feed priced
     * the same bond as "GILT 4.5%", and the cash-ledger view could join neither the intraday quote
     * nor the daily close — so it reported the position unpriced and dropped it from the totals.
     */
    @Test
    void ledgerGiltSymbolsAreRekeyedToTheCanonicalCoupon() throws Exception {
        newCashRepo().saveAjBell(List.of(
                cashRow("2026-08-21", "GILT 4.50% 2028", -50597.51),
                cashRow("2026-08-20", "GILT 0.875% 2033", -70859.39),
                cashRow("2026-08-19", "BARC", -1000.0)));

        newCashRepo();   // constructor runs the migration

        assertEquals(List.of("BARC", "GILT 0.875% 2033", "GILT 4.5% 2028"),
                symbols("cash_transactions"));
    }

    @Test
    void dailyPriceSymbolsAreRekeyedToo() throws Exception {
        newPriceRepo().savePriceBars(List.of(
                bar("GILT 1.00% 2024", "2024-04-19", 99.1),
                bar("GILT 5.00% 2025", "2025-03-06", 100.2),
                bar("GILT 0.125% 2028", "2026-08-21", 92.0)));

        newPriceRepo();

        assertEquals(List.of("GILT 0.125% 2028", "GILT 1% 2024", "GILT 5% 2025"),
                symbols("price_history"));
    }

    /**
     * A bond can already hold rows under both spellings — the live database had
     * {@code GILT 1.00% 2024} beside {@code GILT 1% 2024}. Disjoint dates merge into one series;
     * a collision on {@code (symbol, date)} must not abort the migration, and with it the whole
     * repository constructor.
     */
    @Test
    void bothSpellingsOfOneBondMergeIntoOneSeries() throws Exception {
        newPriceRepo().savePriceBars(List.of(
                bar("GILT 1.00% 2024", "2024-04-18", 99.0),
                bar("GILT 1% 2024", "2024-04-19", 99.5),
                bar("GILT 1.00% 2024", "2024-04-22", 100.0)));

        PriceHistoryRepository migrated = newPriceRepo();

        assertEquals(List.of("GILT 1% 2024"), symbols("price_history"));
        assertEquals(3, migrated.getPriceHistory("GILT 1% 2024",
                        LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31")).size(),
                "every bar survives the merge — the dates were disjoint");
    }

    @Test
    void aCollidingDateDoesNotAbortTheMigration() throws Exception {
        newPriceRepo().savePriceBars(List.of(
                bar("GILT 1.00% 2024", "2024-04-19", 99.0),
                bar("GILT 1% 2024", "2024-04-19", 98.0)));

        assertDoesNotThrow(this::newPriceRepo);
        assertEquals(List.of("GILT 1% 2024"), symbols("price_history"));
    }

    /** Idempotent: a canonical database is read, not written, on every later start. */
    @Test
    void aSecondRunChangesNothing() throws Exception {
        newPriceRepo().savePriceBars(List.of(bar("GILT 4.50% 2028", "2026-08-21", 101.2)));

        newPriceRepo();
        List<String> afterFirst = symbols("price_history");
        newPriceRepo();

        assertEquals(List.of("GILT 4.5% 2028"), afterFirst);
        assertEquals(afterFirst, symbols("price_history"));
    }
}
