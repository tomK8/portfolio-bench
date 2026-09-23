package com.portfolio;

import com.portfolio.application.LadderService;
import com.portfolio.application.SwitchService;
import com.portfolio.domain.Ladders;
import com.portfolio.domain.Switches;
import com.portfolio.domain.model.GroupType;
import com.portfolio.domain.model.OrderGroup;
import com.portfolio.domain.model.Rung;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Ladders and Share-class switches workbooks.
 *
 * <p>Both panels are cards rather than tables, so what these tests are really pinning is that the
 * things the screen says with layout, colour and position survive being flattened into cells —
 * which side of the market a rung sits on, that cash is two figures per account and never one, that
 * a switch has three progress states rather than two.
 */
class ExcelGroupWorkbooksTest {

    private static final Map<String, BigDecimal> RATES = Map.of("USD", new BigDecimal("1.345"));

    private static Rung rung(String account, String side, String qty, String price, String ccy,
                             Rung.State state) {
        // EQQQ is the sterling line and EQQU the dollar one, so the currency names the listing.
        // A ladder's sheets read the group's symbol rather than the rung's, so this is only
        // load-bearing for the switch workbook.
        return new Rung(account + "|" + side + price, null, account,
                "GBP".equals(ccy) ? "EQQQ" : "EQQU", side,
                qty == null ? null : new BigDecimal(qty),
                price == null ? null : new BigDecimal(price), ccy, state,
                state == Rung.State.FILLED ? LocalDate.parse("2026-09-01") : null, null, null,
                null, null);
    }

    private static LadderService.Ladder ladder(String name, String symbol, String market,
                                               List<Rung> rungs) {
        List<Rung> ordered = Ladders.byPrice(rungs);
        BigDecimal current = market == null ? null : new BigDecimal(market);
        return new LadderService.Ladder(
                new OrderGroup(1L, GroupType.LADDER, name, symbol, null, null, LocalDate.now()),
                ordered, Ladders.markerIndex(ordered, current), current, "USD",
                Ladders.totals(ordered));
    }

