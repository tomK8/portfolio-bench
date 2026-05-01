package com.pension;

import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.IISippParser;
import com.pension.parser.RothIraParser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final Path INPUT_DIR  = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), "Documents");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String FX_URL =
            "https://api.frankfurter.dev/v1/latest?from=GBP&to=USD,EUR";

    // Column indices
    private static final int COL_SECURITY_ID    = 0;
    private static final int COL_QUANTITY        = 1;
    private static final int COL_AVG_PRICE       = 2;
    private static final int COL_MKT_VALUE       = 3;
    private static final int COL_MKT_VALUE_GBP   = 4;
    private static final int COL_CURRENCY        = 5;
    private static final int COL_SOURCE          = 6;
    private static final int NUM_COLS            = 7;

    private static final List<AccountParser> PARSERS = List.of(
            new RothIraParser(),
            new AJBellSippParser(),
            new IISippParser()
    );

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("Input directory not found: " + INPUT_DIR);
            return;
        }

        List<Holding> holdings = new ArrayList<>();

        for (AccountParser parser : PARSERS) {
            Optional<Path> file = findMostRecent(INPUT_DIR, parser);
            if (file.isPresent()) {
                System.out.println("Parsing: " + file.get().getFileName());
                holdings.addAll(parser.parse(file.get()));
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

        writeExcel(holdings, gbpRates, output);
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

    /**
     * Returns units of each currency per 1 GBP, e.g. {"GBP":1, "USD":1.3473, "EUR":1.1536}.
     * To convert amount in currency X to GBP: amount / rates.get(X).
     */
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
                                   Path output) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Portfolio");

            CellStyle dataTextStyle    = wb.createCellStyle();
            CellStyle dataNumStyle     = numericStyle(wb, false);
            CellStyle subtotalTextStyle = boldTextStyle(wb);
            CellStyle subtotalNumStyle  = numericStyle(wb, true);
            CellStyle totalTextStyle    = boldTextStyle(wb);
            CellStyle totalNumStyle     = numericStyle(wb, true);

            // ---- Header row ----
            String[] headers = {
                "Security ID", "Quantity", "Avg Price Paid",
                "Market Value", "Market Value GBP", "Currency", "Source"
            };
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = boldTextStyle(wb);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ---- Data rows + accumulate per-source totals ----
            // gbpBySource  : sum of Market Value GBP per source (all sources)
            // nativeBySource: sum of Market Value in native currency (Roth IRA only — all USD)
            Map<String, BigDecimal> gbpBySource    = new LinkedHashMap<>();
            Map<String, BigDecimal> nativeBySource = new LinkedHashMap<>();

            int rowNum = 1;
            for (Holding h : holdings) {
                Row row = sheet.createRow(rowNum++);

                Cell idCell = row.createCell(COL_SECURITY_ID);
                idCell.setCellValue(h.getSecurityId());
                idCell.setCellStyle(dataTextStyle);

                setNumeric(row, COL_QUANTITY,      h.getQuantity(),           dataNumStyle);
                setNumeric(row, COL_AVG_PRICE,     h.getAvgPricePaid(),       dataNumStyle);
                setNumeric(row, COL_MKT_VALUE,     h.getCurrentMarketValue(), dataNumStyle);

                BigDecimal gbp = toGbp(h, gbpRates);
                setNumeric(row, COL_MKT_VALUE_GBP, gbp, dataNumStyle);

                Cell ccyCell = row.createCell(COL_CURRENCY);
                ccyCell.setCellValue(h.getCurrency().getCurrencyCode());
                ccyCell.setCellStyle(dataTextStyle);

                Cell srcCell = row.createCell(COL_SOURCE);
                srcCell.setCellValue(h.getSource());
                srcCell.setCellStyle(dataTextStyle);

                String src = h.getSource();
                gbpBySource.merge(src, gbp != null ? gbp : BigDecimal.ZERO, BigDecimal::add);

                // Roth IRA is entirely USD — track native total separately
                if ("Roth IRA".equals(src) && h.getCurrentMarketValue() != null) {
                    nativeBySource.merge(src, h.getCurrentMarketValue(), BigDecimal::add);
                }
            }

            // ---- Blank separator row ----
            rowNum++;

            // ---- Per-source subtotal rows ----
            for (Map.Entry<String, BigDecimal> entry : gbpBySource.entrySet()) {
                String src = entry.getKey();
                BigDecimal gbpTotal = entry.getValue();

                Row row = sheet.createRow(rowNum++);
                Cell label = row.createCell(COL_SECURITY_ID);
                label.setCellValue(src + " — Total");
                label.setCellStyle(subtotalTextStyle);

                if ("Roth IRA".equals(src) && nativeBySource.containsKey(src)) {
                    // Show USD total in the Market Value column
                    setNumeric(row, COL_MKT_VALUE, nativeBySource.get(src), subtotalNumStyle);
                    Cell ccyCell = row.createCell(COL_CURRENCY);
                    ccyCell.setCellValue("USD");
                    ccyCell.setCellStyle(subtotalTextStyle);
                }
                setNumeric(row, COL_MKT_VALUE_GBP, gbpTotal, subtotalNumStyle);
            }

            // ---- Grand total row ----
            BigDecimal grandTotal = gbpBySource.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            rowNum++; // blank gap before grand total
            Row totalRow = sheet.createRow(rowNum);
            Cell totalLabel = totalRow.createCell(COL_SECURITY_ID);
            totalLabel.setCellValue("PORTFOLIO TOTAL");
            totalLabel.setCellStyle(totalTextStyle);
            setNumeric(totalRow, COL_MKT_VALUE_GBP, grandTotal, totalNumStyle);

            // ---- Auto-size ----
            for (int i = 0; i < NUM_COLS; i++) sheet.autoSizeColumn(i);

            try (OutputStream os = Files.newOutputStream(output)) {
                wb.write(os);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal toGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        // If the source file already provides a GBP value, use it directly
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

    private static CellStyle boldTextStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle numericStyle(Workbook wb, boolean bold) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        if (bold) {
            Font font = wb.createFont();
            font.setBold(true);
            style.setFont(font);
        }
        return style;
    }
}
