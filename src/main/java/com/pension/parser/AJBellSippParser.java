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

/**
 * Parses portfolio*.csv exports for the SIPP account.
 *
 * All portfolio values (Cost, Value) are expressed in GBP.
 * Each row carries its own Valuation currency (GBP/USD/EUR) for downstream
 * FX conversion and live-price fetching.
 * Average price paid = Cost (£) / Quantity.
 */
public class AJBellSippParser implements AccountParser {

    private static final String COL_INVESTMENT   = "Investment";
    private static final String COL_QUANTITY     = "Quantity";
    private static final String COL_COST         = "Cost (£)";
    private static final String COL_MARKET_VALUE = "Value (£)";
    private static final String COL_CURRENCY     = "Valuation currency";
    private static final String COL_PORTFOLIO    = "Portfolio";
    private static final String COL_TICKER       = "Ticker";

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

                String source = record.get(COL_PORTFOLIO).trim();

                BigDecimal marketValue = parseDecimal(record.get(COL_MARKET_VALUE));

                if (isCash) {
                    holdings.add(Holding.builder("CASH", quantity, currency, source)
                            .avgPricePaid(BigDecimal.ONE)
                            .currentMarketValue(marketValue)
                            .build());
                    continue;
                }

                // Ticker column is absent on the cash row; isSet guards against shorter records
                if (!record.isSet(COL_TICKER)) continue;
                String ticker = record.get(COL_TICKER).trim();
                if (ticker.isEmpty()) continue;

                BigDecimal cost = parseDecimal(record.get(COL_COST));
                BigDecimal avgPricePaid = (cost != null && quantity.compareTo(BigDecimal.ZERO) != 0)
                        ? cost.divide(quantity, 10, RoundingMode.HALF_UP)
                        : null;

                holdings.add(Holding.builder(normaliseSecurityId(ticker), quantity, currency, source)
                        .avgPricePaid(avgPricePaid)
                        .currentMarketValue(marketValue)
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

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
