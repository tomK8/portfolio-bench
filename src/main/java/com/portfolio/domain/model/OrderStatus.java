package com.portfolio.domain.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What actually happened to an order, read off the broker's own word for it.
 *
 * <p>Three brokers write six spellings for three outcomes — AJ Bell says "Dealt", II shouts
 * "EXECUTED" and "CANCELLED", Citi writes "Executed" and the American "Canceled" — and the closed
 * orders table printed each verbatim, so the same outcome appeared under several names and the
 * column could not be read (or sorted) as a state. This enum is the <b>single home</b> of that
 * vocabulary: parsers keep storing the broker's raw string (it is evidence, and a broker may
 * invent a word tomorrow), and everything that has to reason about or display the state comes
 * through here.
 *
 * <p><b>The terminal set is exactly the one the open/closed filter already used</b> — no spelling
 * has been added to it. That filter is deliberately a <em>denylist</em>: an unrecognised status is
 * {@link #UNKNOWN}, which counts as open, so a broker's unseen "still working" wording is shown
 * rather than silently dropped from the panel. Teaching this map a new terminal word therefore
 * removes orders from the table, and is a decision to take on evidence from a real export.
 *
 * <p>Note what is <em>not</em> here: an order the broker simply stopped listing. That is the third
 * state the history table shows, and no broker ever writes it — it is inferred from absence, by
 * {@code OrderHistoryRepository.HistoricalOrder}, whose status stays whatever it was last seen as.
 */
public enum OrderStatus {

    FILLED("Filled"),
    /** Part of the quantity traded. Terminal here because the existing filter treated it so. */
    PART_FILLED("Part filled"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired"),
    REJECTED("Rejected"),
    /** The broker says the order is still working: "Open" / "In progress" / "PENDING". */
    WORKING("Working"),
    /** No status, or a word this map has never seen. Counts as open — see the class doc. */
    UNKNOWN("Unknown");

    private static final Map<String, OrderStatus> SPELLINGS = new HashMap<>();

    static {
        // Terminal — byte-for-byte the set the open/closed filter has always used.
        put(FILLED, "dealt", "executed", "filled", "completed", "done", "settled");
        put(PART_FILLED, "part executed");
        put(CANCELLED, "cancelled", "canceled");
        put(EXPIRED, "expired");
        put(REJECTED, "rejected");
        // Still working — the three spellings the live exports carry.
        put(WORKING, "open", "in progress", "pending");
    }

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    private static void put(OrderStatus state, String... spellings) {
        for (String s : spellings) SPELLINGS.put(s, state);
    }

    /**
     * Classify a broker's raw status word. Case, surrounding space and the separator a broker
     * chose are all noise ("Part-Executed" and "PART EXECUTED" are one status); anything the map
     * does not recognise is {@link #UNKNOWN}, never a guess.
     */
    public static OrderStatus of(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String key = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return SPELLINGS.getOrDefault(key, UNKNOWN);
    }

    /** The one word the UI prints for this state. */
    public String label() {
        return label;
    }

    /** True while the order can still trade — the denylist's "everything that isn't terminal". */
    public boolean open() {
        return this == WORKING || this == UNKNOWN;
    }

    /** True when this state says the order is over, whatever the outcome was. */
    public boolean terminal() {
        return !open();
    }

    /**
     * The constant one of our own stored names refers to, or null when this build has never heard
     * of it.
     *
     * <p>Deliberately not {@link #of}: that reads a <em>broker's</em> word, where an unrecognised
     * one is {@link #UNKNOWN} and therefore still open, because a broker may invent a spelling
     * tomorrow and dropping the order would be worse than showing it. This reads a name this
     * application wrote itself, where the only way to meet an unknown one is a column written by a
     * build that knew a constant this one does not — and there the honest answer is "no verdict",
     * not "unknown, so still open". Returning null keeps the two cases from sharing an answer.
     */
    public static OrderStatus named(String name) {
        if (name == null || name.isBlank()) return null;
        for (OrderStatus s : values()) {
            if (s.name().equalsIgnoreCase(name.trim())) return s;
        }
        return null;
    }
}
