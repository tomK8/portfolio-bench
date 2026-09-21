package com.portfolio.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The prices and quantities a reader has typed over the <b>intended</b> rungs of a group panel.
 *
 * <p>P(10) and P(30) are the numbers a ladder is actually built out of, and until these cells
 * existed the only way to move a rung you had decided on but not placed was to delete it and record
 * it again — which threw away the note, the one part of a rung that says why it is there. Now the
 * figures are editable in place, and committing one re-prices the whole block.
 *
 * <h2>Intended rungs, and nothing else</h2>
 * A rung that came from a broker's CSV is <b>fixed</b>. It is not "not worth editing" — it is not
 * ours to restate. The panel's whole claim is that it shows what is resting at the broker; a figure
 * this app let the user type over one would either be reverted on the next load (the pipeline
 * re-parses the export every time, and {@code order_history}'s upsert restates price and quantity
 * from it) or, worse, survive and have the app assert a limit the broker has never been told about.
 * An intended rung is the opposite case in every respect: it exists nowhere but here, nothing else
 * writes it, and editing it is the only way it can ever change.
 *
 * <p>Which of the two a cell belongs to is not decided here or in the browser. Only an intended rung
 * is rendered with inputs at all, and the key it posts is {@code intent:<id>}
 * ({@link com.portfolio.domain.model.IntendedRung#reference()}); anything else is dropped rather
 * than interpreted.
 *
 * <h2>Blank is "no opinion", never zero</h2>
 * An empty cell leaves that figure exactly as it was — the app-wide rule that a missing number is
 * {@code null} and not {@code 0}. It is also what makes the round trip safe: the panel posts
 * <em>every</em> editable cell on screen rather than tracking which ones were touched, so
 * "unchanged" has to be something the server can recognise rather than a claim the client makes. A
 * posted figure is an edit only when it differs from the one the cell was rendered with, compared at
 * {@link #EDIT_SCALE} — the same precision the cell prints, so a value typed straight back in is
 * never mistaken for one.
 */
public final class IntentEdits {

    /**
     * Decimal places an editable cell is rendered and compared at.
     *
     * <p>Six rather than the two the read-only cells printed. It is the intent's own figure either
     * way, but the cells are rendered by the same fragment a resting rung's read-only ones are, and
     * a quantity like {@code 0.3333} typed once must come back as itself rather than as an edit on
     * the next commit.
     */
    public static final int EDIT_SCALE = 6;

    private static final String INTENT_PREFIX = "intent:";
    private static final IntentEdits NONE = new IntentEdits(List.of());

    /** One editable rung as the browser posted it. */
    public record Typed(long intentId, BigDecimal price, BigDecimal quantity) {
    }

    private final List<Typed> typed;

    private IntentEdits(List<Typed> typed) {
        this.typed = typed;
    }

    public static IntentEdits none() {
        return NONE;
    }

    /**
     * Read the three index-aligned lists the panel posts — one entry per editable cell on screen.
     *
     * <p>Parsed leniently, for the reason the intent form's figures are: a half-typed number has to
     * come back as "that cell is unchanged", not a 400 that wipes the block the reader is working
     * in. A key that does not name an intended rung is dropped: those are the only editable cells
     * the panel renders, so anything else reaching here is a stale tab or a hand-made request, and
     * neither is something to act on.
     */
    public static IntentEdits of(List<String> keys, List<String> prices, List<String> quantities) {
        if (keys == null || keys.isEmpty()) return NONE;
        List<Typed> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            Long id = intentId(keys.get(i));
            if (id == null) continue;
            out.add(new Typed(id, parse(at(prices, i)), parse(at(quantities, i))));
        }
        return out.isEmpty() ? NONE : new IntentEdits(out);
    }

    public boolean isEmpty() {
        return typed.isEmpty();
    }

    /** Every cell as posted, in the order the panel drew them. */
    public List<Typed> all() {
        return typed;
    }

    /**
     * Whether a posted figure is an edit of the one that was rendered.
     *
     * <p>Blank is never an edit — it is "no opinion" rather than zero, so tabbing through a row of
     * cells writes nothing and a half-typed figure cannot silently empty a commitment against cash.
     * A figure against an absent one is, though: typing a price onto an intended rung that never had
     * one is exactly the edit that gives it a probability.
     */
    public static boolean differs(BigDecimal posted, BigDecimal original) {
        if (posted == null) return false;
        if (original == null) return true;
        return round(posted).compareTo(round(original)) != 0;
    }

    /**
     * What an editable cell is filled with — the figure at {@link #EDIT_SCALE}, trailing zeros
     * stripped, no grouping separator and no currency symbol, so it is a number the browser can put
     * straight back on the wire and this class will recognise as unchanged. The currency is printed
     * beside the box instead; it was never something the reader was editing.
     */
    public static String editable(BigDecimal value) {
        if (value == null) return "";
        BigDecimal r = round(value).stripTrailingZeros();
        return (r.signum() == 0 ? BigDecimal.ZERO : r).toPlainString();
    }

    /** The intended rung a posted key names, or null when it names anything else. */
    private static Long intentId(String key) {
        if (key == null || !key.startsWith(INTENT_PREFIX)) return null;
        try {
            return Long.parseLong(key.substring(INTENT_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Lenient decimal: blank or unparseable is no figure at all, never zero. */
    private static BigDecimal parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(EDIT_SCALE, RoundingMode.HALF_UP);
    }

    private static String at(List<String> values, int i) {
        return values == null || i >= values.size() ? null : values.get(i);
    }
}
