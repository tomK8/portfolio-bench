package com.portfolio;

import com.portfolio.application.LivePricesService;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.PortfolioAggregator;
import com.portfolio.domain.PortfolioMetrics;
import com.portfolio.domain.PositionIrr;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.OpenOrder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExcelReportWriter {

    private static final String II_SIPP = "II SIPP";

    // ---- Portfolio Raw sheet columns ----
    private static final int COL_SECURITY_ID = 0;  // A
    private static final int COL_QUANTITY = 1;  // B
    private static final int COL_AVG_PRICE = 2;  // C
    private static final int COL_MKT_VALUE = 3;  // D  (native currency)
    private static final int COL_MKT_VALUE_GBP = 4;  // E
    private static final int COL_GAIN = 5;  // F  Gain (£) — price appreciation
    private static final int COL_GAIN_PCT = 6;  // G  Gain/Loss %
    private static final int COL_DIVIDEND = 7;  // H  Dividends (£)
    private static final int COL_TOTAL_GAIN = 8;  // I  Total Gain (£) = gain + dividends
    private static final int COL_TOTAL_GAIN_PCT = 9;  // J  Total Gain % = (gain + dividends) / cost
    private static final int COL_CURRENCY = 10;  // K
    private static final int COL_SOURCE = 11;  // L
    private static final int NUM_COLS = 12;

    // ---- Aggregated Portfolio sheet columns ----
    private static final int AGG_ID = 0;  // A
    private static final int AGG_QTY = 1;  // B
    private static final int AGG_AVG = 2;  // C
    private static final int AGG_CCY = 3;  // D  Exchange Currency
    private static final int AGG_MKTGBP = 4;  // E  Market Value GBP
    private static final int AGG_GAIN = 5;  // F  Gain (£) — price appreciation
    private static final int AGG_GAINPCT = 6;  // G  Gain/Loss %
    private static final int AGG_DIV = 7;  // H  Dividends (£)
    private static final int AGG_TOTAL_GAIN = 8;  // I  Total Gain (£) = gain + dividends
    private static final int AGG_TOTAL_GAIN_PCT = 9;  // J  Total Gain % = (gain + dividends) / cost
    private static final int AGG_SOURCES = 10;  // K
    private static final int AGG_PCT = 11;  // L  Portfolio % = row Market Value GBP / portfolio total
    private static final int AGG_COLS = 12;

    private static final byte[] GREEN = {(byte) 0, (byte) 176, (byte) 80};
    private static final byte[] RED = {(byte) 255, (byte) 0, (byte) 0};

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private static String writeAggregatedSheet(Sheet sheet, List<AggHolding> rows,
                                               Map<String, BigDecimal> gbpRates,
                                               BigDecimal iiSippCash,
                                               Workbook wb) {
        CellStyle dataText = wb.createCellStyle();
        CellStyle dataNum = numericStyle(wb, false);
        CellStyle boldText = boldTextStyle(wb);
        CellStyle boldNum = numericStyle(wb, true);
        CellStyle inputCell = inputCellStyle(wb);
        CellStyle pctData = pctStyle(wb, false);

        List<AggHolding> equities = rows.stream().filter(h -> !"CASH".equals(h.securityId())).collect(Collectors.toList());
        List<AggHolding> cashList = rows.stream().filter(h -> "CASH".equals(h.securityId())).collect(Collectors.toList());
        boolean hasCashGbp = cashList.stream().anyMatch(h -> "GBP".equals(h.currency().getCurrencyCode()));

        int nEquity = equities.size();
        int nBond = (int) equities.stream().filter(h -> Instruments.isBond(h.securityId())).count();
        int nNonBond = nEquity - nBond;
        int nCash = cashList.size() + (hasCashGbp ? 0 : 1);

        int firstCashPoi = nEquity + 3;
        int lastCashPoi = nEquity + 2 + nCash;
        int totalCashPoiRow = nEquity + 3 + nCash;
        int totalPoiRow = nEquity + 5 + nCash;
        int returnPctPoiRow = nEquity + 6 + nCash;
        int returnEquitiesPoiRow = nEquity + 7 + nCash;
        int returnBondsPoiRow = nEquity + 8 + nCash;
        int totalReturnPoiRow = nEquity + 9 + nCash;
        int inputLabelPoi = nEquity + 12 + nCash;
        int inputValuePoi = nEquity + 13 + nCash;
        String inputCellRef = "E" + (inputValuePoi + 1);

        int firstEquityExcel = 2;
        int lastEquityExcel = nEquity + 1;
        int firstCashExcel = firstCashPoi + 1;
        int lastCashExcel = lastCashPoi + 1;

        // Header
        String[] headers = {"Security ID", "Quantity", "Avg Price Paid", "Exchange Currency",
                "Market Value GBP", "Gain (£)", "Gain/Loss %", "Dividends (£)",
                "Total Gain (£)", "Total Gain %", "Sources", "Portfolio %"};
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);
        sheet.setAutoFilter(new CellRangeAddress(0, nEquity, 0, AGG_COLS - 1));

        // Portfolio % = this row's Market Value GBP (col E) ÷ the portfolio TOTAL (col E, total row).
        // Written as a live Excel formula so it re-computes if the user edits the II SIPP cash input.
        String portfolioTotalRef = "$E$" + (totalPoiRow + 1);

        // Equity rows
        int rowNum = 1;
        for (AggHolding h : equities) {
            Row row = sheet.createRow(rowNum++);
            writeAggRow(row, h, dataText, dataNum, wb);
            formulaCell(row, AGG_PCT,
                    "IFERROR(E" + (row.getRowNum() + 1) + "/" + portfolioTotalRef + ",\"\")", pctData);
        }

        // Cash section header
        styledCell(sheet.createRow(nEquity + 2), AGG_ID, "Cash", boldText);

        // Cash rows
        rowNum = firstCashPoi;
        boolean wroteGbpCash = false;
        for (AggHolding h : cashList) {
            Row row = sheet.createRow(rowNum++);
            boolean isGbpCash = "GBP".equals(h.currency().getCurrencyCode());
            styledCell(row, AGG_ID, h.securityId(), dataText);
            styledCell(row, AGG_CCY, h.currency().getCurrencyCode(), dataText);
            if (isGbpCash) {
                String existing = h.marketValueGbp().setScale(2, RoundingMode.HALF_UP).toPlainString();
                String formula = existing + "+" + inputCellRef;
                formulaCell(row, AGG_QTY, formula, dataNum);
                setNumeric(row, AGG_AVG, BigDecimal.ONE, dataNum);
                formulaCell(row, AGG_MKTGBP, formula, dataNum);
                String src = h.sources().contains(II_SIPP) ? h.sources() : h.sources() + ", " + II_SIPP;
                styledCell(row, AGG_SOURCES, src, dataText);
                wroteGbpCash = true;
            } else {
                setNumeric(row, AGG_QTY, h.quantity(), dataNum);
                setNumeric(row, AGG_AVG, h.avgPricePaid(), dataNum);
                setNumeric(row, AGG_MKTGBP, h.marketValueGbp(), dataNum);
                styledCell(row, AGG_SOURCES, h.sources(), dataText);
            }
            formulaCell(row, AGG_PCT,
                    "IFERROR(E" + (row.getRowNum() + 1) + "/" + portfolioTotalRef + ",\"\")", pctData);
        }
        if (!wroteGbpCash) {
            Row row = sheet.createRow(rowNum);
            styledCell(row, AGG_ID, "CASH", dataText);
            styledCell(row, AGG_CCY, "GBP", dataText);
            formulaCell(row, AGG_QTY, inputCellRef, dataNum);
            setNumeric(row, AGG_AVG, BigDecimal.ONE, dataNum);
            formulaCell(row, AGG_MKTGBP, inputCellRef, dataNum);
            styledCell(row, AGG_SOURCES, II_SIPP, dataText);
            formulaCell(row, AGG_PCT,
                    "IFERROR(E" + (row.getRowNum() + 1) + "/" + portfolioTotalRef + ",\"\")", pctData);
        }

        // Total Cash row
        double gbpusd = gbpRates.getOrDefault("USD", BigDecimal.ONE).doubleValue();
        int totalCashExcelRow = totalCashPoiRow + 1;
        Row cashTotalRow = sheet.createRow(totalCashPoiRow);
        styledCell(cashTotalRow, AGG_ID, "Total Cash", boldText);
        formulaCell(cashTotalRow, AGG_MKTGBP,
                "SUM(E" + (firstCashPoi + 1) + ":E" + (lastCashPoi + 1) + ")", boldNum);
        formulaCell(cashTotalRow, AGG_CCY,
                "E" + totalCashExcelRow + "*" + gbpusd, boldNum);

        // TOTAL
        Row total = sheet.createRow(totalPoiRow);
        styledCell(total, AGG_ID, "TOTAL", boldText);
        formulaCell(total, AGG_MKTGBP,
                "SUBTOTAL(9,E" + firstEquityExcel + ":E" + lastEquityExcel + ")"
                        + "+SUM(E" + firstCashExcel + ":E" + lastCashExcel + ")", boldNum);
        formulaCell(total, AGG_GAIN,
                "SUBTOTAL(9,F" + firstEquityExcel + ":F" + lastEquityExcel + ")"
                        + "+SUM(F" + firstCashExcel + ":F" + lastCashExcel + ")", boldNum);

        // Return All Investments row: Gain / (Total Value − Cash)
        int tExcel = totalPoiRow + 1;
        int tcExcel = totalCashPoiRow + 1;
        Row returnRow = sheet.createRow(returnPctPoiRow);
        styledCell(returnRow, AGG_ID, "Return All Investments", boldText);
        Cell returnCell = returnRow.createCell(AGG_GAINPCT);
        returnCell.setCellFormula("F" + tExcel + "/(E" + tExcel + "-E" + tcExcel + ")");
        returnCell.setCellStyle(pctStyle(wb, true));

        // Return Equities row: non-bond equity gain / non-bond equity value
        int lastNonBondExcel = nNonBond + 1;
        int firstBondExcel = nNonBond + 2;
        int lastBondExcel = nEquity + 1;
        Row returnEquitiesRow = sheet.createRow(returnEquitiesPoiRow);
        styledCell(returnEquitiesRow, AGG_ID, "Return Equities", boldText);
        Cell returnEquitiesCell = returnEquitiesRow.createCell(AGG_GAINPCT);
        returnEquitiesCell.setCellFormula(
                "IFERROR(SUM(F2:F" + lastNonBondExcel + ")/SUM(E2:E" + lastNonBondExcel + "),\"\")");
        returnEquitiesCell.setCellStyle(pctStyle(wb, true));

        // Return Fixed Income row: bond gain / bond value
        Row returnBondsRow = sheet.createRow(returnBondsPoiRow);
        styledCell(returnBondsRow, AGG_ID, "Return Fixed Income", boldText);
        Cell returnBondsCell = returnBondsRow.createCell(AGG_GAINPCT);
        returnBondsCell.setCellFormula(
                "IFERROR(SUM(F" + firstBondExcel + ":F" + lastBondExcel + ")/SUM(E" + firstBondExcel + ":E" + lastBondExcel + "),\"\")");
        returnBondsCell.setCellStyle(pctStyle(wb, true));

        // Total Return (including cash) row: Gain / Total Value
        Row totalReturnRow = sheet.createRow(totalReturnPoiRow);
        styledCell(totalReturnRow, AGG_ID, "Total Return (including cash)", boldText);
        Cell totalReturnCell = totalReturnRow.createCell(AGG_GAINPCT);
        totalReturnCell.setCellFormula("F" + tExcel + "/E" + tExcel);
        totalReturnCell.setCellStyle(pctStyle(wb, true));

        // II SIPP cash input cell (yellow, editable in Excel)
        styledCell(sheet.createRow(inputLabelPoi), AGG_ID, "II SIPP Cash (GBP)", boldText);
        Row inputRow = sheet.createRow(inputValuePoi);
        setNumeric(inputRow, AGG_MKTGBP, iiSippCash, inputCell);

        autoSizeColumns(sheet, AGG_COLS);

        return sheet.getSheetName() + "!E" + (inputValuePoi + 1);
    }

    private static void writeAggRow(Row row, AggHolding h,
                                    CellStyle textStyle, CellStyle numStyle, Workbook wb) {
        styledCell(row, AGG_ID, h.securityId(), textStyle);
        setNumeric(row, AGG_QTY, h.quantity(), numStyle);
        setNumeric(row, AGG_AVG, h.avgPricePaid(), numStyle);
        styledCell(row, AGG_CCY, h.currency().getCurrencyCode(), textStyle);
        setNumeric(row, AGG_MKTGBP, h.marketValueGbp(), numStyle);
        if (h.gainGbp() != null) {
            setNumeric(row, AGG_GAIN, h.gainGbp(), gainLossNumStyle(wb, h.gainGbp().signum() >= 0));
        }
        if (h.gainPct() != null) {
            setPercent(row, AGG_GAINPCT, h.gainPct(), gainLossPctStyle(wb, h.gainPct().signum() >= 0));
        }
        setNumeric(row, AGG_DIV, h.dividendGbp(), numStyle);
        if (h.totalGainGbp() != null) {
            setNumeric(row, AGG_TOTAL_GAIN, h.totalGainGbp(),
                    gainLossNumStyle(wb, h.totalGainGbp().signum() >= 0));
        }
        if (h.totalGainPct() != null) {
            setPercent(row, AGG_TOTAL_GAIN_PCT, h.totalGainPct(),
                    gainLossPctStyle(wb, h.totalGainPct().signum() >= 0));
        }
        styledCell(row, AGG_SOURCES, h.sources(), textStyle);
    }

    // -------------------------------------------------------------------------
    // Aggregated Portfolio sheet — returns cross-sheet ref to the II SIPP input cell
    // -------------------------------------------------------------------------

    private static void writePortfolioSheet(Sheet sheet, List<Holding> holdings,
                                            Map<String, BigDecimal> gbpRates,
                                            String portfolioInputRef,
                                            Map<String, BigDecimal> dividendsBySymbol,
                                            Workbook wb) {
        CellStyle dataText = wb.createCellStyle();
        CellStyle dataNum = numericStyle(wb, false);
        CellStyle boldText = boldTextStyle(wb);
        CellStyle boldNum = numericStyle(wb, true);
        CellStyle linkedCell = linkedCellStyle(wb);
        CellStyle gainNum = gainLossNumStyle(wb, true);
        CellStyle lossNum = gainLossNumStyle(wb, false);
        CellStyle gainPct = gainLossPctStyle(wb, true);
        CellStyle lossPct = gainLossPctStyle(wb, false);

        String[] headers = {"Security ID", "Quantity", "Avg Price Paid",
                "Market Value", "Market Value GBP", "Gain (£)", "Gain/Loss %",
                "Dividends (£)", "Total Gain (£)", "Total Gain %", "Currency", "Source"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(headerRow, i, headers[i], boldText);

        Map<String, List<Holding>> bySource = new LinkedHashMap<>();
        for (Holding h : holdings) bySource.computeIfAbsent(h.getSource(), k -> new ArrayList<>()).add(h);

        int numSources = bySource.size();
        boolean hasII = bySource.containsKey(II_SIPP);
        int numDataRows = holdings.size() + (numSources - 1) + (hasII ? 1 : 0);

        int dataAfter = 1 + numDataRows;
        int currHdrPoi = dataAfter + 2;
        int firstSubPoi = currHdrPoi + 1;
        int grandTotalPoi = firstSubPoi + numSources + 1;
        int totalCashPoi = grandTotalPoi + 1;
        int returnPctPoi = totalCashPoi + 1;
        int returnEquitiesPoi = returnPctPoi + 1;
        int returnBondsPoi = returnEquitiesPoi + 1;
        int totalReturnPoi = returnBondsPoi + 1;
        int ratesLabelPoi = totalReturnPoi + 2;
        int gbpusdPoi = ratesLabelPoi + 1;
        int gbpeurPoi = ratesLabelPoi + 2;
        String gbpusdRef = "$B$" + (gbpusdPoi + 1);

        CellStyle rateStyle = rateNumStyle(wb);
        styledCell(sheet.createRow(ratesLabelPoi), COL_SECURITY_ID, "FX Rates (1 GBP =)", boldText);
        Row rusd = sheet.createRow(gbpusdPoi);
        styledCell(rusd, COL_SECURITY_ID, "USD", dataText);
        setRate(rusd, 1, gbpRates.getOrDefault("USD", BigDecimal.ONE), rateStyle);
        Row reur = sheet.createRow(gbpeurPoi);
        styledCell(reur, COL_SECURITY_ID, "EUR", dataText);
        setRate(reur, 1, gbpRates.getOrDefault("EUR", BigDecimal.ONE), rateStyle);

        Map<String, int[]> ranges = new LinkedHashMap<>();
        int rowNum = 1;
        boolean firstSource = true;

        for (Map.Entry<String, List<Holding>> entry : bySource.entrySet()) {
            if (!firstSource) rowNum++;
            firstSource = false;
            String src = entry.getKey();
            int firstRow = rowNum;

            for (Holding h : entry.getValue()) {
                BigDecimal div = dividendsBySymbol.getOrDefault(h.getSecurityId().toUpperCase(), BigDecimal.ZERO);
                writeHoldingRow(sheet.createRow(rowNum++), h, gbpRates, div,
                        dataText, dataNum, gainNum, lossNum, gainPct, lossPct);
            }

            if (II_SIPP.equals(src)) {
                Row ph = sheet.createRow(rowNum);
                String eRef = "E" + (rowNum + 1);
                styledCell(ph, COL_SECURITY_ID, "CASH", dataText);
                formulaCell(ph, COL_QUANTITY, eRef, dataNum);
                setNumeric(ph, COL_AVG_PRICE, BigDecimal.ONE, dataNum);
                formulaCell(ph, COL_MKT_VALUE, eRef, dataNum);
                formulaCell(ph, COL_MKT_VALUE_GBP, portfolioInputRef, linkedCell);
                styledCell(ph, COL_CURRENCY, "GBP", dataText);
                styledCell(ph, COL_SOURCE, II_SIPP, dataText);
                rowNum++;
            }
            ranges.put(src, new int[]{firstRow, rowNum - 1});
        }

        // Currency label row above subtotals
        Row currHdrRow = sheet.createRow(currHdrPoi);
        styledCell(currHdrRow, COL_MKT_VALUE, "USD - Value", boldText);
        styledCell(currHdrRow, COL_MKT_VALUE_GBP, "GBP - Value", boldText);
        styledCell(currHdrRow, COL_GAIN, "GBP - Gain", boldText);

        // Per-source subtotal rows
        List<Integer> subPoiRows = new ArrayList<>();
        int subPoi = firstSubPoi;
        for (Map.Entry<String, int[]> re : ranges.entrySet()) {
            String src = re.getKey();
            int fe = re.getValue()[0] + 1;
            int le = re.getValue()[1] + 1;
            int thisExcel = subPoi + 1;

            Row row = sheet.createRow(subPoi);
            subPoiRows.add(subPoi);
            styledCell(row, COL_SECURITY_ID, src + " — Total", boldText);

            Cell usd = row.createCell(COL_MKT_VALUE);
            usd.setCellFormula("Roth IRA".equals(src)
                    ? "SUM(D" + fe + ":D" + le + ")"
                    : "E" + thisExcel + "*" + gbpusdRef);
            usd.setCellStyle(boldNum);

            formulaCell(row, COL_MKT_VALUE_GBP, "SUM(E" + fe + ":E" + le + ")", boldNum);
            formulaCell(row, COL_GAIN, "SUM(F" + fe + ":F" + le + ")", boldNum);
            subPoi++;
        }

        // Grand total
        Row totalRow = sheet.createRow(grandTotalPoi);
        styledCell(totalRow, COL_SECURITY_ID, "PORTFOLIO TOTAL", boldText);
        formulaCell(totalRow, COL_MKT_VALUE,
                subPoiRows.stream().map(r -> "D" + (r + 1)).collect(Collectors.joining("+")), boldNum);
        formulaCell(totalRow, COL_MKT_VALUE_GBP,
                subPoiRows.stream().map(r -> "E" + (r + 1)).collect(Collectors.joining("+")), boldNum);
        formulaCell(totalRow, COL_GAIN,
                subPoiRows.stream().map(r -> "F" + (r + 1)).collect(Collectors.joining("+")), boldNum);

        // Total Cash row
        int lastDataExcel = numDataRows + 1;
        int totalCashExcel = totalCashPoi + 1;
        Row cashRow = sheet.createRow(totalCashPoi);
        styledCell(cashRow, COL_SECURITY_ID, "Total Cash", boldText);
        formulaCell(cashRow, COL_MKT_VALUE_GBP,
                "SUMIF($A$2:$A$" + lastDataExcel + ",\"CASH\",$E$2:$E$" + lastDataExcel + ")", boldNum);
        formulaCell(cashRow, COL_MKT_VALUE,
                "E" + totalCashExcel + "*" + gbpusdRef, boldNum);

        // Return All Investments row
        int gtExcel = grandTotalPoi + 1;
        Row rawReturnRow = sheet.createRow(returnPctPoi);
        styledCell(rawReturnRow, COL_SECURITY_ID, "Return All Investmentsƒ", boldText);
        Cell rawReturnCell = rawReturnRow.createCell(COL_GAIN_PCT);
        rawReturnCell.setCellFormula(
                "F" + gtExcel + "/(E" + gtExcel + "-E" + totalCashExcel + ")");
        rawReturnCell.setCellStyle(pctStyle(wb, true));

        // Return Equities row: non-bond gain / non-bond invested value
        String bondGainFormula = "SUMIF($A$2:$A$" + lastDataExcel + ",\"*%*\",$F$2:$F$" + lastDataExcel + ")";
        String bondValueFormula = "SUMIF($A$2:$A$" + lastDataExcel + ",\"*%*\",$E$2:$E$" + lastDataExcel + ")";
        Row rawReturnEquitiesRow = sheet.createRow(returnEquitiesPoi);
        styledCell(rawReturnEquitiesRow, COL_SECURITY_ID, "Return Equities", boldText);
        Cell rawReturnEquitiesCell = rawReturnEquitiesRow.createCell(COL_GAIN_PCT);
        rawReturnEquitiesCell.setCellFormula(
                "IFERROR((F" + gtExcel + "-" + bondGainFormula + ")/(E" + gtExcel + "-E" + totalCashExcel + "-" + bondValueFormula + "),\"\")");
        rawReturnEquitiesCell.setCellStyle(pctStyle(wb, true));

        // Return Fixed Income row: bond gain / bond value
        Row rawReturnBondsRow = sheet.createRow(returnBondsPoi);
        styledCell(rawReturnBondsRow, COL_SECURITY_ID, "Return Fixed Income", boldText);
        Cell rawReturnBondsCell = rawReturnBondsRow.createCell(COL_GAIN_PCT);
        rawReturnBondsCell.setCellFormula(
                "IFERROR(" + bondGainFormula + "/" + bondValueFormula + ",\"\")");
        rawReturnBondsCell.setCellStyle(pctStyle(wb, true));

        // Total Return (including cash) row
        Row rawTotalReturnRow = sheet.createRow(totalReturnPoi);
        styledCell(rawTotalReturnRow, COL_SECURITY_ID, "Total Return (including cash)", boldText);
        Cell rawTotalReturnCell = rawTotalReturnRow.createCell(COL_GAIN_PCT);
        rawTotalReturnCell.setCellFormula("F" + gtExcel + "/E" + gtExcel);
        rawTotalReturnCell.setCellStyle(pctStyle(wb, true));

        autoSizeColumns(sheet, NUM_COLS);
    }

    private static void writeHoldingRow(Row row, Holding h, Map<String, BigDecimal> gbpRates,
                                        BigDecimal dividendGbp,
                                        CellStyle textStyle, CellStyle numStyle,
                                        CellStyle gainNum, CellStyle lossNum,
                                        CellStyle gainPct, CellStyle lossPct) {
        styledCell(row, COL_SECURITY_ID, h.getSecurityId(), textStyle);
        setNumeric(row, COL_QUANTITY, h.getQuantity(), numStyle);
        setNumeric(row, COL_AVG_PRICE, h.getAvgPricePaid(), numStyle);
        setNumeric(row, COL_MKT_VALUE, h.getCurrentMarketValue(), numStyle);
        BigDecimal gbpVal = PortfolioAggregator.toGbp(h, gbpRates);
        setNumeric(row, COL_MKT_VALUE_GBP, gbpVal, numStyle);

        BigDecimal dividend = dividendGbp != null ? dividendGbp : BigDecimal.ZERO;
        setNumeric(row, COL_DIVIDEND, dividend, numStyle);

        BigDecimal costGbp = PortfolioAggregator.costInGbp(h, gbpRates);
        if (costGbp != null && gbpVal != null) {
            BigDecimal gain = gbpVal.subtract(costGbp);
            boolean gainPos = gain.signum() >= 0;
            setNumeric(row, COL_GAIN, gain, gainPos ? gainNum : lossNum);

            // Total Gain = price appreciation + dividends, computed in code (no Excel formula)
            BigDecimal totalGain = gain.add(dividend);
            boolean totalPos = totalGain.signum() >= 0;
            setNumeric(row, COL_TOTAL_GAIN, totalGain, totalPos ? gainNum : lossNum);

            if (costGbp.signum() != 0) {
                setPercent(row, COL_GAIN_PCT, gain.divide(costGbp, 10, RoundingMode.HALF_UP),
                        gainPos ? gainPct : lossPct);
                setPercent(row, COL_TOTAL_GAIN_PCT, totalGain.divide(costGbp, 10, RoundingMode.HALF_UP),
                        totalPos ? gainPct : lossPct);
            }
        }

        styledCell(row, COL_CURRENCY, h.getCurrency().getCurrencyCode(), textStyle);
        styledCell(row, COL_SOURCE, h.getSource(), textStyle);
    }

    // -------------------------------------------------------------------------
    // Portfolio Raw sheet
    // -------------------------------------------------------------------------

    private static void writeRawSheet(Path file, Sheet sheet) throws IOException {
        if (file.getFileName().toString().endsWith(".xlsx")) copyXlsxToSheet(file, sheet);
        else copyCsvToSheet(file, sheet);
    }

    private static void copyXlsxToSheet(Path file, Sheet target) throws IOException {
        try (Workbook src = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet srcSheet = src.getSheet("Holdings");
            if (srcSheet == null) srcSheet = src.getSheetAt(0);
            for (Row srcRow : srcSheet) {
                Row dest = target.createRow(srcRow.getRowNum());
                for (Cell srcCell : srcRow) {
                    Cell dc = dest.createCell(srcCell.getColumnIndex());
                    switch (srcCell.getCellType()) {
                        case STRING -> dc.setCellValue(srcCell.getStringCellValue());
                        case NUMERIC -> dc.setCellValue(srcCell.getNumericCellValue());
                        case BOOLEAN -> dc.setCellValue(srcCell.getBooleanCellValue());
                        default -> dc.setCellValue(srcCell.toString());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Raw input sheets
    // -------------------------------------------------------------------------

    private static void copyCsvToSheet(Path file, Sheet sheet) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);
        try (CSVParser csv = CSVParser.parse(content, CSVFormat.DEFAULT)) {
            int rn = 0;
            for (CSVRecord record : csv) {
                Row row = sheet.createRow(rn++);
                for (int i = 0; i < record.size(); i++) row.createCell(i).setCellValue(record.get(i));
            }
        }
    }

    private static void write(Workbook wb, Path outputPath) throws IOException {
        try (OutputStream os = Files.newOutputStream(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            wb.write(os);
        }
    }

    private static void setNumeric(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(2, RoundingMode.HALF_UP).doubleValue());
        cell.setCellStyle(style);
    }

    /**
     * Writes a percentage value held as a decimal fraction (0.1234 → displayed "12.34%");
     * the supplied style carries the "0.00%" number format.
     */
    private static void setPercent(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(4, RoundingMode.HALF_UP).doubleValue());
        cell.setCellStyle(style);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void setRate(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void styledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void formulaCell(Row row, int col, String formula, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellFormula(formula);
        if (style != null) cell.setCellStyle(style);
    }

    private static void autoSizeColumns(Sheet sheet, int numCols) {
        for (int i = 0; i < numCols; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1024);
        }
    }

    private static CellStyle boldTextStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static CellStyle numericStyle(Workbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
        }
        return s;
    }

    // ---- Cell styles ----

    private static CellStyle pctStyle(Workbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
        }
        return s;
    }

    private static CellStyle rateNumStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.0000"));
        return s;
    }

    private static CellStyle inputCellStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private static CellStyle linkedCellStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private static CellStyle gainLossNumStyle(Workbook wb, boolean isGain) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        XSSFFont f = (XSSFFont) wb.createFont();
        f.setColor(new XSSFColor(isGain ? GREEN : RED, null));
        s.setFont(f);
        return s;
    }

    private static CellStyle gainLossPctStyle(Workbook wb, boolean isGain) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        XSSFFont f = (XSSFFont) wb.createFont();
        f.setColor(new XSSFColor(isGain ? GREEN : RED, null));
        s.setFont(f);
        return s;
    }

    /**
     * Writes the full workbook: Portfolio (aggregated), Portfolio Raw, plus one raw-input
     * tab per source file.
     *
     * @param rawSources ordered map of sourceName → file path for the raw-input tabs
     */
    public void writeFullReport(Path outputPath,
                                List<AggHolding> aggregated,
                                List<Holding> holdings,
                                Map<String, Path> rawSources,
                                Map<String, BigDecimal> gbpRates,
                                BigDecimal iiSippCash,
                                Map<String, BigDecimal> dividendsBySymbol) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            String portfolioInputRef = writeAggregatedSheet(
                    wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, wb);
            writePortfolioSheet(
                    wb.createSheet("Portfolio Raw"), holdings, gbpRates, portfolioInputRef, dividendsBySymbol, wb);
            for (Map.Entry<String, Path> e : rawSources.entrySet()) {
                // A hand-entered account contributes holdings but no file, so there is no raw
                // sheet to copy — it is named in the Portfolio sheet's Source column instead.
                if (e.getValue() == null) continue;
                writeRawSheet(e.getValue(), wb.createSheet(e.getKey()));
            }
            wb.setForceFormulaRecalculation(true);   // else formula cells (incl. Portfolio %) open blank
            write(wb, outputPath);
        }
    }

    /**
     * Writes a standalone summary workbook containing only the aggregated Portfolio sheet.
     */
    public void writeSummaryReport(Path outputPath,
                                   List<AggHolding> aggregated,
                                   Map<String, BigDecimal> gbpRates,
                                   BigDecimal iiSippCash) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writeAggregatedSheet(wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, wb);
            wb.setForceFormulaRecalculation(true);   // else formula cells (incl. Portfolio %) open blank
            write(wb, outputPath);
        }
    }

    // -------------------------------------------------------------------------
    // Open orders workbook (bytes; the caller writes them to the output dir)
    // -------------------------------------------------------------------------

    /** Single-sheet workbook of the given open orders, returned as bytes for a direct download. */
    public byte[] ordersWorkbook(List<OpenOrder> orders) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            writeOrdersSheet(wb.createSheet("Open Orders"), orders, wb);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static void writeOrdersSheet(Sheet sheet, List<OpenOrder> orders, Workbook wb) {
        CellStyle boldText = boldTextStyle(wb);
        CellStyle text = wb.createCellStyle();
        CellStyle qtyNum = numericStyle(wb, false);
        Map<String, CellStyle> moneyStyles = new HashMap<>();   // reuse one style per number format

        CellStyle pctStyle = wb.createCellStyle();
        pctStyle.setDataFormat(wb.createDataFormat().getFormat("+0.00%;-0.00%"));
        CellStyle volPctStyle = wb.createCellStyle();       // RV10 / RV30 — unsigned daily σ
        volPctStyle.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        CellStyle touchPctStyle = wb.createCellStyle();      // touch probability
        touchPctStyle.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
        CellStyle sigmaStyle = wb.createCellStyle();         // signed σ distance to limit
        sigmaStyle.setDataFormat(wb.createDataFormat().getFormat("+0.00;-0.00"));

        // "Quote" sits beside Current rather than being folded into it: the price stays a real
        // number the sheet can sort and chart, while the caveat that it came from a thin
        // extended-hours session travels with the file instead of only living on screen.
        // The horizon is carried in sessions, not days: √sessions is what the σ scaling actually
        // consumed, so the sheet states the number the probability beside it was computed over. The
        // calendar view of the same thing is the Expiry column at the end.
        // P(low)/P(high) bracket the headline with the same probability computed off RV10 and RV30,
        // and σ eff / Vol source say which forecast produced it — a probability read months later
        // is only judgeable against the model that made it, so the sheet carries both.
        String[] headers = {"Broker", "Symbol", "Side", "Type", "Quantity",
                "Price", "Current", "Quote", "Vs limit", "RV10", "RV30", "σ→lim (5d)", "P(touch 5d)",
                "Horizon (sessions)", "Horizon from", "P(touch horizon)", "P(low)", "P(high)",
                "σ eff", "σ eff (diffusive)", "Vol source", "Drift (annual)", "Events in horizon",
                "Amount", "Amount GBP", "Date entered", "Expiry"};
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);

        int rowNum = 1;
        for (OpenOrder o : orders) {
            Row row = sheet.createRow(rowNum++);
            styledCell(row, 0, o.broker(), text);
            styledCell(row, 1, dashIfNull(o.symbol()), text);
            styledCell(row, 2, dashIfNull(o.side()), text);
            styledCell(row, 3, dashIfNull(o.orderType()), text);
            if (o.quantity() != null) setNumeric(row, 4, o.quantity(), qtyNum);
            if (o.price() != null) setMoney(row, 5, o.price(), o.currency(), o.priceEstimated(), wb, moneyStyles);
            if (o.currentPrice() != null) setMoney(row, 6, o.currentPrice(), o.currency(), false, wb, moneyStyles);
            styledCell(row, 7, quoteSessionCell(o), text);
            if (o.distancePct() != null) setStyled(row, 8, o.distancePct(), pctStyle);
            if (o.dailyVol10() != null) setStyled(row, 9, o.dailyVol10(), volPctStyle);
            if (o.dailyVol30() != null) setStyled(row, 10, o.dailyVol30(), volPctStyle);
            if (o.sigmaToLimit() != null) setStyled(row, 11, o.sigmaToLimit(), sigmaStyle);
            if (o.touchProb5d() != null) setStyled(row, 12, o.touchProb5d(), touchPctStyle);
            if (o.horizonSessions() != null) setNumeric(row, 13, BigDecimal.valueOf(o.horizonSessions()), qtyNum);
            styledCell(row, 14, o.horizonSessions() == null ? "" : (o.horizonFromExpiry() ? "expiry" : "GTC default"), text);
            if (o.touchProbHorizon() != null) setStyled(row, 15, o.touchProbHorizon(), touchPctStyle);
            if (o.touchProbLow() != null) setStyled(row, 16, o.touchProbLow(), touchPctStyle);
            if (o.touchProbHigh() != null) setStyled(row, 17, o.touchProbHigh(), touchPctStyle);
            if (o.sigmaEff() != null) setStyled(row, 18, o.sigmaEff(), volPctStyle);
            // The diffusive σ beside the one actually used: the gap between them is exactly what the
            // calendar contributed, and it is the only way to see afterwards how much of a
            // probability came from a scheduled print rather than from the trailing windows.
            if (o.sigmaEffDiffusive() != null) setStyled(row, 19, o.sigmaEffDiffusive(), volPctStyle);
            styledCell(row, 20, dashIfNull(o.volSource()), text);
            // Annualised back up from the per-session figure the model consumed (×252), because a
            // reader comparing this file against a policy ("we assume 6% for equities") is thinking
            // in years, and 0.000238 is the same number in a unit nobody states.
            if (o.touchProbHorizon() != null) setStyled(row, 21, o.driftAnnual(), volPctStyle);
            if (o.touchProbHorizon() != null) {
                setNumeric(row, 22, BigDecimal.valueOf(o.eventCount()), qtyNum);
            }
            if (o.amount() != null) setMoney(row, 23, o.amount(), o.currency(), false, wb, moneyStyles);
            if (o.amountGbp() != null) setMoney(row, 24, o.amountGbp(), "GBP", false, wb, moneyStyles);
            styledCell(row, 25, o.dateEntered() != null ? o.dateEntered().toString() : "", text);
            String expiry = o.expiry() != null ? o.expiry().toString()
                    : (o.duration() != null ? o.duration() : "");
            styledCell(row, 26, expiry, text);
        }
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, orders.size()), 0, headers.length - 1));
        autoSizeColumns(sheet, headers.length);
    }

    private static String dashIfNull(String s) {
        return s != null ? s : "—";
    }


    /** Session label for the Current price — blank for an ordinary regular-hours quote or close. */
    private static String quoteSessionCell(OpenOrder o) {
        return switch (o.quoteSessionLabel()) {
            case "PRE" -> "pre-market";
            case "AH" -> "after-hours";
            default -> "";
        };
    }

    /** Numeric cell carrying a raw fraction/number, formatted by a caller-supplied style. */
    private static void setStyled(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    /**
     * Numeric money cell formatted with the currency symbol via the cell's number format (so a
     * mixed-currency sheet reads naturally and the value stays a real number). Estimated prices
     * (II) get a literal "~" prefix in the format — matching the on-screen convention — while the
     * underlying value stays numeric.
     */
    private static void setMoney(Row row, int col, BigDecimal value, String currency,
                                 boolean estimated, Workbook wb, Map<String, CellStyle> cache) {
        String prefix = (estimated ? "~" : "") + currencySymbolFor(currency);
        String fmt = prefix.isEmpty() ? "#,##0.00" : "\"" + prefix + "\"#,##0.00";
        CellStyle style = cache.computeIfAbsent(fmt, f -> {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(wb.createDataFormat().getFormat(f));
            return s;
        });
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(4, RoundingMode.HALF_UP).doubleValue());
        cell.setCellStyle(style);
    }

    private static String currencySymbolFor(String ccy) {
        if (ccy == null) return "";
        return switch (ccy) {
            case "GBP", "GBp" -> "£";
            case "USD" -> "$";
            case "EUR" -> "€";
            default -> "";
        };
    }

    // -------------------------------------------------------------------------
    // Cash-ledger workbook (Holdings → From cash ledger tab)
    // -------------------------------------------------------------------------

    private static final int LED_ID = 0;          // A
    private static final int LED_CCY = 1;         // B
    private static final int LED_QTY = 2;         // C
    private static final int LED_PRICE = 3;       // D  latest quote, native currency
    private static final int LED_VALUE = 4;       // E  Market Value £ (the single combined value column)
    private static final int LED_PCT = 5;         // F  Portfolio %
    private static final int LED_GAIN = 6;        // G
    private static final int LED_GAINPCT = 7;     // H
    private static final int LED_DIV = 8;         // I
    private static final int LED_TOTAL_GAIN = 9;  // J
    private static final int LED_TOTAL_GAINPCT = 10; // K
    private static final int LED_IRR = 11;        // L  annualised return (IRR), or period return
    private static final int LED_IRR_BASIS = 12;  // M  which of the two the cell above is
    private static final int LED_IRR_YEARS = 13;  // N  cost-weighted holding period
    private static final int LED_IRR_NATIVE = 14; // O  same rate in the listing currency
    private static final int LED_IRR_FX = 15;     // P  GBP rate − native rate
    private static final int LED_SOURCES = 16;    // Q
    private static final int LED_COLS = 17;

    /**
     * Single-sheet workbook of the cash-ledger-derived portfolio, as bytes for the caller to write.
     *
     * <p>Mirrors the on-screen table rather than reusing the aggregated Portfolio sheet, for the
     * same reason the two views differ on screen: this one has a single combined value column and
     * — the load-bearing part — <b>no II SIPP cash input cell</b>. Ledger cash comes from the
     * stored per-{@code (account, currency)} balances, so the yellow editable cell the holdings
     * workbook carries would be added on top of cash that is already in the rows.
     *
     * <p>Totals are live formulas over the rows (the cash line is a {@code SUMIF} on the CASH
     * symbol, since ledger cash rows are interleaved by currency section rather than contiguous),
     * so a reader who filters or deletes rows still gets a total that adds up — the same bargain
     * the Portfolio sheet strikes.
     */
    public byte[] ledgerWorkbook(List<AggHolding> rows, PortfolioMetrics.Totals totals)
            throws IOException {
        return ledgerWorkbook(rows, totals, Map.of());
    }

    /**
     * As above, with the per-symbol IRR the cash-ledger table shows. The rate travels as four
     * columns rather than one string because a sheet has nowhere to put a tooltip: the figure
     * stays a real number the sheet can sort and chart, and {@code Return basis} carries the
     * caveat (annualised vs a sub-90-day period return) beside it — the same convention the
     * orders sheet uses for the PRE/AH quote badge.
     */
    public byte[] ledgerWorkbook(List<AggHolding> rows, PortfolioMetrics.Totals totals,
                                 Map<String, PositionIrr.Result> irrBySymbol) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            writeLedgerSheet(wb.createSheet("Portfolio (ledger)"), rows, totals, wb,
                    irrBySymbol == null ? Map.of() : irrBySymbol);
            wb.setForceFormulaRecalculation(true);   // else every formula cell opens blank
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static void writeLedgerSheet(Sheet sheet, List<AggHolding> rows,
                                         PortfolioMetrics.Totals totals, Workbook wb,
                                         Map<String, PositionIrr.Result> irrBySymbol) {
        CellStyle text = wb.createCellStyle();
        CellStyle boldText = boldTextStyle(wb);
        CellStyle num = numericStyle(wb, false);
        CellStyle boldNum = numericStyle(wb, true);
        CellStyle pct = pctStyle(wb, false);
        CellStyle boldPct = pctStyle(wb, true);
        CellStyle priceNum = wb.createCellStyle();
        priceNum.setDataFormat(wb.createDataFormat().getFormat("#,##0.0000"));
        // One style per sign, created up front: gainLossNumStyle mints a new CellStyle per call,
        // and a hundred-row book would otherwise carry four hundred near-identical styles.
        CellStyle gainNum = gainLossNumStyle(wb, true);
        CellStyle lossNum = gainLossNumStyle(wb, false);
        CellStyle gainPct = gainLossPctStyle(wb, true);
        CellStyle lossPct = gainLossPctStyle(wb, false);

        CellStyle yearsNum = wb.createCellStyle();
        yearsNum.setDataFormat(wb.createDataFormat().getFormat("0.00"));

        String[] headers = {"Security", "Ccy", "Quantity", "Price", "Market Value £", "Portfolio %",
                "Gain (£)", "Gain %", "Dividends (£)", "Total Gain (£)", "Total Gain %",
                "Annualised return", "Return basis", "Holding period (yr)", "Native return",
                "Currency effect", "Sources"};
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rows.size()), 0, LED_COLS - 1));

        int firstExcel = 2;
        int lastExcel = rows.size() + 1;
        int totalPoi = rows.size() + 2;
        String totalRef = "$E$" + (totalPoi + 1);

        int rowNum = 1;
        for (AggHolding h : rows) {
            Row row = sheet.createRow(rowNum++);
            int excel = row.getRowNum() + 1;
            styledCell(row, LED_ID, h.securityId(), text);
            styledCell(row, LED_CCY, h.currencyCode(), text);
            setNumeric(row, LED_QTY, h.quantity(), num);
            if (h.latestPrice() != null) setStyled(row, LED_PRICE, h.latestPrice(), priceNum);
            setNumeric(row, LED_VALUE, h.marketValueGbp(), num);
            formulaCell(row, LED_PCT, "IFERROR(E" + excel + "/" + totalRef + ",\"\")", pct);
            if (h.gainGbp() != null) {
                setNumeric(row, LED_GAIN, h.gainGbp(), h.gainGbp().signum() >= 0 ? gainNum : lossNum);
            }
            if (h.gainPct() != null) {
                setPercent(row, LED_GAINPCT, h.gainPct(), h.gainPct().signum() >= 0 ? gainPct : lossPct);
            }
            setNumeric(row, LED_DIV, h.dividendGbp(), num);
            if (h.totalGainGbp() != null) {
                setNumeric(row, LED_TOTAL_GAIN, h.totalGainGbp(),
                        h.totalGainGbp().signum() >= 0 ? gainNum : lossNum);
            }
            if (h.totalGainPct() != null) {
                setPercent(row, LED_TOTAL_GAINPCT, h.totalGainPct(),
                        h.totalGainPct().signum() >= 0 ? gainPct : lossPct);
            }
            writeLedgerIrr(row, irrBySymbol.get(h.securityId()), gainPct, lossPct, text, yearsNum);
            styledCell(row, LED_SOURCES, h.sources(), text);
        }

        // Totals block — the same five figures the on-screen tiles show, in the same order.
        // The IRR columns get no total: rates over different flow series do not sum, and a
        // portfolio-level money-weighted return is a different calculation over different
        // flows (Performance → Returns computes it).
        Row total = sheet.createRow(totalPoi);
        styledCell(total, LED_ID, "TOTAL", boldText);
        Row cash = sheet.createRow(totalPoi + 1);
        styledCell(cash, LED_ID, "Total Cash", boldText);
        Row investedReturn = sheet.createRow(totalPoi + 2);
        styledCell(investedReturn, LED_ID, "Return on invested", boldText);
        Row totalReturn = sheet.createRow(totalPoi + 3);
        styledCell(totalReturn, LED_ID, "Return on total", boldText);

        if (rows.isEmpty()) {
            // No rows to sum: a SUM over an empty range is a #REF!, so state the figures we hold.
            if (totals != null) {
                setNumeric(total, LED_VALUE, totals.totalGbp(), boldNum);
                setNumeric(total, LED_GAIN, totals.totalGainGbp(), boldNum);
                setNumeric(cash, LED_VALUE, totals.totalCashGbp(), boldNum);
                setPercent(investedReturn, LED_GAINPCT, totals.returnPct(), boldPct);
                setPercent(totalReturn, LED_GAINPCT, totals.totalReturn(), boldPct);
            }
            autoSizeColumns(sheet, LED_COLS);
            return;
        }

        int t = totalPoi + 1;
        int c = totalPoi + 2;
        formulaCell(total, LED_VALUE, "SUM(E" + firstExcel + ":E" + lastExcel + ")", boldNum);
        formulaCell(total, LED_GAIN, "SUM(G" + firstExcel + ":G" + lastExcel + ")", boldNum);
        formulaCell(total, LED_DIV, "SUM(I" + firstExcel + ":I" + lastExcel + ")", boldNum);
        formulaCell(total, LED_TOTAL_GAIN, "SUM(J" + firstExcel + ":J" + lastExcel + ")", boldNum);
        formulaCell(cash, LED_VALUE,
                "SUMIF(A" + firstExcel + ":A" + lastExcel + ",\"CASH\",E" + firstExcel + ":E" + lastExcel + ")",
                boldNum);
        formulaCell(investedReturn, LED_GAINPCT,
                "IFERROR(G" + t + "/(E" + t + "-E" + c + "),\"\")", boldPct);
        formulaCell(totalReturn, LED_GAINPCT, "IFERROR(G" + t + "/E" + t + ",\"\")", boldPct);

        autoSizeColumns(sheet, LED_COLS);
    }

    /**
     * The four IRR cells for one ledger row. A position with no solvable rate writes nothing
     * at all rather than a zero — an empty cell reads as "not computed", a 0.00% reads as
     * "broke even", and those are opposite claims.
     */
    private static void writeLedgerIrr(Row row, PositionIrr.Result irr,
                                       CellStyle gainPct, CellStyle lossPct,
                                       CellStyle text, CellStyle yearsNum) {
        if (irr == null) return;
        BigDecimal rate = irr.displayReturn();
        if (rate != null) {
            setPercent(row, LED_IRR, rate, rate.signum() >= 0 ? gainPct : lossPct);
            styledCell(row, LED_IRR_BASIS, irr.annualised()
                    ? (irr.reentered() ? "Annualised (current episode)" : "Annualised")
                    : "Period return, " + irr.episodeDays() + "d", text);
        } else {
            styledCell(row, LED_IRR_BASIS, "No rate", text);
        }
        if (irr.holdingYears() != null) {
            setStyled(row, LED_IRR_YEARS, irr.holdingYears(), yearsNum);
        }
        if (irr.nativeAnnualisedReturn() != null) {
            BigDecimal n = irr.nativeAnnualisedReturn();
            setPercent(row, LED_IRR_NATIVE, n, n.signum() >= 0 ? gainPct : lossPct);
            BigDecimal fx = irr.currencyContribution();
            if (fx != null) setPercent(row, LED_IRR_FX, fx, fx.signum() >= 0 ? gainPct : lossPct);
        }
    }

    // -------------------------------------------------------------------------
    // Live positions workbook (Holdings → Live tab)
    // -------------------------------------------------------------------------

    private static final int LIVE_SYMBOL = 0;    // A
    private static final int LIVE_CCY = 1;       // B
    private static final int LIVE_QTY = 2;       // C
    private static final int LIVE_LAST = 3;      // D
    private static final int LIVE_QUOTE = 4;     // E  session the quote came from
    private static final int LIVE_MARKET = 5;    // F  what that listing's market is doing now
    private static final int LIVE_DAY_PCT = 6;   // G
    private static final int LIVE_DAY_GBP = 7;   // H
    private static final int LIVE_5D = 8;        // I
    private static final int LIVE_10D = 9;       // J
    private static final int LIVE_30D = 10;      // K
    private static final int LIVE_3M = 11;       // L
    private static final int LIVE_6M = 12;       // M
    private static final int LIVE_POSITION = 13; // N
    private static final int LIVE_ACCOUNTS = 14; // O
    private static final int LIVE_TS = 15;       // P
    private static final int LIVE_COLS = 16;

    private static final DateTimeFormatter QUOTE_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Europe/London"));

    /**
     * Single-sheet workbook of the Live tab's positions, as bytes for the caller to write.
     *
     * <p>Two things the screen says with colour become their own columns, because a spreadsheet
     * has nowhere else to put them: <b>Quote</b> carries the PRE / AH badge (an extended-hours
     * print is a thin one, and a sheet that folded the caveat into the price would lose it), and
     * <b>Market</b> carries the state that greys a closed listing's row. Both stay beside the
     * price rather than inside it, so Last remains a real number the sheet can sort and chart.
     */
    public byte[] liveWorkbook(LivePricesService.Snapshot snapshot) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            writeLiveSheet(wb.createSheet("Live Positions"), snapshot, wb);
            wb.setForceFormulaRecalculation(true);   // else the totals block opens blank
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static void writeLiveSheet(Sheet sheet, LivePricesService.Snapshot snapshot, Workbook wb) {
        CellStyle text = wb.createCellStyle();
        CellStyle boldText = boldTextStyle(wb);
        CellStyle num = numericStyle(wb, false);
        CellStyle boldNum = numericStyle(wb, true);
        CellStyle priceNum = wb.createCellStyle();
        priceNum.setDataFormat(wb.createDataFormat().getFormat("#,##0.0000"));
        CellStyle signedPct = wb.createCellStyle();
        signedPct.setDataFormat(wb.createDataFormat().getFormat("+0.00%;-0.00%"));
        CellStyle boldSignedPct = wb.createCellStyle();
        boldSignedPct.setDataFormat(wb.createDataFormat().getFormat("+0.00%;-0.00%"));
        Font bold = wb.createFont();
        bold.setBold(true);
        boldSignedPct.setFont(bold);

        String[] headers = {"Symbol", "Ccy", "Quantity", "Last", "Quote", "Market",
                "Day Δ %", "Day Δ £", "5d %", "10d %", "30d %", "3M %", "6M %",
                "Position £", "Accounts", "Quote ts"};
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);

        List<LivePricesService.Row> rows = snapshot.rows();
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rows.size()), 0, LIVE_COLS - 1));

        int rowNum = 1;
        for (LivePricesService.Row r : rows) {
            Row row = sheet.createRow(rowNum++);
            styledCell(row, LIVE_SYMBOL, r.symbol(), text);
            styledCell(row, LIVE_CCY, dashIfNull(r.currency()), text);
            setNumeric(row, LIVE_QTY, r.quantity(), num);
            if (r.lastPrice() != null) setStyled(row, LIVE_LAST, r.lastPrice(), priceNum);
            styledCell(row, LIVE_QUOTE, sessionCell(r.session()), text);
            styledCell(row, LIVE_MARKET, marketStateCell(r.marketState()), text);
            if (r.dayChangePct() != null) setStyled(row, LIVE_DAY_PCT, r.dayChangePct(), signedPct);
            setNumeric(row, LIVE_DAY_GBP, r.dayChangeGbp(), num);
            if (r.change5dPct() != null) setStyled(row, LIVE_5D, r.change5dPct(), signedPct);
            if (r.change10dPct() != null) setStyled(row, LIVE_10D, r.change10dPct(), signedPct);
            if (r.monthChangePct() != null) setStyled(row, LIVE_30D, r.monthChangePct(), signedPct);
            if (r.change3mPct() != null) setStyled(row, LIVE_3M, r.change3mPct(), signedPct);
            if (r.change6mPct() != null) setStyled(row, LIVE_6M, r.change6mPct(), signedPct);
            setNumeric(row, LIVE_POSITION, r.positionGbp(), num);
            styledCell(row, LIVE_ACCOUNTS, dashIfNull(r.accounts()), text);
            styledCell(row, LIVE_TS, r.priceTs() != null ? QUOTE_TS_FMT.format(r.priceTs()) : "", text);
        }

        LivePricesService.Totals totals = snapshot.totals();
        int positionsPoi = rows.size() + 2;
        Row positions = sheet.createRow(positionsPoi);
        styledCell(positions, LIVE_SYMBOL, "Positions", boldText);
        Row cash = sheet.createRow(positionsPoi + 1);
        styledCell(cash, LIVE_SYMBOL, "Cash", boldText);
        Row total = sheet.createRow(positionsPoi + 2);
        styledCell(total, LIVE_SYMBOL, "TOTAL", boldText);
        Row day = sheet.createRow(positionsPoi + 3);
        styledCell(day, LIVE_SYMBOL, "Day change", boldText);

        if (totals != null) {
            setNumeric(cash, LIVE_POSITION, totals.cashGbp(), boldNum);
            if (totals.dayChangePct() != null) {
                setStyled(day, LIVE_DAY_PCT, totals.dayChangePct(), boldSignedPct);
            }
        }
        if (rows.isEmpty()) {
            // SUM over an empty range is a #REF! — state what the snapshot holds instead.
            if (totals != null) {
                setNumeric(positions, LIVE_POSITION, totals.positionsGbp(), boldNum);
                setNumeric(total, LIVE_POSITION, totals.totalGbp(), boldNum);
                setNumeric(day, LIVE_DAY_GBP, totals.dayChangeGbp(), boldNum);
            }
        } else {
            int first = 2;
            int last = rows.size() + 1;
            formulaCell(positions, LIVE_POSITION, "SUM(N" + first + ":N" + last + ")", boldNum);
            formulaCell(total, LIVE_POSITION,
                    "N" + (positionsPoi + 1) + "+N" + (positionsPoi + 2), boldNum);
            formulaCell(day, LIVE_DAY_GBP, "SUM(H" + first + ":H" + last + ")", boldNum);
        }

        autoSizeColumns(sheet, LIVE_COLS);
    }

    /** PRE / POST → the words the panel's legend uses; a regular quote needs no label. */
    private static String sessionCell(String session) {
        if (session == null) return "";
        return switch (session) {
            case "PRE" -> "pre-market";
            case "POST" -> "after-hours";
            default -> "";
        };
    }

    /** The state that greys a row on screen, spelled out for a sheet that has no row tint. */
    private static String marketStateCell(String state) {
        if (state == null) return "";
        return switch (state) {
            case "OPEN" -> "open";
            case "PRE" -> "pre-market";
            case "POST" -> "after-hours";
            case "CLOSED" -> "closed";
            default -> "";
        };
    }
}
