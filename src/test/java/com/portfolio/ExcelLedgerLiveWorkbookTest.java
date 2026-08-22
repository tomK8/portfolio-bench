package com.portfolio;

import com.portfolio.application.LivePricesService;
import com.portfolio.domain.Irr;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.PositionIrr;
import com.portfolio.domain.model.AggHolding;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Excel counterparts of the two Holdings views that had no download: the cash-ledger table
 * and the Live table. Both assert the shape a reader relies on — the columns, and that the
 * totals are live formulas rather than baked numbers.
 */
class ExcelLedgerLiveWorkbookTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency GBP = Currency.getInstance("GBP");

    private static AggHolding equity(String symbol, String gain) {
        return new AggHolding(symbol, new BigDecimal("10"), null, new BigDecimal("1500.00"),
                new BigDecimal(gain), new BigDecimal("0.5000"), new BigDecimal("12.00"),
                new BigDecimal(gain).add(new BigDecimal("12.00")), new BigDecimal("0.5120"),
                USD, "Roth IRA", new BigDecimal("187.5000"), new BigDecimal("1875.00"),
                new BigDecimal("1500.00"), new BigDecimal("0.0300"));
    }

    private static AggHolding cash(String amount) {
        return new AggHolding("CASH", new BigDecimal(amount), BigDecimal.ONE, new BigDecimal(amount),
                null, null, BigDecimal.ZERO, null, null, GBP, "II SIPP", null, null, null, null);
    }

    @Test
    void ledgerSheetCarriesTheOnScreenColumnsAndLiveTotals() throws Exception {
        List<AggHolding> rows = List.of(equity("NVDA", "500.00"), equity("MRNA", "-40.00"), cash("250.00"));
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(rows, BigDecimal.ZERO);

        byte[] bytes = new ExcelReportWriter().ledgerWorkbook(rows, totals);
        assertTrue(bytes.length > 0);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Portfolio (ledger)");
            assertNotNull(sheet);

            Row hdr = sheet.getRow(0);
            assertEquals("Security", hdr.getCell(0).getStringCellValue());
            assertEquals("Ccy", hdr.getCell(1).getStringCellValue());
            assertEquals("Price", hdr.getCell(3).getStringCellValue());
            assertEquals("Market Value £", hdr.getCell(4).getStringCellValue());
            assertEquals("Portfolio %", hdr.getCell(5).getStringCellValue());
            assertEquals("Annualised return", hdr.getCell(11).getStringCellValue());
            assertEquals("Return basis", hdr.getCell(12).getStringCellValue());
            assertEquals("Sources", hdr.getCell(16).getStringCellValue());
            // No II SIPP cash input column/cell: ledger cash is already in the rows.
            assertEquals(17, hdr.getLastCellNum());

            Row first = sheet.getRow(1);
            assertEquals("NVDA", first.getCell(0).getStringCellValue());
            assertEquals(187.5, first.getCell(3).getNumericCellValue(), 1e-9);
            assertEquals(1500.00, first.getCell(4).getNumericCellValue(), 1e-9);
            assertEquals("IFERROR(E2/$E$6,\"\")", first.getCell(5).getCellFormula());

            Row totalRow = sheet.getRow(5);
            assertEquals("TOTAL", totalRow.getCell(0).getStringCellValue());
            assertEquals("SUM(E2:E4)", totalRow.getCell(4).getCellFormula());
            assertEquals("SUM(G2:G4)", totalRow.getCell(6).getCellFormula());

            // Cash rows are interleaved by currency section, so the cash line is a SUMIF rather
            // than a range — that is what keeps "Return on invested" honest.
            Row cashRow = sheet.getRow(6);
            assertEquals("Total Cash", cashRow.getCell(0).getStringCellValue());
            assertEquals("SUMIF(A2:A4,\"CASH\",E2:E4)", cashRow.getCell(4).getCellFormula());
            assertEquals("IFERROR(G6/(E6-E7),\"\")", sheet.getRow(7).getCell(7).getCellFormula());
            assertEquals("IFERROR(G6/E6,\"\")", sheet.getRow(8).getCell(7).getCellFormula());

            assertTrue(wb.getForceFormulaRecalculation(),
                    "formula cells open blank without the fullCalcOnLoad flag");
        }
    }

    @Test
    void ledgerSheetCarriesTheIrrAsNumbersWithItsCaveatInItsOwnColumn() throws Exception {
        // A sheet has nowhere to put a tooltip, so the rate stays a real number the sheet can
        // sort and the caveat travels beside it — same convention as the orders sheet's Quote.
        List<AggHolding> rows = List.of(equity("NVDA", "500.00"), equity("MRNA", "-40.00"));
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(rows, BigDecimal.ZERO);

        var annualised = new PositionIrr.Result("NVDA", new BigDecimal("0.184200"),
                new BigDecimal("0.500000"), true, new BigDecimal("2.75"),
                LocalDate.parse("2023-02-01"), 1298, 2, 4,
                new BigDecimal("0.210000"), "USD", Irr.Method.NEWTON);
        var shortHold = new PositionIrr.Result("MRNA", null, new BigDecimal("-0.026700"),
                false, new BigDecimal("0.11"), LocalDate.parse("2026-07-12"), 41, 1, 2,
                null, null, null);

        byte[] bytes = new ExcelReportWriter().ledgerWorkbook(rows, totals,
                Map.of("NVDA", annualised, "MRNA", shortHold));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Portfolio (ledger)");

            Row nvda = sheet.getRow(1);
            assertEquals(0.1842, nvda.getCell(11).getNumericCellValue(), 1e-9);
            assertEquals("Annualised (current episode)", nvda.getCell(12).getStringCellValue());
            assertEquals(2.75, nvda.getCell(13).getNumericCellValue(), 1e-9);
            assertEquals(0.21, nvda.getCell(14).getNumericCellValue(), 1e-9);
            // Currency effect = GBP rate − native rate: the pound cost 2.58pp a year here.
            assertEquals(-0.0258, nvda.getCell(15).getNumericCellValue(), 1e-9);

            Row mrna = sheet.getRow(2);
            assertEquals(-0.0267, mrna.getCell(11).getNumericCellValue(), 1e-9);
            assertEquals("Period return, 41d", mrna.getCell(12).getStringCellValue(),
                    "a sub-90-day figure must not be readable as an annual rate");
            assertNull(mrna.getCell(14), "no native leg, no cell — not a zero");
        }
    }

    @Test
    void aPositionWithNoSolvableRateWritesAnEmptyCellRatherThanZero() throws Exception {
        // An empty cell reads as "not computed"; a 0.00% reads as "broke even". Opposite claims.
        List<AggHolding> rows = List.of(equity("NVDA", "500.00"));
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(rows, BigDecimal.ZERO);

        byte[] bytes = new ExcelReportWriter().ledgerWorkbook(rows, totals,
                Map.of("NVDA", PositionIrr.Result.unavailable("NVDA")));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row nvda = wb.getSheet("Portfolio (ledger)").getRow(1);
            assertNull(nvda.getCell(11));
            assertEquals("No rate", nvda.getCell(12).getStringCellValue());
        }
    }

    @Test
    void ledgerSheetWithNoRowsStatesTheTotalsInsteadOfSummingAnEmptyRange() throws Exception {
        PortfolioMetrics.Totals totals = new PortfolioMetrics().compute(List.of(), BigDecimal.ZERO);

        byte[] bytes = new ExcelReportWriter().ledgerWorkbook(List.of(), totals);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row totalRow = wb.getSheet("Portfolio (ledger)").getRow(2);
            assertEquals("TOTAL", totalRow.getCell(0).getStringCellValue());
            assertEquals(0.0, totalRow.getCell(4).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void liveSheetKeepsTheSessionAndMarketStateBesideThePriceRatherThanInIt() throws Exception {
        LivePricesService.Row pre = new LivePricesService.Row("MRNA", "USD",
                new BigDecimal("122.0000"), new BigDecimal("155.5100"), new BigDecimal("0.021400"),
                new BigDecimal("240.00"), new BigDecimal("14000.00"), "Roth IRA",
                Instant.parse("2026-08-21T12:58:00Z"), "PRE", "PRE",
                new BigDecimal("0.050000"), new BigDecimal("0.080000"), new BigDecimal("0.120000"),
                new BigDecimal("0.200000"), new BigDecimal("0.300000"));
        LivePricesService.Row closed = new LivePricesService.Row("EQQQ", "GBP",
                new BigDecimal("50.0000"), new BigDecimal("538.0000"), new BigDecimal("-0.004000"),
                new BigDecimal("-90.00"), new BigDecimal("26900.00"), "AJ Bell SIPP",
                Instant.parse("2026-08-20T15:29:00Z"), "REGULAR", "CLOSED",
                null, null, null, null, null);
        LivePricesService.Snapshot snapshot = new LivePricesService.Snapshot(
                List.of(pre, closed),
                new LivePricesService.Totals(new BigDecimal("40900.00"), new BigDecimal("1000.00"),
                        new BigDecimal("41900.00"), new BigDecimal("150.00"), new BigDecimal("0.003700")),
                Instant.parse("2026-08-21T13:00:00Z"), Instant.parse("2026-08-21T12:58:00Z"));

        byte[] bytes = new ExcelReportWriter().liveWorkbook(snapshot);
        assertTrue(bytes.length > 0);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Live Positions");
            assertNotNull(sheet);

            Row hdr = sheet.getRow(0);
            assertEquals("Last", hdr.getCell(3).getStringCellValue());
            assertEquals("Quote", hdr.getCell(4).getStringCellValue());
            assertEquals("Market", hdr.getCell(5).getStringCellValue());
            assertEquals("Day Δ %", hdr.getCell(6).getStringCellValue());
            assertEquals("Position £", hdr.getCell(13).getStringCellValue());
            assertEquals("Quote ts", hdr.getCell(15).getStringCellValue());

            Row preRow = sheet.getRow(1);
            assertEquals(155.51, preRow.getCell(3).getNumericCellValue(), 1e-9);
            assertEquals("pre-market", preRow.getCell(4).getStringCellValue());
            assertEquals("pre-market", preRow.getCell(5).getStringCellValue());
            assertEquals("2026-08-21 13:58", preRow.getCell(15).getStringCellValue());

            Row closedRow = sheet.getRow(2);
            assertEquals("", closedRow.getCell(4).getStringCellValue(), "a regular quote needs no label");
            assertEquals("closed", closedRow.getCell(5).getStringCellValue());

            assertEquals("SUM(N2:N3)", sheet.getRow(4).getCell(13).getCellFormula());
            assertEquals(1000.00, sheet.getRow(5).getCell(13).getNumericCellValue(), 1e-9);
            assertEquals("N5+N6", sheet.getRow(6).getCell(13).getCellFormula());
            assertEquals("SUM(H2:H3)", sheet.getRow(7).getCell(7).getCellFormula());
        }
    }
}
