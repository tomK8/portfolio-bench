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

    public boolean isGilt(String internalSymbol) {
        return internalSymbol != null && internalSymbol.startsWith("GILT");
    }
}
