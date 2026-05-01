package com.pension;

import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.IISippParser;
import com.pension.parser.RothIraParser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {

    private static final Path INPUT_DIR  = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), "Documents");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String FX_URL =
            "https://api.frankfurter.dev/v1/latest?from=GBP&to=USD,EUR";

    // Portfolio sheet column indices (A–G)
    private static final int COL_SECURITY_ID   = 0;
    private static final int COL_QUANTITY       = 1;
    private static final int COL_AVG_PRICE      = 2;
    private static final int COL_MKT_VALUE      = 3;   // D – native currency in data rows; USD in total rows
    private static final int COL_MKT_VALUE_GBP  = 4;   // E – GBP throughout
    private static final int COL_CURRENCY       = 5;
    private static final int COL_SOURCE         = 6;
    private static final int NUM_COLS           = 7;

    private static final String II_SIPP = "II SIPP";

    private static final List<AccountParser> PARSERS = List.of(
            new RothIraParser(),
            new AJBellSippParser(),
            new IISippParser()
    );

    private record SourceFile(AccountParser parser, Path file) {}

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("Input directory not found: " + INPUT_DIR);
            return;
        }

        List<SourceFile> sources = new ArrayList<>();
        List<Holding> holdings   = new ArrayList<>();

        for (AccountParser parser : PARSERS) {
            Optional<Path> file = findMostRecent(INPUT_DIR, parser);
            if (file.isPresent()) {
                System.out.println("Parsing: " + file.get().getFileName());
                holdings.addAll(parser.parse(file.get()));
                sources.add(new SourceFile(parser, file.get()));
            }
        }

        if (holdings.isEmpty()) {
            System.out.println("No holdings found — check that input files are present in " + INPUT_DIR);
            return;
        }

        Map<String, BigDecimal> gbpRates = fetchGbpRates();
        System.out.println("FX rates (per 1 GBP): " + gbpRates);

        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        Path output = OUTPUT_DIR.resolve("portfolio" + timestamp + ".xlsx");

        writeExcel(holdings, gbpRates, sources, output);
        System.out.println("Written " + holdings.size() + " holdings to: " + output);
    }

    // -------------------------------------------------------------------------
    // File discovery
    // -------------------------------------------------------------------------

    private static Optional<Path> findMostRecent(Path dir, AccountParser parser) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(parser::supports)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        }
    }

    // -------------------------------------------------------------------------
    // FX rates
    // -------------------------------------------------------------------------

    private static Map<String, BigDecimal> fetchGbpRates() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(FX_URL)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("GBP", BigDecimal.ONE);
        Matcher m = Pattern.compile("\"([A-Z]{3})\":(\\d+\\.\\d+)").matcher(response.body());
        while (m.find()) rates.put(m.group(1), new BigDecimal(m.group(2)));
        return rates;
    }

    // -------------------------------------------------------------------------
    // Excel output
    // -------------------------------------------------------------------------

    private static void writeExcel(List<Holding> holdings, Map<String, BigDecimal> gbpRates,
                                   List<SourceFile> sources, Path output) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writePortfolioSheet(wb.createSheet("Portfolio"), holdings, gbpRates, wb);
            for (SourceFile sf : sources) {
                writeRawSheet(sf.file(), wb.createSheet(sf.parser().sourceName()));
            }
            try (OutputStream os = Files.newOutputStream(output)) {
                wb.write(os);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Portfolio sheet
    // -------------------------------------------------------------------------

    private static void writePortfolioSheet(Sheet sheet, List<Holding> holdings,
                                            Map<String, BigDecimal> gbpRates, Workbook wb) {
        CellStyle dataText  = wb.createCellStyle();
        CellStyle dataNum   = numericStyle(wb, false);
        CellStyle boldText  = boldTextStyle(wb);
        CellStyle boldNum   = numericStyle(wb, true);
        CellStyle inputCell = inputCellStyle(wb);

        // ---- Header row (row 0) ----
        String[] headers = { "Security ID", "Quantity", "Avg Price Paid",
                              "Market Value", "Market Value GBP", "Currency", "Source" };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(headerRow, i, headers[i], boldText);

        // ---- Group holdings by source ----
        Map<String, List<Holding>> bySource = new LinkedHashMap<>();
        for (Holding h : holdings) bySource.computeIfAbsent(h.getSource(), k -> new ArrayList<>()).add(h);

        int numSources  = bySource.size();
        boolean hasII   = bySource.containsKey(II_SIPP);
        // Total rows consumed by data section: holdings + (numSources-1) blank separators + II placeholder
        int numDataRows = holdings.size() + (numSources - 1) + (hasII ? 1 : 0);

        // Pre-calculate every key row offset (0-based POI).
        // Data occupies POI rows 1 .. numDataRows.
        // After data: 2 blank rows, then currency-label row, then subtotals, then 1 blank, grand total,
        // then 2 blank rows, then FX rates block.
        int dataAfter          = 1 + numDataRows;          // first row after data
        int currHdrPoi         = dataAfter + 2;            // "USD  |  GBP" label row
        int firstSubPoi        = currHdrPoi + 1;           // first per-source subtotal
        int grandTotalPoi      = firstSubPoi + numSources + 1;  // +1 blank before grand total
        int ratesLabelPoi      = grandTotalPoi + 2;
        int gbpusdPoi          = ratesLabelPoi + 1;
        int gbpeurPoi          = ratesLabelPoi + 2;

        // Excel (1-based) absolute reference to the GBPUSD rate value cell (col B = index 1)
        String gbpusdRef = "$B$" + (gbpusdPoi + 1);

        // ---- Write FX-rate block (needed before writing subtotal formulas) ----
        styledCell(sheet.createRow(ratesLabelPoi), COL_SECURITY_ID, "FX Rates (1 GBP =)", boldText);
        Row rusd = sheet.createRow(gbpusdPoi);
        styledCell(rusd, COL_SECURITY_ID, "USD", dataText);
        setNumeric(rusd, 1, gbpRates.getOrDefault("USD", BigDecimal.ONE), dataNum);
        Row reur = sheet.createRow(gbpeurPoi);
        styledCell(reur, COL_SECURITY_ID, "EUR", dataText);
        setNumeric(reur, 1, gbpRates.getOrDefault("EUR", BigDecimal.ONE), dataNum);

        // ---- Write data rows, tracking per-source ranges ----
        Map<String, int[]> ranges = new LinkedHashMap<>();
        int rowNum = 1;
        boolean firstSource = true;

        for (Map.Entry<String, List<Holding>> entry : bySource.entrySet()) {
            if (!firstSource) rowNum++;
            firstSource = false;

            String src   = entry.getKey();
            int firstRow = rowNum;

            for (Holding h : entry.getValue()) {
                writeHoldingRow(sheet.createRow(rowNum++), h, gbpRates, dataText, dataNum);
            }

            // II SIPP cash placeholder – yellow E cell for user input
            if (II_SIPP.equals(src)) {
                Row ph    = sheet.createRow(rowNum);
                String eRef = "E" + (rowNum + 1);          // E of this row (1-based Excel)
                styledCell(ph, COL_SECURITY_ID, "CASH", dataText);
                formulaCell(ph, COL_QUANTITY,    eRef, dataNum);
                setNumeric(ph, COL_AVG_PRICE, BigDecimal.ONE, dataNum);
                formulaCell(ph, COL_MKT_VALUE,   eRef, dataNum);
                ph.createCell(COL_MKT_VALUE_GBP).setCellStyle(inputCell); // blank – user fills
                styledCell(ph, COL_CURRENCY, "GBP", dataText);
                styledCell(ph, COL_SOURCE,   II_SIPP, dataText);
                rowNum++;
            }

            ranges.put(src, new int[]{ firstRow, rowNum - 1 });
        }

        // ---- Currency label row above subtotals ----
        Row currHdrRow = sheet.createRow(currHdrPoi);
        styledCell(currHdrRow, COL_MKT_VALUE,     "USD", boldText);
        styledCell(currHdrRow, COL_MKT_VALUE_GBP, "GBP", boldText);

        // ---- Per-source subtotal rows ----
        List<Integer> subPoiRows = new ArrayList<>();
        int subPoi = firstSubPoi;

        for (Map.Entry<String, int[]> re : ranges.entrySet()) {
            String src       = re.getKey();
            int firstExcel   = re.getValue()[0] + 1;
            int lastExcel    = re.getValue()[1] + 1;
            int thisExcelRow = subPoi + 1;

            Row row = sheet.createRow(subPoi);
            subPoiRows.add(subPoi);

            styledCell(row, COL_SECURITY_ID, src + " — Total", boldText);

            // USD: for Roth IRA sum the native D column; for others convert the GBP subtotal via rate
            Cell usd = row.createCell(COL_MKT_VALUE);
            usd.setCellFormula("Roth IRA".equals(src)
                    ? "SUM(D" + firstExcel + ":D" + lastExcel + ")"
                    : "E" + thisExcelRow + "*" + gbpusdRef);
            usd.setCellStyle(boldNum);

            Cell gbp = row.createCell(COL_MKT_VALUE_GBP);
            gbp.setCellFormula("SUM(E" + firstExcel + ":E" + lastExcel + ")");
            gbp.setCellStyle(boldNum);

            subPoi++;
        }

        // ---- Grand total ----
        Row totalRow = sheet.createRow(grandTotalPoi);
        styledCell(totalRow, COL_SECURITY_ID, "PORTFOLIO TOTAL", boldText);

        Cell grandUsd = totalRow.createCell(COL_MKT_VALUE);
        grandUsd.setCellFormula(subPoiRows.stream().map(r -> "D" + (r + 1)).collect(Collectors.joining("+")));
        grandUsd.setCellStyle(boldNum);

        Cell grandGbp = totalRow.createCell(COL_MKT_VALUE_GBP);
        grandGbp.setCellFormula(subPoiRows.stream().map(r -> "E" + (r + 1)).collect(Collectors.joining("+")));
        grandGbp.setCellStyle(boldNum);

        for (int i = 0; i < NUM_COLS; i++) sheet.autoSizeColumn(i);
    }

    private static void writeHoldingRow(Row row, Holding h, Map<String, BigDecimal> gbpRates,
                                        CellStyle textStyle, CellStyle numStyle) {
        styledCell(row, COL_SECURITY_ID,  h.getSecurityId(),           textStyle);
        setNumeric(row, COL_QUANTITY,     h.getQuantity(),             numStyle);
        setNumeric(row, COL_AVG_PRICE,    h.getAvgPricePaid(),         numStyle);
        setNumeric(row, COL_MKT_VALUE,    h.getCurrentMarketValue(),   numStyle);
        setNumeric(row, COL_MKT_VALUE_GBP, toGbp(h, gbpRates),        numStyle);
        styledCell(row, COL_CURRENCY,     h.getCurrency().getCurrencyCode(), textStyle);
        styledCell(row, COL_SOURCE,       h.getSource(),               textStyle);
    }

    // -------------------------------------------------------------------------
    // Raw input sheets
    // -------------------------------------------------------------------------

    private static void writeRawSheet(Path file, Sheet sheet) throws IOException {
        if (file.getFileName().toString().endsWith(".xlsx")) {
            copyXlsxToSheet(file, sheet);
        } else {
            copyCsvToSheet(file, sheet);
        }
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
                        case STRING  -> dc.setCellValue(srcCell.getStringCellValue());
                        case NUMERIC -> dc.setCellValue(srcCell.getNumericCellValue());
                        case BOOLEAN -> dc.setCellValue(srcCell.getBooleanCellValue());
                        default      -> dc.setCellValue(srcCell.toString());
                    }
                }
            }
        }
    }

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal toGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        if (h.getCurrentMarketValueGbp() != null) return h.getCurrentMarketValueGbp();
        BigDecimal mktVal = h.getCurrentMarketValue();
        if (mktVal == null) return null;
        BigDecimal rate = gbpRates.get(h.getCurrency().getCurrencyCode());
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return mktVal.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static void setNumeric(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(2, RoundingMode.HALF_UP).doubleValue());
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
        cell.setCellStyle(style);
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
        if (bold) { Font f = wb.createFont(); f.setBold(true); s.setFont(f); }
        return s;
    }

    private static CellStyle inputCellStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }
}
