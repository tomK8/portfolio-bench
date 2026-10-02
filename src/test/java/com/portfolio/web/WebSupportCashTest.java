package com.portfolio.web;

import com.portfolio.application.IiCash;
import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
        kv.putBigDecimal(IiCashField.GBP.key(), new BigDecimal(gbp));
        return kv;
    }

    private BigDecimal stored(KeyValueStore kv) {
        return stored(kv, IiCashField.GBP);
    }

    private BigDecimal stored(KeyValueStore kv, IiCashField field) {
        return kv.getBigDecimal(field.key(), new BigDecimal("-1"));
    }

    /** Post the form with only this box present — the shape a stray script or curl sends. */
    private BigDecimal post(KeyValueStore kv, IiCashField field, String raw) {
        Map<String, String> form = new HashMap<>();
        if (raw != null) form.put(field.param(), raw);
        return WebSupport.persistIiCash(kv, form).nativeByCurrency().get(field.currency());
    }

    /** The regression: a request that omits the parameter used to overwrite a real balance with 0. */
    @Test
    void anAbsentParameterLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");

        BigDecimal returned = post(kv, IiCashField.GBP, null);

        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
        assertEquals(0, new BigDecimal("718.38").compareTo(returned),
                "the view must be given the balance that is actually stored");
    }

    @Test
    void aBlankFieldLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");
        post(kv, IiCashField.GBP, "   ");
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** A typo must not silently zero a four-figure balance either. */
    @Test
    void anUnreadableFieldLeavesTheStoredBalanceAlone() {
        KeyValueStore kv = storeHolding("718.38");
        post(kv, IiCashField.GBP, "seven hundred");
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** Zero is a real balance a user can state, and stating it must still work. */
    @Test
    void anExplicitZeroDoesZeroTheBalance() {
        KeyValueStore kv = storeHolding("718.38");
        assertEquals(0, BigDecimal.ZERO.compareTo(post(kv, IiCashField.GBP, "0")));
        assertEquals(0, BigDecimal.ZERO.compareTo(stored(kv)));
    }

    @Test
    void aStatedFigureIsStoredAndReturned() {
        KeyValueStore kv = storeHolding("0");
        assertEquals(0, new BigDecimal("718.38").compareTo(post(kv, IiCashField.GBP, "718.38")));
        assertEquals(0, new BigDecimal("718.38").compareTo(stored(kv)));
    }

    /** The form is typed in by a human, so it accepts what a human types. */
    @Test
    void commasAndPoundSignsAreAccepted() {
        KeyValueStore kv = storeHolding("0");
        post(kv, IiCashField.GBP, " £74,739.15 ");
        assertEquals(0, new BigDecimal("74739.15").compareTo(stored(kv)));
    }

    /** With nothing stored yet and nothing supplied, zero is the only answer available. */
    @Test
    void nothingStoredAndNothingSuppliedReadsAsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                post(new KeyValueStore(dbDir), IiCashField.GBP, null)));
    }

    /** Every currency leg is independent — stating one must not disturb the others. */
    @Test
    void theCurrencyLegsAreIndependent() {
        KeyValueStore kv = new KeyValueStore(dbDir);
        WebSupport.persistIiCash(kv, Map.of(
                IiCashField.GBP.param(), "718.38",
                IiCashField.USD.param(), "74739.15",
                IiCashField.EUR.param(), "1282.29"));

        // A post that states only sterling leaves the other two where they were.
        post(kv, IiCashField.GBP, "700.00");

        assertEquals(0, new BigDecimal("700.00").compareTo(stored(kv, IiCashField.GBP)));
        assertEquals(0, new BigDecimal("74739.15").compareTo(stored(kv, IiCashField.USD)));
        assertEquals(0, new BigDecimal("1282.29").compareTo(stored(kv, IiCashField.EUR)));
    }

    /** Each box has its own key and its own parameter — a shared one would overwrite a balance. */
    @Test
    void everyFieldHasItsOwnParameterAndKey() {
        assertEquals(IiCashField.values().length,
                Arrays.stream(IiCashField.values()).map(IiCashField::param).distinct().count());
        assertEquals(IiCashField.values().length,
                Arrays.stream(IiCashField.values()).map(IiCashField::key).distinct().count());
    }

    /** What the form posts is what the services are handed — every box, keyed by its currency. */
    @Test
    void theWholeFormComesBackAsOneSetOfBalances() {
        KeyValueStore kv = new KeyValueStore(dbDir);
        IiCash cash = WebSupport.persistIiCash(kv, Map.of(
                IiCashField.GBP.param(), "500",
                IiCashField.USD.param(), "250",
                IiCashField.EUR.param(), "1200"));

        assertEquals(0, new BigDecimal("1200").compareTo(cash.nativeByCurrency().get("EUR")),
                "the euro box has to survive the trip to the services as euro, not as sterling");
        // Rates are foreign units per £1, so each leg divides: 500 + 250/1.25 + 1200/1.20.
        assertEquals(0, new BigDecimal("1700").compareTo(cash.totalGbp(Map.of(
                "GBP", BigDecimal.ONE,
                "USD", new BigDecimal("1.25"),
                "EUR", new BigDecimal("1.20")))));
    }

    /** The stored balances come back under the names the form posts them with. */
    @Test
    void storedBalancesAreNamedAfterTheirOwnFormFields() {
        KeyValueStore kv = new KeyValueStore(dbDir);
        WebSupport.persistIiCash(kv, Map.of(IiCashField.EUR.param(), "1282.29"));

        assertEquals("1282.29", WebSupport.storedIiCash(kv).get(IiCashField.EUR.param()));
        assertEquals("0", WebSupport.storedIiCash(kv).get(IiCashField.GBP.param()));
    }

    @Test
    void parseCashDistinguishesNoFigureFromZero() {
        assertNull(WebSupport.parseCash(null));
        assertNull(WebSupport.parseCash(""));
        assertNull(WebSupport.parseCash("nonsense"));
        assertEquals(0, BigDecimal.ZERO.compareTo(WebSupport.parseCash("0")));
    }
}
