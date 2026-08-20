package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses Citi (Roth IRA) {@code Order+Status*.xlsx} exports.
 *
 * <p>The sheet has a metadata preamble; the real header row ({@code Date, Transaction, Security ID,
 * Quantity, Order Type, Price, Duration, Status}) is located by scanning rather than a fixed index.
 * All Roth orders are USD and GTC (no dated expiry), so {@code duration} carries the "GTC" and
 * {@code expiry} stays null.
 */
public class RothOrdersParser implements OrderParser {

    private static final Pattern FILENAME = Pattern.compile("order.*status.*\\.xlsx", Pattern.CASE_INSENSITIVE);
    private static final DataFormatter FMT = new DataFormatter();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Override
    public String sourceName() {
        return "Roth IRA";
    }

    @Override
    public boolean supports(Path file) {
        return FILENAME.matcher(file.getFileName().toString()).matches();
    }

    @Override
    public List<OpenOrder> parse(Path file) throws IOException {
        List<OpenOrder> orders = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet sheet = wb.getSheet("Order Status");
            if (sheet == null) sheet = wb.getSheetAt(0);

            int headerRow = findHeaderRow(sheet);
            if (headerRow < 0) return orders;
            Map<String, Integer> col = columnIndex(sheet.getRow(headerRow));

            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String symbol = str(row, col, "Security ID");
                BigDecimal qty = decimal(str(row, col, "Quantity"));
                if (symbol == null || symbol.isBlank() || qty == null) continue;

                // Roth doesn't export the order value, but it exports the (exact) limit price, so
                // derive amount = price × qty.
                BigDecimal price = decimal(str(row, col, "Price"));
                BigDecimal amount = price != null ? price.multiply(qty) : null;

                orders.add(new OpenOrder(
                        sourceName(),
                        symbol,
                        null,
                        str(row, col, "Transaction"),
                        str(row, col, "Order Type"),
                        qty,
                        price,
                        false,
                        amount,
                        "USD",
                        date(str(row, col, "Date")),
                        null,
                        str(row, col, "Duration"),
                        str(row, col, "Status"),
                        null,
                        null, null, null, null, null, null, null, null, null));
            }
        }
        return orders;
    }

    private static int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean date = false, status = false, secId = false;
            for (Cell cell : row) {
                String v = FMT.formatCellValue(cell).trim();
                if ("Date".equalsIgnoreCase(v)) date = true;
                else if ("Status".equalsIgnoreCase(v)) status = true;
                else if ("Security ID".equalsIgnoreCase(v)) secId = true;
            }
            if (date && status && secId) return r;
        }
        return -1;
    }

    private static Map<String, Integer> columnIndex(Row header) {
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : header) {
            String v = FMT.formatCellValue(cell).trim();
            if (!v.isEmpty()) index.put(v, cell.getColumnIndex());
        }
        return index;
    }

    private static String str(Row row, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        String v = FMT.formatCellValue(cell).trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replaceAll("[$,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim().split("\\s+")[0], DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
