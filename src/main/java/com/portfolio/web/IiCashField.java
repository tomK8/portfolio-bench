package com.portfolio.web;

/**
 * One II SIPP cash box on the Holdings form, in the order the form shows them.
 *
 * <p><b>Adding a currency is a constant here plus a label in {@code dashboard.html}.</b> Nothing
 * between the form and the Excel sheet names a currency: the request parameters, the stored keys
 * and the model attributes the template reads are all generated from this list, and the figures
 * travel on as one {@link com.portfolio.application.IiCash}.
 *
 * <p>The sterling names carry no currency — {@code iiSippCash}, {@code ii_sipp_cash_last} — because
 * sterling was the only box when they were chosen. They stay as they are: the key names a file on
 * disk, and renaming it would strand a real balance the broker export cannot supply again.
 */
enum IiCashField {

    GBP("iiSippCash", "ii_sipp_cash_last"),
    USD("iiSippCashUsd", "ii_sipp_cash_last_usd"),
    EUR("iiSippCashEur", "ii_sipp_cash_last_eur");

    private final String param;
    private final String key;

    IiCashField(String param, String key) {
        this.param = param;
        this.key = key;
    }

    /** ISO code — the same key the FX rate map and the reconciliation panel use. */
    String currency() {
        return name();
    }

    /** The request parameter the box posts under, and the model attribute the template reads. */
    String param() {
        return param;
    }

    /** The {@code KeyValueStore} key the last stated figure is remembered in. */
    String key() {
        return key;
    }
}
