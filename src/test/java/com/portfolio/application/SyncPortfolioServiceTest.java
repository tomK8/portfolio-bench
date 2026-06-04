package com.portfolio.application;

import com.portfolio.PortfolioDatabase;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncPortfolioServiceTest {

    /**
     * Fake FX provider — the reason FxRateProvider is a port: no network in tests.
     */
    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir
    Path inputDir;
    @TempDir
    Path dbDir;

    private SyncPortfolioService service() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        PortfolioGatherer gatherer = new PortfolioGatherer(FX, new HoldingFileLocator(inputDir));
        return new SyncPortfolioService(gatherer, db, new DividendService(db));
    }

    @Test
    void returnsEmptyWhenNoInputFiles() {
        SyncResult result = service().sync(new BigDecimal("1000"));

        assertTrue(result.empty());
        assertTrue(result.holdings().isEmpty());
        assertEquals(0, new BigDecimal("1.25").compareTo(result.rates().get("USD")));
    }

    @Test
    void aggregatesParsedHoldingsAndComputesTotals() throws IOException {
        // Minimal II SIPP-style export (UUID filename, USD-priced row).
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");

        SyncResult result = service().sync(new BigDecimal("500"));

        assertFalse(result.empty());
        assertEquals(1, result.holdings().size());
        assertEquals("AAPL", result.holdings().get(0).securityId());
        // 1500 USD / 1.25 = 1200 GBP market value; + 500 cash = 1700 total.
        assertEquals(0, new BigDecimal("1700").compareTo(result.totals().totalGbp()));
        // cost 1000 USD / 1.25 = 800 GBP; gain = 1200 - 800 = 400.
        assertEquals(0, new BigDecimal("400").compareTo(result.totals().totalGainGbp()));
    }
}
