package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AJBellOrdersParserTest {

    private final AJBellOrdersParser parser = new AJBellOrdersParser();

    @Test
    void supports_matchesActiveOrdersCsv() {
        assertTrue(parser.supports(Paths.get("activeorders.csv")));
        assertTrue(parser.supports(Paths.get("activeorders (2).csv")));
        assertFalse(parser.supports(Paths.get("portfolio-SIPP.csv")));
        assertFalse(parser.supports(Paths.get("Order+Status.xlsx")));
    }

    @Test
    void parse_normalisesPenceAndDates(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("activeorders.csv");
        Files.writeString(f, """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Invesco EQQQ NASDAQ-100 ETF GBP","44626CD2R9X","Sell","Limit","50","26901.41","53800p","14/07/2026","29/07/2026","Dealt"
                "London Stock Exchange Group PLC","44626CDDGTP","Buy","Limit","50","4098.88","8150p","07/07/2026","28/08/2026","In progress"
                """);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(2, orders.size());

        OpenOrder sell = orders.get(0);
        assertEquals("AJ Bell SIPP", sell.broker());
        assertEquals("EQQQ", sell.symbol(), "investment name mapped to internal ticker");
        assertEquals("Invesco EQQQ NASDAQ-100 ETF GBP", sell.name());
        assertEquals("Sell", sell.side());
        assertEquals("Limit", sell.orderType());
        assertEquals(0, new BigDecimal("50").compareTo(sell.quantity()));
        // 53800p → £538.00
        assertEquals(0, new BigDecimal("538.00").compareTo(sell.price()));
        assertEquals("GBP", sell.currency());
        assertEquals(0, new BigDecimal("26901.41").compareTo(sell.amount()));
        assertEquals(LocalDate.of(2026, 7, 14), sell.dateEntered());
        assertEquals(LocalDate.of(2026, 7, 29), sell.expiry());
        assertEquals("Dealt", sell.status());

        assertEquals("Buy", orders.get(1).side());
        assertEquals("LSEG", orders.get(1).symbol());
        assertEquals(0, new BigDecimal("81.50").compareTo(orders.get(1).price()));
    }

    @Test
    void parse_unmappedNameFallsIntoSymbolField(@TempDir Path dir) throws Exception {
        // No NAME_TO_SYMBOL entry for this instrument → the raw name surfaces in the Symbol column
        // (the cue to add a mapping), rather than showing "—".
        Path f = dir.resolve("activeorders.csv");
        Files.writeString(f, """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Some Brand New ETF PLC","44626CZZZZZ","Buy","Limit","10","1000.00","10000p","07/07/2026","28/08/2026","In progress"
                """);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(1, orders.size());
        assertEquals("Some Brand New ETF PLC", orders.get(0).symbol(),
                "unmapped name falls through to the symbol field");
    }

    /**
     * The three UK REITs bought in Aug 2026. An unmapped name still renders (as the full name), but
     * resolves to no ticker, so its Current / vs-limit / touch-probability columns stay blank —
     * which is what prompted the mapping.
     */
    @Test
    void parse_mapsUkReitNamesToInternalSymbols(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("activeorders.csv");
        Files.writeString(f, """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Primary Health Properties PLC","44626CHCC62","Buy","Limit","0","10053.5","93.71p","13/08/2026","30/10/2026","In progress"
                "LondonMetric Property PLC","44626CHCKP7","Buy","Limit","0","7038.5","193.2p","13/08/2026","30/10/2026","In progress"
                "British Land Co PLC","44626CHCPMG","Buy","Limit","0","5028.5","434.7p","13/08/2026","10/11/2026","In progress"
                """);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(List.of("PHP", "LMP", "BLND"),
                orders.stream().map(OpenOrder::symbol).toList());
        // Pence normalised to pounds, same as every other London price.
        assertEquals(0, orders.get(2).price().compareTo(new BigDecimal("4.347")));
    }

    @Test
    void symbolOrName_fallsThroughUnmappedNamesUnchanged() {
        assertEquals("PHP", parser.symbolOrName("Primary Health Properties PLC"));
        assertEquals("Some New Holding PLC", parser.symbolOrName("Some New Holding PLC"));
    }

    /** A keyword added on the dashboard maps a name the committed table has never heard of. */
    @Test
    void symbolOrName_usesUserAddedAliases() {
        var p = new AJBellOrdersParser(() -> Map.of("xtrackers msci world health", "XWHS"));
        assertEquals("XWHS", p.symbolOrName("Xtrackers MSCI World Health Care ETF 1C GBP"));
    }

    /** User keywords are consulted first, so a committed rule aimed at the wrong name is fixable. */
    @Test
    void userAliasesOverrideTheCommittedKeywords() {
        var p = new AJBellOrdersParser(() -> Map.of("relx", "REL2"));
        assertEquals("REL2", p.symbolOrName("RELX PLC"));
    }
}
