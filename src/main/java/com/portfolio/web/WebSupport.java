package com.portfolio.web;

import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Small set of helpers shared across the web controllers: the timestamp string the result
 * fragments render after each action, the cash-form parser, and the II SIPP cash form
 * persistence used by both the {@code POST /sync} and {@code POST /export} flows.
 */
final class WebSupport {

    private static final Logger log = LoggerFactory.getLogger(WebSupport.class);

    /** KV key for the last II SIPP GBP cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_KEY = "ii_sipp_cash_last";

    /** KV key for the last II SIPP USD cash balance entered on the dashboard. */
    static final String II_SIPP_CASH_USD_KEY = "ii_sipp_cash_last_usd";

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private WebSupport() {}

    static String now() {
        return LocalTime.now().format(HMS);
    }

    /**
     * A cash figure from the form, or {@code null} when the field says nothing.
     *
     * <p>Null covers three cases that all mean "no figure was given": the parameter was absent
     * from the request, it arrived blank, or it could not be read as a number. They are
     * deliberately not distinguished from each other here — {@link #persistCash} treats all three
     * the same way — but they are all distinguished from an explicit {@code 0}, which is a real
     * balance a user can state.
     */
    static BigDecimal parseCash(String raw) {
        if (raw == null) return null;
        String clean = raw.replace(",", "").replace("£", "").trim();
        if (clean.isEmpty()) return null;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            log.warn("Ignoring unreadable cash value '{}' — the stored balance is left alone", raw);
            return null;
        }
    }

    /**
     * Stores a cash figure from the form and returns what the view should use — or, when the form
     * gave no figure, <b>leaves the stored balance alone</b> and returns that instead.
     *
     * <p><b>An absent parameter means "unchanged", never zero.</b> It used to mean zero, by way of
     * a {@code defaultValue = "0"} on the request parameter and a {@code parseCash} that mapped
     * blank and unreadable input to {@code BigDecimal.ZERO}. These two values are the holdings
     * view's <em>only</em> source of II SIPP cash — II's holdings export omits the balance
     * entirely — so any request that omitted the parameters silently overwrote a real balance with
     * zero, and the only symptom was a portfolio total quietly short by that amount. A form post
     * that forgets a field, a hand-run {@code curl}, or a script hitting {@code /export} would all
     * have done it.
     *
     * <p>Clearing the box therefore no longer zeroes the balance: <b>type {@code 0} to mean
     * zero</b>. That asymmetry is deliberate — stating zero is one keystroke, while silently
     * losing a four-figure balance is invisible until the totals stop reconciling.
     */
    private static BigDecimal persistCash(KeyValueStore settings, String key, String raw) {
        BigDecimal cash = parseCash(raw);
        if (cash == null) return settings.getBigDecimal(key, BigDecimal.ZERO);
        settings.putBigDecimal(key, cash);
        return cash;
    }

    static BigDecimal persistIiSippCash(KeyValueStore settings, String raw) {
        return persistCash(settings, II_SIPP_CASH_KEY, raw);
    }

    static BigDecimal persistIiSippCashUsd(KeyValueStore settings, String raw) {
        return persistCash(settings, II_SIPP_CASH_USD_KEY, raw);
    }
}
