package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryRepositoryTest {

    @TempDir
    Path dbDir;

    private static PriceBar bar(String symbol, String date, double close, double adjClose, Long volume) {
        return new PriceBar(symbol, LocalDate.parse(date), close - 0.5, close + 0.5, close - 1.0,
                close, adjClose, 1.0, volume, "USD");
    }

    private static CashTransaction cash(String date, String type, String symbol, double balGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.valueOf(type),
                symbol, 1, -10, "GBP", 1.0, -10, balGbp, balGbp, date);
    }

    private PriceHistoryRepository newPriceRepo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private CashTransactionRepository newCashRepo() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        return new CashTransactionRepository(cf, new KeyValueStore(dbDir));
    }

    // ---- bulk as-of lookups --------------------------------------------------------------------
    // Boundary valuations need two points per symbol, not whole series. AttributionService used to
    // load ~245,000 rows per request to read 94 of them.

    @Test
    void loadPricesAsOfReturnsNewestBarAtOrBeforeThatDatePerSymbol() {
        PriceHistoryRepository repo = newPriceRepo();
        repo.savePriceBars(List.of(
                bar("AAA", "2026-01-05", 10.0, 10.0, 1L),
                bar("AAA", "2026-01-09", 11.0, 11.0, 1L),
                bar("AAA", "2026-01-20", 12.0, 12.0, 1L),   // after the as-of date
                bar("BBB", "2026-01-02", 50.0, 50.0, 1L)));

        Map<String, PriceBar> got = repo.loadPricesAsOf(
                List.of("AAA", "BBB", "CCC"), LocalDate.parse("2026-01-10"));

        assertEquals(2, got.size(), "CCC has no rows at all");
        assertEquals(LocalDate.parse("2026-01-09"), got.get("AAA").date());
        assertEquals(11.0, got.get("AAA").close(), 1e-9);
        assertEquals(50.0, got.get("BBB").close(), 1e-9);
    }

    @Test
    void loadPricesAsOfOmitsSymbolsWhoseHistoryStartsLater() {
        PriceHistoryRepository repo = newPriceRepo();
        repo.savePriceBars(List.of(bar("LATE", "2026-07-31", 99.0, 99.0, 1L)));

        assertTrue(repo.loadPricesAsOf(List.of("LATE"), LocalDate.parse("2026-07-30")).isEmpty());
        // ...and loadEarliestPrices is the ceil-fill partner for exactly that case.
        assertEquals(LocalDate.parse("2026-07-31"),
                repo.loadEarliestPrices(List.of("LATE")).get("LATE").date());
    }

    @Test
    void bulkLookupsOnEmptyInputTouchNothing() {
        PriceHistoryRepository repo = newPriceRepo();
        assertTrue(repo.loadPricesAsOf(List.of(), LocalDate.parse("2026-01-01")).isEmpty());
        assertTrue(repo.loadEarliestPrices(List.of()).isEmpty());
    }

    @Test
    void getLatestPriceDateNullThenReturnsMax() {
        PriceHistoryRepository repo = newPriceRepo();
        assertNull(repo.getLatestPriceDate("NVDA"), "no rows yet");

        repo.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.2, 48.1, 1000L),
                bar("NVDA", "2024-01-05", 50.0, 49.9, 2000L),
                bar("NVDA", "2024-01-03", 49.0, 48.9, null)));

        assertEquals(LocalDate.of(2024, 1, 5), repo.getLatestPriceDate("NVDA"));
    }

    @Test
    void savePriceBarsIsIdempotent() {
        PriceHistoryRepository repo = newPriceRepo();
        List<PriceBar> bars = List.of(
                bar("EQQQ.L", "2024-01-02", 480.0, 480.0, 5L),
                bar("EQQQ.L", "2024-01-03", 482.0, 482.0, 6L));

        assertEquals(2, repo.savePriceBars(bars), "first insert writes both");
        assertEquals(0, repo.savePriceBars(bars), "re-insert of same (symbol,date) keys ignored");
    }

    @Test
    void getPriceOnReturnsClosestOnOrBefore() {
        PriceHistoryRepository repo = newPriceRepo();
        repo.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.0, 48.0, 1L),
                bar("NVDA", "2024-01-05", 50.0, 50.0, 1L)));

        assertNull(repo.getPriceOn("NVDA", LocalDate.of(2024, 1, 1)), "nothing on or before");
        assertEquals(48.0, repo.getPriceOn("NVDA", LocalDate.of(2024, 1, 2)).close());
        assertEquals(48.0, repo.getPriceOn("NVDA", LocalDate.of(2024, 1, 4)).close(),
                "falls back to the prior available day");
        assertEquals(50.0, repo.getPriceOn("NVDA", LocalDate.of(2024, 1, 9)).close());
    }

    @Test
    void getPriceHistoryReturnsRangeInOrderWithNullVolume() {
        PriceHistoryRepository repo = newPriceRepo();
        repo.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.0, 48.0, null),
                bar("NVDA", "2024-01-03", 49.0, 49.0, 7L),
                bar("NVDA", "2024-01-10", 52.0, 52.0, 8L)));

        List<PriceBar> got = repo.getPriceHistory("NVDA",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertEquals(2, got.size());
        assertEquals(LocalDate.of(2024, 1, 2), got.get(0).date());
        assertNull(got.get(0).volume(), "null volume round-trips");
        assertEquals(LocalDate.of(2024, 1, 3), got.get(1).date());
    }

    @Test
    void upsertPriceBarsReplacesOnConflict() {
        PriceHistoryRepository repo = newPriceRepo();
        PriceBar first = new PriceBar("GILT 3.75% 2038", LocalDate.of(2024, 1, 2),
                null, null, null, 90.0, 90.0, 1.0, null, "GBP");
        PriceBar second = new PriceBar("GILT 3.75% 2038", LocalDate.of(2024, 1, 2),
                null, null, null, 91.5, 91.5, 1.0, null, "GBP");

        assertEquals(1, repo.upsertPriceBars(List.of(first)), "fresh insert");
        assertEquals(1, repo.upsertPriceBars(List.of(second)),
                "same (symbol,date) updates rather than ignoring");

        assertEquals(91.5, repo.getPriceOn("GILT 3.75% 2038", LocalDate.of(2024, 1, 2)).close(),
                "later upsert wins");
    }

    @Test
    void distinctTradedSymbolsExcludesNonInstrumentRows() {
        CashTransactionRepository cashRepo = newCashRepo();
        cashRepo.saveAjBell(List.of(
                cash("2024-01-01", "TRANSACTION", "NVDA", 100.0),
                cash("2024-01-02", "DIVIDEND", "NVDA", 110.0),
                cash("2024-01-03", "TRANSACTION", "EQQQ", 120.0),
                cash("2024-01-04", "INTEREST", "CASH", 130.0),
                cash("2024-01-05", "CHARGE", "FEE", 140.0)));

        assertEquals(List.of("EQQQ", "NVDA"), cashRepo.distinctTradedSymbols(),
                "only TRANSACTION/DIVIDEND symbols, sorted, de-duplicated");
    }
}
