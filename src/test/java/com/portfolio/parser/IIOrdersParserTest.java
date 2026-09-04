package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IIOrdersParserTest {

    private static final String UUID = "2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv";
    private final IIOrdersParser parser = new IIOrdersParser();

    private static final String ORDERS = """
            Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
            14/07/2026 16:52,27726IDT248,SELL/LIMIT,NVDA,NVIDIA Corp,51,n/a,"$11,467.84",PENDING
            14/07/2026 16:58,27726IDT4BS,SELL/LIMIT,RBTX,iShares Automation&Robotics ETF USD Acc GBP,300,n/a,"£4,917.01",PENDING
            03/09/2026 15:10,27726KBRXX7,SELL/LIMIT,BNP,BNP Paribas Act. Cat.A,220,n/a,"€23,171.33",PENDING
            """;

    @Test
    void supports_sniffsOrdersHeaderNotHoldingsOrCash(@TempDir Path dir) throws Exception {
        Path orders = dir.resolve(UUID);
        Files.writeString(orders, ORDERS);
        assertTrue(parser.supports(orders));

        Path holdings = dir.resolve("015a6aaf-fed6-4d02-8d05-c1e2b874141c.csv");
        Files.writeString(holdings, "Symbol,Name,Qty,Price,Market Value £,Book Cost\nNVDA,NVIDIA,1,1,1,1\n");
        assertFalse(parser.supports(holdings));

        Path cash = dir.resolve("07c831a6-b77b-4db1-8c5e-d7e4080ee0ce.csv");
        Files.writeString(cash, "Date,Description,Debit,Credit,Running Balance\n");
        assertFalse(parser.supports(cash));
    }

    @Test
    void parse_splitsTypeAndInfersCurrency(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(UUID);
        Files.writeString(f, ORDERS);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(3, orders.size());

        OpenOrder nvda = orders.get(0);
        assertEquals("II SIPP", nvda.broker());
        assertEquals("NVDA", nvda.symbol());
        assertEquals("Sell", nvda.side());
        assertEquals("Limit", nvda.orderType());
        // No exported limit price → estimated from amount ÷ qty: 11467.84 / 51 = 224.8596
        assertTrue(nvda.priceEstimated());
        assertEquals(0, new BigDecimal("224.8596").compareTo(nvda.price()));
        assertEquals("USD", nvda.currency());
        assertEquals(0, new BigDecimal("11467.84").compareTo(nvda.amount()));
        assertEquals(LocalDate.of(2026, 7, 14), nvda.dateEntered());
        assertNull(nvda.expiry());
        assertEquals("PENDING", nvda.status());

        assertEquals("GBP", orders.get(1).currency());
    }

    /**
     * II holds euro listings and prefixes their order values with {@code €}. Neither
     * {@code currency} nor {@code decimal} knew the symbol until 2026-09-03, so a live BNP sell
     * limit parsed with a null amount and a null currency — and every column derived from either
     * (price, Current, vs limit, σ→lim, both touch probabilities, both Amount columns) went blank,
     * while RV10/RV30 rendered off the symbol's own daily series and made the row look like a
     * broken instrument rather than an unparsed currency symbol.
     */
    @Test
    void parse_readsEuroAmounts(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(UUID);
        Files.writeString(f, ORDERS);

        OpenOrder bnp = parser.parse(f).get(2);
        assertEquals("BNP", bnp.symbol());
        assertEquals("EUR", bnp.currency());
        assertEquals(0, new BigDecimal("23171.33").compareTo(bnp.amount()));
        // 23171.33 / 220 = 105.3242, estimated because the fee is folded into the amount.
        assertTrue(bnp.priceEstimated());
        assertEquals(0, new BigDecimal("105.3242").compareTo(bnp.price()));
        assertEquals("€", bnp.currencySymbol());
    }

    /**
     * A currency symbol the parser does not know leaves the amount unparsed <em>and</em> the
     * currency null, never a number under the wrong currency. The two readings share one table so
     * they cannot drift apart into that half-parsed state — an amount with no currency is what
     * {@code toGbp} and {@code priceInCurrency} both refuse, so the row degrades to "—" rather
     * than being valued against the wrong quote.
     */
    @Test
    void parse_leavesAnUnknownCurrencyWhollyUnparsed(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(UUID);
        Files.writeString(f, """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                03/09/2026 15:10,27726KBRXX8,SELL/LIMIT,NESN,Nestle SA,10,n/a,"CHF1,234.00",PENDING
                """);

        OpenOrder o = parser.parse(f).get(0);
        assertNull(o.currency());
        assertNull(o.amount());
        assertNull(o.price());
        assertFalse(o.priceEstimated());
        assertEquals(0, new BigDecimal("10").compareTo(o.quantity()));   // quantity is unaffected
    }

    /**
     * A value-based order ("buy £6,597.50 worth") exports {@code Quantity=n/a}. It is still a
     * resting order and must appear on the panel — dropping it made a live XWIS buy invisible.
     */
    @Test
    void parse_keepsValueBasedOrderWithNoQuantity(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(UUID);
        Files.writeString(f, """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                18/08/2026 22:34,27726JDB55K,BUY/LIMIT,XWIS,Xtrackers MSCI World Indust ETF 1C GBP,n/a,n/a,"£6,597.50",PENDING
                """);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(1, orders.size());

        OpenOrder o = orders.get(0);
        assertEquals("XWIS", o.symbol());
        assertEquals("Buy", o.side());
        assertNull(o.quantity());
        assertNull(o.price());            // nothing to divide the amount by
        assertFalse(o.priceEstimated());
        assertEquals("GBP", o.currency());
        assertEquals(0, new BigDecimal("6597.50").compareTo(o.amount()));
        assertEquals(LocalDate.of(2026, 8, 18), o.dateEntered());
    }
}
