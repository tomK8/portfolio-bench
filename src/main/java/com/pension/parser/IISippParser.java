package com.pension.parser;

import com.pension.model.Holding;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses UUID-named CSV exports (e.g. 1f072c5f-a547-4589-9003-4ee13cba7ddd.csv) for the II SIPP account.
 *
 * Currency per row is inferred from the Market Value column prefix ($ → USD, £ → GBP).
 * GBP-priced securities may show price in pence; Market Value and Book Cost are always in pounds.
 * Average price paid = Book Cost / Qty.
 */
public class IISippParser implements AccountParser {

    private static final String ACCOUNT_SOURCE = "II SIPP";

    private static final Pattern UUID_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv",
            Pattern.CASE_INSENSITIVE);

    private static final String COL_SYMBOL       = "Symbol";
    private static final String COL_QTY          = "Qty";
    private static final String COL_MARKET_VALUE = "Market Value";
    private static final String COL_BOOK_COST    = "Book Cost";

    @Override
    public String sourceName() { return "II SIPP"; }

    @Override
    public boolean supports(Path file) {
        if (!UUID_FILENAME.matcher(file.getFileName().toString()).matches()) return false;
        // II also exports orders/activity as UUID CSVs — verify this is a holdings file
        // by checking the header contains the expected "Market Value" column.
        // If the file can't be read, fall back to accepting it (e.g. in tests with synthetic paths).
        if (!Files.exists(file)) return true;
        try {
            String firstLine = Files.lines(file, StandardCharsets.UTF_8)
                    .filter(l -> !l.isBlank() && !l.chars().allMatch(c -> c == '﻿'))
                    .findFirst().orElse("");
            return firstLine.contains("Market Value") && firstLine.contains("Book Cost");
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public List<Holding> parse(Path file) throws IOException, ParseException {
        List<Holding> holdings = new ArrayList<>();

        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord record : csv) {
                String symbol = record.get(COL_SYMBOL).trim();
                if (symbol.isEmpty()) continue; // totals rows

                BigDecimal qty = parseDecimal(record.get(COL_QTY));
                if (qty == null) continue;

                String mktValRaw = record.get(COL_MARKET_VALUE).trim();
                Currency currency    = parseCurrency(mktValRaw);
                BigDecimal mktVal    = parseCurrencyAmount(mktValRaw);

                BigDecimal bookCost  = parseCurrencyAmount(record.get(COL_BOOK_COST).trim());
                BigDecimal avgPrice  = (bookCost != null && qty.compareTo(BigDecimal.ZERO) != 0)
                        ? bookCost.divide(qty, 10, RoundingMode.HALF_UP)
                        : null;

                holdings.add(Holding.builder(normaliseSecurityId(symbol), qty, currency, ACCOUNT_SOURCE)
                        .avgPricePaid(avgPrice)
                        .currentMarketValue(mktVal)
                        .build());
            }
        }

        return holdings;
    }

    // -------------------------------------------------------------------------

    static String normaliseSecurityId(String rawId) {
        if (rawId == null) return null;
        String id = rawId.trim().toUpperCase();
        return switch (id) {
            case "GOOG", "GOOGL" -> "GOOG/GOOGL";
            default              -> id;
        };
    }

    private Currency parseCurrency(String value) {
        if (value != null && value.startsWith("$")) return Currency.getInstance("USD");
        return Currency.getInstance("GBP");
    }

    private BigDecimal parseCurrencyAmount(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[£$,]", "").trim();
        if (clean.isEmpty()) return null;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
