package com.portfolio.parser;

import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Holding;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code portfolio*.csv} exports from AJ Bell. One subclass per AJ Bell account.
 * <p>
 * All portfolio values (Cost, Value) are expressed in GBP.
 * Each row carries its own Valuation currency (GBP/USD/EUR) for downstream
 * FX conversion and live-price fetching.
 * Average price paid = Cost (£) / Quantity.
 * Bond rows identified by (SEDOL:...) in the Investment description are
 * given a human-readable ID of the form "GILT {coupon}% {year}".
 *
 * <p><b>The file says which account it is, twice</b> — in the filename
 * ({@code portfolio-A50742S-SIPP.csv} vs {@code portfolio-A50742D-Dealing account.csv}) and in
 * every row's {@code Portfolio} column. {@link #supports} tests the column rather than the
 * filename, because the column is the broker's own statement of it and survives a rename or a
 * browser's {@code (2)} suffix. This is also the only AJ Bell export that identifies its account
 * at all, which is why it is the registry {@link com.portfolio.domain.AjBellPortfolios} routes the
 * other two against — see {@link #tickersIn}.
 */
public abstract class AJBellPortfolioParser implements AccountParser {

    private static final Logger log = LoggerFactory.getLogger(AJBellPortfolioParser.class);

    private static final String COL_INVESTMENT = "Investment";
    private static final String COL_QUANTITY = "Quantity";
    private static final String COL_COST = "Cost (£)";
    private static final String COL_VALUE_GBP = "Value (£)";   // always GBP
    private static final String COL_EXCHANGE_RATE = "Exchange rate"; // units of Valuation currency per 1 GBP
    private static final String COL_CURRENCY = "Valuation currency";
    private static final String COL_PORTFOLIO = "Portfolio";
    private static final String COL_TICKER = "Ticker";
    private static final Pattern COUPON = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/(\\d{2,4})");

    /**
     * Live GBP-based FX rates (e.g. {"USD": 1.3621, "EUR": 1.1573}).
     * When present, these are preferred over the snapshot rate in the CSV file
     * so that avgPricePaid and currentMarketValue use a consistent, current rate.
     * Falls back to the file rate when a currency is not found here.
     */
    private final Map<String, BigDecimal> liveRates;

    protected AJBellPortfolioParser(Map<String, BigDecimal> liveRates) {
        this.liveRates = liveRates == null ? Map.of() : liveRates;
    }

    static String normaliseSecurityId(String rawId) {
        if (rawId == null) return null;
        return rawId.trim().toUpperCase();
    }

    static String extractBondId(String investment) {
        Matcher cm = COUPON.matcher(investment);
        Matcher dm = DATE.matcher(investment);
        if (cm.find() && dm.find()) {
            String yr = dm.group(1);
            int year = Integer.parseInt(yr);
            if (yr.length() == 2) year += 2000;
            return Instruments.giltId(cm.group(1), year);
        }
        return investment.replaceAll("\\s*\\(SEDOL:[^)]+\\)", "").trim();
    }

    /** True for any AJ Bell holdings export, whichever account it is for. */
    public static boolean isAjBellPortfolioFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("portfolio") && name.endsWith(".csv");
    }

    /**
     * The value of the {@code Portfolio} column — "SIPP", "Dealing account" — or null when the
     * file cannot be read or states none. Read from the first data row; AJ Bell exports one
     * account per file, so the first row speaks for all of them.
     */
    public static String portfolioIn(Path file) {
        if (!Files.exists(file)) return null;
        try (CSVParser csv = open(file)) {
            for (CSVRecord r : csv) {
                if (!r.isSet(COL_PORTFOLIO)) return null;
                String v = r.get(COL_PORTFOLIO).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * Every ticker the export lists, cash rows excluded. This is the per-account registry the
     * cash-statement and active-orders routing is decided against — it is read from the broker's
     * current export rather than stored, so a position opened or closed changes it with no
     * maintenance. Empty when the file cannot be read.
     */
    public static Set<String> tickersIn(Path file) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(file)) return out;
        try (CSVParser csv = open(file)) {
            for (CSVRecord r : csv) {
                if (!r.isSet(COL_INVESTMENT)) continue;
                String investment = r.get(COL_INVESTMENT).trim();
                if (investment.toLowerCase().startsWith("cash")) continue;
                if (investment.contains("(SEDOL:")) {
                    out.add(extractBondId(investment).toUpperCase());
                    continue;
                }
                if (!r.isSet(COL_TICKER)) continue;
                String ticker = r.get(COL_TICKER).trim();
                if (!ticker.isEmpty()) out.add(ticker.toUpperCase());
            }
        } catch (Exception e) {
            log.warn("Could not read tickers from AJ Bell export {} — "
                    + "cash and order files cannot be routed against it", file, e);
        }
        return out;
    }

    private static CSVParser open(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);
        return CSVParser.parse(content, CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build());
    }

    /** The {@code Portfolio} column value this parser's account exports, matched case-insensitively. */
    protected abstract String portfolioName();

    // -------------------------------------------------------------------------

    @Override
    public boolean supports(Path file) {
        if (!isAjBellPortfolioFile(file)) return false;
        // Tests build synthetic paths that are never written; accept those on the filename alone.
        if (!Files.exists(file)) return true;
        String portfolio = portfolioIn(file);
        // An export with no Portfolio column at all predates the second account. It can only be
        // the SIPP, which is the account that existed when it was written.
        if (portfolio == null) return isDefaultAccount();
        return portfolio.equalsIgnoreCase(portfolioName());
    }

    /** Whether this parser claims an export that does not name its portfolio. */
    protected boolean isDefaultAccount() {
        return false;
    }

    @Override
    public List<Holding> parse(Path file) throws IOException, ParseException {
        List<Holding> holdings = new ArrayList<>();

        try (CSVParser csv = open(file)) {

            for (CSVRecord record : csv) {
                String investment = record.get(COL_INVESTMENT).trim();
                boolean isCash = investment.toLowerCase().startsWith("cash");

                BigDecimal quantity = parseDecimal(record.get(COL_QUANTITY));
                if (quantity == null) continue;

                String ccyCode = record.get(COL_CURRENCY).trim();
                Currency currency;
                try {
                    currency = Currency.getInstance(ccyCode);
                } catch (IllegalArgumentException e) {
                    throw new ParseException("Unrecognised currency '" + ccyCode + "' in: " + investment);
                }

                // Value (£) is always the GBP amount; multiply by rate to get native currency.
                // Prefer the live rate injected at construction; fall back to the CSV snapshot rate.
                BigDecimal valueGbp = parseDecimal(record.get(COL_VALUE_GBP));
                BigDecimal fileRate = parseDecimal(record.get(COL_EXCHANGE_RATE));
                BigDecimal effectiveRate = liveRates.getOrDefault(ccyCode, fileRate);
                BigDecimal nativeValue = (valueGbp != null && effectiveRate != null)
                        ? valueGbp.multiply(effectiveRate)
                        : valueGbp;

                if (isCash) {
                    holdings.add(Holding.builder("CASH", quantity, currency, sourceName())
                            .avgPricePaid(BigDecimal.ONE)
                            .currentMarketValue(nativeValue)
                            .currentMarketValueGbp(valueGbp)
                            .costBasisGbp(valueGbp)   // cash cost = face value
                            .build());
                    continue;
                }

                // Ticker column is absent on the cash row; isSet guards against shorter records
                if (!record.isSet(COL_TICKER)) continue;
                String ticker = record.get(COL_TICKER).trim();
                if (ticker.isEmpty()) continue;

                boolean isBond = investment.contains("(SEDOL:");
                String id = isBond ? extractBondId(investment) : normaliseSecurityId(ticker);

                BigDecimal cost = costOf(record, id, valueGbp);
                // cost is in GBP; multiply by effectiveRate to express avg price in Valuation currency
                // (for GBP stocks effectiveRate = 1, so no change)
                BigDecimal avgPricePaid = (cost != null && quantity.compareTo(BigDecimal.ZERO) != 0
                        && effectiveRate != null)
                        ? cost.divide(quantity, 10, RoundingMode.HALF_UP).multiply(effectiveRate)
                        : null;

                holdings.add(Holding.builder(id, quantity, currency, sourceName())
                        .avgPricePaid(avgPricePaid)
                        .currentMarketValue(nativeValue)
                        .currentMarketValueGbp(valueGbp)
                        .costBasisGbp(cost)   // Cost (£) is always in GBP regardless of Valuation currency
                        .build());
            }
        }

        return holdings;
    }

    /**
     * {@code Cost (£)}, except that a <b>zero</b> cost against a position that is worth something
     * is read as "AJ Bell does not know the basis", not as "this cost nothing".
     *
     * <p>That is what the broker reports for shares transferred in in specie — it never saw the
     * purchase, so it has no basis to state. Taken literally, the position's entire market value
     * books as gain: the BARC holding that arrived at the dealing account this way would have
     * shown £31,845.28 of pure profit on an export whose own {@code Change (%)} column said 0.00.
     * Returning null instead puts the row in the "could not be valued" bucket that the sync and
     * attribution paths already report by name, and leaves the real basis to the ledger — which
     * has it, carried across by the in-specie transfer. Absent is never zero.
     */
    private BigDecimal costOf(CSVRecord record, String id, BigDecimal valueGbp) {
        BigDecimal cost = parseDecimal(record.get(COL_COST));
        if (cost != null && cost.signum() == 0 && valueGbp != null && valueGbp.signum() != 0) {
            log.info("[{}] {} reports a zero cost against a market value of {} — treating the cost "
                            + "basis as unknown rather than zero. The ledger carries the real basis.",
                    sourceName(), id, valueGbp);
            return null;
        }
        return cost;
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
