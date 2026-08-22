package com.portfolio.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * How far an instrument typically moves on a scheduled event — the size half of the event-variance
 * term (the dates are the other half, and come from the earnings calendar).
 *
 * <p>Sizes are per asset class with per-symbol overrides, for the same reason drift is
 * ({@link AssetClass}): a per-name estimate of "how much does this one jump on earnings" is a
 * forecast of that name, and a wrong one is worse than a stated convention. The default is 6% for a
 * single-name equity and zero for anything without a print.
 *
 * <p>Loads {@code /event-moves.properties} once, the same self-loading pattern
 * {@link SectorClassifier} uses for its judgements file, so nothing has to be wired to reach it.
 */
public final class EventMoves {

    private static final String RESOURCE = "/event-moves.properties";
    private static final String OVERRIDE_PREFIX = "override.";

    private final BigDecimal equityDefault;
    private final Map<String, BigDecimal> overrides;

    public EventMoves() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.equityDefault = decimal(props.getProperty("default.equity"), new BigDecimal("0.06"));
        Map<String, BigDecimal> ov = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(OVERRIDE_PREFIX)) continue;
            String symbol = name.substring(OVERRIDE_PREFIX.length()).trim().toUpperCase();
            BigDecimal move = decimal(props.getProperty(name), null);
            if (!symbol.isEmpty() && move != null && move.signum() >= 0) ov.put(symbol, move);
        }
        this.overrides = Map.copyOf(ov);
    }

    /**
     * The move size for a symbol, as a fraction: a per-symbol override if one is declared, else the
     * class default. Anything that isn't a single-name equity returns zero — a fund, a gilt or a
     * bullion ETC has no scheduled event to price.
     *
     * <p>Zero is also the honest answer for a name whose class we can't tell, and it costs nothing:
     * a symbol with no dates in the calendar adds no variance regardless of what this returns.
     */
    public BigDecimal moveFor(String symbol, AssetClass assetClass) {
        if (symbol != null) {
            BigDecimal override = overrides.get(symbol.trim().toUpperCase());
            if (override != null) return override;
        }
        return assetClass == AssetClass.EQUITY ? equityDefault : BigDecimal.ZERO;
    }

    private static BigDecimal decimal(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;   // a typo in a checked-in file must not take the panel down
        }
    }
}
