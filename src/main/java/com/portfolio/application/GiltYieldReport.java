package com.portfolio.application;

import java.util.List;

/**
 * What the Reference page's gilt-yield table renders: one row per tenor the source answered, in
 * tenor order and already formatted, plus the tenors it did not answer — named, never shown as a
 * blank or zero row.
 *
 * @param rows      tenor order; empty only when nothing has been fetched since the app started
 * @param missing   tenors asked for and not answered, as labels ({@code "30-year"})
 * @param source    who the figures are from
 * @param fetchedAt when {@code rows} were fetched, London time; null when there are none
 * @param error     why the latest refresh failed, or null when it succeeded. Rows beside an error
 *                  are the last good fetch, and {@code fetchedAt} says how old
 */
public record GiltYieldReport(List<Row> rows, List<String> missing, String source,
                              String fetchedAt, String error) {

    /**
     * One tenor, display-ready. Every field but {@code tenor} and {@code yield} may be null,
     * which the page shows as a dash.
     *
     * @param change        signed move since the previous close, in basis points
     * @param previousClose the yield the change is measured from
     * @param benchmark     the gilt the tenor currently tracks, under this app's id
     * @param asOf          when the source last updated the yield, London time
     * @param url           the source's own page for the tenor
     */
    public record Row(String tenor, String yield, String change, String previousClose,
                      String benchmark, String matures, String asOf, String url) {
    }
}
