package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * The identifiers one gilt is published under, beside the {@code "GILT {coupon}% {year}"}
 * id this app keys on. All of it comes out of the single name cell HL's gilt-prices page
 * already renders next to the price — {@code "Treasury 4.875% 2036 GBP | GB00BWBR1N39 |
 * BWBR1N3"} — so carrying it costs no extra HTTP.
 *
 * <p>It exists because the id is the one name for a gilt that <em>nobody else uses</em>. The
 * LSE quotes T36N, Interactive Investor's cash statement writes T30, and a page a holder is
 * reading shows an ISIN; the Watchlist's gilt picker is searched with whichever of those the
 * user happens to have in hand, and an alert on a hand-typed coupon that joins no price never
 * fires. The SEDOL half of that was already stored — see {@code GiltPriceFetchJob} — for the
 * narrower job of recognising a gilt in an II cash statement.
 *
 * <p>{@code maturity} is the full date, not just the year already in the symbol: two gilts can
 * mature in the same year (4.25% 7 March 2036 and 4.875% 31 July 2036), and it is the date a
 * buyer picks a gilt by, so the picker is ordered on it.
 *
 * <p>Every field but the symbol is nullable. A page that stops printing an ISIN costs the
 * picker a search handle, nothing more — absent is absent, never a wrong identifier.
 */
public record GiltRef(String symbol, String isin, String sedol, LocalDate maturity) {

    /**
     * The field separator in the {@code KeyValueStore} form. A pipe can appear in neither an
     * ISIN, a SEDOL nor an ISO date, and the store's own line format already claims {@code =}
     * and the newline.
     */
    private static final String SEP = "|";

    /**
     * The stored form, without the symbol — that is the KV key. Encoding and decoding live on
     * the record rather than in the job that writes and the service that reads, so the format
     * has one spelling and cannot drift between them.
     */
    public String toKvValue() {
        return String.join(SEP, nullToBlank(isin), nullToBlank(sedol),
                maturity == null ? "" : maturity.toString());
    }

    /**
     * Decodes one stored entry. Tolerant by design: a blank or unparseable field comes back
     * null, and a value written by an older build with fewer fields decodes to what it does
     * carry. This feeds a picker — a half-read entry must cost a search handle, not the row.
     */
    public static GiltRef fromKv(String symbol, String value) {
        String[] parts = (value == null ? "" : value).split("\\" + SEP, -1);
        return new GiltRef(symbol, blankToNull(part(parts, 0)), blankToNull(part(parts, 1)),
                parseDate(part(parts, 2)));
    }

    private static String part(String[] parts, int i) {
        return i < parts.length ? parts[i].trim() : null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
