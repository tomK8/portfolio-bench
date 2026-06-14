package com.portfolio.web;

import com.portfolio.persistence.KeyValueStore;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Small set of helpers shared across the web controllers: the timestamp string the result
 * fragments render after each action, the cash-form parser, and the II SIPP cash form
 * persistence used by both the {@code POST /sync} and {@code POST /export} flows.
 */
final class WebSupport {

    /** KV key for the last II SIPP GBP cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_KEY = "ii_sipp_cash_last";

    /** KV key for the last II SIPP USD cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_USD_KEY = "ii_sipp_cash_last_usd";

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private WebSupport() {}

    static String now() {
        return LocalTime.now().format(HMS);
    }

    static BigDecimal parseCash(String raw) {
        String clean = raw.replace(",", "").replace("£", "").trim();
        if (clean.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    static BigDecimal persistIiSippCash(KeyValueStore settings, String raw) {
        BigDecimal cash = parseCash(raw);
        settings.putBigDecimal(II_SIPP_CASH_KEY, cash);
        return cash;
    }

    static BigDecimal persistIiSippCashUsd(KeyValueStore settings, String raw) {
        BigDecimal cash = parseCash(raw);
        settings.putBigDecimal(II_SIPP_CASH_USD_KEY, cash);
        return cash;
    }
}
