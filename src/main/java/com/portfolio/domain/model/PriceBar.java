package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * One day of OHLCV for an instrument, in its listing currency.
 *
 * @param symbol   the key under which the bar is stored — the resolved Yahoo ticker (e.g. {@code EQQQ.L})
 * @param close    unadjusted close — use for "market value of the position on {@code date}"
 * @param adjClose split/dividend-adjusted close — use for total-return calculations
 * @param currency Yahoo listing currency. NOTE: .L (London) tickers report {@code "GBp"} = pence,
 *                 not GBP — a Phase-2 valuation concern, stored verbatim here.
 */
public record PriceBar(
        String symbol, LocalDate date,
        Double open, Double high, Double low,
        double close, double adjClose,
        Long volume, String currency) {
}
