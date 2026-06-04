package com.portfolio.parser;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.port.HistoricalFxRateProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RothIraCashStatementParserTest {

    // GBP→USD series with a deliberate gap on 2026-05-13 to exercise nearest-prior fallback.
    private static final HistoricalFxRateProvider FX = (ccy, start, end) -> {
        Map<LocalDate, BigDecimal> s = new TreeMap<>();
        s.put(LocalDate.parse("2025-12-19"), new BigDecimal("1.20"));
        s.put(LocalDate.parse("2026-05-05"), new BigDecimal("1.30"));
        s.put(LocalDate.parse("2026-05-12"), new BigDecimal("1.25"));
        s.put(LocalDate.parse("2026-05-14"), new BigDecimal("1.40"));
        return s;
    };

    @TempDir
    Path dir;

    private static void dataRow(Sheet sheet, int r, String date, String sym, String desc,
                                double net, double qty, CellStyle dateStyle) {
        Row row = sheet.createRow(r);
        Cell dc = row.createCell(0);
        dc.setCellValue(LocalDate.parse(date).atStartOfDay());
        dc.setCellStyle(dateStyle);
        row.createCell(1).setCellValue("Cash");
        row.createCell(2).setCellValue(sym);
        row.createCell(3).setCellValue(desc);
        row.createCell(4).setCellValue(net);
        row.createCell(5).setCellValue(qty);
    }

    private Path writeHistory() throws IOException {
        Path file = dir.resolve("History.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = wb.createSheet("history");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

            // a preamble row, then the header — parser locates the header by name, not position
            sheet.createRow(0).createCell(0).setCellValue("History");
            String[] headers = {"Date", "Type", "Security ID", "Activity Description", "Net Amount", "Quantity"};
            Row h = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);

            dataRow(sheet, 3, "2026-05-14", "AAPL", "Cash Dividend Received", 6.21, 0, dateStyle);
            dataRow(sheet, 4, "2026-05-13", "BIDU", "Sell -30.0 Share(s) Of Bidu At 142", 4260, -30, dateStyle);
            dataRow(sheet, 5, "2026-05-12", "NVDA", "Buy 70.0 Share(s) Of Nvda At 218", -15260, 70, dateStyle);
            dataRow(sheet, 6, "2026-05-05", "ASML", "Foreign Tax Withheld At The Source", -2.38, 0, dateStyle);
            dataRow(sheet, 7, "2025-12-19", "NOW", "Stock Split Received", 0, 8, dateStyle);
            wb.write(out);
        }
        return file;
    }

    @Test
    void classifiesAndSignsRows() throws Exception {
        List<CashTransaction> rows = new RothIraCashStatementParser(FX).parse(writeHistory());
        Map<String, CashTransaction> bySymbol = rows.stream()
                .collect(Collectors.toMap(CashTransaction::symbol, Function.identity()));

        assertEquals("DIVIDEND", bySymbol.get("AAPL").type());
        assertEquals("CHARGE", bySymbol.get("ASML").type());

        CashTransaction sell = bySymbol.get("BIDU");
        assertEquals("TRANSACTION", sell.type());
        assertEquals(-30, sell.quantity(), 0.001, "sell keeps negative share quantity");
        assertTrue(sell.amount() > 0, "sell is cash in");

        CashTransaction buy = bySymbol.get("NVDA");
        assertEquals(70, buy.quantity(), 0.001);
        assertTrue(buy.amount() < 0, "buy is cash out");

        CashTransaction split = bySymbol.get("NOW");
        assertEquals("TRANSACTION", split.type());
        assertEquals(0.0, split.amount(), 0.001, "split moves no cash");
        assertEquals(8, split.quantity(), 0.001, "split records the extra shares");
    }

    @Test
    void everythingIsUsdWithNoBalanceYet() throws Exception {
        List<CashTransaction> rows = new RothIraCashStatementParser(FX).parse(writeHistory());
        for (CashTransaction t : rows) {
            assertEquals("USD", t.currency());
            assertNull(t.cashBalance(), "native balance is derived later, at save time");
            assertNull(t.cashBalanceGbp(), "GBP balance is derived later, at save time");
        }
    }

    @Test
    void usesHistoricalRatePerDateWithNearestPriorFallback() throws Exception {
        Map<String, CashTransaction> bySymbol = new RothIraCashStatementParser(FX).parse(writeHistory())
                .stream().collect(Collectors.toMap(CashTransaction::symbol, Function.identity()));

        // 2026-05-14 dividend uses that day's rate (1.40): 6.21 / 1.40
        assertEquals(6.21 / 1.40, bySymbol.get("AAPL").amountGbp(), 1e-6);
        // 2026-05-13 has no rate → falls back to 2026-05-12's 1.25
        assertEquals(1.25, bySymbol.get("BIDU").fxToGbp(), 1e-9);
        assertEquals(4260 / 1.25, bySymbol.get("BIDU").amountGbp(), 1e-6);
    }
}
