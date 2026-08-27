package com.portfolio.web;

import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The II SIPP cash inputs are the holdings view's <em>only</em> source of that balance — II's
 * holdings export omits it — so the rule that matters is that nothing except a stated figure can
 * ever change them.
 */
class WebSupportCashTest {

    @TempDir
    Path dbDir;

    private KeyValueStore storeHolding(String gbp) {
        KeyValueStore kv = new KeyValueStore(dbDir);
        kv.putBigDecimal(WebSupport.II_SIPP_CASH_KEY, new BigDecimal(gbp));
        return kv;
    }

    private BigDecimal stored(KeyValueStore kv) {
        return kv.getBigDecimal(WebSupport.II_SIPP_CASH_KEY, new BigDecimal("-1"));
    }

    /** The regression: a request that omits the parameter used to overwrite a real balance with 0. */
    @Test
    void anAbsentParameterLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");

        BigDecimal returned = WebSupport.persistIiSippCash(kv, null);

        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
        assertEquals(0, new BigDecimal("718.38").compareTo(returned),
                "the view must be given the balance that is actually stored");
    }

    @Test
    void aBlankFieldLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");
        WebSupport.persistIiSippCash(kv, "   ");
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** A typo must not silently zero a four-figure balance either. */
    @Test
    void anUnreadableFieldLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");
        WebSupport.persistIiSippCash(kv, "seven hundred");
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** Zero is a real balance a user can state, and stating it must still work. */
    @Test
    void anExplicitZeroDoesZeroTheBalance() {
        KeyValueStore kv = storeHolding("718.38");
        assertEquals(0, BigDecimal.ZERO.compareTo(WebSupport.persistIiSippCash(kv, "0")));
        assertEquals(0, BigDecimal.ZERO.compareTo(stored(kv)));
    }

    @Test
    void aStatedFigureIsStoredAndReturned() {
        KeyValueStore kv = storeHolding("0");
        assertEquals(0, new BigDecimal("718.38").compareTo(
                WebSupport.persistIiSippCash(kv, "718.38")));
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** The form is typed in by a human, so it accepts what a human types. */
    @Test
    void commasAndPoundSignsAreAccepted() {
        KeyValueStore kv = storeHolding("0");
        WebSupport.persistIiSippCash(kv, " £74,739.15 ");
        assertEquals(0, new BigDecimal("74739.15").compareTo(stored(kv)));
    }

    /** With nothing stored yet and nothing supplied, zero is the only answer available. */
    @Test
    void nothingStoredAndNothingSuppliedReadsAsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                WebSupport.persistIiSippCash(new KeyValueStore(dbDir), null)));
    }

    /** The GBP and USD legs are independent — supplying one must not disturb the other. */
    @Test
    void theTwoCurrencyLegsAreIndependent() {
        KeyValueStore kv = new KeyValueStore(dbDir);
        WebSupport.persistIiSippCash(kv, "718.38");
        WebSupport.persistIiSippCashUsd(kv, "74739.15");

        WebSupport.persistIiSippCash(kv, null);
        assertEquals(0, new BigDecimal("74739.15").compareTo(
                kv.getBigDecimal(WebSupport.II_SIPP_CASH_USD_KEY, BigDecimal.ZERO)));
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    @Test
    void parseCashDistinguishesNoFigureFromZero() {
        assertNull(WebSupport.parseCash(null));
        assertNull(WebSupport.parseCash(""));
        assertNull(WebSupport.parseCash("nonsense"));
        assertEquals(0, BigDecimal.ZERO.compareTo(WebSupport.parseCash("0")));
    }
}
