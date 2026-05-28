package com.pension.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Maps internal instrument symbols to Yahoo Finance tickers, loaded from the committed
 * {@code /yahoo-tickers.properties} resource. US listings need no entry — they map to themselves.
 *
 * <p>A value may be comma-separated to fetch multiple listings for one internal symbol, e.g.
 * {@code GOOG/GOOGL=GOOG,GOOGL} pulls both Alphabet share classes.
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

    /**
     * Yahoo ticker(s) for an internal symbol; a single-element list of the symbol itself for
     * US listings without an override, or several tickers when the mapping value is comma-separated.
     */
    public List<String> tickersFor(String internalSymbol) {
        String value = props.getProperty(internalSymbol, internalSymbol);
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public boolean isGilt(String internalSymbol) {
        return internalSymbol != null && internalSymbol.startsWith("GILT");
    }
}
