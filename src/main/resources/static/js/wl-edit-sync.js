/*
 * Watchlist inline-edit sync — the pure logic that keeps a just-saved (or in-progress) inline edit
 * (the four alert thresholds, the two order targets, and the note beside them) from being reverted
 * by a stale full reload.
 *
 * The bug this exists to kill: the dashboard's /watchlist view is slow (FX + ledger + fundamentals,
 * ~1s+). A background reload (add-timer, Refresh, tab re-entry) issued around the same instant as a
 * fast inline save can have its server-side read run *before* the save commits, so it returns the
 * pre-edit value. When that reload's response repaints the table it reverts the input to the old
 * number — even though the save persisted. From the user's chair the edit "won't save".
 *
 * The fix is an optimistic overlay confirmed by echo: when the user saves symbol S we remember the
 * saved values. Any subsequent render whose server row for S does NOT yet match those values is
 * treated as a stale read (its server-side query beat the commit) and the saved values win; the
 * overlay is only stood down once a render actually carries them back — proof the write landed.
 * Because the client is the single writer of these fields, its own last save is the truth until it
 * saves again; issue-time ordering is deliberately NOT used, because a reload issued a hair after the
 * save can still read pre-commit (observed in the wild), so "later request" ≠ "saw the write".
 * A failed save clears the overlay separately (dashboard.js), so a value that didn't persist can't
 * linger on screen.
 *
 * This module is deliberately free of the DOM and of the network so it can be unit-tested under
 * plain node (see src/test/js/wl-edit-sync.test.mjs). dashboard.js owns the DOM wiring.
 *
 * Loaded both as a browser <script> (attaches to window.WlEditSync) and as a CommonJS module.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;   // node / require
    else root.WlEditSync = api;                                                   // browser global
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    var KINDS = ['high', 'move', 'above', 'below'];

    /**
     * Canonicalise a raw input string into the exact string the table renders in that cell, so a
     * saved value and a server-echoed value compare equal. Blank / non-numeric / disabled → ''.
     *   - high/move are percents: 0 or negative means "trigger disabled" → ''.
     *   - above/below are price levels: negative is rejected → ''; 0 is disabled → ''.
     * Currency symbols, thousands separators, percent signs and whitespace are stripped first.
     */
    function thrDisplay(kind, raw) {
        if (raw === null || raw === undefined) return '';
        var s = String(raw).replace(/[£$€,%\s]/g, '');
        if (s === '') return '';
        var n = parseFloat(s);
        if (!isFinite(n)) return '';
        if (n <= 0) return '';                     // 0 / negative → disabled for every kind
        return String(n);
    }

    /** The canonical display string for one kind read off a server row (null/≤0 → ''). */
    function rowDisplay(kind, row) {
        if (!row) return '';
        if (kind === 'high') return thrDisplay(kind, row.highThresholdPct);
        if (kind === 'move') return thrDisplay(kind, row.moveThresholdPct);
        if (kind === 'above') return row.priceAbove == null ? '' : thrDisplay(kind, row.priceAbove);
        if (kind === 'below') return row.priceBelow == null ? '' : thrDisplay(kind, row.priceBelow);
        return '';
    }

    /** The two order-target inputs. Price semantics, so they canonicalise exactly as a level does. */
    var TARGET_KINDS = ['tgtAbove', 'tgtBelow'];

    /** The canonical display strings for a server row's two order targets (null/≤0 → ''). */
    function rowTargets(row) {
        return {
            tgtAbove: row && row.targetAbove != null ? thrDisplay('above', row.targetAbove) : '',
            tgtBelow: row && row.targetBelow != null ? thrDisplay('below', row.targetBelow) : ''
        };
    }

    /** Canonicalise the two raw strings the user just saved into a comparable shape. */
    function savedTargets(above, below) {
        return { tgtAbove: thrDisplay('above', above), tgtBelow: thrDisplay('below', below) };
    }

    /**
     * What one row's two target inputs should show, given any pending save for that symbol. Same
     * rule as resolve(): the overlay wins until a render carries it back.
     *
     * <p>A third record rather than two more slots in the threshold one, for the same reason the
     * note is separate: the targets are their own save to their own endpoint (POST
     * /watchlist/target, which deliberately re-arms nothing, because nothing fires on a target).
     * A threshold save carries no target and a target save carries no thresholds, so one record
     * holding both would have each write erase the other's pending value.
     */
    function resolveTargets(row, pending) {
        var server = rowTargets(row);
        if (pending && !equalKeys(TARGET_KINDS, server, pending)) {
            return { values: pending, confirmed: false };
        }
        return { values: server, confirmed: !!pending };
    }

    /**
     * The same optimistic-overlay bargain for the per-symbol note, kept separate from the four
     * thresholds because it is a separate save (POST /watchlist/note, which deliberately does not
     * re-arm the alerts) and the two must not be able to blank one another: a threshold save
     * carries no note and a note save carries no thresholds, so one overlay holding all five would
     * have each write erase the other's pending value.
     *
     * <p>Text, not a number — nothing is canonicalised but the absence: null, undefined and a
     * blank string are one state ("no plan written"), which is what the empty input shows.
     */
    function noteDisplay(raw) {
        if (raw === null || raw === undefined) return '';
        var s = String(raw).trim();
        return s;
    }

    /**
     * What one row's note input should show, given any pending save for that symbol.
     * Same rule as resolve(): the overlay wins until a render carries it back.
     *
     * @param row      the server row from this render's payload
     * @param pending  the note string last saved for this symbol, or null/undefined if none
     */
    function resolveNote(row, pending) {
        var server = noteDisplay(row && row.note);
        if (pending !== null && pending !== undefined && noteDisplay(pending) !== server) {
            return { value: noteDisplay(pending), confirmed: false };
        }
        return { value: server, confirmed: pending !== null && pending !== undefined };
    }

    /** All four canonical display strings for a server row. */
    function rowThresholds(row) {
        return {
            high: rowDisplay('high', row),
            move: rowDisplay('move', row),
            above: rowDisplay('above', row),
            below: rowDisplay('below', row)
        };
    }

    /** Canonicalise the four raw strings the user just saved into a comparable shape. */
    function savedThresholds(high, move, above, below) {
        return {
            high: thrDisplay('high', high),
            move: thrDisplay('move', move),
            above: thrDisplay('above', above),
            below: thrDisplay('below', below)
        };
    }

    /**
     * The values one save must post for a set of fields, given only the boxes this list's layout
     * actually puts on screen.
     *
     * <p>Each of these writes restates its whole group — POST /watchlist/threshold carries all four
     * levels, POST /watchlist/target both targets — but the column picker can take any of them off,
     * and then there is no input to read. **A box that is not on screen is not a blank.** Posting
     * '' for it would clear a trigger the user cannot see and never touched; reading `.value` off
     * the null the lookup returns is worse still, because it throws before the save is issued at
     * all, so nothing persists, nothing re-arms, and the only evidence is the console. (Observed:
     * a list showing `Below trigger` alone — no High %, Move % or Above — where every edit to a
     * below level silently did nothing, and the row stayed coloured because `clearFiredFor` was
     * never reached either.)
     *
     * <p>So a hidden field is carried through from the stored row. The server row is what the
     * table was rendered from, which makes it the same value the box would have shown had the
     * column been on — the write is still a restatement of the whole group, just one assembled
     * from two places.
     *
     * @param keys    the group's field names ({@link #KINDS} or {@link #TARGET_KINDS})
     * @param boxes   raw strings keyed by field for the ones on screen; a hidden field is absent
     * @param stored  the same fields read off the server row (see rowThresholds / rowTargets)
     */
    function posted(keys, boxes, stored) {
        var out = {};
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            var v = boxes ? boxes[k] : null;
            out[k] = (v === null || v === undefined) ? ((stored && stored[k]) || '') : String(v).trim();
        }
        return out;
    }

    /** The four level strings to post, with any kind this layout hides taken from the row. */
    function postedThresholds(boxes, row) {
        return posted(KINDS, boxes, rowThresholds(row));
    }

    /** The two target strings to post, with either side this layout hides taken from the row. */
    function postedTargets(boxes, row) {
        return posted(TARGET_KINDS, boxes, rowTargets(row));
    }

    function equalKeys(keys, a, b) {
        if (!a || !b) return false;
        for (var i = 0; i < keys.length; i++) {
            if ((a[keys[i]] || '') !== (b[keys[i]] || '')) return false;
        }
        return true;
    }

    function equal(a, b) {
        return equalKeys(KINDS, a, b);
    }

    /**
     * Decide what one row's four threshold inputs should show, given any optimistic overlay for the
     * symbol.
     *
     * @param row      the server row from this render's payload
     * @param pending  the optimistic saved values for this symbol {high,move,above,below}, or null
     * @returns { values: {high,move,above,below}, confirmed: boolean }
     *   - values: the strings to put in the inputs. The overlay wins over any render that hasn't
     *     caught up to it yet, so a stale read can't revert a just-saved edit.
     *   - confirmed: true when the server row already carries the overlay values — the write is
     *     provably visible. Informational; the overlay is retained regardless (single writer), and is
     *     replaced by the next save, cleared on save failure, or reset by a full page reload.
     */
    function resolve(row, pending) {
        var serverVals = rowThresholds(row);
        if (pending && !equal(serverVals, pending)) {
            return { values: pending, confirmed: false };   // server hasn't echoed the save → keep it
        }
        return { values: serverVals, confirmed: !!pending };
    }

    return {
        KINDS: KINDS,
        TARGET_KINDS: TARGET_KINDS,
        thrDisplay: thrDisplay,
        rowDisplay: rowDisplay,
        rowThresholds: rowThresholds,
        rowTargets: rowTargets,
        savedTargets: savedTargets,
        resolveTargets: resolveTargets,
        postedThresholds: postedThresholds,
        postedTargets: postedTargets,
        noteDisplay: noteDisplay,
        resolveNote: resolveNote,
        savedThresholds: savedThresholds,
        equal: equal,
        resolve: resolve
    };
});
