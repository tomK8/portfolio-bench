package com.portfolio.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IIInstrumentsTest {

    @Test
    void readsTheHoldingsExportForm() {
        // II's CSV writes the coupon as a bare decimal with no % sign at all.
        assertEquals("GILT 4.375% 2030",
                IIInstruments.giltIdFromName("UNITED KINGDOM 4.375 07/03/2030"));
        assertEquals("GILT 0.875% 2033",
                IIInstruments.giltIdFromName("UNITED KINGDOM 0.875 22/01/2033"));
    }

    @Test
    void readsTheEighthsAGiltIsQuotedIn() {
        // The order export and II's website state the coupon as a vulgar fraction.
        assertEquals("GILT 4.375% 2030",
                IIInstruments.giltIdFromName("4 3/8% TREASURY GILT 07/03/2030"));
        assertEquals("GILT 4.25% 2032",
                IIInstruments.giltIdFromName("4 1/4% TREASURY GILT 07/06/2032"));
    }

    @Test
    void bothFormsOfTheSameGiltProduceOneId() {
        // The id is a join key: the holdings file, the ledger and the price feed must agree on it,
        // whichever way the broker happened to spell the coupon.
        assertEquals(IIInstruments.giltIdFromName("UNITED KINGDOM 4.375 07/03/2030"),
                IIInstruments.giltIdFromName("4 3/8% TREASURY GILT 07/03/2030"));
    }

    @Test
    void trailingZerosAreCanonicalisedByGiltId() {
        assertEquals("GILT 4.5% 2028",
                IIInstruments.giltIdFromName("UNITED KINGDOM 4.500 07/06/2028"));
        assertEquals("GILT 6% 2028",
                IIInstruments.giltIdFromName("TREASURY 6.00% 07/12/2028"));
    }

    @Test
    void twoDigitYearsAreThisCentury() {
        assertEquals("GILT 4.375% 2030",
                IIInstruments.giltIdFromName("4 3/8% TREASURY GILT 07/03/30"));
    }

    @Test
    void anEquityIsNotAGilt() {
        assertNull(IIInstruments.giltIdFromName("Alphabet Inc Class A"));
        assertNull(IIInstruments.giltIdFromName("iShares MSCI Global Semiconductors ETF"));
        assertNull(IIInstruments.giltIdFromName(""));
        assertNull(IIInstruments.giltIdFromName(null));
    }

    @Test
    void aGiltMarkerWithoutAMaturityIsNotEnough() {
        // Half a gilt id is worse than none: Instruments.isBond would switch the position onto
        // the per-£100-nominal formula on the strength of a guess.
        assertNull(IIInstruments.giltIdFromName("TREASURY WINE ESTATES LTD"));
        assertNull(IIInstruments.giltIdFromName("UNITED KINGDOM 4.375"));
    }

    @Test
    void aDatedEquityNameIsNotTurnedIntoAGilt() {
        assertNull(IIInstruments.giltIdFromName("SOME FUND 12/05/2030"));
    }
}
