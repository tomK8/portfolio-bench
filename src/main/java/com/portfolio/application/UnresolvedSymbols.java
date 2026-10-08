package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher.Suggestion;
import com.portfolio.persistence.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The record of symbols Yahoo has actually rejected — the difference between "still fetching" and
 * "we asked, and there is nothing there".
 *
 * <p>Background price fetches log their failures and return 0 rows, which is right for the
 * scheduler (a dead ticker must not crash it) but leaves every reader unable to tell a backfill in
 * flight from one that finished empty. Both rendered the same: the watchlist row sat at
 * "(fetching…)" and the limit probe repeated "a fetch is queued now — reload in a few seconds", for
 * as long as the symbol stayed wrong. This is the missing third state, and the convention it
 * restores is the standard one — an instrument that cannot be priced is reported by name, never
 * absorbed silently.
 *
 * <p>A verdict carries the candidates from {@link com.portfolio.adapter.YahooPriceFetcher#search},
 * so the report names the ticker that would have worked rather than only the one that did not
 * ({@code EQQU} → {@code EQQU.L}), and points at the mappings editor that fixes it. Recording one
 * is the only path that calls Yahoo; readers are pure KV lookups and cost nothing, which is what
 * lets the limit probe consult this on every keystroke.
 *
 * <p>Verdicts are cleared the moment a fetch for that symbol succeeds or its mapping is edited, so
 * a corrected ticker never keeps showing a stale complaint. Nothing else expires them: a symbol
 * Yahoo rejected last week is still rejected today, and re-asking on a timer would be the
 * maintenance this app avoids.
 */
public class UnresolvedSymbols {

    private static final Logger log = LoggerFactory.getLogger(UnresolvedSymbols.class);

    private static final String KEY = "unresolved_tickers";
    /** Field/item separators — control characters, so a listing name may hold any punctuation. */
    private static final String FIELD_SEP = "\u001F";   // unit separator
    private static final String ITEM_SEP = "\u001E";    // record separator
    /** Enough to show the shape of the answer (a London and a Paris line) without a wall of noise. */
    private static final int MAX_SUGGESTIONS = 4;
    /**
     * Where the reader fixes it. There is no standalone mappings tab — the editor is the
     * "Add a mapping" row that opens the "All instrument mappings" list at the foot of Open
     * orders, plus the inline unpriced-symbol editors on the two sync tabs.
     *
     * <p>Naming the wrong place is worse than naming none, and until that add row existed this
     * hint did exactly that: the list it points at was its own inventory, Remove buttons and
     * nothing else. Every other editor in the app is attached to a gap the app itself noticed
     * — an unpriced <em>holding</em>, an order description that resolves to no symbol —
     * so a symbol in neither state had no reachable editor at all. A watchlist name Yahoo rejects
     * is exactly that symbol, and it is the one this class is written for.
     */
    static final String FIX_HINT =
            "Add it under \"All instrument mappings\" at the foot of the Open orders tab";

    private final KeyValueStore kv;

    public UnresolvedSymbols(KeyValueStore kv) {
        this.kv = kv;
    }

    /**
     * One rejected symbol. {@code ticker} is what was actually sent to Yahoo (it differs from
     * {@code symbol} exactly when a mapping resolved it), {@code suggestions} the listings Yahoo
     * offered instead — empty when Yahoo knows nothing by that name at all, which reads as a typo
     * rather than a missing exchange suffix.
     */
    public record Unresolved(String symbol, String ticker, LocalDate checkedOn, List<String> suggestions) {

        /** The best candidate to map to, or null when Yahoo offered none. */
        public String bestSuggestion() {
            if (suggestions.isEmpty()) return null;
            String first = suggestions.get(0);
            int sp = first.indexOf(' ');
            return sp < 0 ? first : first.substring(0, sp);
        }

        /** A complete sentence for a warning strip: what failed, when, what to use, and where to fix it. */
        public String message() {
            String head = ticker.equals(symbol)
                    ? "Yahoo has no listing for " + symbol
                    : "Yahoo has no listing for " + symbol + "'s mapped ticker " + ticker;
            if (suggestions.isEmpty()) {
                return head + " (checked " + checkedOn + "), and its search knows nothing by that "
                        + "name either — check the spelling. " + FIX_HINT + ".";
            }
            return head + " (checked " + checkedOn + "), but Yahoo's own search does know: "
                    + String.join("; ", suggestions) + ". " + FIX_HINT
                    + " — likely " + symbol + " → " + bestSuggestion() + ".";
        }
    }

    /**
     * Note that {@code ticker} resolved to nothing, asking Yahoo what it should have been. Called
     * from the background fetch paths, so it costs one extra search request on a failure only.
     */
    public void record(String symbol, String ticker, List<Suggestion> suggestions) {
        if (symbol == null || symbol.isBlank()) return;
        List<String> shown = new ArrayList<>();
        for (Suggestion s : suggestions == null ? List.<Suggestion>of() : suggestions) {
            if (shown.size() >= MAX_SUGGESTIONS) break;
            shown.add(s.display().replace(FIELD_SEP, " ").replace(ITEM_SEP, " ").replace('\n', ' '));
        }
        Map<String, String> all = new LinkedHashMap<>(kv.getStringMap(KEY));
        all.put(symbol.trim().toUpperCase(),
                String.join(FIELD_SEP, ticker, LocalDate.now().toString(), String.join(ITEM_SEP, shown)));
        kv.putStringMap(KEY, all);
        log.info("Recorded unresolved symbol {} -> {} ({})", symbol, ticker,
                shown.isEmpty() ? "no Yahoo candidates" : "Yahoo suggests " + String.join("; ", shown));
    }

    /** The stored verdict for {@code symbol}, or null if it has never been rejected. */
    public Unresolved get(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String sym = symbol.trim().toUpperCase();
        String raw = kv.getStringMap(KEY).get(sym);
        if (raw == null) return null;

        String[] parts = raw.split(FIELD_SEP, -1);
        if (parts.length < 2) return null;
        LocalDate on;
        try {
            on = LocalDate.parse(parts[1]);
        } catch (RuntimeException e) {
            return null;   // a hand-edited or half-written line is not a verdict
        }
        List<String> suggestions = parts.length < 3 || parts[2].isBlank()
                ? List.of() : List.of(parts[2].split(ITEM_SEP));
        return new Unresolved(sym, parts[0], on, suggestions);
    }

    /** Forget {@code symbol}'s verdict — its prices arrived, or its mapping changed. No-op if clean. */
    public void clear(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        Map<String, String> all = new LinkedHashMap<>(kv.getStringMap(KEY));
        if (all.remove(symbol.trim().toUpperCase()) != null) kv.putStringMap(KEY, all);
    }
}
