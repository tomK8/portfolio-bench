package com.pension;

import com.pension.domain.model.CashTransaction;
import com.pension.domain.model.PriceBar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioDatabasePriceTest {

    @TempDir
    Path dbDir;

    private static PriceBar bar(String symbol, String date, double close, double adjClose, Long volume) {
        return new PriceBar(symbol, LocalDate.parse(date), close - 0.5, close + 0.5, close - 1.0,
                close, adjClose, volume, "USD");
    }

    private static CashTransaction cash(String date, String type, String symbol, double balGbp) {
        return new CashTransaction(date, "AJBell", type, symbol, 1, -10, "GBP",
                1.0, -10, balGbp, balGbp, date);
    }

    @Test
    void getLatestPriceDateNullThenReturnsMax() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        assertNull(db.getLatestPriceDate("NVDA"), "no rows yet");

        db.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.2, 48.1, 1000L),
                bar("NVDA", "2024-01-05", 50.0, 49.9, 2000L),
                bar("NVDA", "2024-01-03", 49.0, 48.9, null)));

        assertEquals(LocalDate.of(2024, 1, 5), db.getLatestPriceDate("NVDA"));
    }

    @Test
    void savePriceBarsIsIdempotent() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        List<PriceBar> bars = List.of(
                bar("EQQQ.L", "2024-01-02", 480.0, 480.0, 5L),
                bar("EQQQ.L", "2024-01-03", 482.0, 482.0, 6L));

        assertEquals(2, db.savePriceBars(bars), "first insert writes both");
        assertEquals(0, db.savePriceBars(bars), "re-insert of same (symbol,date) keys ignored");
    }

    @Test
    void getPriceOnReturnsClosestOnOrBefore() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.0, 48.0, 1L),
                bar("NVDA", "2024-01-05", 50.0, 50.0, 1L)));

        assertNull(db.getPriceOn("NVDA", LocalDate.of(2024, 1, 1)), "nothing on or before");
        assertEquals(48.0, db.getPriceOn("NVDA", LocalDate.of(2024, 1, 2)).close());
        assertEquals(48.0, db.getPriceOn("NVDA", LocalDate.of(2024, 1, 4)).close(),
                "falls back to the prior available day");
        assertEquals(50.0, db.getPriceOn("NVDA", LocalDate.of(2024, 1, 9)).close());
    }

    @Test
    void getPriceHistoryReturnsRangeInOrderWithNullVolume() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.savePriceBars(List.of(
                bar("NVDA", "2024-01-02", 48.0, 48.0, null),
                bar("NVDA", "2024-01-03", 49.0, 49.0, 7L),
                bar("NVDA", "2024-01-10", 52.0, 52.0, 8L)));

        List<PriceBar> got = db.getPriceHistory("NVDA",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertEquals(2, got.size());
        assertEquals(LocalDate.of(2024, 1, 2), got.get(0).date());
        assertNull(got.get(0).volume(), "null volume round-trips");
        assertEquals(LocalDate.of(2024, 1, 3), got.get(1).date());
    }

    @Test
    void distinctTradedSymbolsExcludesNonInstrumentRows() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        db.saveCashTransactions(List.of(
                cash("2024-01-01", "TRANSACTION", "NVDA", 100.0),
                cash("2024-01-02", "DIVIDEND", "NVDA", 110.0),
                cash("2024-01-03", "TRANSACTION", "EQQQ", 120.0),
                cash("2024-01-04", "INTEREST", "CASH", 130.0),
                cash("2024-01-05", "CHARGE", "FEE", 140.0)));

        assertEquals(List.of("EQQQ", "NVDA"), db.distinctTradedSymbols(),
                "only TRANSACTION/DIVIDEND symbols, sorted, de-duplicated");
    }
}
