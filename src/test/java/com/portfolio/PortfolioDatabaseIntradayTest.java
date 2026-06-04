package com.portfolio;

import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioDatabaseIntradayTest {

    @TempDir
    Path dbDir;

    private static IntradayBar bar(String ticker, Instant ts, double close, String currency) {
        return new IntradayBar(ticker, ts, close, 100L, currency);
    }

    @Test
    void saveIntradayBarsIsIdempotent() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        Instant t0 = Instant.parse("2025-01-02T14:30:00Z");
        List<IntradayBar> bars = List.of(
                bar("AAPL", t0, 190.10, "USD"),
                bar("AAPL", t0.plusSeconds(60), 190.25, "USD"));

        assertEquals(2, db.saveIntradayBars(bars), "first insert writes both");
        assertEquals(0, db.saveIntradayBars(bars), "re-insert of same (symbol,ts) keys ignored");
    }

    @Test
    void getLatestIntradayTsNullThenReturnsMax() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        assertNull(db.getLatestIntradayTs("AAPL"));

        Instant t0 = Instant.parse("2025-01-02T14:30:00Z");
        db.saveIntradayBars(List.of(
                bar("AAPL", t0, 190.10, "USD"),
                bar("AAPL", t0.plusSeconds(120), 190.40, "USD"),
                bar("AAPL", t0.plusSeconds(60), 190.25, "USD")));

        assertEquals(t0.plusSeconds(120), db.getLatestIntradayTs("AAPL"));
    }

    @Test
    void loadLatestIntradayPricesPicksMaxTsPerSymbol() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        Instant t0 = Instant.parse("2025-01-02T14:30:00Z");
        db.saveIntradayBars(List.of(
                bar("AAPL", t0, 190.10, "USD"),
                bar("AAPL", t0.plusSeconds(60), 190.25, "USD"),
                bar("EQQQ.L", t0, 451.23, "GBp"),
                bar("EQQQ.L", t0.plusSeconds(120), 452.10, "GBp")));

        Map<String, IntradayPrice> latest = db.loadLatestIntradayPrices(List.of("AAPL", "EQQQ.L", "MISSING"));

        assertEquals(2, latest.size(), "tickers with no rows simply absent from result");
        assertEquals(190.25, latest.get("AAPL").close());
        assertEquals("USD", latest.get("AAPL").currency());
        assertEquals(t0.plusSeconds(60), latest.get("AAPL").ts());
        assertEquals(452.10, latest.get("EQQQ.L").close());
        assertEquals("GBp", latest.get("EQQQ.L").currency(),
                "GBp preserved verbatim — dashboard handles pence→pounds");
    }

    @Test
    void loadLatestIntradayPricesEmptyInputReturnsEmpty() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        assertTrue(db.loadLatestIntradayPrices(List.of()).isEmpty());
    }

    @Test
    void pruneIntradayBeforeRemovesStrictlyOlderRows() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        Instant cutoff = Instant.parse("2025-01-05T00:00:00Z");
        db.saveIntradayBars(List.of(
                bar("AAPL", cutoff.minusSeconds(60), 1.0, "USD"),
                bar("AAPL", cutoff, 2.0, "USD"),                  // boundary: kept
                bar("AAPL", cutoff.plusSeconds(60), 3.0, "USD")));

        assertEquals(1, db.pruneIntradayBefore(cutoff));
        assertEquals(cutoff.plusSeconds(60), db.getLatestIntradayTs("AAPL"));
    }
}
