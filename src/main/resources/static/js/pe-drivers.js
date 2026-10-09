/*
 * P/E history — the sentence under the decomposition cards.
 *
 * The panel publishes two exact identities over one window:
 *
 *     priceMult  ≡  epsMult × peMult
 *     epsMult    ≡  opEpsMult × mixMult
 *
 * and this turns them into English. Two rules, both learned the hard way on GOOG, whose window
 * to 2026-10-09 read "price ×6.07 = EPS ×9.12 × multiple ×0.67. Drivers: mostly earnings growth
 * — multiple barely moved."
 *
 * RULE ONE: A MULTIPLICATIVE IDENTITY IS COMPARED IN LOGS. The test used to be
 * |epsMult - 1| > |peMult - 1| × 1.5 — an additive deviation from 1 on a multiplicative
 * identity, which scores ×2 at 1.0 and ×0.5 at 0.5 though they are the same move in opposite
 * directions. A de-rating therefore always read smaller than the equivalent re-rating. In logs
 * they are equal and opposite, which is what the identity itself says.
 *
 * RULE TWO: DOMINANCE IS NOT MAGNITUDE. The old sentence asserted the multiple "barely moved"
 * on the strength of a test that only ever asked which term was larger. On GOOG the dominance
 * claim was true (ln 9.12 = 2.21 against ln 0.67 = -0.40) and the magnitude claim was false by
 * a third. So the secondary term is now described BY WHAT IT DID, and "barely moved" is said
 * only when it is actually true — |ln m| inside FLAT, which is a statement about m and nothing
 * else.
 *
 * DOM-free and network-free so it can be unit-tested under plain node (see
 * src/test/js/pe-drivers.test.mjs). dashboard.js owns the DOM wiring.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;   // node / require
    else root.PeDrivers = api;                                                    // browser global
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    /**
     * How much larger one term's log has to be before it is called the driver. Unchanged from
     * the original rule — only the space it is measured in changed.
     */
    var DOMINANCE = 1.5;

    /** Inside this, a factor is reported as having barely moved. ±5%. */
    var FLAT = Math.log(1.05);

    /**
     * The mix is held to a tighter band than FLAT. A 2% drift in net ÷ operating income is a
     * rounding of two filed figures; past that the tax-and-non-operating mix has genuinely
     * changed, and the extra clause is worth its length.
     */
    var MIX_FLAT = Math.log(1.02);

    /** A finite, strictly positive multiple, or null. A ratio of zero or a loss is not one. */
    function mult(v) {
        var n = typeof v === 'number' ? v : parseFloat(v);
        return isFinite(n) && n > 0 ? n : null;
    }

    /** Percent move of a multiple, as a whole number: 0.67 → 33, 1.124 → 12. */
    function pct(m) {
        return Math.round(Math.abs(m - 1) * 100);
    }

    /** A filed ratio, printed as a multiple: 1.6543 → "1.65×". */
    function ratio(v) {
        var n = typeof v === 'number' ? v : parseFloat(v);
        return n.toFixed(2) + '×';
    }

    /**
     * What the multiple did, as a clause that can follow "mostly earnings growth". Never claims
     * a magnitude the factor does not have.
     */
    function peClause(peMult) {
        if (Math.abs(Math.log(peMult)) < FLAT) return ' — the multiple barely moved.';
        return peMult < 1
            ? ', against a ' + pct(peMult) + '% de-rating of the multiple.'
            : ', with the multiple adding a further ' + pct(peMult) + '%.';
    }

    /** The same for the earnings term, when the multiple is the driver. */
    function epsClause(epsMult) {
        if (Math.abs(Math.log(epsMult)) < FLAT) return ' — earnings barely moved.';
        return epsMult < 1
            ? ', against a ' + pct(epsMult) + '% fall in earnings.'
            : ', with earnings adding a further ' + pct(epsMult) + '%.';
    }

    /** Neither term dominates: say what each did rather than picking one. */
    function evenClause(epsMult, peMult) {
        var up = epsMult >= 1, peUp = peMult >= 1;
        if (up && peUp) {
            return 'a roughly even mix of <b>earnings growth</b> and <b>multiple expansion</b>.';
        }
        if (!up && !peUp) {
            return 'earnings and the multiple fell in roughly equal measure.';
        }
        if (up) {
            return '<b>earnings growth</b> of ' + pct(epsMult) + '%, largely offset by a '
                + pct(peMult) + '% de-rating of the multiple.';
        }
        return 'a ' + pct(epsMult) + '% fall in earnings, largely offset by a '
            + pct(peMult) + '% re-rating of the multiple.';
    }

    /**
     * The "Drivers: …" clause. Returns null when either term is not a usable multiple, in which
     * case the caller prints the identity without a claim about it.
     */
    function drivers(summary) {
        if (!summary) return null;
        var epsMult = mult(summary.epsMult), peMult = mult(summary.peMult);
        if (epsMult === null || peMult === null) return null;

        var le = Math.abs(Math.log(epsMult)), lp = Math.abs(Math.log(peMult));
        if (le > lp * DOMINANCE) return 'mostly <b>earnings growth</b>' + peClause(peMult);
        if (lp > le * DOMINANCE) return 'mostly <b>multiple expansion</b>' + epsClause(epsMult);
        return evenClause(epsMult, peMult);
    }

    /**
     * The second sentence: what the EPS term is once everything below the operating line is
     * taken out of it.
     *
     * <p>Operating income is pre-tax, so the sentence says so and never calls a level built on
     * it a P/E. Only the change over the window is published, and only beside the GAAP figures.
     *
     * <p>The mix term is NOT called "non-operating". Tax sits between the operating line and the
     * bottom line too, and nothing in the filings splits the two into a per-share figure without
     * a modelled tax rate — so the sentence names both and claims neither. It is also why the
     * ratio is printed net ÷ operating: tax usually outweighs non-operating income, so operating
     * income exceeds net income for most filers and "operating is 132% of net income" reads as
     * an error.
     *
     * Returns null when there is no split to describe.
     */
    function operating(summary, split) {
        if (!summary || !split) return null;
        var epsMult = mult(summary.epsMult), peMult = mult(summary.peMult);
        var opEps = mult(split.opEpsMult), mix = mult(split.mixMult);
        var opPe = mult(split.opMultipleMult);
        if (epsMult === null || opEps === null || mix === null) return null;

        var ends = 'net income was ' + ratio(split.netToOperatingStart)
            + ' operating income over the window’s base twelve months and '
            + ratio(split.netToOperatingEnd) + ' over its latest';

        if (Math.abs(Math.log(mix)) < MIX_FLAT) {
            return 'EPS ×' + epsMult.toFixed(2) + ' is operating income per share ×'
                + opEps.toFixed(2) + ': ' + ends + ', so the decomposition above is the operating '
                + 'business on a steady tax and non-operating mix.';
        }

        var text = 'EPS ×' + epsMult.toFixed(2) + ' splits into operating income per share ×'
            + opEps.toFixed(2) + ' and ×' + mix.toFixed(2)
            + ' from everything between the operating line and the bottom line — non-operating '
            + 'items and tax, which no filing separates per share without a modelled rate. '
            + ends.charAt(0).toUpperCase() + ends.slice(1) + '.';
        if (opPe !== null && peMult !== null) {
            text += ' On operating income per share (pre-tax, so a level on it is not a P/E) the '
                + 'multiple is ×' + opPe.toFixed(2) + ', not ×' + peMult.toFixed(2) + '.';
        }
        return text;
    }

    /**
     * The third sentence, where there is one: the window's base twelve months is one the filer
     * has since restated, and the decomposition on its own latest figure.
     *
     * A decomposition is a two-point comparison, so it is only as good as its endpoints — and the
     * base is the end that can have been superseded. MSFT's FY2016 quarters are pre-ASC-606 and
     * were never re-tagged; the filer's own annual for the identical window is 22% higher, which
     * is the difference between the multiple supplying 7% of a ten-year move and supplying a
     * third of it.
     *
     * Nothing above this line changes. The chart is look-ahead-free and the restated figure was
     * not public at the base date, so the cards keep the assembled figure and cannot disagree
     * with the chart beneath them. This says what a reader cannot otherwise see.
     *
     * Returns null when the base window has not been restated.
     */
    function restated(symbol, summary, r) {
        if (!summary || !r) return null;
        var epsMult = mult(r.epsMult), peMult = mult(r.peMult);
        if (epsMult === null || peMult === null) return null;
        var was = mult(summary.peMult);

        var text = 'Base window restated: the twelve months to ' + r.periodEnd
            + ' assemble to ' + money(r.assembled) + ' of EPS from four filed quarters, but '
            + symbol + '’s own latest annual for that same window is ' + money(r.filedAnnual)
            + ' — the quarters were never re-tagged when the year was. The chart keeps what was '
            + 'public at the time; on the restated base the window reads EPS ×' + epsMult.toFixed(2)
            + ' × multiple ×' + peMult.toFixed(2);
        return was === null ? text + '.'
            : text + ', against the ×' + was.toFixed(2) + ' above.';
    }

    /** A per-share figure as filed, to the cent. */
    function money(v) {
        var n = typeof v === 'number' ? v : parseFloat(v);
        return (n < 0 ? '−$' : '$') + Math.abs(n).toFixed(2);
    }

    return {
        DOMINANCE: DOMINANCE,
        FLAT: FLAT,
        MIX_FLAT: MIX_FLAT,
        drivers: drivers,
        operating: operating,
        restated: restated
    };
});
