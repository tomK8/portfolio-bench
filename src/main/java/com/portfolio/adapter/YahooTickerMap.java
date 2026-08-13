package com.portfolio.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Maps internal instrument symbols to Yahoo Finance tickers, loaded from the committed
 * {@code /yahoo-tickers.properties} resource. US listings need no entry — they map to themselves.
 */
public class YahooTickerMap {

    private final Properties props = new Properties();

    public YahooTickerMap() {
        try (InputStream in = getClass().getResourceAsStream("/yahoo-tickers.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Yahoo ticker for an internal symbol; identity for US listings without an override. */
    public String tickerFor(String internalSymbol) {
        return props.getProperty(internalSymbol, internalSymbol);
    }

    /**
     * Whether the symbol has an explicit entry, as opposed to falling through to the identity
     * mapping. Absence is not by itself an error — US listings are deliberately unmapped — but it
     * is what separates "Yahoo has no data for this US ticker" from "this non-US listing needs an
     * exchange suffix here", so a symbol that yields no prices can be reported with the right fix.
     * Identity is also a silent trap on its own: an unmapped {@code PHP} resolves to a delisted US
     * mutual fund rather than to nothing at all.
     */
    public boolean hasMapping(String internalSymbol) {
        return props.getProperty(internalSymbol) != null;
    }

    /**
     * Whether a resolved Yahoo ticker can only have come from the identity fallback — no property
     * maps to it. The fetch jobs iterate resolved tickers, by which point {@link #hasMapping} would
     * be asking about the wrong string (an {@code EQQQ.L} never appears on the left-hand side).
     */
    public boolean resolvedByIdentity(String yahooTicker) {
        return !props.containsValue(yahooTicker);
    }
}
