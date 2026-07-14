package com.portfolio;

import com.portfolio.domain.model.OpenOrder;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelOrdersWorkbookTest {

    @Test
    void writesOrdersSheetWithHeadersAndEstimatedPriceFormat() throws Exception {
        OpenOrder ajb = new OpenOrder("AJ Bell SIPP", "EQQQ", "Invesco EQQQ NASDAQ-100 ETF GBP",
                "Sell", "Limit", new BigDecimal("50"), new BigDecimal("538.00"), false,
                new BigDecimal("26901.41"), "GBP", LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 29), null, "In progress", "ref1");
        OpenOrder ii = new OpenOrder("II SIPP", "NVDA", "NVIDIA Corp", "Sell", "Limit",
                new BigDecimal("51"), new BigDecimal("224.8596"), true, new BigDecimal("11467.84"),
                "USD", LocalDate.of(2026, 7, 14), null, null, "PENDING", "ref2");

        byte[] bytes = new ExcelReportWriter().ordersWorkbook(List.of(ajb, ii));
        assertTrue(bytes.length > 0);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Open Orders");
            assertNotNull(sheet);

            Row hdr = sheet.getRow(0);
            assertEquals("Broker", hdr.getCell(0).getStringCellValue());
            assertEquals("Symbol", hdr.getCell(1).getStringCellValue());
            assertEquals("Price", hdr.getCell(6).getStringCellValue());
            assertEquals("Expiry", hdr.getCell(9).getStringCellValue());

            Row ajbRow = sheet.getRow(1);
            assertEquals("EQQQ", ajbRow.getCell(1).getStringCellValue());
            assertEquals(538.00, ajbRow.getCell(6).getNumericCellValue(), 1e-6);
            assertEquals("2026-07-29", ajbRow.getCell(9).getStringCellValue());

            Row iiRow = sheet.getRow(2);
            // value stays numeric; the "~" lives only in the display number format
            assertEquals(224.8596, iiRow.getCell(6).getNumericCellValue(), 1e-6);
            assertTrue(iiRow.getCell(6).getCellStyle().getDataFormatString().contains("~"),
                    "estimated price cell should carry a ~ in its number format");
        }
    }
}
