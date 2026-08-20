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
        BigDecimal distancePct, // signed (currentPrice − price) / price as a fraction — how far the
                                // market sits from the limit. null when either side is missing/zero.
        BigDecimal dailyVol10,  // realized daily volatility over the last 10 sessions (NOT annualised),
                                // a fraction; enriched from the symbol's daily series. null when unknown.
        BigDecimal dailyVol30,  // realized daily volatility over the last 30 sessions (NOT annualised).
        BigDecimal sigmaToLimit,// signed distance current→limit in 5-session σ units (ln(limit/current)
                                // ÷ dailyVol×√5). null when price/current/vol unavailable.
        BigDecimal touchProb5d, // probability the market touches the limit within 5 sessions
                                // (driftless first-passage, 2·Φ(−|sigmaToLimit|)); a fraction. null when
                                // sigmaToLimit is unavailable.
        BigDecimal touchProb20d,// same touch probability over a 20-session horizon (σ scaled by √20
                                // off the same daily vol); a fraction. null when unavailable.
        String quoteSession     // trading session currentPrice came from: "PRE" / "POST", or null
                                // for a regular-hours quote, a daily close, or no quote at all.
) {
    /**
     * True when a resting order is already through its own limit — a buy whose limit sits at or
     * above the market, or a sell whose limit sits at or below it.
     *
     * <p>That state should not persist: a buy limit executes at the limit *or better*, so once the
     * market trades down to it the order fills and stops being open. Seeing it therefore means one
     * of three things, and the row is flagged rather than interpreted: the quote is stale (nothing
     * was keeping that symbol's feed warm), the export is older than the fill, or the order really
     * is about to go. It reads off {@link #currentPrice}, so it is only meaningful after the
     * service's enrichment.
     *
     * <p>An {@link #priceEstimated} limit (II, derived as {@code amount ÷ quantity}) needs a margin:
     * the ~£2.99 dealing fee is inside the amount, which inflates a buy's implied limit and deflates
     * a sell's — biasing both toward a false flag. {@link #ESTIMATE_TOLERANCE} (0.5%) sits well above
     * that fee on any realistic order size.
     */
    public boolean throughLimit() {
        if (price == null || currentPrice == null || side == null || price.signum() == 0) return false;
        // An extended-hours quote can sit through the limit all morning without the order being
        // wrong: a resting regular-hours order does not fill pre- or post-market, so the "this
        // should already have gone" reading does not apply and the amber row would be noise every
        // day a US name gapped overnight.
        if (extendedHoursQuote()) return false;
        BigDecimal edge = priceEstimated ? price.multiply(ESTIMATE_TOLERANCE) : BigDecimal.ZERO;
        if ("buy".equalsIgnoreCase(side)) return currentPrice.compareTo(price.subtract(edge)) <= 0;
        if ("sell".equalsIgnoreCase(side)) return currentPrice.compareTo(price.add(edge)) >= 0;
        return false;
    }

    /** True when {@link #currentPrice} came from a pre- or post-market session rather than regular hours. */
    public boolean extendedHoursQuote() {
        return "PRE".equals(quoteSession) || "POST".equals(quoteSession);
    }

    /** Short badge for an extended-hours quote ("PRE" / "AH"), or "" for a regular-hours price. */
    public String quoteSessionLabel() {
        if ("PRE".equals(quoteSession)) return "PRE";
        if ("POST".equals(quoteSession)) return "AH";
        return "";
    }

    /** Margin required before an estimated (fee-inflated) limit counts as through — see {@link #throughLimit()}. */
    private static final BigDecimal ESTIMATE_TOLERANCE = new BigDecimal("0.005");

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
                currentPrice, distancePct, dailyVol10, dailyVol30, sigmaToLimit, touchProb5d,
                touchProb20d, quoteSession);
    }

    /**
     * Copy with the live-price columns set — {@link #currentPrice} (in {@link #currency} units) and
     * {@link #distancePct} = signed {@code (current − price) / price}. Enriched by the service after
     * parsing, mirroring {@link #withAmountGbp}.
     */
    public OpenOrder withCurrentPrice(BigDecimal current, BigDecimal distance, String session) {
        return new OpenOrder(broker, symbol, name, side, orderType, quantity, price, priceEstimated,
                amount, currency, dateEntered, expiry, duration, status, reference, amountGbp,
                current, distance, dailyVol10, dailyVol30, sigmaToLimit, touchProb5d, touchProb20d,
                session);
    }

    /**
     * Copy with the volatility columns set — realized daily σ over 10 / 30 sessions (not annualised),
     * the signed 5-session σ distance from the market to the limit, and the 5- and 20-session touch
     * probabilities. Enriched by the service, mirroring {@link #withCurrentPrice}.
     */
    public OpenOrder withVolatility(BigDecimal dv10, BigDecimal dv30, BigDecimal sigmaToLimit,
                                    BigDecimal touchProb5d, BigDecimal touchProb20d) {
        return new OpenOrder(broker, symbol, name, side, orderType, quantity, price, priceEstimated,
                amount, currency, dateEntered, expiry, duration, status, reference, amountGbp,
                currentPrice, distancePct, dv10, dv30, sigmaToLimit, touchProb5d, touchProb20d,
                quoteSession);
    }
}
