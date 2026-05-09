package com.pension.parser;

import com.pension.model.Holding;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses portfolio*.csv exports from AJ Bell for the SIPP account.
 *
 * All portfolio values (Cost, Value) are expressed in GBP.
 * Each row carries its own Valuation currency (GBP/USD/EUR) for downstream
 * FX conversion and live-price fetching.
 * Average price paid = Cost (£) / Quantity.
 * Bond rows identified by (SEDOL:...) in the Investment description are
 * given a human-readable ID of the form "GILT {coupon}% {year}".
 */
public class AJBellSippParser implements AccountParser {

    /**
     * Live GBP-based FX rates (e.g. {"USD": 1.3621, "EUR": 1.1573}).
     * When present, these are preferred over the snapshot rate in the CSV file
     * so that avgPricePaid and currentMarketValue use a consistent, current rate.
     * Falls back to the file rate when a currency is not found here.
     */
    private final Map<String, BigDecimal> liveRates;

    public AJBellSippParser() { this.liveRates = Map.of(); }
    public AJBellSippParser(Map<String, BigDecimal> liveRates) { this.liveRates = liveRates; }

    private static final String ACCOUNT_SOURCE = "AJ Bell SIPP";

    private static final String COL_INVESTMENT    = "Investment";
    private static final String COL_QUANTITY      = "Quantity";
    private static final String COL_COST          = "Cost (£)";
    private static final String COL_VALUE_GBP     = "Value (£)";   // always GBP
    private static final String COL_EXCHANGE_RATE = "Exchange rate"; // units of Valuation currency per 1 GBP
    private static final String COL_CURRENCY      = "Valuation currency";
    private static final String COL_TICKER        = "Ticker";

    private static final Pattern COUPON = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern DATE   = Pattern.compile("\\d{1,2}/\\d{1,2}/(\\d{2,4})");

    @Override
    public String sourceName() { return "AJ Bell SIPP"; }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("portfolio") && name.endsWith(".csv");
    }

    @Override
    public List<Holding> parse(Path file) throws IOException, ParseException {
        List<Holding> holdings = new ArrayList<>();

        String content = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (CSVParser csv = CSVParser.parse(content, format)) {

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
                BigDecimal valueGbp      = parseDecimal(record.get(COL_VALUE_GBP));
                BigDecimal fileRate      = parseDecimal(record.get(COL_EXCHANGE_RATE));
                BigDecimal effectiveRate = liveRates.getOrDefault(ccyCode, fileRate);
                BigDecimal nativeValue   = (valueGbp != null && effectiveRate != null)
                        ? valueGbp.multiply(effectiveRate)
                        : valueGbp;

                if (isCash) {
                    holdings.add(Holding.builder("CASH", quantity, currency, ACCOUNT_SOURCE)
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

                BigDecimal cost = parseDecimal(record.get(COL_COST));
                // cost is in GBP; multiply by effectiveRate to express avg price in Valuation currency
                // (for GBP stocks effectiveRate = 1, so no change)
                BigDecimal avgPricePaid = (cost != null && quantity.compareTo(BigDecimal.ZERO) != 0
                                           && effectiveRate != null)
                        ? cost.divide(quantity, 10, RoundingMode.HALF_UP).multiply(effectiveRate)
                        : null;

                holdings.add(Holding.builder(id, quantity, currency, ACCOUNT_SOURCE)
                        .avgPricePaid(avgPricePaid)
                        .currentMarketValue(nativeValue)
                        .currentMarketValueGbp(valueGbp)
                        .costBasisGbp(cost)   // Cost (£) is always in GBP regardless of Valuation currency
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

    static String extractBondId(String investment) {
        Matcher cm = COUPON.matcher(investment);
        Matcher dm = DATE.matcher(investment);
        if (cm.find() && dm.find()) {
            String coupon = cm.group(1);
            String yr = dm.group(1);
            int year = Integer.parseInt(yr);
            if (yr.length() == 2) year += 2000;
            return "GILT " + coupon + "% " + year;
        }
        return investment.replaceAll("\\s*\\(SEDOL:[^)]+\\)", "").trim();
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
