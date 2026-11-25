package com.portfolio.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class GiltYieldTest {

    private static GiltYield quote(String last, String previous) {
        return new GiltYield(10, new BigDecimal(last), previous == null ? null : new BigDecimal(previous),
                null, null, null, null);
    }

    @Test
    void changeIsInBasisPoints() {
        assertEquals(0, new BigDecimal("1.48").compareTo(quote("4.8037", "4.7889").changeBp()));
        assertEquals(0, new BigDecimal("-0.29").compareTo(quote("5.1360", "5.1389").changeBp()));
    }

    @Test
    void noPreviousCloseMeansNoChangeRatherThanAFlatDay() {
        assertNull(quote("4.8037", null).changeBp());
    }

    @Test
    void aTenorWithoutAYieldIsNotARow() {
        assertThrows(NullPointerException.class,
                () -> new GiltYield(2, null, BigDecimal.ONE, null, null, null, null));
    }
}
