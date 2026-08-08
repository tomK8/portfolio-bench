package com.portfolio.domain.swing;

/**
 * §D — per-symbol earnings context, bound to one {@link SwingSeries}'s bar indexing so the pure
 * engines can ask "is the entry at bar {@code i} earnings-adjacent?" without knowing where the dates
 * came from. Built in the application layer from the EDGAR filing-date proxy; the domain only sees
 * this interface, keeping the swing package framework- and network-free.
 *
 * <p>{@link #verified()} is {@code false} when no earnings source exists for the symbol (a non-US
 * filer such as ASML/SEMI.L). Such symbols are never silently treated as clean — their events carry
 * {@code verified=false} through the whole report so the UI can mark them.
 */
public interface EarningsLookup {

    /** Earnings context for the entry at bar index {@code i}. */
    Info at(int i);

    /** {@code true} when earnings dates were sourced for this symbol; {@code false} → unverified. */
    boolean verified();

    /**
     * @param tradingDaysToNearest signed-magnitude trading sessions to the nearest earnings date
     *                             ({@code null} when unverified or no dates in range)
     * @param withinWindow         {@code true} when within the configured ±session window of an
     *                             earnings date (always {@code false} when unverified)
     */
    record Info(Integer tradingDaysToNearest, boolean withinWindow) {
    }

    /** Sentinel for a symbol with no earnings source. */
    EarningsLookup UNVERIFIED = new EarningsLookup() {
        @Override public Info at(int i) { return new Info(null, false); }
        @Override public boolean verified() { return false; }
    };
}
