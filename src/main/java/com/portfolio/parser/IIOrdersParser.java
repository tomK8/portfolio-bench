package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses II SIPP order-history exports — UUID-named CSVs sharing the filename shape with II's
 * holdings and cash exports, so disambiguated by a header sniff.
 *
 * <p>Header: {@code Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status}.
 * {@code Type} bundles side and order type ("SELL/LIMIT"); the currency comes from the {@code Amount}
 * prefix (£/$). II does not export a per-share limit price or an expiry, so those stay null.
 */
public class IIOrdersParser implements OrderParser {

    private static final Pattern UUID_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter SUBMITTED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public String sourceName() {
        return "II SIPP";
    }

    @Override
    public boolean supports(Path file) {
        if (!UUID_FILENAME.matcher(file.getFileName().toString()).matches()) return false;
        if (!Files.exists(file)) return false;   // header sniff is required to disambiguate II files
        try {
            String header = Files.lines(file, StandardCharsets.UTF_8)
                    .filter(l -> !l.isBlank() && !l.chars().allMatch(c -> c == '﻿'))
                    .findFirst().orElse("");
            return header.contains("Submitted") && header.contains("Settlement Date")
                    && header.contains("Status");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public List<OpenOrder> parse(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        List<OpenOrder> orders = new ArrayList<>();
        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord r : csv) {
                String symbol = get(r, "Symbol");
                BigDecimal qty = decimal(get(r, "Quantity"));
                if (symbol == null || symbol.isBlank() || qty == null) continue;

                String[] type = splitType(get(r, "Type"));
                String amountRaw = get(r, "Amount");
                BigDecimal amount = decimal(amountRaw);

                // II never exports the resting limit price, so estimate it from the order value ÷
                // quantity. It's inexact — the ~£2.99 dealing fee (and, on USD orders, an FX charge
                // in GBP) is baked into the amount — hence the priceEstimated flag / "~" in the UI.
                boolean priceEstimated = amount != null && qty.compareTo(BigDecimal.ZERO) != 0;
                BigDecimal price = priceEstimated
                        ? amount.divide(qty, 4, java.math.RoundingMode.HALF_UP) : null;

                orders.add(new OpenOrder(
                        sourceName(),
                        symbol,
                        get(r, "Name"),
                        type[0],
                        type[1],
                        qty,
                        price,
                        priceEstimated,
                        amount,
                        currency(amountRaw),
                        date(get(r, "Submitted")),
                        null,
                        null,
                        get(r, "Status"),
                        get(r, "Reference"),
                        null, null, null, null, null, null, null));
            }
        }
        return orders;
    }

    private static String get(CSVRecord r, String col) {
        return r.isMapped(col) ? r.get(col).trim() : null;
    }

    /** "SELL/LIMIT" → {"Sell", "Limit"}; unknown shapes degrade to {raw, null}. */
    private static String[] splitType(String raw) {
        if (raw == null || raw.isBlank()) return new String[]{null, null};
        int slash = raw.indexOf('/');
        if (slash < 0) return new String[]{titleCase(raw.trim()), null};
        return new String[]{titleCase(raw.substring(0, slash).trim()),
                titleCase(raw.substring(slash + 1).trim())};
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private static String currency(String amount) {
        if (amount == null) return null;
        if (amount.contains("$")) return "USD";
        if (amount.contains("£")) return "GBP";
        return null;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replaceAll("[£$,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return LocalDate.parse(s, SUBMITTED);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s.split("\\s+")[0], DATE_ONLY);
        } catch (Exception e) {
            return null;
        }
    }
}
