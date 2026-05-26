package com.pension.application;

/** Untrusted dividend row as entered on the form, before validation/conversion. */
public record RawDividend(
        String date,
        String account,
        String symbol,
        String currency,
        String amount) {
}