    private static List<String> sheetNames(Workbook wb) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
        return names;
    }

    private static List<String> headers(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (Cell c : sheet.getRow(0)) out.add(c.getStringCellValue());
        return out;
    }

    /** The cell's text, or "" for a cell that was never written — which is what a null figure is. */
    private static String str(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return "";
        Cell c = r.getCell(col);
        return c == null ? "" : c.getStringCellValue();
    }

    // ---- ladders ------------------------------------------------------------

    @Test
    void laddersWorkbookHasASheetPerBlockOfThePanel() throws Exception {
        byte[] bytes = new ExcelReportWriter().laddersWorkbook(List.of(ladder("NVDA round trip",
                "NVDA", "212.33",
                List.of(rung("II SIPP", "Sell", "30", "246.97", "USD", Rung.State.WORKING),
                        rung("Roth IRA", "Buy", "20", "193.73", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(List.of("Rungs", "Ladders", "Cash"), sheetNames(wb));
            assertEquals("Ladder", headers(wb.getSheet("Rungs")).get(0),
                    "every sheet leads with the group name — that is the join back to the card");
            assertEquals("Ladder", headers(wb.getSheet("Ladders")).get(0));
            assertEquals("Ladder", headers(wb.getSheet("Cash")).get(0));
        }
    }

    /**
     * The price axis is an ordering, and a spreadsheet loses an ordering the moment anyone sorts
     * it. What the ordering <em>means</em> — which side of the market a rung is on — therefore gets
     * its own column, or the sheet would quietly drop the one thing the axis is for.
     */
    @Test
    void whichSideOfTheMarketARungSitsOnSurvivesAsAColumn() throws Exception {
        byte[] bytes = new ExcelReportWriter().laddersWorkbook(List.of(ladder("NVDA round trip",
                "NVDA", "212.33",
                List.of(rung("II SIPP", "Sell", "30", "246.97", "USD", Rung.State.WORKING),
                        rung("Roth IRA", "Buy", "20", "193.73", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet rungs = wb.getSheet("Rungs");
            assertEquals("Side of market", headers(rungs).get(2));
            assertEquals("Above market", str(rungs, 1, 2), "246.97 sell, market 212.33");
            assertEquals("Below market", str(rungs, 2, 2), "193.73 buy, market 212.33");
            assertEquals("Intended", str(rungs, 2, 9),
                    "a rung decided on but never placed is a row of the file like any other");
        }
    }

    /**
     * No live price means no marker on screen rather than one at an invented position, and the
     * column says nothing rather than guessing a side.
     */
    @Test
    void withNoMarketPriceNoRungIsPlacedOnASide() throws Exception {
        byte[] bytes = new ExcelReportWriter().laddersWorkbook(List.of(ladder("Unpriced", "XYZ",
                null, List.of(rung("II SIPP", "Sell", "30", "246.97", "USD", Rung.State.WORKING)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("", str(wb.getSheet("Rungs"), 1, 2));
        }
    }

    /**
     * Cash is two figures per account and currency, and there is deliberately no total row and no
     * SUM anywhere: a ladder raising $7,409 in one wrapper and needing $3,874 in another is not
     * "$3,535 up", it is money in the wrong account. A column total is exactly the netting the
     * split exists to prevent.
     */
    @Test
    void cashIsPerAccountAndNothingSumsAcrossAccounts() throws Exception {
        byte[] bytes = new ExcelReportWriter().laddersWorkbook(List.of(ladder("NVDA round trip",
                "NVDA", "212.33",
                List.of(rung("II SIPP", "Sell", "30", "246.97", "USD", Rung.State.WORKING),
                        rung("Roth IRA", "Buy", "20", "193.73", "USD", Rung.State.WORKING)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet cash = wb.getSheet("Cash");
            assertEquals(List.of("Ladder", "Account", "Currency", "Raises", "Needs", "Net"),
                    headers(cash));
            assertEquals(2, cash.getLastRowNum(), "two account lines and a header — no total row");

            assertEquals("II SIPP", str(cash, 1, 1));
            assertEquals(30 * 246.97, cash.getRow(1).getCell(3).getNumericCellValue(), 1e-6);
            assertEquals(0.0, cash.getRow(1).getCell(4).getNumericCellValue(), 1e-6);
            assertEquals("Roth IRA", str(cash, 2, 1));
            assertEquals(20 * 193.73, cash.getRow(2).getCell(4).getNumericCellValue(), 1e-6);

            for (Sheet s : List.of(wb.getSheet("Rungs"), wb.getSheet("Ladders"), cash)) {
                for (Row r : s) {
                    for (Cell c : r) {
                        assertNotEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, c.getCellType(),
                                "nothing in these sheets is editable, so nothing is a formula");
                    }
                }
            }
        }
    }

    /**
     * A rung with no quantity is left out of the net position and named, never counted as zero —
     * so the cell is empty rather than 0, and the reason travels in the file.
     */
    @Test
    void whatCouldNotBeComputedIsBlankAndNamedRatherThanZero() throws Exception {
        byte[] bytes = new ExcelReportWriter().laddersWorkbook(List.of(ladder("Half-known", "NVDA",
                "212.33",
                List.of(rung("II SIPP", "Sell", null, "246.97", "USD", Rung.State.WORKING)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet rungs = wb.getSheet("Rungs");
            assertNull(rungs.getRow(1).getCell(5), "no quantity is not a quantity of zero");
            assertNull(rungs.getRow(1).getCell(6), "and so there is no cash figure either");

            Sheet summary = wb.getSheet("Ladders");
            assertNull(summary.getRow(1).getCell(6), "the net position is unknown, not zero");
            assertTrue(str(summary, 1, 13).contains("net position"),
                    "and the sheet says which rung took it out: " + str(summary, 1, 13));
        }
    }

    // ---- switches -----------------------------------------------------------

    /** A leg on a named listing, for the sides that hold both. */
    private static Rung on(String symbol, String account, String side, String qty, String price,
                           String ccy, Rung.State state) {
        return new Rung(account + "|" + symbol + side + price, null, account, symbol, side,
                new BigDecimal(qty), new BigDecimal(price), ccy, state, null, null, null,
                null, null);
    }

    /**
     * A switch worked from both ends: the Legs sheet reads each row's own listing rather than the
     * side's, and the Yardstick names both lines on a side that holds both.
     *
     * <p>The native average goes blank there and only the GBP one is written — the sheet has no
     * tooltip to explain a blended figure, and a column of pounds with a dollar figure in it is how
     * a reader reconciles a total and cannot.
     */
    @Test
    void bothListingsOnOneSideTravelWithTheirOwnRows() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(on("EQQQ", "II SIPP", "Sell", "50", "530.00", "GBP", Rung.State.WORKING),
                        on("EQQU", "Roth IRA", "Sell", "50", "716.00", "USD", Rung.State.WORKING)),
                List.of(on("EQQU", "Roth IRA", "Buy", "100", "672.50", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet legs = wb.getSheet("Legs");
            assertEquals("Selling", str(legs, 1, 1));
            assertEquals("EQQQ", str(legs, 1, 2), "the rung's own listing, not the side's");
            assertEquals("Selling", str(legs, 2, 1));
            assertEquals("EQQU", str(legs, 2, 2), "the other line, on the same side");
            assertEquals("Buying", str(legs, 3, 1));

            Sheet y = wb.getSheet("Yardstick");
            assertEquals("EQQQ / EQQU", str(y, 1, 3), "both lines named on the selling side");
            assertEquals("", str(y, 1, 6), "two currencies have no average, so none is written");
            assertEquals((50 * 530.00 + 50 * 716.00 / 1.345) / 100,
                    y.getRow(1).getCell(7).getNumericCellValue(), 1e-4,
                    "the GBP column still answers, which is why it is there");
            assertEquals(500.00, y.getRow(1).getCell(15).getNumericCellValue(), 1e-4,
                    "the side's own blended market, which Limit vs market is measured against");
        }
    }

    private static SwitchService.Switch aSwitch(List<Rung> out, List<Rung> in) {
        Switches.Summary summary = Switches.summarise(out, in,
                new Switches.LinePrice("EQQQ", new BigDecimal("500.00"), "GBP"),
                new Switches.LinePrice("EQQU", new BigDecimal("672.50"), "USD"),
                RATES, List.of());
        return new SwitchService.Switch(
                new OrderGroup(2L, GroupType.SHARE_CLASS_SWITCH, "Nasdaq line to USD",
                        "EQQQ", "EQQU", null, LocalDate.now()),
                out, in, summary, List.of());
    }

    @Test
    void switchesWorkbookHasASheetPerBlockOfThePanel() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(List.of("Switches", "Yardstick", "Legs", "Sequence", "Cash"),
                    sheetNames(wb));
            assertEquals("Switch", headers(wb.getSheet("Yardstick")).get(0));
            assertEquals("Switch", headers(wb.getSheet("Sequence")).get(0));
        }
    }

    /**
     * Three progress states, not two. Units sold and not yet bought back are in neither line — they
     * own nothing while the fund moves — and that is the exposure a half-finished switch actually
     * carries. A file with only "sold" and "bought" columns would lose it, which is how it goes
     * missing from every view that counts positions.
     */
    @Test
    void theUnitsInNeitherLineGetTheirOwnColumn() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.FILLED)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("Switches");
            assertEquals("Sold, not bought back", headers(s).get(5));
            assertEquals(100.0, s.getRow(1).getCell(5).getNumericCellValue(), 1e-6,
                    "sold and not bought back — in neither listing");
            assertEquals(0.0, s.getRow(1).getCell(6).getNumericCellValue(), 1e-6);
            assertEquals("", str(s, 1, 8), "nothing has arrived, so it is not complete");
        }
    }

    /**
     * The basis is the only live evidence the pair is the pair, so the workbook carries both the
     * gap and the verdict — the screen says "not tracking" in amber and a sheet has no amber.
     */
    @Test
    void theBasisAndItsVerdictBothTravel() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("Switches");
            assertEquals("Lines apart (GBP)", headers(s).get(13));
            assertEquals(0.0, s.getRow(1).getCell(13).getNumericCellValue(), 1e-9,
                    "two lines of one fund differ by exactly the rate, so in GBP they agree");
            assertEquals("yes", str(s, 1, 14));
            assertEquals(1.345, s.getRow(1).getCell(15).getNumericCellValue(), 1e-9,
                    "the rate standing when the file was written, or the basis cannot be judged later");
        }
    }

    /**
     * The yardstick is the block the panel exists for: both legs in GBP, then the net underneath.
     * Two rows per basis plus the net, and the net only when both sides are actually present.
     */
    @Test
    void theYardstickCarriesBothLegsInGbpAndTheNetUnderThem() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet y = wb.getSheet("Yardstick");
            assertEquals("Resting and intended", str(y, 1, 1));
            assertEquals("Selling", str(y, 1, 2));
            assertEquals(530.00, y.getRow(1).getCell(7).getNumericCellValue(), 1e-4);
            assertEquals("Buying", str(y, 2, 2));
            assertEquals(716.00 / 1.345, y.getRow(2).getCell(7).getNumericCellValue(), 1e-4,
                    "the dollar leg divided by the rate — the only column the two can be read in");

            assertEquals("Net", str(y, 3, 2));
            assertEquals(0.0044188, y.getRow(3).getCell(10).getNumericCellValue(), 1e-6,
                    "buying 0.44% above where it sells");

            // "Already traded" has no legs here, so it gets its two empty sides and no net row:
            // a net line under two absent sides would look like a comparison that was made.
            assertEquals(5, y.getLastRowNum());
            assertEquals("Already traded", str(y, 4, 1));
            assertNotEquals("Net", str(y, 5, 2),
                    "a net line under two absent sides would read as a comparison that was made");
        }
    }

    /**
     * The sequence is the part no broker will do, and the order is the content — the first step
     * that is not done is what everything after it waits on. Numbering it is what survives a sort.
     */
    @Test
    void theSequenceIsNumberedAndNamesWhatBlocksTheNextStep() throws Exception {
        byte[] bytes = new ExcelReportWriter().switchesWorkbook(List.of(aSwitch(
                List.of(rung("II SIPP", "Sell", "100", "530.00", "GBP", Rung.State.WORKING)),
                List.of(rung("Roth IRA", "Buy", "100", "716.00", "USD", Rung.State.INTENDED)))));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet seq = wb.getSheet("Sequence");
            assertEquals(List.of("Switch", "Step", "What has to happen", "State", "Detail",
                    "Blocks the next step"), headers(seq));
            assertEquals(1.0, seq.getRow(1).getCell(1).getNumericCellValue(), 1e-9);
            assertTrue(seq.getLastRowNum() >= 5, "five steps from sell to buy");

            boolean anyBlocks = false;
            for (int i = 1; i <= seq.getLastRowNum(); i++) {
                if ("yes".equals(str(seq, i, 5))) anyBlocks = true;
            }
            assertTrue(anyBlocks, "nothing has sold yet, so some step is blocking");
        }
    }

    /** Empty in, empty out — the caller never asks for a workbook of nothing, but it must not throw. */
    @Test
    void noGroupsWritesAnEmptyButValidWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(new ExcelReportWriter().laddersWorkbook(List.of())))) {
            assertEquals(0, wb.getSheet("Rungs").getLastRowNum(), "the header row and nothing else");
        }
        try (Workbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(new ExcelReportWriter().switchesWorkbook(List.of())))) {
            assertEquals(0, wb.getSheet("Switches").getLastRowNum());
        }
    }
}
