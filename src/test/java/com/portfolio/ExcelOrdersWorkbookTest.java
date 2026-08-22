package com.portfolio;

import com.portfolio.domain.AssetClass;
import com.portfolio.domain.VolForecast;
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
        OpenOrder ajb = OpenOrder.parsed("AJ Bell SIPP", "EQQQ", "Invesco EQQQ NASDAQ-100 ETF GBP",
                        "Sell", "Limit", new BigDecimal("50"), new BigDecimal("538.00"), false,
                        new BigDecimal("26901.41"), "GBP", LocalDate.of(2026, 7, 14),
                        LocalDate.of(2026, 7, 29), null, "In progress", "ref1")
                .withAmountGbp(new BigDecimal("26901.41"))
                .withCurrentPrice(new BigDecimal("540.00"), new BigDecimal("0.0037"), null)
                .withVolatility(new BigDecimal("0.0200"), new BigDecimal("0.0225"),
                        new BigDecimal("0.0250"), new BigDecimal("0.0180"),
                        new BigDecimal("0.50"), new BigDecimal("0.6170"))
                // Priced to its own expiry: 11 sessions away, which is what the sheet must say.
                .withHorizonTouch(VolForecast.Touch.diffusive(11, new BigDecimal("0.0210"),
                        new BigDecimal("0.8200"), VolForecast.Source.BLEND, new BigDecimal("0.33"),
                        new BigDecimal("0.7400"), new BigDecimal("0.7100"), new BigDecimal("0.7700")), 11);
        OpenOrder ii = OpenOrder.parsed("II SIPP", "NVDA", "NVIDIA Corp", "Sell", "Limit",
                        new BigDecimal("51"), new BigDecimal("224.8596"), true, new BigDecimal("11467.84"),
                        "USD", LocalDate.of(2026, 7, 14), null, null, "PENDING", "ref2")
                .withAmountGbp(new BigDecimal("9174.27"));

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
            assertEquals("Quote", hdr.getCell(7).getStringCellValue());
            assertEquals("Vs limit", hdr.getCell(8).getStringCellValue());
            assertEquals("RV10", hdr.getCell(9).getStringCellValue());
            assertEquals("RV30", hdr.getCell(10).getStringCellValue());
            assertEquals("σ→lim (5d)", hdr.getCell(11).getStringCellValue());
            assertEquals("P(touch 5d)", hdr.getCell(12).getStringCellValue());
            // The 20-session column is gone: P(N) runs to each order's own expiry now, and the
            // sheet carries the forecast that priced it rather than a second fixed horizon.
            assertEquals("Horizon (sessions)", hdr.getCell(13).getStringCellValue());
            assertEquals("Horizon from", hdr.getCell(14).getStringCellValue());
            assertEquals("P(touch horizon)", hdr.getCell(15).getStringCellValue());
            assertEquals("P(low)", hdr.getCell(16).getStringCellValue());
            assertEquals("P(high)", hdr.getCell(17).getStringCellValue());
            assertEquals("σ eff", hdr.getCell(18).getStringCellValue());
            assertEquals("σ eff (diffusive)", hdr.getCell(19).getStringCellValue());
            assertEquals("Vol source", hdr.getCell(20).getStringCellValue());
            assertEquals("Drift (annual)", hdr.getCell(21).getStringCellValue());
            assertEquals("Events in horizon", hdr.getCell(22).getStringCellValue());
            assertEquals("Amount", hdr.getCell(23).getStringCellValue());
            assertEquals("Amount GBP", hdr.getCell(24).getStringCellValue());
            assertEquals("Expiry", hdr.getCell(26).getStringCellValue());

            Row ajbRow = sheet.getRow(1);
            assertEquals("EQQQ", ajbRow.getCell(1).getStringCellValue());
            assertEquals(538.00, ajbRow.getCell(5).getNumericCellValue(), 1e-6);
            assertEquals(540.00, ajbRow.getCell(6).getNumericCellValue(), 1e-6);
            assertEquals("", ajbRow.getCell(7).getStringCellValue(),
                    "a regular-hours quote carries no session label");
            assertEquals(0.0037, ajbRow.getCell(8).getNumericCellValue(), 1e-6);
            assertTrue(ajbRow.getCell(8).getCellStyle().getDataFormatString().contains("%"),
                    "distance cell should be percent-formatted");
            // RV10 / RV30 carry the raw daily-σ fraction, percent-formatted
            assertEquals(0.0200, ajbRow.getCell(9).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(9).getCellStyle().getDataFormatString().contains("%"));
            assertEquals(0.0250, ajbRow.getCell(10).getNumericCellValue(), 1e-9);
            // σ→limit is a plain signed number; touch probability is a percent-formatted fraction
            assertEquals(0.50, ajbRow.getCell(11).getNumericCellValue(), 1e-9);
            assertEquals(0.6170, ajbRow.getCell(12).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(12).getCellStyle().getDataFormatString().contains("%"));
            // The horizon travels in sessions — the number the √-scaling actually consumed — and
            // says where it came from, since 11 sessions to an expiry and 11 typed into a box are
            // different claims about the same figure.
            assertEquals(11, ajbRow.getCell(13).getNumericCellValue(), 1e-9);
            assertEquals("expiry", ajbRow.getCell(14).getStringCellValue());
            assertEquals(0.7400, ajbRow.getCell(15).getNumericCellValue(), 1e-9);
            assertTrue(ajbRow.getCell(15).getCellStyle().getDataFormatString().contains("%"));
            // The RV10/RV30 bracket, and the forecast that produced the headline: a probability
            // read months later is only judgeable against the model that made it.
            assertEquals(0.7100, ajbRow.getCell(16).getNumericCellValue(), 1e-9);
            assertEquals(0.7700, ajbRow.getCell(17).getNumericCellValue(), 1e-9);
            assertEquals(0.0210, ajbRow.getCell(18).getNumericCellValue(), 1e-9);
            assertEquals("BLEND", ajbRow.getCell(20).getStringCellValue());
            // Driftless, event-free row: 0% and 0, stated rather than blank — "no adjustment was
            // applied" is a fact about the number beside it, not missing data. And σ eff equals its
            // own diffusive value, which is what "no calendar contribution" looks like.
            assertEquals(0.0, ajbRow.getCell(21).getNumericCellValue(), 1e-12);
            assertEquals(0.0, ajbRow.getCell(22).getNumericCellValue(), 1e-12);
            assertEquals(ajbRow.getCell(18).getNumericCellValue(),
                    ajbRow.getCell(19).getNumericCellValue(), 1e-12);
            assertEquals(26901.41, ajbRow.getCell(24).getNumericCellValue(), 1e-6);
            assertEquals("2026-07-29", ajbRow.getCell(26).getStringCellValue());

            Row iiRow = sheet.getRow(2);
            // value stays numeric; the "~" lives only in the display number format
            assertEquals(224.8596, iiRow.getCell(5).getNumericCellValue(), 1e-6);
            assertTrue(iiRow.getCell(5).getCellStyle().getDataFormatString().contains("~"),
                    "estimated price cell should carry a ~ in its number format");
            // no live price / vols for this order → those columns stay blank
            assertNull(iiRow.getCell(6));
            assertNull(iiRow.getCell(8));
            assertNull(iiRow.getCell(9));
            assertNull(iiRow.getCell(12));
            assertNull(iiRow.getCell(13), "unpriced row → no horizon column either");
            assertNull(iiRow.getCell(15));
            assertNull(iiRow.getCell(18));
            assertNull(iiRow.getCell(21), "unpriced row → no model columns either");
        }
    }

    @Test
    void aDriftedRowStatesTheDriftThatPricedIt() throws Exception {
        // The sheet already carries σ_eff and the vol source so a probability read months later is
        // judgeable against its model. Which of the two first-passage models produced it is part of
        // that, and it is the one thing the other columns cannot imply.
        OpenOrder drifted = OpenOrder.parsed("Roth IRA", "PEP", "PepsiCo", "Buy", "Limit",
                        new BigDecimal("10"), new BigDecimal("140.87"), false, new BigDecimal("1408.70"),
                        "USD", LocalDate.of(2026, 8, 19), null, "GTC", "Open", "ref9")
                .withCurrentPrice(new BigDecimal("143.48"), new BigDecimal("0.0185"), null)
                .withVolatility(new BigDecimal("0.011"), new BigDecimal("0.012"),
                        new BigDecimal("0.013"), new BigDecimal("0.014"),
                        new BigDecimal("-0.80"), new BigDecimal("0.4200"))
                .withHorizonTouch(new VolForecast.Touch(90, new BigDecimal("0.01296727"),
                        new BigDecimal("0.3064"), VolForecast.Source.BLEND, new BigDecimal("-0.15"),
                        new BigDecimal("0.8800"), new BigDecimal("0.8600"), new BigDecimal("0.9000"),
                        AssetClass.EQUITY.dailyDrift(), new BigDecimal("0.000153"),
                        new BigDecimal("0.01296727"), 0, BigDecimal.ZERO), null);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                new ExcelReportWriter().ordersWorkbook(List.of(drifted))))) {
            Row row = wb.getSheet("Open Orders").getRow(1);
            // 1e-6, not exact: the row carries the per-session figure the model actually consumed,
            // rounded to 10 dp, so annualising it back lands at 0.0599999904 — 6.00% once the cell
            // format is applied. The per-session number is the one that has to be faithful.
            assertEquals(0.06, row.getCell(21).getNumericCellValue(), 1e-6,
                    "the per-session drift, stated back in the year it was quoted in");
            assertTrue(row.getCell(21).getCellStyle().getDataFormatString().contains("%"));
        }
    }

    @Test
    void extendedHoursQuoteIsLabelledInItsOwnColumn() throws Exception {
        // The price itself stays a plain number so the sheet can still sort and chart it; the
        // caveat rides alongside rather than being baked into the cell's text.
        OpenOrder pre = OpenOrder.parsed("Roth IRA", "MRNA", "Moderna, Inc.", "Buy", "Limit",
                        new BigDecimal("50"), new BigDecimal("150.00"), false,
                        new BigDecimal("7500.00"), "USD", LocalDate.of(2026, 8, 19), null, "GTC",
                        "Open", "ref3")
                .withAmountGbp(new BigDecimal("5500.00"))
                .withCurrentPrice(new BigDecimal("160.90"), new BigDecimal("0.0727"), "PRE");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                new ExcelReportWriter().ordersWorkbook(List.of(pre))))) {
            Row row = wb.getSheet("Open Orders").getRow(1);
            assertEquals(160.90, row.getCell(6).getNumericCellValue(), 1e-6);
            assertEquals("pre-market", row.getCell(7).getStringCellValue());
        }
    }
}
