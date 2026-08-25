package com.portfolio.parser;

import com.portfolio.domain.model.Holding;
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
 * <p>
 * Currency per row is inferred from the Market Value column prefix ($ → USD, £ → GBP).
 * GBP-priced securities may show price in pence; Market Value and Book Cost are always in pounds.
 * Average price paid = Book Cost / Qty.
 * <p>
 * A gilt is filed under the canonical {@code "GILT {coupon}% {year}"} id derived from its Name
 * rather than under II's own code — see {@link #securityIdFor}. Note II quotes a gilt <b>per £1
 * nominal</b> ({@code £0.9951} against a quantity of {@code 20,000}) where AJ Bell quotes per
 * £100; nothing here has to reconcile that, because the exported Market Value is used directly
 * and the Price column is never read.
 */
public class IISippParser implements AccountParser {

    private static final String ACCOUNT_SOURCE = "II SIPP";

    private static final Pattern UUID_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv",
            Pattern.CASE_INSENSITIVE);

    private static final String COL_SYMBOL = "Symbol";
    private static final String COL_NAME = "Name";
    private static final String COL_QTY = "Qty";
    private static final String COL_MARKET_VALUE = "Market Value";
    private static final String COL_BOOK_COST = "Book Cost";

    static String normaliseSecurityId(String rawId) {
        if (rawId == null) return null;
        return rawId.trim().toUpperCase();
    }

    /**
     * The internal id for a row: II's own ticker, except for a gilt, which takes the canonical
     * {@code "GILT {coupon}% {year}"} form derived from the Name column.
     *
     * <p>II's ticker for a gilt is a short code of its own ({@code T30} for the 4.375% 2030) that
     * no price feed knows and that {@code Instruments.isBond} cannot recognise — so the position
     * was valued per share instead of per £100 nominal, joined no gilt quote, and the cash-ledger
     * view dropped it from the totals altogether (2026-08-25). The name carries the coupon and
     * maturity, so the canonical id is derivable with no mapping to maintain.
     */
    static String securityIdFor(String symbol, String name) {
        String giltId = IIInstruments.giltIdFromName(name);
        return giltId != null ? giltId : normaliseSecurityId(symbol);
    }

    @Override
    public String sourceName() {
        return "II SIPP";
    }

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

    // -------------------------------------------------------------------------

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
                Currency currency = parseCurrency(mktValRaw);
                BigDecimal mktVal = parseCurrencyAmount(mktValRaw);

                BigDecimal bookCost = parseCurrencyAmount(record.get(COL_BOOK_COST).trim());
                BigDecimal avgPrice = (bookCost != null && qty.compareTo(BigDecimal.ZERO) != 0)
                        ? bookCost.divide(qty, 10, RoundingMode.HALF_UP)
                        : null;

                String name = record.isSet(COL_NAME) ? record.get(COL_NAME).trim() : "";

                holdings.add(Holding.builder(securityIdFor(symbol, name), qty, currency, ACCOUNT_SOURCE)
                        .avgPricePaid(avgPrice)
                        .currentMarketValue(mktVal)
                        .build());
            }
        }

        return holdings;
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
