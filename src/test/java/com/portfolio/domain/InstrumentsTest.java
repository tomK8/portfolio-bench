package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentsTest {

    @Test
    void giltPrefixIsRecognised() {
        assertTrue(Instruments.isBond("GILT 0.875% 2033"));
        assertTrue(Instruments.isBond("GILT 2025"), "GILT prefix alone is enough");
    }

    @Test
    void couponMarkerIsRecognised() {
        assertTrue(Instruments.isBond("TREASURY 2.75% 2024"),
                "any string containing % counts — defensive catch-all for unnormalised bond rows");
    }

    @Test
    void equitiesAndCashAreNotBonds() {
        assertFalse(Instruments.isBond("NVDA"));
        assertFalse(Instruments.isBond("CASH"));
        assertFalse(Instruments.isBond("BRK.B"));
    }

    @Test
    void nullIsNotABond() {
        assertFalse(Instruments.isBond(null));
    }
}
