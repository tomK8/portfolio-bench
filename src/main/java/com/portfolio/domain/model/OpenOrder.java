package com.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A resting (open / working) order at a broker, normalised across the three export formats
 * (AJ Bell {@code activeorders*.csv}, II UUID {@code .csv}, Citi Roth {@code Order+Status*.xlsx}).
 *
 * <p>Not every broker supplies every field — II omits the per-share limit price (it's estimated
 * from {@code amount ÷ quantity} and flagged via {@link #priceEstimated}), Roth omits a dated
 * expiry (all orders are GTC). Absent fields are {@code null} and rendered as "—". Prices and
 * amounts are per-share / total in {@link #currency} (AJ Bell pence are normalised to pounds so a
 * row carries a single currency).
 */
public record OpenOrder(
        String broker,          // display label, e.g. "AJ Bell SIPP"
        String symbol,          // ticker/security id when the export carries one; else null
        String name,            // descriptive instrument name; null when only a ticker is given
        String side,            // "Buy" / "Sell"
        String orderType,       // "Limit" / "Stop" / …; null when unknown
        BigDecimal quantity,
        BigDecimal price,       // per-share, in `currency`; null when neither exported nor derivable
        boolean priceEstimated, // true when price is amount÷qty (II), not the exported limit price
        BigDecimal amount,      // total order value, in `currency`; null when not exported (Roth)
        String currency,        // ISO code the price/amount are quoted in; null when unknown
        LocalDate dateEntered,  // date the order was placed; null when unparseable
        LocalDate expiry,       // dated good-till; null for GTC / when not exported
        String duration,        // e.g. "GTC" when the broker states a duration rather than a date
        String status,          // raw broker status (e.g. "In progress", "PENDING", "Open")
        String reference,       // broker order reference; null when none
        BigDecimal amountGbp,   // `amount` converted to GBP at current FX; enriched by the service,
                                // so parsers leave it null. null when amount/FX is unavailable.
        BigDecimal currentPrice,// latest live price per share, in `currency` units; enriched by the
                                // service from the intraday/daily feed. null when unavailable.
        BigDecimal distancePct  // signed (currentPrice − price) / price as a fraction — how far the
                                // market sits from the limit. null when either side is missing/zero.
) {
    /** Convenience for views — the leading symbol for {@link #currency} ($/£/€), or "" if unknown. */
    public String currencySymbol() {
        if (currency == null) return "";
        return switch (currency) {
            case "GBP", "GBp" -> "£";
            case "USD" -> "$";
            case "EUR" -> "€";
            default -> "";
        };
    }

    /** Copy with {@link #amountGbp} set — parsers build FX-free orders, the service enriches them. */
    public OpenOrder withAmountGbp(BigDecimal gbp) {
        return new OpenOrder(broker, symbol, name, side, orderType, quantity, price, priceEstimated,
                amount, currency, dateEntered, expiry, duration, status, reference, gbp,
                currentPrice, distancePct);
    }

    /**
     * Copy with the live-price columns set — {@link #currentPrice} (in {@link #currency} units) and
     * {@link #distancePct} = signed {@code (current − price) / price}. Enriched by the service after
     * parsing, mirroring {@link #withAmountGbp}.
     */
    public OpenOrder withCurrentPrice(BigDecimal current, BigDecimal distance) {
        return new OpenOrder(broker, symbol, name, side, orderType, quantity, price, priceEstimated,
                amount, currency, dateEntered, expiry, duration, status, reference, amountGbp,
                current, distance);
    }
}
