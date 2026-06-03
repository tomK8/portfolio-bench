package com.pension.parser;

import com.pension.domain.model.CashTransaction;
import com.pension.port.HistoricalFxRateProvider;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses UUID-named II cash statement CSVs (one per currency — e.g. one GBP file
 * and one USD file). Distinguished from the holdings UUID CSVs by header sniff
 * (this file's header carries {@code Debit,Credit,Running Balance}).
 *
 * <p>Single account {@code "II"}; the row's {@code currency} column distinguishes
 * the per-currency cash ledger. File currency is inferred from the £/$ prefix of
 * the first amount cell.
 *
 * <p>Every trade row produces a {@code TRANSACTION} + {@code CHARGE} pair so the
 * commission and FX markup are recorded as a distinct cost rather than being
 * buried in the position cost basis. Listing currency is detected heuristically:
 * try the file currency first; if {@code price·qty} doesn't agree with the file
 * debit/credit within 0.5%, try other currencies via historical FX and pick the
 * smallest residual — the residual then becomes the markup.
 *
 * <p>After processing each row the cumulative balance is checked against the
 * file's {@code Running Balance} column; a mismatch greater than 1p / 1c aborts
 * the import with a {@link ParseException}.
 */
public class IICashStatementParser implements CashTransactionParser {

    static final String ACCOUNT = "II";

    private static final DateTimeFormatter DDMMYYYY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Pattern UUID_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv",
            Pattern.CASE_INSENSITIVE);

    private static final String COL_DATE = "Date";
    private static final String COL_SYMBOL = "Symbol";
    private static final String COL_QTY = "Quantity";
    private static final String COL_PRICE = "Price";
    private static final String COL_DESC = "Description";
    private static final String COL_DEBIT = "Debit";
    private static final String COL_CREDIT = "Credit";
    private static final String COL_BALANCE = "Running Balance";

    /** "10 ALPHABET  Del  359.00 S Date 03/06/26" — qty, name, Del|Bal, price, S Date. */
    private static final Pattern PAT_TRADE = Pattern.compile(
            "^\\s*([\\d.]+)\\s+(.+?)\\s+(Del|Bal)\\s+[\\d.]+\\s+S\\s+Date\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}\\s*$");

    /** "100 POUNDS STERLING NoTf 1.32 S Date 02/04/26" — currency-exchange row. */
    private static final Pattern PAT_FX_EXCHANGE = Pattern.compile(
            "(?i).*POUNDS STERLING NoTf.*");

    private static final Pattern PAT_DIVIDEND = Pattern.compile("(?i)^Div\\s+.+");
    private static final Pattern PAT_INTEREST = Pattern.compile("(?i)^Gross\\s+interest\\b.*");
    private static final Pattern PAT_MONTHLY_FEE = Pattern.compile("(?i)^Total\\s+Monthly\\s+Fee\\s*$");
    private static final Pattern PAT_CONTRIBUTION = Pattern.compile(
            "(?i)^(Trf\\s+from|PBB\\s+NET\\s+CONTRIBUTION|Basic\\s+rate\\s+tax\\s+relief).*");

    /** Foreign currencies considered for listing-currency detection beyond the file currency. */
    private static final List<String> FOREIGN_CANDIDATES = List.of("USD", "GBP", "EUR");

    private static final double SAME_CCY_TOLERANCE = 0.005;  // 0.5% — same-ccy commission only
    private static final double CROSS_CCY_TOLERANCE = 0.05;  // 5% — FX charge + commission ceiling

    private final HistoricalFxRateProvider fxProvider;

    public IICashStatementParser(HistoricalFxRateProvider fxProvider) {
        this.fxProvider = fxProvider;
    }

    @Override
    public String accountName() {
        return ACCOUNT;
    }

    @Override
    public boolean supports(Path file) {
        if (!UUID_FILENAME.matcher(file.getFileName().toString()).matches()) return false;
        if (!Files.exists(file)) return false;
        try {
            String firstLine = Files.lines(file, StandardCharsets.UTF_8)
                    .filter(l -> !l.isBlank() && !l.chars().allMatch(c -> c == '﻿'))
                    .findFirst().orElse("");
            return firstLine.contains(COL_DEBIT)
                    && firstLine.contains(COL_CREDIT)
                    && firstLine.contains(COL_BALANCE);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Top-level parse ----------------------------------------------------

    @Override
    public List<CashTransaction> parse(Path file) throws IOException, ParseException {
        RawFile raw = readRawRows(file);
        List<RawRow> rows = raw.rows();
        if (rows.isEmpty()) return List.of();

        // File is newest-first; reverse so balances accumulate forward chronologically.
        Collections.reverse(rows);

        String fileCcy = raw.fileCcy();
        Map<String, Map<LocalDate, BigDecimal>> fxByCcy = fetchFxSeries(rows, fileCcy);

        List<CashTransaction> result = new ArrayList<>();
        double running = rows.get(0).balance() - rows.get(0).net();

        for (RawRow row : rows) {
            double expected = running + row.net();
            if (Math.abs(expected - row.balance()) > 0.02) {
                throw new ParseException(String.format(
                        "[II cash %s] Running-balance mismatch at %s '%s': "
                                + "prior %.2f + net %.2f = %.2f, file shows %.2f",
                        fileCcy, row.date(), row.description(),
                        running, row.net(), expected, row.balance()));
            }
            running = row.balance();
            result.addAll(classify(row, fileCcy, fxByCcy));
        }
        return result;
    }

    // ---- Row classification -------------------------------------------------

    private List<CashTransaction> classify(RawRow row, String fileCcy,
                                           Map<String, Map<LocalDate, BigDecimal>> fxByCcy)
            throws ParseException {
        String desc = row.description();
        double fxToGbp = fxToGbp(fileCcy, row.date(), fxByCcy);
        String isoDate = row.date().toString();

        if (PAT_FX_EXCHANGE.matcher(desc).matches()) {
            // Currency exchange — paired row in the other file produces the mirror entry.
            return List.of(make(isoDate, "CONTRIBUTION", "FX", 0.0,
                    row.net(), fileCcy, fxToGbp, row.balance(), desc));
        }
        if (PAT_DIVIDEND.matcher(desc).matches()) {
            return List.of(make(isoDate, "DIVIDEND", row.symbol(), 0.0,
                    row.net(), fileCcy, fxToGbp, row.balance(), desc));
        }
        if (PAT_INTEREST.matcher(desc).matches()) {
            return List.of(make(isoDate, "INTEREST", fileCcy, 0.0,
                    row.net(), fileCcy, fxToGbp, row.balance(), desc));
        }
        if (PAT_MONTHLY_FEE.matcher(desc).matches()) {
            return List.of(make(isoDate, "CHARGE", fileCcy, 0.0,
                    row.net(), fileCcy, fxToGbp, row.balance(), desc));
        }
        if (PAT_CONTRIBUTION.matcher(desc).matches()) {
            return List.of(make(isoDate, "CONTRIBUTION", fileCcy, 0.0,
                    row.net(), fileCcy, fxToGbp, row.balance(), desc));
        }

        Matcher trade = PAT_TRADE.matcher(desc);
        if (trade.matches() && !row.symbol().isEmpty()) {
            return splitTrade(row, fileCcy, fxByCcy, fxToGbp);
        }

        System.err.printf("[II cash %s] Unclassified row, defaulting to CONTRIBUTION: %s%n",
                fileCcy, desc);
        return List.of(make(isoDate, "CONTRIBUTION", fileCcy, 0.0,
                row.net(), fileCcy, fxToGbp, row.balance(), desc));
    }

    /** TRANSACTION (gross, in file ccy) + CHARGE (commission/FX markup, in file ccy). */
    private List<CashTransaction> splitTrade(RawRow row, String fileCcy,
                                             Map<String, Map<LocalDate, BigDecimal>> fxByCcy,
                                             double fxToGbp) throws ParseException {
        double qty = row.qty();
        double nativePrice = row.price();
        double rowAbsAmount = Math.abs(row.net());
        boolean isBuy = row.net() < 0;

        Listing best = detectListingCcy(nativePrice, qty, rowAbsAmount, fileCcy, row.date(), fxByCcy);
        // Gross trade value in file currency, signed: negative for buys (cash out), positive for sells.
        double grossFileCcy = best.grossInFileCcy() * (isBuy ? -1 : 1);
        // Markup (commission + FX): for a buy the file debit exceeds gross; for a sell the file
        // credit is less than gross. Either way the markup is a cost, so the CHARGE row is signed
        // such that TRANSACTION + CHARGE = file net debit/credit.
        double markup = row.net() - grossFileCcy;

        String isoDate = row.date().toString();
        String symbol = row.symbol();
        String desc = row.description();

        CashTransaction tx = new CashTransaction(
                isoDate, ACCOUNT, "TRANSACTION", symbol, qty,
                grossFileCcy, fileCcy, fxToGbp, grossFileCcy / fxToGbp,
                null,                       // intermediate row — running balance set on the CHARGE
                null,
                desc);
        CashTransaction charge = new CashTransaction(
                isoDate, ACCOUNT, "CHARGE", symbol, 0.0,
                markup, fileCcy, fxToGbp, markup / fxToGbp,
                row.balance(), row.balance() / fxToGbp,
                desc + " [commission+FX]");
        return List.of(tx, charge);
    }

    // ---- Listing-currency detection ----------------------------------------

    private Listing detectListingCcy(double price, double qty, double absAmount,
                                     String fileCcy, LocalDate date,
                                     Map<String, Map<LocalDate, BigDecimal>> fxByCcy)
            throws ParseException {
        // Same-currency candidate first: price·qty already in file ccy.
        double sameGross = price * qty;
        double sameResidual = absAmount - sameGross;
        if (Math.abs(sameResidual) / absAmount < SAME_CCY_TOLERANCE) {
            return new Listing(fileCcy, sameGross);
        }

        // Otherwise try foreign candidates and pick the smallest absolute residual.
        Listing best = new Listing(fileCcy, sameGross);
        double bestResidualRatio = Math.abs(sameResidual) / absAmount;

        for (String ccy : FOREIGN_CANDIDATES) {
            if (ccy.equals(fileCcy)) continue;
            BigDecimal ccyPerGbp = HistoricalFxRateProvider.rateOnOrBefore(
                    fxByCcy.getOrDefault(ccy, Map.of()), date);
            if (ccyPerGbp == null) continue;
            double fileCcyPerGbp = fxToGbp(fileCcy, date, fxByCcy);

            // price·qty is in `ccy`; convert to file ccy via GBP cross-rate.
            // (units in fileCcy) = (units in ccy) / (ccy per GBP) * (fileCcy per GBP)
            double grossFileCcy = price * qty / ccyPerGbp.doubleValue() * fileCcyPerGbp;
            double residualRatio = Math.abs(absAmount - grossFileCcy) / absAmount;
            if (residualRatio < bestResidualRatio) {
                bestResidualRatio = residualRatio;
                best = new Listing(ccy, grossFileCcy);
            }
        }

        if (bestResidualRatio > CROSS_CCY_TOLERANCE) {
            throw new ParseException(String.format(
                    "Could not match trade to any listing currency within %.0f%% "
                            + "(date=%s, price=%.4f, qty=%.4f, file amount=%.2f %s, best residual %.1f%%)",
                    CROSS_CCY_TOLERANCE * 100, date, price, qty, absAmount, fileCcy,
                    bestResidualRatio * 100));
        }
        return best;
    }

    // ---- FX rates ----------------------------------------------------------

    private Map<String, Map<LocalDate, BigDecimal>> fetchFxSeries(List<RawRow> rows, String fileCcy)
            throws ParseException {
        LocalDate min = rows.stream().map(RawRow::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = rows.stream().map(RawRow::date).max(Comparator.naturalOrder()).orElseThrow();
        Map<String, Map<LocalDate, BigDecimal>> result = new HashMap<>();
        Set<String> needed = new LinkedHashSet<>();
        if (!"GBP".equals(fileCcy)) needed.add(fileCcy);
        for (String c : FOREIGN_CANDIDATES) if (!"GBP".equals(c) && !c.equals(fileCcy)) needed.add(c);
        for (String ccy : needed) {
            try {
                result.put(ccy, fxProvider.fetchRateSeries(ccy, min.minusDays(7), max));
            } catch (Exception e) {
                throw new ParseException("Could not fetch historical FX for " + ccy + ": " + e.getMessage());
            }
        }
        return result;
    }

    private double fxToGbp(String ccy, LocalDate date,
                           Map<String, Map<LocalDate, BigDecimal>> fxByCcy) throws ParseException {
        if ("GBP".equals(ccy)) return 1.0;
        BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(
                fxByCcy.getOrDefault(ccy, Map.of()), date);
        if (rate == null) throw new ParseException("No GBP→" + ccy + " rate on or before " + date);
        return rate.doubleValue();
    }

    // ---- Raw row reading ---------------------------------------------------

    private static RawFile readRawRows(Path file) throws IOException, ParseException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build();

        List<RawRow> rows = new ArrayList<>();
        String fileCcy = null;
        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord r : csv) {
                String dateStr = trimToNull(r.get(COL_DATE));
                if (dateStr == null) continue;
                LocalDate date = LocalDate.parse(dateStr, DDMMYYYY);
                String symbol = naBlank(r.get(COL_SYMBOL));
                Double qty = parseAmount(r.get(COL_QTY));
                Double price = parseAmount(r.get(COL_PRICE));
                String desc = r.get(COL_DESC).trim();
                String debitRaw = r.get(COL_DEBIT);
                String creditRaw = r.get(COL_CREDIT);
                String balanceRaw = r.get(COL_BALANCE);
                if (fileCcy == null) fileCcy = detectCcyPrefix(balanceRaw);
                Double debit = parseAmount(debitRaw);
                Double credit = parseAmount(creditRaw);
                Double balance = parseAmount(balanceRaw);
                if (balance == null) {
                    throw new ParseException("Missing Running Balance at " + date + " '" + desc + "'");
                }
                double net = (credit == null ? 0 : credit) - (debit == null ? 0 : debit);
                rows.add(new RawRow(date, symbol, qty == null ? 0 : qty,
                        price == null ? 0 : price, desc, net, balance));
            }
        }
        if (fileCcy == null) throw new ParseException("Could not detect file currency in " + file);
        return new RawFile(fileCcy, rows);
    }

    private static String detectCcyPrefix(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("£") || t.startsWith("\"£")) return "GBP";
        if (t.startsWith("$") || t.startsWith("\"$")) return "USD";
        if (t.startsWith("€") || t.startsWith("\"€")) return "EUR";
        return null;
    }

    private static String naBlank(String v) {
        if (v == null) return "";
        String t = v.trim();
        return ("n/a".equalsIgnoreCase(t) || t.isEmpty()) ? "" : t;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static Double parseAmount(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || "n/a".equalsIgnoreCase(t)) return null;
        String clean = t.replaceAll("[£$,€]", "").trim();
        if (clean.isEmpty()) return null;
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private static CashTransaction make(String date, String type, String symbol, double qty,
                                        double amount, String currency, double fxToGbp,
                                        Double cashBalance, String description) {
        return new CashTransaction(date, ACCOUNT, type, symbol, qty,
                amount, currency, fxToGbp, amount / fxToGbp,
                cashBalance, cashBalance == null ? null : cashBalance / fxToGbp,
                description);
    }

    private record RawRow(LocalDate date, String symbol, double qty, double price,
                          String description, double net, double balance) {
    }

    private record RawFile(String fileCcy, List<RawRow> rows) {
    }

    private record Listing(String currency, double grossInFileCcy) {
    }
}
