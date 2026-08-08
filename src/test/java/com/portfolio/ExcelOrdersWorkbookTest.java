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
                LocalDate.of(2026, 7, 29), null, "In progress", "ref1", new BigDecimal("26901.41"),
                new BigDecimal("540.00"), new BigDecimal("0.0037"),
                new BigDecimal("0.0200"), new BigDecimal("0.0250"), new BigDecimal("0.50"),
                new BigDecimal("0.6170"), new BigDecimal("0.8000"));
        OpenOrder ii = new OpenOrder("II SIPP", "NVDA", "NVIDIA Corp", "Sell", "Limit",
                new BigDecimal("51"), new BigDecimal("224.8596"), true, new BigDecimal("11467.84"),
                "USD", LocalDate.of(2026, 7, 14), null, null, "PENDING", "ref2", new BigDecimal("9174.27"),
                null, null, null, null, null, null, null);

        byte[] bytes = new ExcelReportWriter().ordersWorkbook(List.of(ajb, ii));
        assertTrue(bytes.length > 0);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Open Orders");
            assertNotNull(sheet);

            Row hdr = sheet.getRow(0);
            assertEquals("Broker", hdr.getCell(0).getStringCellValue());
            assertEquals("Symbol", hdr.getCell(1).getStringCellValue());
            assertEquals("Side", hdr.getCell(2).getStringCellValue());
            assertEquals("Price", hdr.getCell(5).getStringCellValue());
            assertEquals("Current", hdr.getCell(6).getStringCellValue());
            assertEquals("Vs limit", hdr.getCell(7).getStringCellValue());
            assertEquals("RV10", hdr.getCell(8).getStringCellValue());
            assertEquals("RV30", hdr.getCell(9).getStringCellValue());
            assertEquals("σ→lim (5d)", hdr.getCell(10).getStringCellValue());
            assertEquals("P(touch 5d)", hdr.getCell(11).getStringCellValue());
            assertEquals("P(touch 20d)", hdr.getCell(12).getStringCellValue());
            assertEquals("Amount", hdr.getCell(13).getStringCellValue());
            assertEquals("Amount GBP", hdr.getCell(14).getStringCellValue());
            assertEquals("Expiry", hdr.getCell(16).getStringCellValue());

            Row ajbRow = sheet.getRow(1);
            assertEquals("EQQQ", ajbRow.getCell(1).getStringCellValue());
            assertEquals(538.00, ajbRow.getCell(5).getNumericCellValue(), 1e-6);
            assertEquals(540.00, ajbRow.getCell(6).getNumericCellValue(), 1e-6);
            assertEquals(0.0037, ajbRow.getCell(7).getNumericCellValue(), 1e-6);
            assertTrue(ajbRow.getCell(7).getCellStyle().getDataFormatString().contains("%"),
                    "distance cell should be percent-formatted");
            // RV10 / RV30 carry the raw daily-σ fraction, percent-formatted
            assertEquals(0.0200, ajbRow.getCell(8).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(8).getCellStyle().getDataFormatString().contains("%"));
            assertEquals(0.0250, ajbRow.getCell(9).getNumericCellValue(), 1e-9);
            // σ→limit is a plain signed number; touch probability is a percent-formatted fraction
            assertEquals(0.50, ajbRow.getCell(10).getNumericCellValue(), 1e-9);
            assertEquals(0.6170, ajbRow.getCell(11).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(11).getCellStyle().getDataFormatString().contains("%"));
            assertEquals(0.8000, ajbRow.getCell(12).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(12).getCellStyle().getDataFormatString().contains("%"));
            assertEquals(26901.41, ajbRow.getCell(14).getNumericCellValue(), 1e-6);
            assertEquals("2026-07-29", ajbRow.getCell(16).getStringCellValue());

            Row iiRow = sheet.getRow(2);
            // value stays numeric; the "~" lives only in the display number format
            assertEquals(224.8596, iiRow.getCell(5).getNumericCellValue(), 1e-6);
            assertTrue(iiRow.getCell(5).getCellStyle().getDataFormatString().contains("~"),
                    "estimated price cell should carry a ~ in its number format");
            // no live price / vols for this order → those columns stay blank
            assertNull(iiRow.getCell(6));
            assertNull(iiRow.getCell(7));
            assertNull(iiRow.getCell(8));
            assertNull(iiRow.getCell(11));
            assertNull(iiRow.getCell(12));
        }
    }
}
