package com.pension.parser;

import com.pension.model.Holding;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Currency;

/**
 * Parses broker Holdings*.xlsx exports for the Roth IRA account.
 *
 * File layout:
 *   - Rows 0–10: header metadata
 *   - Row 11:    column headers
 *   - Row 12+:   one holding per row
 *
 * Cash instruments (BDP, USD999997) are merged into a single CASH entry.
 * Average price paid is derived as (Market Value - Gain/Loss $) / Quantity.
 */
public class RothIraParser implements AccountParser {

    private static final String   ACCOUNT_SOURCE = "Roth IRA";
    private static final String   CASH_ID        = "CASH";
    private static final Currency CURRENCY       = Currency.getInstance("USD");

    private static final String COL_SECURITY_ID  = "Security ID";
    private static final String COL_QUANTITY     = "Quantity";
    private static final String COL_MARKET_VALUE = "Market Value";
    private static final String COL_GAIN_LOSS    = "Gain/Loss $";

    private static final int HEADER_ROW_INDEX = 11;

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("Holdings") && name.endsWith(".xlsx");
    }

    @Override
    public List<Holding> parse(Path file) throws IOException, ParseException {
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet sheet = workbook.getSheet("Holdings");
            if (sheet == null) {
                throw new ParseException("Expected sheet 'Holdings' not found in: " + file);
            }

            Map<String, Integer> colIndex = buildColumnIndex(sheet.getRow(HEADER_ROW_INDEX));

            List<Holding> holdings = new ArrayList<>();
            BigDecimal cashCostBasis = BigDecimal.ZERO;
            BigDecimal cashQuantity  = BigDecimal.ZERO;
            boolean hasCash = false;

            for (int r = HEADER_ROW_INDEX + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String rawId = getString(row, colIndex, COL_SECURITY_ID);
                if (rawId == null || rawId.isBlank()) continue;

                BigDecimal quantity = getDecimal(row, colIndex, COL_QUANTITY);
                if (quantity == null) continue;

                String id          = normaliseSecurityId(rawId);
                BigDecimal mktVal  = getDecimal(row, colIndex, COL_MARKET_VALUE);
                BigDecimal gainLoss = getDecimal(row, colIndex, COL_GAIN_LOSS);

                if (CASH_ID.equals(id)) {
                    cashQuantity = cashQuantity.add(quantity);
                    if (mktVal != null && gainLoss != null) {
                        cashCostBasis = cashCostBasis.add(mktVal.subtract(gainLoss));
                    }
                    hasCash = true;
                } else {
                    holdings.add(Holding.builder(id, quantity, CURRENCY, ACCOUNT_SOURCE)
                            .avgPricePaid(computeAvgPricePaid(mktVal, gainLoss, quantity))
                            .build());
                }
            }

            if (hasCash) {
                holdings.add(Holding.builder(CASH_ID, cashQuantity, CURRENCY, ACCOUNT_SOURCE)
                        .avgPricePaid(computeAvgPricePaid(cashCostBasis, BigDecimal.ZERO, cashQuantity))
                        .build());
            }

            return holdings;
        }
    }

    // -------------------------------------------------------------------------

    static String normaliseSecurityId(String rawId) {
        if (rawId == null) return null;
        String id = rawId.trim().toUpperCase();
        return switch (id) {
            case "GOOG", "GOOGL"               -> "GOOG/GOOGL";
            case "USD999997", "BDP"            -> CASH_ID;
            default                            -> id;
        };
    }

    private BigDecimal computeAvgPricePaid(BigDecimal marketValue, BigDecimal gainLoss, BigDecimal quantity) {
        if (marketValue == null || gainLoss == null || quantity == null
                || quantity.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return marketValue.subtract(gainLoss).divide(quantity, 10, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------

    private Map<String, Integer> buildColumnIndex(Row headerRow) throws ParseException {
        if (headerRow == null) {
            throw new ParseException("Header row at index " + HEADER_ROW_INDEX + " is missing");
        }
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : headerRow) {
            if (cell.getCellType() == CellType.STRING) {
                index.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
            }
        }
        return index;
    }

    private String getString(Row row, Map<String, Integer> colIndex, String colName) {
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

    private BigDecimal getDecimal(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null || cell.getCellType() != CellType.NUMERIC) return null;
        return BigDecimal.valueOf(cell.getNumericCellValue());
    }
}
