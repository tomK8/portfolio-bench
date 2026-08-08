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
        assertEquals(2, orders.size());

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
}
