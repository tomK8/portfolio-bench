package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * One day of OHLCV for an instrument, in its listing currency.
 *
 * @param symbol      the key under which the bar is stored — the resolved Yahoo ticker (e.g. {@code EQQQ.L})
 * @param close       split-adjusted close in current basis — Yahoo returns this pre-adjusted across the
 *                    whole series (e.g. GOOG's 2016 close is ~$39 even though the raw price was ~$780).
 *                    Multiply by {@code splitFactor} to recover the raw close on {@code date}.
 * @param adjClose    split + dividend-adjusted close — use for total-return calculations between two dates.
 * @param splitFactor cumulative split factor from {@code date} forward to today
 *                    (e.g. 20.0 for a GOOG bar dated before the 2022 20:1 split, 1.0 otherwise).
 *                    {@code close × splitFactor} = the raw close at {@code date} in the basis a user
 *                    holding from that day would have used. 1.0 for symbols with no splits since.
 * @param currency    Yahoo listing currency. NOTE: .L (London) tickers report {@code "GBp"} = pence,
 *                    not GBP — a Phase-2 valuation concern, stored verbatim here.
 */
public record PriceBar(
        String symbol, LocalDate date,
        Double open, Double high, Double low,
        double close, double adjClose,
        double splitFactor,
        Long volume, String currency) {
}
