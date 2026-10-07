/*
 * Closed orders — which rows the history table shows.
 *
 * The table was already sortable, which answers "in what order". This answers "which rows", so
 * one security's whole life in the panel can be read on its own: every order ever placed on it
 * and what became of each, oldest first. The two compose — narrow to a security, sort on Closed,
 * and the result is that security's order history in date order.
 *
 * Narrowing is client-side because the history is already here. It rides on the same payload as
 * the table above it (OrdersReport.history), so asking the server for a subset of what the
 * browser is holding would re-parse three broker exports and re-read the price feed in order to
 * show *fewer* of the rows already on screen — and it would collapse the <details> the user is
 * working inside, which is the same reason a verdict is posted over fetch rather than htmx.
 *
 * Every choice a dropdown offers is DERIVED FROM THE ROWS, never a list kept here. A maintained
 * list of securities is wrong the first time an order is placed on a new one; a maintained list
 * of outcomes is wrong the first time OrderStatus learns a label — and either, being wrong,
 * hides rows without saying so. Options built from the rendered cells can only ever offer what
 * is actually there, and the filter can only ever match what a cell actually says.
 *
 * DOM-free and network-free so it can be unit-tested under plain node (see
 * src/test/js/ord-filter.test.mjs). dashboard.js owns the DOM wiring.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;   // node / require
    else root.OrdFilter = api;                                                    // browser global
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    /** What every dropdown's "All" option carries: no constraint on that field. */
    var ANY = '';

    /**
     * What a cell prints where the export carried nothing. It is absence, not a value, so it is
     * never offered as a choice — a dropdown listing it would read as a security called "—".
     * Such a row is still in "All" and still in the total, so nothing is hidden by leaving it
     * out; it simply cannot be singled out, which is the honest answer for a row that names
     * nothing.
     */
    var ABSENT = '—';

    /** The three fields a row can be narrowed by, and the keys a filter object uses. */
    var FIELDS = ['security', 'side', 'state'];

    function norm(v) {
        return v == null ? '' : String(v).trim();
    }

    /**
     * The value two sides are compared on. Side is matched case-blind and grouped case-blind:
     * each parser lifts it straight out of its own export and the three brokers have already
     * been caught writing one thing six ways in the Status column, which is the whole reason
     * OrderStatus exists. Were one to write "SELL", a case-sensitive filter would offer Sell and
     * SELL as two sides of the market. Security is compared verbatim — it is a ticker or an
     * exported name, where case is part of the identity rather than a broker's house style.
     */
    function key(field, value) {
        var s = norm(value);
        return field === 'side' ? s.toLowerCase() : s;
    }

    /** The filter that constrains nothing — what "All, All, All" means. */
    function blank() {
        return {security: ANY, side: ANY, state: ANY};
    }

    /** True when the filter narrows anything, i.e. the view is showing less than it holds. */
    function active(filter) {
        return FIELDS.some(function (f) { return norm(filter && filter[f]) !== ANY; });
    }

    /** True when this row survives the filter. A field left at "All" constrains nothing. */
    function matches(row, filter) {
        if (!filter) return true;
        return FIELDS.every(function (f) {
            var want = norm(filter[f]);
            return want === ANY || key(f, row && row[f]) === key(f, want);
        });
    }

    /**
     * What each dropdown can offer: {security: [...], side: [...], state: [...]}, each distinct
     * and alphabetical. Alphabetical rather than a hand-picked order (fills first, say) for the
     * same reason the lists themselves are derived — a hand-picked order is one more thing that
     * goes stale when a label is added, and it would have to be kept in step with a vocabulary
     * that lives in Java.
     *
     * `selected` is the filter currently applied, and its value is kept in the list even when no
     * row carries it any more. That happens for real: settle the last "No longer listed" row
     * while filtered to that outcome and the choice vanishes from the rows at the moment it is
     * being used. Dropping it would reset the dropdown to "All" and silently widen the view back
     * out; keeping it leaves the selection standing and lets the count say "0 of 34", which is
     * the truth.
     */
    function options(rows, selected) {
        var out = {};
        FIELDS.forEach(function (f) {
            var seen = Object.create(null);
            (rows || []).forEach(function (r) {
                var v = norm(r && r[f]);
                if (v === '' || v === ABSENT) return;
                var k = key(f, v);
                if (!(k in seen)) seen[k] = v;   // first spelling seen is the one offered
            });
            var sel = norm(selected && selected[f]);
            if (sel !== ANY && !(key(f, sel) in seen)) seen[key(f, sel)] = sel;
            out[f] = Object.keys(seen).map(function (k) { return seen[k]; }).sort(byLabel);
        });
        return out;
    }

    function byLabel(a, b) {
        var x = a.toLowerCase(), y = b.toLowerCase();
        return x < y ? -1 : x > y ? 1 : 0;
    }

    /** How many rows the filter shows, out of how many there are. */
    function count(rows, filter) {
        var all = rows || [];
        var shown = 0;
        all.forEach(function (r) { if (matches(r, filter)) shown++; });
        return {shown: shown, total: all.length};
    }

    /**
     * What the <summary> prints beside "Closed orders": the plain total when nothing is filtered,
     * and "shown of total" when something is. The filtered form is deliberately visible while the
     * block is collapsed — a filter still applied to a list the user cannot see would otherwise
     * be a count quietly disagreeing with the table under it.
     */
    function label(rows, filter) {
        var c = count(rows, filter);
        return active(filter) ? '(' + c.shown + ' of ' + c.total + ')' : '(' + c.total + ')';
    }

    return {
        ANY: ANY,
        ABSENT: ABSENT,
        FIELDS: FIELDS,
        blank: blank,
        active: active,
        matches: matches,
        options: options,
        count: count,
        label: label
    };
});
