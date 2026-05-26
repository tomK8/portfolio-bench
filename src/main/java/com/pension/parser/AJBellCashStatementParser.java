package com.pension.parser;

import com.pension.domain.model.CashTransaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the fixed-path ~/Downloads/cashstatements.csv cash statement export from AJ Bell.
 *
 * The file is newest-first; rows are reversed internally for chronological balance verification.
 * All amounts are already in GBP, so fx_to_gbp = 1.0 throughout.
 * BALANCE B/F rows (opening balance markers) anchor the verification chain but are not persisted.
 */
public class AJBellCashStatementParser implements CashTransactionParser {

    private static final String ACCOUNT   = "AJBell";
    private static final String CURRENCY  = "GBP";
    private static final double FX_TO_GBP = 1.0;

    private static final String COL_DATE        = "Date";
    private static final String COL_DESCRIPTION = "Description";
    private static final String COL_RECEIPT     = "Receipt (GBP)";
    private static final String COL_PAYMENT     = "Payment (GBP)";
    private static final String COL_BALANCE     = "Balance (GBP)";

    // Matches "Dividend 620 ..." or "DIVIDEND 620 ..."
    private static final Pattern PAT_DIVIDEND = Pattern.compile(
            "(?i)dividend\\s+([\\d,]+(?:\\.\\d+)?)\\s+(.*)", Pattern.DOTALL);

    // Matches "Purchase 100 ...", "Sale 99,802 ...", "Redemption 500 ..."
    private static final Pattern PAT_TRANSACTION = Pattern.compile(
            "(?i)(purchase|sale|redemption)\\s+([\\d,]+(?:\\.\\d+)?)\\s+(.*)", Pattern.DOTALL);

    // "NAME     Redemption" — name comes first, no explicit quantity
    private static final Pattern PAT_REVERSED_REDEMPTION = Pattern.compile(
            "(?i)(.*?)\\s+Redemption\\s*$", Pattern.DOTALL);

    // Used to extract GILT coupon and maturity from descriptions
    private static final Pattern PAT_COUPON    = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern PAT_GILT_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/(\\d{2,4})");

    // Compressed GILT names in redemption rows: TREASURY2.75L24, TSY3.5L28
    private static final Pattern PAT_GILT_TREASURY_L = Pattern.compile(
            "(?i)(?:TREASURY|TSY)\\s*(\\d+\\.\\d+)L(\\d{2})\\b");

    // Compressed GILT names: HM TREA0.2525  (coupon=0.25, year=25→2025)
    private static final Pattern PAT_GILT_HM_TREA = Pattern.compile(
            "(?i)HM\\s+TREA(\\d+\\.\\d{2})(\\d{2})\\b");

    // Ordered description-keyword → portfolio ticker rules; matched against uppercased name.
    // Listed longest/most-specific first to avoid partial matches.
    private record SymbolRule(Pattern pattern, String ticker) {}

    private static final List<SymbolRule> SYMBOL_RULES = List.of(
        new SymbolRule(Pattern.compile("BRITISH AMERICAN TOBACCO"), "BATS"),
        new SymbolRule(Pattern.compile("BANK OF AMERICA"),          "BAC"),
        new SymbolRule(Pattern.compile("BNP PARIBAS"),              "BNP"),
        new SymbolRule(Pattern.compile("SOCIETE GENERALE"),         "GLE"),
        new SymbolRule(Pattern.compile("RHEINMETALL"),              "RHM"),
        new SymbolRule(Pattern.compile("LONDON.*EXCHANGE"),         "LSEG"),
        new SymbolRule(Pattern.compile("\\bRELX\\b"),               "REL"),
        new SymbolRule(Pattern.compile("H.{0,3}S.{0,3}BC"),        "HSBA"),
        new SymbolRule(Pattern.compile("(?:INVESCO|POWERSHARES).*EQQQ"), "EQQQ"),
        new SymbolRule(Pattern.compile("(?:SSGA|STT).*SPDR"),      "SPX5"),
        new SymbolRule(Pattern.compile("ALPHABET"),                 "GOOG/GOOGL"),
        new SymbolRule(Pattern.compile("META PLATFORMS"),           "META"),
        new SymbolRule(Pattern.compile("MICROSOFT"),                "MSFT"),
        new SymbolRule(Pattern.compile("LEGAL.{0,5}GENERAL"),       "LGEN"),
        new SymbolRule(Pattern.compile("\\bINTEL(?:CORP)?\\b"),    "INTC"),
        new SymbolRule(Pattern.compile("\\bAVIVA\\b"),              "AV."),
        new SymbolRule(Pattern.compile("BAE SYSTEMS"),              "BA."),
        new SymbolRule(Pattern.compile("\\bBARCLAYS\\b"),           "BARC"),
        new SymbolRule(Pattern.compile("\\bNETFLIX\\b"),            "NFLX"),
        new SymbolRule(Pattern.compile("\\bAPPLE\\b"),              "AAPL"),
        new SymbolRule(Pattern.compile("\\bAMAZON\\b"),             "AMZN"),
        new SymbolRule(Pattern.compile("WISDOMTREE.*SWISS.*GOLD"),  "SGBX"),
        new SymbolRule(Pattern.compile("WISDOMTREE.*GOLD"),         "PHGP"),
        new SymbolRule(Pattern.compile("GOLD BULLION"),             "GBS"),
        new SymbolRule(Pattern.compile("VANGUARD.*GILT"),           "VGOV"),
        new SymbolRule(Pattern.compile("\\bBP\\b|\\bB\\s+P\\b"),    "BP.")
    );

