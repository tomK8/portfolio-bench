/*
 * Watchlist column layout — which columns a list shows, and in what order.
 *
 * The stored layout is just an ordered list of keys, so it can name a column that no longer
 * exists (a metric dropped from the picker, a database restored over a newer build) and it can
 * name none at all. Resolving it is therefore the whole job: unknown keys are dropped rather
 * than rendered as empty cells, and a layout that resolves to nothing falls back to the default
 * set — a table of no columns is never what anyone meant, and without the fallback one bad row
 * in watchlist_column would leave a list permanently blank with no way back through the UI.
 *
 * Symbol and the ↑↓× actions cell are NOT part of a layout. They are pinned either side of it,
 * so no stored order can lose the one column that names the row or the one that edits it.
 *
 * This module is deliberately free of the DOM and of the network so it can be unit-tested under
 * plain node (see src/test/js/wl-columns.test.mjs). dashboard.js owns the DOM wiring.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;   // node / require
    else root.WlColumns = api;                                                    // browser global
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    /** Pinned first — it names the row, and the sticky-left cell the rest scrolls under. */
    var FIRST = 'sym';
    /** Pinned last — ↑ ↓ ×. */
    var LAST = 'actions';

    /**
     * The columns the watchlist shipped with, in their original order. A list that has never
     * been customised renders exactly this, so an existing list and a brand-new one look the
     * same without either needing a stored row.
     */
    var DEFAULTS = [
        'price', 'todayPct', 'pct2d', 'pct3d', 'pct5d', 'pct30d',
        'pctFromHigh', 'pctFromLow',
        'vol5d', 'vol10d', 'vol30d', 'vol1y',
        'trailingPe', 'position', 'gain',
        'thrHigh', 'thrMove', 'thrAbove', 'thrBelow'
    ];

    function isPinned(key) {
        return key === FIRST || key === LAST;
    }

    /**
     * Settle a stored layout against the columns this build actually offers.
     *
     * @param saved     ordered keys from the server; null/empty means "never customised"
     * @param available every key the picker knows about (array, or an object keyed by column key)
     * @return ordered keys to render between Symbol and the actions cell
     */
    function resolve(saved, available) {
        var known = {};
        if (Array.isArray(available)) available.forEach(function (k) { known[k] = true; });
        else if (available) Object.keys(available).forEach(function (k) { known[k] = true; });
        var hasRegistry = Object.keys(known).length > 0;

        var seen = {};
        var out = [];
        (Array.isArray(saved) ? saved : []).forEach(function (k) {
            if (typeof k !== 'string') return;
            k = k.trim();
            // Pinned columns are rendered either side of this list; a stored copy would
            // duplicate them. Drop rather than honour.
            if (!k || isPinned(k) || seen[k]) return;
            if (hasRegistry && !known[k]) return;              // a key this build no longer has
            seen[k] = true;
            out.push(k);
        });
        if (out.length) return out;
        return DEFAULTS.filter(function (k) { return !hasRegistry || known[k]; });
    }

    /**
     * Move one key a single place. Returns a new array; a no-op at either end, so the caller
     * need not know where the key sits.
     */
    function move(keys, key, up) {
        var out = (keys || []).slice();
        var i = out.indexOf(key);
        if (i < 0) return out;
        var j = up ? i - 1 : i + 1;
        if (j < 0 || j >= out.length) return out;
        var tmp = out[i];
        out[i] = out[j];
        out[j] = tmp;
        return out;
    }

    /** Add a key at the front (right after Symbol, where a comparison column wants to be). */
    function addFirst(keys, key) {
        if (isPinned(key)) return (keys || []).slice();
        var out = (keys || []).filter(function (k) { return k !== key; });
        out.unshift(key);
        return out;
    }

    function remove(keys, key) {
        return (keys || []).filter(function (k) { return k !== key; });
    }

    /** True when the layout is the shipped one, so the picker can show "default" rather than a diff. */
    function isDefault(keys) {
        var a = keys || [];
        return a.length === DEFAULTS.length && a.every(function (k, i) { return k === DEFAULTS[i]; });
    }

    return {
        FIRST: FIRST,
        LAST: LAST,
        DEFAULTS: DEFAULTS,
        resolve: resolve,
        move: move,
        addFirst: addFirst,
        remove: remove,
        isDefault: isDefault
    };
});
