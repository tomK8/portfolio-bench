package com.pension.parser;

import com.pension.domain.model.CashTransaction;
import com.pension.port.HistoricalFxRateProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Parses the RothIRA broker activity export {@code History*.xlsx} (sheet "history")
 * into native-USD {@link CashTransaction}s.
 *
 * <p>The file has no cash-balance column and no GBP figures: this parser fills the
 * native amount, signed quantity and a per-row {@code fxToGbp}/{@code amountGbp}
 * resolved from the historical GBP→USD rate on each transaction's date. The running
 * {@code cashBalance}/{@code cashBalanceGbp} are left null and derived later by
 * {@link com.pension.PortfolioDatabase#saveRothIraCashTransactions}, which needs the
 * stored balance to continue from.
 */
public class RothIraCashStatementParser implements CashTransactionParser {

    private static final String ACCOUNT   = "RothIRA";
    private static final String CURRENCY  = "USD";
    private static final String SHEET     = "history";
    static final String FILE_NAME = "History.xlsx";

    private static final String COL_DATE        = "Date";
    private static final String COL_SECURITY_ID  = "Security ID";
    private static final String COL_DESCRIPTION  = "Activity Description";
    private static final String COL_NET_AMOUNT   = "Net Amount";
    private static final String COL_QUANTITY     = "Quantity";

    private final HistoricalFxRateProvider fxProvider;

    public RothIraCashStatementParser(HistoricalFxRateProvider fxProvider) {
        this.fxProvider = fxProvider;
    }

    @Override
    public String accountName() { return ACCOUNT; }

    @Override
    public boolean supports(Path file) {
        return FILE_NAME.equals(file.getFileName().toString());
    }

    @Override
    public List<CashTransaction> parse(Path file) throws IOException, ParseException {
        List<RawRow> raw = readRows(file);
        if (raw.isEmpty()) return List.of();

        Map<LocalDate, BigDecimal> fxSeries = fetchFxSeries(raw);

        List<CashTransaction> result = new ArrayList<>();
        for (RawRow row : raw) {
            double fx = resolveFx(fxSeries, row.date());
            double amountGbp = fx != 0 ? row.amount() / fx : 0.0;
            result.add(new CashTransaction(
                    row.date().toString(),
                    ACCOUNT,
                    classifyType(row.description()),
                    RothIraParser.normaliseSecurityId(row.symbol()),
                    row.quantity(),
                    row.amount(),
                    CURRENCY,
                    fx,
                    amountGbp,
                    null,   // native running balance — derived at save time
                    null,   // GBP running balance   — derived at save time
                    row.description()
            ));
        }
        return result;
    }

    // ---- Reading ------------------------------------------------------------

    private List<RawRow> readRows(Path file) throws IOException, ParseException {
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet sheet = workbook.getSheet(SHEET);
            if (sheet == null) {
                throw new ParseException("Expected sheet '" + SHEET + "' not found in: " + file);
            }

            int headerRowIndex = findHeaderRow(sheet);
            Map<String, Integer> colIndex = buildColumnIndex(sheet.getRow(headerRowIndex));

            List<RawRow> rows = new ArrayList<>();
            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                LocalDate date = getDate(row, colIndex, COL_DATE);
                String symbol  = getString(row, colIndex, COL_SECURITY_ID);
                if (date == null || symbol == null || symbol.isBlank()) continue;

                rows.add(new RawRow(
                        date,
                        symbol,
                        getString(row, colIndex, COL_DESCRIPTION),
                        getDouble(row, colIndex, COL_NET_AMOUNT),
                        getDouble(row, colIndex, COL_QUANTITY)
                ));
            }
            return rows;
        }
    }

    private static int findHeaderRow(Sheet sheet) throws ParseException {
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean hasDate = false, hasAmount = false;
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.STRING) continue;
                String v = cell.getStringCellValue().trim();
                if (COL_DATE.equals(v))       hasDate = true;
                if (COL_NET_AMOUNT.equals(v))  hasAmount = true;
            }
            if (hasDate && hasAmount) return r;
        }
        throw new ParseException("Could not locate header row (need '" + COL_DATE
                + "' and '" + COL_NET_AMOUNT + "')");
    }

    private static Map<String, Integer> buildColumnIndex(Row headerRow) {
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : headerRow) {
            if (cell.getCellType() == CellType.STRING) {
                index.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
            }
        }
        return index;
    }

    // ---- Classification -----------------------------------------------------

    /** Maps the broker's Activity Description to a cash_transactions type. */
    static String classifyType(String description) {
        String d = description == null ? "" : description.toLowerCase();
        if (d.contains("dividend"))                       return "DIVIDEND";
        if (d.contains("tax withheld"))                   return "CHARGE";
        if (d.contains("buy") || d.contains("sell")
                || d.contains("stock split"))             return "TRANSACTION";
        if (d.contains("interest"))                       return "INTEREST";
        if (d.contains("deposit") || d.contains("transfer")
                || d.contains("contribution"))            return "CONTRIBUTION";
        System.err.println("[RothIRA cash] Unclassified row, defaulting to TRANSACTION: " + description);
        return "TRANSACTION";
    }

    // ---- FX -----------------------------------------------------------------

    private Map<LocalDate, BigDecimal> fetchFxSeries(List<RawRow> rows) throws ParseException {
        LocalDate min = rows.stream().map(RawRow::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = rows.stream().map(RawRow::date).max(Comparator.naturalOrder()).orElseThrow();
        // Widen the start so a transaction on a Monday holiday can still borrow a prior rate.
        try {
            return fxProvider.fetchRateSeries(CURRENCY, min.minusDays(7), max);
        } catch (Exception e) {
            throw new ParseException("Could not fetch historical FX rates: " + e.getMessage());
        }
    }

    private static double resolveFx(Map<LocalDate, BigDecimal> series, LocalDate date) throws ParseException {
        BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series, date);
        if (rate == null) {
            throw new ParseException("No GBP→USD rate available on or before " + date);
        }
        return rate.doubleValue();
    }

    // ---- Cell helpers -------------------------------------------------------

    private static String getString(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> null;
        };
    }

    private static double getDouble(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return 0.0;
        Cell cell = row.getCell(idx);
        return (cell != null && cell.getCellType() == CellType.NUMERIC)
                ? cell.getNumericCellValue() : 0.0;
    }

    private static LocalDate getDate(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null || cell.getCellType() != CellType.NUMERIC
                || !DateUtil.isCellDateFormatted(cell)) {
            return null;
        }
        return cell.getLocalDateTimeCellValue().toLocalDate();
    }

    private record RawRow(LocalDate date, String symbol, String description,
                          double amount, double quantity) {}
}
