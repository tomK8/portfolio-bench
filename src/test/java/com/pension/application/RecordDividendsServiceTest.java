package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordDividendsServiceTest {

    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir Path dbDir;

    private RecordDividendsService service(PortfolioDatabase db) {
        return new RecordDividendsService(FX, db);
    }

    @Test
    void savesValidRowsConvertedToGbp() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        RecordResult result = service(db).record(List.of(
                new RawDividend("2026-05-26", "ROTH_IRA", "AAPL", "USD", "100"),
                new RawDividend("2026-05-26", "AJBELL", "VOD", "GBP", "50")));

        assertTrue(result.ok());
        assertEquals(2, result.savedCount());

        Map<String, BigDecimal> persisted = db.loadDividendsBySymbol();
        assertEquals(0, new BigDecimal("80").compareTo(persisted.get("AAPL")));  // 100 / 1.25
        assertEquals(0, new BigDecimal("50").compareTo(persisted.get("VOD")));
    }

    @Test
    void reportsErrorsAndSavesNothingWhenAnyRowInvalid() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        RecordResult result = service(db).record(List.of(
                new RawDividend("2026-05-26", "AJBELL", "VOD", "GBP", "50"),
                new RawDividend("2026-05-26", "AJBELL", "BP", "GBP", "not-a-number")));

        assertFalse(result.ok());
        assertEquals(1, result.errors().size());
        assertEquals(0, result.savedCount());
        assertTrue(db.loadDividendsBySymbol().isEmpty()); // nothing persisted
    }

    @Test
    void skipsBlankRows() {
        PortfolioDatabase db = new PortfolioDatabase(dbDir);
        RecordResult result = service(db).record(List.of(
                new RawDividend("2026-05-26", "AJBELL", "", "GBP", ""),
                new RawDividend("2026-05-26", "AJBELL", "VOD", "GBP", "25")));

        assertTrue(result.ok());
        assertEquals(1, result.savedCount());
    }
}
