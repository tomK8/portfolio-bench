/*
 * Watchlist inline-edit sync — the pure logic that keeps a just-saved (or in-progress) threshold
 * edit from being reverted by a stale full reload.
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

    function equal(a, b) {
        if (!a || !b) return false;
        for (var i = 0; i < KINDS.length; i++) {
            if ((a[KINDS[i]] || '') !== (b[KINDS[i]] || '')) return false;
        }
        return true;
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
        thrDisplay: thrDisplay,
        rowDisplay: rowDisplay,
        rowThresholds: rowThresholds,
        savedThresholds: savedThresholds,
        equal: equal,
        resolve: resolve
    };
});
