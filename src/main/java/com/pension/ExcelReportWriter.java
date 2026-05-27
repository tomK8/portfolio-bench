package com.pension;

import com.pension.domain.PortfolioAggregator;
import com.pension.domain.model.AggHolding;
import com.pension.domain.model.Holding;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
    private static final int COL_GAIN = 5;  // F  Gain (£)
    private static final int COL_GAIN_PCT = 6;  // G  Gain/Loss %
    private static final int COL_TOTAL_GAIN_PCT = 7;  // H  Total Gain/Loss % (incl. dividends)
    private static final int COL_CURRENCY = 8;  // I
    private static final int COL_SOURCE = 9;  // J
    private static final int NUM_COLS = 10;

    // ---- Aggregated Portfolio sheet columns ----
    private static final int AGG_ID = 0;  // A
    private static final int AGG_QTY = 1;  // B
    private static final int AGG_AVG = 2;  // C
    private static final int AGG_CCY = 3;  // D  Exchange Currency
    private static final int AGG_MKTGBP = 4;  // E  Market Value GBP
    private static final int AGG_GAIN = 5;  // F  Gain (£)
    private static final int AGG_GAINPCT = 6;  // G  Gain/Loss %
    private static final int AGG_TOTAL_GAIN_PCT = 7;  // H  Total Gain/Loss % (incl. dividends)
    private static final int AGG_SOURCES = 8;  // I
    private static final int AGG_COLS = 9;

    private static final byte[] GREEN = {(byte) 0, (byte) 176, (byte) 80};
    private static final byte[] RED = {(byte) 255, (byte) 0, (byte) 0};

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private static String writeAggregatedSheet(Sheet sheet, List<AggHolding> rows,
                                               Map<String, BigDecimal> gbpRates,
                                               BigDecimal iiSippCash,
                                               Map<String, BigDecimal> dividendsBySymbol,
                                               Workbook wb) {
        CellStyle dataText = wb.createCellStyle();
        CellStyle dataNum = numericStyle(wb, false);
        CellStyle boldText = boldTextStyle(wb);
        CellStyle boldNum = numericStyle(wb, true);
        CellStyle inputCell = inputCellStyle(wb);

        List<AggHolding> equities = rows.stream().filter(h -> !"CASH".equals(h.securityId())).collect(Collectors.toList());
        List<AggHolding> cashList = rows.stream().filter(h -> "CASH".equals(h.securityId())).collect(Collectors.toList());
        boolean hasCashGbp = cashList.stream().anyMatch(h -> "GBP".equals(h.currency().getCurrencyCode()));

        int nEquity = equities.size();
        int nBond = (int) equities.stream().filter(h -> PortfolioAggregator.isBond(h.securityId())).count();
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
                "Market Value GBP", "Gain (£)", "Gain/Loss %", "Total Gain/Loss %", "Sources"};
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);
        sheet.setAutoFilter(new CellRangeAddress(0, nEquity, 0, AGG_COLS - 1));

        // Equity rows
        int rowNum = 1;
        for (AggHolding h : equities) {
            BigDecimal div = dividendsBySymbol.getOrDefault(h.securityId().toUpperCase(), BigDecimal.ZERO);
            writeAggRow(sheet.createRow(rowNum++), h, div, dataText, dataNum, wb);
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
        }
        if (!wroteGbpCash) {
            Row row = sheet.createRow(rowNum);
            styledCell(row, AGG_ID, "CASH", dataText);
            styledCell(row, AGG_CCY, "GBP", dataText);
            formulaCell(row, AGG_QTY, inputCellRef, dataNum);
            setNumeric(row, AGG_AVG, BigDecimal.ONE, dataNum);
            formulaCell(row, AGG_MKTGBP, inputCellRef, dataNum);
            styledCell(row, AGG_SOURCES, II_SIPP, dataText);
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

    private static void writeAggRow(Row row, AggHolding h, BigDecimal dividendGbp,
                                    CellStyle textStyle, CellStyle numStyle, Workbook wb) {
        styledCell(row, AGG_ID, h.securityId(), textStyle);
        setNumeric(row, AGG_QTY, h.quantity(), numStyle);
        setNumeric(row, AGG_AVG, h.avgPricePaid(), numStyle);
        styledCell(row, AGG_CCY, h.currency().getCurrencyCode(), textStyle);
        setNumeric(row, AGG_MKTGBP, h.marketValueGbp(), numStyle);
        if (h.gainGbp() != null) {
            boolean g = h.gainGbp().compareTo(BigDecimal.ZERO) >= 0;
            setNumeric(row, AGG_GAIN, h.gainGbp(), gainLossNumStyle(wb, g));
        }
        if (h.gainPct() != null) {
            boolean g = h.gainPct().compareTo(BigDecimal.ZERO) >= 0;
            Cell c = row.createCell(AGG_GAINPCT);
            c.setCellValue(h.gainPct().setScale(4, RoundingMode.HALF_UP).doubleValue());
            c.setCellStyle(gainLossPctStyle(wb, g));
        }
        // Total Gain/Loss % = (gain + dividends) / cost, where cost = market_value - gain
        int r = row.getRowNum() + 1;
        String divStr = (dividendGbp != null ? dividendGbp : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        Cell tc = row.createCell(AGG_TOTAL_GAIN_PCT);
        tc.setCellFormula("IFERROR((F" + r + "+" + divStr + ")/(E" + r + "-F" + r + "),\"\")");
        tc.setCellStyle(pctStyle(wb, false));
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
                "Total Gain/Loss %", "Currency", "Source"};
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

        BigDecimal costGbp = PortfolioAggregator.costInGbp(h, gbpRates);
        if (costGbp != null && gbpVal != null) {
            BigDecimal g = gbpVal.subtract(costGbp);
            boolean pos = g.compareTo(BigDecimal.ZERO) >= 0;
            setNumeric(row, COL_GAIN, g, pos ? gainNum : lossNum);
            if (costGbp.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal pct = g.divide(costGbp, 10, RoundingMode.HALF_UP);
                Cell c = row.createCell(COL_GAIN_PCT);
                c.setCellValue(pct.setScale(4, RoundingMode.HALF_UP).doubleValue());
                c.setCellStyle(pos ? gainPct : lossPct);

                int r = row.getRowNum() + 1;
                String divStr = (dividendGbp != null ? dividendGbp : BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP).toPlainString();
                Cell tc = row.createCell(COL_TOTAL_GAIN_PCT);
                tc.setCellFormula("IFERROR((F" + r + "+" + divStr + ")/(E" + r + "-F" + r + "),\"\")");
                tc.setCellStyle(pos ? gainPct : lossPct);
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
                    wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, dividendsBySymbol, wb);
            writePortfolioSheet(
                    wb.createSheet("Portfolio Raw"), holdings, gbpRates, portfolioInputRef, dividendsBySymbol, wb);
            for (Map.Entry<String, Path> e : rawSources.entrySet())
                writeRawSheet(e.getValue(), wb.createSheet(e.getKey()));
            write(wb, outputPath);
        }
    }

    /**
     * Writes a standalone summary workbook containing only the aggregated Portfolio sheet.
     */
    public void writeSummaryReport(Path outputPath,
                                   List<AggHolding> aggregated,
                                   Map<String, BigDecimal> gbpRates,
                                   BigDecimal iiSippCash,
                                   Map<String, BigDecimal> dividendsBySymbol) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writeAggregatedSheet(
                    wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, dividendsBySymbol, wb);
            write(wb, outputPath);
        }
    }
}
