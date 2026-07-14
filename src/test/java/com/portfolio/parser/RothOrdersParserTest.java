package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RothOrdersParserTest {

    private final RothOrdersParser parser = new RothOrdersParser();

    @Test
    void supports_matchesOrderStatusXlsx() {
        assertTrue(parser.supports(Paths.get("Order+Status.xlsx")));
        assertTrue(parser.supports(Paths.get("Order+Status (1).xlsx")));
        assertFalse(parser.supports(Paths.get("Holdings.xlsx")));
        assertFalse(parser.supports(Paths.get("activeorders.csv")));
    }

    @Test
    void parse_locatesHeaderRowAndReadsGtc(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("Order+Status.xlsx");
        writeRothWorkbook(f);

        List<OpenOrder> orders = parser.parse(f);
        assertEquals(3, orders.size());

        OpenOrder snps = orders.get(0);
        assertEquals("Roth IRA", snps.broker());
        assertEquals("SNPS", snps.symbol());
        assertEquals("Sell", snps.side());
        assertEquals("Limit", snps.orderType());
        assertEquals(0, new BigDecimal("6").compareTo(snps.quantity()));
        assertEquals(0, new BigDecimal("460.00").compareTo(snps.price()));
        assertEquals("USD", snps.currency());
        assertEquals(LocalDate.of(2026, 7, 14), snps.dateEntered());
        assertNull(snps.expiry());
        assertEquals("GTC", snps.duration());
        assertEquals("Open", snps.status());

        assertEquals("Canceled", orders.get(1).status());
        assertEquals("Executed", orders.get(2).status());
    }

    /** Writes a workbook mirroring the Citi export: a metadata preamble, then the header + rows. */
    static void writeRothWorkbook(Path file) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Order Status");
            cell(sheet.createRow(1), 1, "Order Status");
            cell(sheet.createRow(3), 1, "As of: Jul 14 2026 12:10 pm ET");
            Row hdr = sheet.createRow(5);
            String[] cols = {"Date", "Transaction", "Security ID", "Quantity", "Order Type", "Price", "Duration", "Status"};
            for (int i = 0; i < cols.length; i++) cell(hdr, 1 + i, cols[i]);
            addRow(sheet, 6, "07/14/2026 11:13:37 AM", "Sell", "SNPS", "6", "Limit", "460.00", "GTC", "Open");
            addRow(sheet, 7, "07/14/2026 10:25:28 AM", "Sell", "ASML", "5", "Limit", "1780.00", "GTC", "Canceled");
            addRow(sheet, 8, "07/14/2026 10:22:44 AM", "Sell", "AMAT", "13", "Limit", "600.00", "GTC", "Executed");
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }
    }

    private static void addRow(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) cell(row, 1 + i, values[i]);
    }

    private static void cell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value);
    }
}
