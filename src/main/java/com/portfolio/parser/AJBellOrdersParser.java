package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses AJ Bell SIPP {@code activeorders*.csv} exports.
 *
 * <p>Header: {@code Investment,Reference,Type,Order type,Quantity,Amount,Price,Date entered,
 * Expiry date,Status}. The export names the instrument (no ticker), so the name is mapped to the
 * app's internal symbol by keyword; an <em>unmapped</em> name is passed straight through as the
 * {@code symbol} so it shows up in the (only) identifier column — the cue to add a mapping here and
 * fix it. Prices are London pence ("53800p") — normalised to pounds so the row carries a single GBP
 * currency alongside the pound-denominated Amount.
 */
public class AJBellOrdersParser implements OrderParser {

    private static final Logger log = LoggerFactory.getLogger(AJBellOrdersParser.class);

    private static final Pattern FILENAME = Pattern.compile("activeorders.*\\.csv", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * AJ Bell's active-orders export names the instrument but carries no ticker, so map the
     * (long) investment name to the app's internal symbol by keyword — the same symbols used
     * elsewhere (YahooTickerMap adds the .L suffix: EQQQ→EQQQ.L, LSEG→LSEG.L, REL→REL.L).
     * First matching keyword wins.
     *
     * <p>This is the committed baseline only. User-added keywords come from
     * {@code instrument_aliases} via {@link #aliasSource} and are consulted <em>first</em>, so a
     * new instrument is mapped from the dashboard rather than from a source edit.
     */
    private static final Map<String, String> NAME_TO_SYMBOL = new LinkedHashMap<>();

    static {
        NAME_TO_SYMBOL.put("eqqq", "EQQQ");
        NAME_TO_SYMBOL.put("london stock exchange", "LSEG");
        NAME_TO_SYMBOL.put("relx", "REL");
        NAME_TO_SYMBOL.put("primary health", "PHP");
        NAME_TO_SYMBOL.put("londonmetric", "LMP");
        NAME_TO_SYMBOL.put("british land", "BLND");
    }

    /**
     * User-added keyword → symbol aliases, longest keyword first. Read per parse (the export is
     * re-parsed on every tab click, so a mapping added on the dashboard takes effect on the next
     * one) and never cached here.
     */
    private final Supplier<Map<String, String>> aliasSource;

    /** Committed keywords only — the default for tests and any alias-store-less context. */
    public AJBellOrdersParser() {
        this(Map::of);
    }

    public AJBellOrdersParser(Supplier<Map<String, String>> aliasSource) {
        this.aliasSource = aliasSource == null ? Map::of : aliasSource;
    }

    /** Internal symbol for an AJ Bell investment name, or null when unmapped. */
    String symbolFor(String investmentName) {
        if (investmentName == null) return null;
        String lower = investmentName.toLowerCase();
        Map<String, String> aliases = aliasSource.get();
        if (aliases != null) {
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (lower.contains(e.getKey())) return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : NAME_TO_SYMBOL.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * The identifier shown in the Symbol column: the mapped internal symbol when known, else the raw
     * investment name (so an unmapped name is visible and can be turned into a {@link #NAME_TO_SYMBOL}
     * entry rather than silently vanishing). It won't resolve to a live quote until mapped — fine.
     */
    String symbolOrName(String investmentName) {
        String mapped = symbolFor(investmentName);
        if (mapped != null) return mapped;
        // Visible in the Symbol column, but it resolves to no ticker — so the order shows no
        // Current price, no vs-limit and no touch probability. Say so at parse time; the column
        // being blank looks like a dead feed rather than a missing mapping. The Open orders tab
        // spots the same fall-through (symbol == name) and offers the mapping inline.
        log.warn("[AJBell orders] No symbol mapping for \"{}\" — the order will show without live "
                + "price columns. Add it from the Open orders tab, which offers the mapping "
                + "inline, or add a NAME_TO_SYMBOL keyword.", investmentName);
        return investmentName;
    }

    @Override
    public String sourceName() {
        return "AJ Bell SIPP";
    }

    @Override
    public boolean supports(Path file) {
        return FILENAME.matcher(file.getFileName().toString()).matches();
    }

    @Override
    public List<OpenOrder> parse(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        List<OpenOrder> orders = new ArrayList<>();
        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord r : csv) {
                String name = get(r, "Investment");
                if (name == null || name.isBlank()) continue;
                BigDecimal qty = decimal(get(r, "Quantity"));
                if (qty == null) continue;

                orders.add(new OpenOrder(
                        sourceName(),
                        symbolOrName(name),
                        name,
                        get(r, "Type"),
                        get(r, "Order type"),
                        qty,
                        price(get(r, "Price")),
                        false,
                        decimal(get(r, "Amount")),
                        "GBP",
                        date(get(r, "Date entered")),
                        date(get(r, "Expiry date")),
                        null,
                        get(r, "Status"),
                        get(r, "Reference"),
                        // enrichment (FX, live price, vol, touch horizons) — filled by the service
                        null, null, null, null, null, null, null, null, null, null, null, null));
            }
        }
        return orders;
    }

    private static String get(CSVRecord r, String col) {
        return r.isMapped(col) ? r.get(col).trim() : null;
    }

    /** "53800p" → 538.00 (pence → pounds); a bare number is already pounds. */
    private static BigDecimal price(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        boolean pence = s.endsWith("p") || s.endsWith("P");
        BigDecimal v = decimal(pence ? s.substring(0, s.length() - 1) : s);
        return (v != null && pence) ? v.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP) : v;
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
        try {
            return LocalDate.parse(raw.trim(), DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