    // ---- CashTransactionParser ----

    @Override
    public String accountName() { return ACCOUNT; }

    @Override
    public boolean supports(Path file) {
        return "cashstatements.csv".equals(file.getFileName().toString());
    }

    @Override
    public List<CashTransaction> parse(Path file) throws IOException, ParseException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        List<RawRow> rawRows = new ArrayList<>();
        double bofBalance = Double.NaN;

        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord r : csv) {
                String desc = r.get(COL_DESCRIPTION).trim();
                if (desc.toUpperCase().contains("BALANCE B/F")) {
                    bofBalance = parseAmount(r.get(COL_BALANCE));
                    continue; // anchor only — not persisted
                }
                rawRows.add(new RawRow(
                        r.get(COL_DATE).trim(),
                        desc,
                        parseAmount(r.get(COL_RECEIPT)),
                        parseAmount(r.get(COL_PAYMENT)),
                        parseAmount(r.get(COL_BALANCE))
                ));
            }
        }

        if (rawRows.isEmpty()) return List.of();

        // File is newest-first; reverse for chronological balance verification
        Collections.reverse(rawRows);

        double running = deriveStartingBalance(bofBalance, rawRows.get(0));
        List<CashTransaction> result = new ArrayList<>();

        for (RawRow row : rawRows) {
            // Payment column stores negative values (e.g. -5.17 for a charge),
            // so the balance change is receipt + payment in both directions.
            double expected = running + row.receipt() + row.payment();
            if (Math.abs(expected - row.balance()) > 0.02) {
                System.err.printf("[AJBell cash] Balance mismatch at %s '%s': expected %.2f, got %.2f%n",
                        row.date(), row.description(), expected, row.balance());
            }
            running = row.balance();

            double amount = row.receipt() + row.payment(); // positive = cash in, negative = cash out
            ClassifiedRow cr = classify(row.description());
            result.add(new CashTransaction(
                    convertDate(row.date()),
                    ACCOUNT,
                    cr.type(),
                    cr.symbol(),
                    cr.quantity(),
                    amount,
                    CURRENCY,
                    FX_TO_GBP,
                    amount,
                    row.balance(),
                    row.description()
            ));
        }

        // Second pass: for Redemption rows whose symbol couldn't be resolved to a GILT ID
        // (e.g. "TSY STK25 Redemption" has no coupon), borrow the symbol from a same-date DIVIDEND.
        Map<String, String> dateToGilt = new HashMap<>();
        for (CashTransaction t : result) {
            if ("DIVIDEND".equals(t.type()) && t.symbol().startsWith("GILT ")) {
                dateToGilt.put(t.transactionDate(), t.symbol());
            }
        }
        result.replaceAll(t -> {
            if ("TRANSACTION".equals(t.type())
                    && !t.symbol().startsWith("GILT ")
                    && t.description().trim().toUpperCase().endsWith("REDEMPTION")) {
                String gilt = dateToGilt.get(t.transactionDate());
                if (gilt != null) {
                    return new CashTransaction(t.transactionDate(), t.account(), t.type(),
                            gilt, t.quantity(), t.amount(), t.currency(), t.fxToGbp(),
                            t.amountGbp(), t.cashBalanceGbp(), t.description());
                }
            }
            return t;
        });

        return result;
    }

    // ---- Classification ----

    private record ClassifiedRow(String type, String symbol, double quantity) {}

    private ClassifiedRow classify(String description) {
        Matcher m = PAT_DIVIDEND.matcher(description);
        if (m.matches()) {
            return new ClassifiedRow("DIVIDEND", resolveSymbol(m.group(2).trim()), parseQty(m.group(1)));
        }

        m = PAT_TRANSACTION.matcher(description);
        if (m.matches()) {
            return new ClassifiedRow("TRANSACTION", resolveSymbol(m.group(3).trim()), parseQty(m.group(2)));
        }

        String upper = description.trim().toUpperCase();
        if (upper.contains("CHARGE"))             return new ClassifiedRow("CHARGE",       "GBP", 0.0);
        if (upper.startsWith("GROSS INTEREST"))   return new ClassifiedRow("INTEREST",     "GBP", 0.0);
        if (upper.startsWith("PENSION")
         || upper.startsWith("TRANSFER"))         return new ClassifiedRow("CONTRIBUTION", "GBP", 0.0);

        m = PAT_REVERSED_REDEMPTION.matcher(description);
        if (m.matches()) {
            return new ClassifiedRow("TRANSACTION", resolveSymbol(m.group(1).trim()), 0.0);
        }

        System.err.println("[AJBell cash] Unclassified row, defaulting to CONTRIBUTION: " + description);
        return new ClassifiedRow("CONTRIBUTION", "GBP", 0.0);
    }

    // ---- Symbol resolution ----

    private static String resolveSymbol(String name) {
        String bondId = tryExtractGiltId(name);
        if (bondId != null) return bondId;

        String upper = name.toUpperCase();
        for (SymbolRule rule : SYMBOL_RULES) {
            if (rule.pattern().matcher(upper).find()) return rule.ticker();
        }

        return cleanName(name);
    }

    private static String tryExtractGiltId(String name) {
        // Compressed "TREASURY2.75L24" / "TSY3.5L28" format
        Matcher lm = PAT_GILT_TREASURY_L.matcher(name);
        if (lm.find()) {
            return "GILT " + lm.group(1) + "% " + (Integer.parseInt(lm.group(2)) + 2000);
        }
        // Compressed "HM TREA0.2525" format (coupon=0.25, year=25)
        Matcher hm = PAT_GILT_HM_TREA.matcher(name);
        if (hm.find()) {
            return "GILT " + hm.group(1) + "% " + (Integer.parseInt(hm.group(2)) + 2000);
        }
        // Standard "0.875% TREASURY GILT 22/01/2033" format
        Matcher cm = PAT_COUPON.matcher(name);
        Matcher dm = PAT_GILT_DATE.matcher(name);
        if (cm.find() && dm.find()) {
            String coupon = cm.group(1);
            String yr = dm.group(1);
            int year = Integer.parseInt(yr);
            if (yr.length() == 2) year += 2000;
            return "GILT " + coupon + "% " + year;
        }
        return null;
    }

    private static String cleanName(String name) {
        String cleaned = name.trim().toUpperCase();
        cleaned = cleaned.replaceAll("\\s+(ORD|NPV|COM|ADR)\\b.*$", "").trim();
        cleaned = cleaned.replaceAll("\\s+(GBP|USD|EUR)\\d.*$",     "").trim();
        return cleaned.isEmpty() ? name.trim().toUpperCase() : cleaned;
    }

    // ---- Helpers ----

    private static double deriveStartingBalance(double bofBalance, RawRow firstRow) {
        if (!Double.isNaN(bofBalance)) return bofBalance;
        // No B/F row: back-calculate from the oldest transaction
        return firstRow.balance() - firstRow.receipt() - firstRow.payment();
    }

    private static double parseAmount(String value) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) return 0.0;
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double parseQty(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String convertDate(String ddmmyyyy) {
        String[] p = ddmmyyyy.split("/");
        return p.length == 3 ? p[2] + "-" + p[1] + "-" + p[0] : ddmmyyyy;
    }

    private record RawRow(String date, String description, double receipt, double payment, double balance) {}
}
