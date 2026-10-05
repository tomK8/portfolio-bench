package com.portfolio.parser;

import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the ~/Downloads/cashstatements*.csv cash statement export from AJ Bell. One instance per
 * AJ Bell account.
 * <p>
 * The file is newest-first; rows are reversed internally for chronological balance verification.
 * All amounts are already in GBP, so fx_to_gbp = 1.0 throughout.
 * BALANCE B/F rows (opening balance markers) anchor the verification chain but are not persisted.
 *
 * <p><b>The statement does not say which AJ Bell account it is.</b> Two accounts behind one login
 * export the same filename with the same header and no account number in it, so which ledger a
 * statement belongs to is decided outside this class, by {@code AjBellFileRouter}, from the
 * instruments the statement names ({@link #symbolsIn}). The account is then handed in at
 * construction. Misfiling one would corrupt two ledgers at once and the dedup key is scoped per
 * account, so the next import would not repair it — which is why an unroutable statement is
 * refused rather than defaulted.
 */
public class AJBellCashStatementParser implements CashTransactionParser {

    private static final Logger log = LoggerFactory.getLogger(AJBellCashStatementParser.class);

    private static final String CURRENCY = "GBP";
    private static final double FX_TO_GBP = 1.0;

    private static final String COL_DATE = "Date";
    private static final String COL_DESCRIPTION = "Description";
    private static final String COL_RECEIPT = "Receipt (GBP)";
    private static final String COL_PAYMENT = "Payment (GBP)";
    private static final String COL_BALANCE = "Balance (GBP)";

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
    private static final Pattern PAT_COUPON = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern PAT_GILT_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/(\\d{2,4})");

    // Compressed GILT names in redemption rows: TREASURY2.75L24, TSY3.5L28
    private static final Pattern PAT_GILT_TREASURY_L = Pattern.compile(
            "(?i)(?:TREASURY|TSY)\\s*(\\d+\\.\\d+)L(\\d{2})\\b");

    // Compressed GILT names: HM TREA0.2525  (coupon=0.25, year=25→2025)
    private static final Pattern PAT_GILT_HM_TREA = Pattern.compile(
            "(?i)HM\\s+TREA(\\d+\\.\\d{2})(\\d{2})\\b");
    /**
     * User-added keyword → symbol aliases from {@code instrument_aliases}, longest keyword first.
     * Consulted before {@link #SYMBOL_RULES}; read per parse so a mapping added on the dashboard
     * applies to the next import without a restart.
     */
    private final Supplier<Map<String, String>> aliasSource;

    /** The ledger these rows are filed against. */
    private final Account account;

    /** Committed rules only — the default for tests and any alias-store-less context. */
    public AJBellCashStatementParser() {
        this(Map::of);
    }

    public AJBellCashStatementParser(Supplier<Map<String, String>> aliasSource) {
        this(Account.AJBELL, aliasSource);
    }

    public AJBellCashStatementParser(Account account, Supplier<Map<String, String>> aliasSource) {
        this.account = account;
        this.aliasSource = aliasSource == null ? Map::of : aliasSource;
    }

    /** The same parser reading for another AJ Bell account, once the statement has been routed. */
    public AJBellCashStatementParser forAccount(Account other) {
        return other == account ? this : new AJBellCashStatementParser(other, aliasSource);
    }

    private static final List<SymbolRule> SYMBOL_RULES = List.of(
            new SymbolRule(Pattern.compile("BRITISH AMERICAN TOBACCO"), "BATS"),
            new SymbolRule(Pattern.compile("BANK OF AMERICA"), "BAC"),
            new SymbolRule(Pattern.compile("BNP PARIBAS"), "BNP"),
            new SymbolRule(Pattern.compile("SOCIETE GENERALE"), "GLE"),
            new SymbolRule(Pattern.compile("RHEINMETALL"), "RHM"),
            new SymbolRule(Pattern.compile("LONDON.*EXCHANGE"), "LSEG"),
            // UK REITs. LONDONMETRIC is listed after LONDON.*EXCHANGE only for grouping — the two
            // can't collide, "LondonMetric Property PLC" carries no "EXCHANGE".
            new SymbolRule(Pattern.compile("PRIMARY HEALTH"), "PHP"),
            new SymbolRule(Pattern.compile("LONDONMETRIC"), "LMP"),
            new SymbolRule(Pattern.compile("BRITISH LAND"), "BLND"),
            new SymbolRule(Pattern.compile("\\bRELX\\b"), "REL"),
            new SymbolRule(Pattern.compile("H.{0,3}S.{0,3}BC"), "HSBA"),
            new SymbolRule(Pattern.compile("(?:INVESCO|POWERSHARES).*EQQQ"), "EQQQ"),
            new SymbolRule(Pattern.compile("(?:SSGA|STT).*SPDR"), "SPX5"),
            new SymbolRule(Pattern.compile("ALPHABET"), "GOOG"),   // AJBell holds Class C (GOOG)
            new SymbolRule(Pattern.compile("META PLATFORMS"), "META"),
            new SymbolRule(Pattern.compile("MICROSOFT"), "MSFT"),
            new SymbolRule(Pattern.compile("LEGAL.{0,5}GENERAL"), "LGEN"),
            new SymbolRule(Pattern.compile("\\bINTEL(?:CORP)?\\b"), "INTC"),
            new SymbolRule(Pattern.compile("\\bAVIVA\\b"), "AV."),
            new SymbolRule(Pattern.compile("\\bASTRAZENECA\\b"), "AZN"),
            new SymbolRule(Pattern.compile("B\\.?\\s*A\\.?\\s*E\\.?\\s*SYSTEMS"), "BA."),
            new SymbolRule(Pattern.compile("\\bBARCLAYS\\b"), "BARC"),
            new SymbolRule(Pattern.compile("\\bNETFLIX\\b"), "NFLX"),
            new SymbolRule(Pattern.compile("\\bAPPLE\\b"), "AAPL"),
            new SymbolRule(Pattern.compile("\\bAMAZON\\b"), "AMZN"),
            new SymbolRule(Pattern.compile("WISDOMTREE.*SWISS.*GOLD"), "SGBX"),
            new SymbolRule(Pattern.compile("WISDOMTREE.*GOLD"), "PHGP"),
            new SymbolRule(Pattern.compile("GOLD BULLION"), "GBS"),
            new SymbolRule(Pattern.compile("VANGUARD.*GILT"), "VGOV"),
            new SymbolRule(Pattern.compile("\\bBP\\b|\\bB\\s+P\\b"), "BP.")
    );

    /**
     * The symbol a description resolves to, or null when no rule matches — the quiet form. Used by
     * {@link #symbolsIn} so that merely sniffing a file to find out whose account it is does not
     * emit the "add a mapping" warning for every row of every candidate file in the directory.
     */
    private String resolveSymbolOrNull(String name) {
        String bondId = tryExtractGiltId(name);
        if (bondId != null) return bondId;

        // User-added aliases first, so a keyword entered on the dashboard also overrides a
        // committed rule that turns out to match the wrong instrument. Matched on the canonical
        // form of the description (Instruments.matchKey), the same one the keyword was stored in,
        // so punctuation in the name can't stop it firing. SYMBOL_RULES below stay on the raw
        // uppercased name — those are regexes that deliberately read punctuation ("B.A.E SYSTEMS").
        String lower = Instruments.matchKey(name);
        Map<String, String> aliases = aliasSource.get();
        if (aliases != null) {
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (lower.contains(e.getKey())) return e.getValue();
            }
        }

        String upper = name.toUpperCase();
        for (SymbolRule rule : SYMBOL_RULES) {
            if (rule.pattern().matcher(upper).find()) return rule.ticker();
        }
        return null;
    }

    /**
     * Every instrument the statement names, as internal symbols — the evidence this file's account
     * is routed on. A row naming no instrument (a charge, interest, a contribution) and a name no
     * rule matches both contribute nothing, so a statement of nothing but fees is honestly
     * unroutable rather than filed against whichever account happened to ask first.
     */
    public Set<String> symbolsIn(Path file) {
        Set<String> out = new LinkedHashSet<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            while (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader().setSkipHeaderRecord(true).build();
            try (CSVParser csv = CSVParser.parse(content, format)) {
                for (CSVRecord r : csv) {
                    String desc = r.get(COL_DESCRIPTION).trim();
                    if (desc.toUpperCase().contains("BALANCE B/F")) continue;
                    ClassifiedRow cr = classify(desc, true);
                    if (cr.mapped()) out.add(cr.symbol().toUpperCase());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read instruments from {} — it cannot be routed to an account", file, e);
        }
        return out;
    }

    // ---- CashTransactionParser ----

    private static String tryExtractGiltId(String name) {
        // Compressed "TREASURY2.75L24" / "TSY3.5L28" format
        Matcher lm = PAT_GILT_TREASURY_L.matcher(name);
        if (lm.find()) {
            return Instruments.giltId(lm.group(1), Integer.parseInt(lm.group(2)) + 2000);
        }
        // Compressed "HM TREA0.2525" format (coupon=0.25, year=25)
        Matcher hm = PAT_GILT_HM_TREA.matcher(name);
        if (hm.find()) {
            return Instruments.giltId(hm.group(1), Integer.parseInt(hm.group(2)) + 2000);
        }
        // Standard "0.875% TREASURY GILT 22/01/2033" format
        Matcher cm = PAT_COUPON.matcher(name);
        Matcher dm = PAT_GILT_DATE.matcher(name);
        if (cm.find() && dm.find()) {
            String yr = dm.group(1);
            int year = Integer.parseInt(yr);
            if (yr.length() == 2) year += 2000;
            return Instruments.giltId(cm.group(1), year);
        }
        return null;
    }

    private static String cleanName(String name) {
        String cleaned = name.trim().toUpperCase();
        cleaned = cleaned.replaceAll("\\s+(ORD|NPV|COM|ADR)\\b.*$", "").trim();
        cleaned = cleaned.replaceAll("\\s+(GBP|USD|EUR)\\d.*$", "").trim();
        return cleaned.isEmpty() ? name.trim().toUpperCase() : cleaned;
    }

    private static double deriveStartingBalance(double bofBalance, RawRow firstRow) {
        if (!Double.isNaN(bofBalance)) return bofBalance;
        // No B/F row: back-calculate from the oldest transaction
        return firstRow.balance() - firstRow.receipt() - firstRow.payment();
    }

    // ---- Classification ----

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

    // ---- Symbol resolution ----

    private static String convertDate(String ddmmyyyy) {
        String[] p = ddmmyyyy.split("/");
        return p.length == 3 ? p[2] + "-" + p[1] + "-" + p[0] : ddmmyyyy;
    }

    @Override
    public Account account() {
        return account;
    }

    @Override
    public boolean supports(Path file) {
        return isAjBellCashFile(file);
    }

    /**
     * True for any AJ Bell cash statement, whichever account it turns out to be for. The browser
     * suffixes a second download ({@code cashstatements (1).csv}), which is exactly how both
     * accounts' statements come to sit in the directory at once.
     */
    public static boolean isAjBellCashFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("cashstatements") && name.endsWith(".csv");
    }

    // ---- Helpers ----

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
                log.warn("[" + account.dbValue() + " cash] Balance mismatch at {} '{}': expected {}, got {}",
                        row.date(), row.description(), String.format("%.2f", expected),
                        String.format("%.2f", row.balance()));
            }
            running = row.balance();

            double amount = row.receipt() + row.payment(); // positive = cash in, negative = cash out
            ClassifiedRow cr = classify(row.description());
            result.add(new CashTransaction(
                    convertDate(row.date()),
                    account,
                    cr.type(),
                    cr.symbol(),
                    cr.quantity(),
                    amount,
                    CURRENCY,
                    FX_TO_GBP,
                    amount,
                    row.balance(),
                    row.balance(),
                    row.description()
            ));
        }

        // Second pass: for Redemption rows whose symbol couldn't be resolved to a GILT ID
        // (e.g. "TSY STK25 Redemption" has no coupon), borrow the symbol AND the position
        // size from a same-date DIVIDEND. Redemption descriptions don't carry the quantity,
        // so without this back-fill the row would have qty=0 and the FIFO position-from-
        // ledger reconstruction (used by the cash-ledger view and the value chart) would
        // never see the gilt as sold — the holding would stay open forever.
        record DividendInfo(String symbol, double quantity) {
        }
        Map<String, DividendInfo> dateToGilt = new HashMap<>();
        for (CashTransaction t : result) {
            if (t.type() == TransactionType.DIVIDEND && t.symbol().startsWith("GILT ")) {
                dateToGilt.put(t.transactionDate(), new DividendInfo(t.symbol(), t.quantity()));
            }
        }
        result.replaceAll(t -> {
            if (t.type() == TransactionType.TRANSACTION
                    && !t.symbol().startsWith("GILT ")
                    && t.description().trim().toUpperCase().endsWith("REDEMPTION")) {
                DividendInfo info = dateToGilt.get(t.transactionDate());
                if (info != null) {
                    return new CashTransaction(t.transactionDate(), t.account(), t.type(),
                            info.symbol(), info.quantity(), t.amount(), t.currency(), t.fxToGbp(),
                            t.amountGbp(), t.cashBalance(), t.cashBalanceGbp(), t.description());
                }
            }
            return t;
        });

        return result;
    }

    private ClassifiedRow classify(String description) {
        return classify(description, false);
    }

    /** @param quiet suppress the unmapped-name and unclassified-row warnings (routing sniff only) */
    private ClassifiedRow classify(String description, boolean quiet) {
        Matcher m = PAT_DIVIDEND.matcher(description);
        if (m.matches()) {
            return instrument(TransactionType.DIVIDEND, m.group(2).trim(), parseQty(m.group(1)), quiet);
        }

        m = PAT_TRANSACTION.matcher(description);
        if (m.matches()) {
            return instrument(TransactionType.TRANSACTION, m.group(3).trim(), parseQty(m.group(2)), quiet);
        }

        String upper = description.trim().toUpperCase();
        if (upper.contains("CHARGE")) return cash(TransactionType.CHARGE);
        if (upper.startsWith("GROSS INTEREST")) return cash(TransactionType.INTEREST);
        if (upper.startsWith("PENSION")
                || upper.startsWith("TRANSFER")) return cash(TransactionType.CONTRIBUTION);

        m = PAT_REVERSED_REDEMPTION.matcher(description);
        if (m.matches()) {
            return instrument(TransactionType.TRANSACTION, m.group(1).trim(), 0.0, quiet);
        }

        if (!quiet) {
            log.warn("[{} cash] Unclassified row, defaulting to CONTRIBUTION: {}",
                    account.dbValue(), description);
        }
        return cash(TransactionType.CONTRIBUTION);
    }

    /** A row that moves cash and names no instrument. */
    private static ClassifiedRow cash(TransactionType type) {
        return new ClassifiedRow(type, "GBP", 0.0, false);
    }

    /**
     * A row that names an instrument. An unmapped name is kept as the cleaned-up description — it
     * is visible and fixable that way — but flagged {@code mapped=false}, because it joins to
     * nothing: the holdings file calls the same position PHP while the ledger would call it
     * PRIMARY HEALTH PROPERTIES PLC, the reconciliation panel reports the whole position as drift,
     * and no price feed resolves a long name. Warn with the fix at parse time rather than leaving
     * it to be noticed downstream.
     */
    private ClassifiedRow instrument(TransactionType type, String name, double qty, boolean quiet) {
        String mapped = resolveSymbolOrNull(name);
        if (mapped != null) return new ClassifiedRow(type, mapped, qty, true);
        String cleaned = cleanName(name);
        if (!quiet) {
            log.warn("[{} cash] No symbol rule for \"{}\" — storing it as \"{}\". Map it from the "
                    + "Open orders tab (saving a name mapping there also rewrites ledger rows "
                    + "already stored under the long name), or this position will not price or "
                    + "reconcile.", account.dbValue(), name, cleaned);
        }
        return new ClassifiedRow(type, cleaned, qty, false);
    }

    // Ordered description-keyword → portfolio ticker rules; matched against uppercased name.
    // Listed longest/most-specific first to avoid partial matches.
    private record SymbolRule(Pattern pattern, String ticker) {
    }

    /** @param mapped whether {@code symbol} is a real internal symbol rather than a raw name */
    private record ClassifiedRow(TransactionType type, String symbol, double quantity,
                                 boolean mapped) {
    }

    private record RawRow(String date, String description, double receipt, double payment, double balance) {
    }
}
