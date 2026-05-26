package com.pension.application;

import com.pension.PortfolioDatabase;
import com.pension.domain.DividendCalculator;
import com.pension.domain.model.DividendEntry;
import com.pension.port.FxRateProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records manually-entered dividends. Validates the raw rows, converts each to
 * GBP at current FX rates and persists them. Replaces the Swing dividend dialog;
 * the FX conversion now lives in the domain ({@link DividendCalculator}).
 *
 * <p>If any row is invalid, nothing is saved and the errors are returned, so the
 * user can correct the whole batch at once (matching the old dialog's behaviour).
 */
public class RecordDividendsService {

    private final FxRateProvider fxRateProvider;
    private final PortfolioDatabase db;
    private final DividendCalculator calculator = new DividendCalculator();

    public RecordDividendsService(FxRateProvider fxRateProvider, PortfolioDatabase db) {
        this.fxRateProvider = fxRateProvider;
        this.db = db;
    }

    public RecordResult record(List<RawDividend> rows) {
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch FX rates", e);
        }

        List<DividendEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int rowNumber = 0;

        for (RawDividend row : rows) {
            rowNumber++;
            String date = row.date().trim();
            String account = row.account().trim();
            String symbol = row.symbol().trim().toUpperCase();
            String currency = row.currency().trim().toUpperCase();
            String amountStr = row.amount()
                    .replace(",", "").replace("$", "").replace("£", "").replace("€", "").trim();

            if (symbol.isEmpty() && amountStr.isEmpty()) continue; // blank row

            if (date.isEmpty() || symbol.isEmpty() || amountStr.isEmpty()) {
                errors.add("Row " + rowNumber + ": date, symbol and amount are required");
                continue;
            }

            double amount;
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                errors.add("Row " + rowNumber + ": invalid amount '" + amountStr + "'");
                continue;
            }

            entries.add(calculator.toEntry(date, account, symbol, currency, amount, rates));
        }

        if (!errors.isEmpty()) {
            return new RecordResult(0, errors);
        }
        if (!entries.isEmpty()) {
            db.saveDividends(entries);
        }
        return new RecordResult(entries.size(), List.of());
    }
}
