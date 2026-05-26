package com.pension.domain;

import com.pension.domain.model.DividendEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DividendCalculatorTest {

    private static final Map<String, BigDecimal> RATES =
            Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"), "EUR", new BigDecimal("1.20"));

    private final DividendCalculator calc = new DividendCalculator();

    @Test
    void gbpDividendIsUnchanged() {
        DividendEntry e = calc.toEntry("2026-05-26", "AJBELL", "VOD", "GBP", 100.0, RATES);
        assertEquals(1.0, e.fxToGbp());
        assertEquals(100.0, e.amountGbp());
    }

    @Test
    void usdDividendIsConvertedByDividingByRate() {
        DividendEntry e = calc.toEntry("2026-05-26", "ROTH_IRA", "AAPL", "USD", 100.0, RATES);
        assertEquals(1.25, e.fxToGbp());
        assertEquals(80.0, e.amountGbp(), 1e-9); // 100 / 1.25
    }

    @Test
    void eurDividendIsConvertedByDividingByRate() {
        DividendEntry e = calc.toEntry("2026-05-26", "II", "ASML", "EUR", 120.0, RATES);
        assertEquals(1.20, e.fxToGbp());
        assertEquals(100.0, e.amountGbp(), 1e-9); // 120 / 1.20
    }
}
