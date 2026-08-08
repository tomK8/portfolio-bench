package com.portfolio.application;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriceMomentumTest {

    @TempDir
    Path dbDir;

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);

    private PriceHistoryRepository repo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private static PriceBar bar(String ticker, LocalDate date, double close) {
        return new PriceBar(ticker, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    @Test
    void nullTickerReturnsNull() {
        assertNull(PriceMomentum.change(repo(), null, TODAY, 30));
    }

    @Test
    void noHistoryReturnsNull() {
        assertNull(PriceMomentum.change(repo(), "NVDA", TODAY, 30));
    }

    @Test
    void missingBaseCloseReturnsNull() {
        // Only have a close on TODAY, nothing 30 days back.
        var r = repo();
        r.savePriceBars(List.of(bar("NVDA", TODAY, 100.0)));
        assertNull(PriceMomentum.change(r, "NVDA", TODAY, 30));
    }

    @Test
    void computesPercentChangeCorrectly() {
        var r = repo();
        LocalDate base = TODAY.minusDays(30);
        r.savePriceBars(List.of(bar("NVDA", base, 100.0), bar("NVDA", TODAY, 110.0)));
        BigDecimal result = PriceMomentum.change(r, "NVDA", TODAY, 30);
        assertNotNull(result);
        assertEquals(0, new BigDecimal("0.100000").compareTo(result));
    }

    @Test
    void walksBackOverWeekendForBaseClose() {
        // Base date is a Saturday — getPriceOn walks back to Friday.
        var r = repo();
        LocalDate friday = LocalDate.of(2024, 5, 3); // Fri
        LocalDate saturday = friday.plusDays(1);      // Sat — 29 days before TODAY
        r.savePriceBars(List.of(bar("AAPL", friday, 200.0), bar("AAPL", TODAY, 220.0)));
        BigDecimal result = PriceMomentum.change(r, "AAPL", TODAY, 29);
        assertNotNull(result); // walks back to Friday bar
        assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void monthChangeIsAlias30DayChange() {
        var r = repo();
        LocalDate base = TODAY.minusDays(30);
        r.savePriceBars(List.of(bar("X", base, 50.0), bar("X", TODAY, 50.0)));
        assertEquals(PriceMomentum.monthChange(r, "X", TODAY),
                PriceMomentum.change(r, "X", TODAY, 30));
    }

    @Test
    void downMoveReturnsNegativeFraction() {
        var r = repo();
        r.savePriceBars(List.of(
                bar("TSL", TODAY.minusDays(5), 100.0),
                bar("TSL", TODAY, 80.0)));
        BigDecimal result = PriceMomentum.change(r, "TSL", TODAY, 5);
        assertNotNull(result);
        assertTrue(result.compareTo(BigDecimal.ZERO) < 0);
    }
}
