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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses II SIPP order-history exports — UUID-named CSVs sharing the filename shape with II's
 * holdings and cash exports, so disambiguated by a header sniff.
 *
 * <p>Header: {@code Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status}.
 * {@code Type} bundles side and order type ("SELL/LIMIT"); the currency comes from the {@code Amount}
 * prefix (£/$/€ — II holds all three). II does not export a per-share limit price or an expiry, so
 * those stay null; a value-based order carries {@code Quantity=n/a}, which leaves the quantity
 * null too.
 */
public class IIOrdersParser implements OrderParser {

    private static final Pattern UUID_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter SUBMITTED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * The currency symbols II prefixes an order value with, paired with the code each denotes.
     *
     * <p>One table because the two readings of an amount have to agree. {@link #currency} names the
     * currency and {@link #decimal} strips the symbol to leave a number, and a symbol known to one
     * but not the other yields half an order: an amount nothing can convert, or a currency with no
     * value under it. They agreed only by both being wrong until 2026-09-03, when a BNP sell limit
     * exported as {@code "€23,171.33"} came through with a null amount <em>and</em> a null
     * currency — and with them a null limit price, so Price, Current, vs limit, σ→lim, both touch
     * probabilities and both Amount columns were all blank while RV10/RV30 (which read the symbol's
     * own daily series and need no currency at all) rendered normally. That combination is what
     * makes the gap read as a broken instrument rather than an unparsed euro sign.
     *
     * <p>An unrecognised symbol resolves to no currency rather than falling back to GBP, which is
     * the opposite of {@code IISippParser.parseCurrency}'s convention and deliberate here: a
     * holdings row has a GBP market value column to fall back on, an order has nothing, so a wrong
     * currency would price the row against the wrong quote instead of leaving it visibly unpriced.
     */
    private static final String[][] CURRENCIES = {{"£", "GBP"}, {"$", "USD"}, {"€", "EUR"}};

    /** Every currency symbol above, plus the thousands separator and any whitespace. */
    private static final Pattern NUMERIC_NOISE = Pattern.compile(
            Arrays.stream(CURRENCIES).map(c -> Pattern.quote(c[0]))
                    .collect(Collectors.joining("|", "", "|[,\\s]")));

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
                if (symbol == null || symbol.isBlank()) continue;

                // A value-based order ("buy £6,597.50 worth") exports Quantity as "n/a" — the share
                // count is only settled at execution. That is a real resting order, so it must not be
                // dropped: quantity stays null and renders as "—", and the limit price can't be
                // estimated (there's nothing to divide the amount by).
                BigDecimal qty = decimal(get(r, "Quantity"));

                String[] type = splitType(get(r, "Type"));
                String amountRaw = get(r, "Amount");
                BigDecimal amount = decimal(amountRaw);

                // II never exports the resting limit price, so estimate it from the order value ÷
                // quantity. It's inexact — the ~£2.99 dealing fee (and, on USD orders, an FX charge
                // in GBP) is baked into the amount — hence the priceEstimated flag / "~" in the UI.
                boolean priceEstimated = amount != null && qty != null && qty.signum() != 0;
                BigDecimal price = priceEstimated
                        ? amount.divide(qty, 4, java.math.RoundingMode.HALF_UP) : null;

                orders.add(OpenOrder.parsed(
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
                        get(r, "Reference")));
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
        for (String[] c : CURRENCIES) {
            if (amount.contains(c[0])) return c[1];
        }
        return null;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(NUMERIC_NOISE.matcher(raw).replaceAll(""));
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
