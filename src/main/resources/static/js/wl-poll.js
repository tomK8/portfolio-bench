/*
 * Watchlist poll-interval guard — the pure logic that keeps the near-real-time poll from ever
 * running as a runaway timer.
 *
 * The bug this exists to kill: dashboard.js is one big IIFE. On page load it restores the last
 * active tab; when that tab is the watchlist, `activatePanel('panel-watchlist')` runs DURING the
 * IIFE's top-level execution → setupWatchlist() → wlStartPolling(). That happens thousands of lines
 * BEFORE `var WL_POLL_MS = 30000` has been assigned, so the hoisted var still reads `undefined`.
 * `setInterval(fn, undefined)` is treated as 0, which the browser clamps to a ~4ms timer — a poll
 * that fires hundreds of times a second, hammering the server and making inline edits race/revert.
 *
 * pollIntervalMs() collapses any unusable period (undefined / 0 / NaN / non-numeric / implausibly
 * small) to the safe 30s default, so the interval is correct regardless of init order and regardless
 * of a fat-fingered small literal. Anything finite and ≥ MIN_MS passes through unchanged.
 *
 * DOM- and network-free so it can be unit-tested under plain node (see wl-poll.test.mjs).
 * Loaded both as a browser <script> (attaches to window.WlPoll) and as a CommonJS module.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;   // node / require
    else root.WlPoll = api;                                                       // browser global
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    var DEFAULT_MS = 30000;   // the intended 30s watchlist cadence
    var MIN_MS = 1000;        // anything under 1s is treated as a mistake (the runaway-poll vector)

    /**
     * Resolve a raw poll-period value into a safe interval in milliseconds.
     * undefined / null / 0 / NaN / non-numeric / < MIN_MS → DEFAULT_MS; otherwise the value itself.
     */
    function pollIntervalMs(raw) {
        var n = Number(raw);
        return (isFinite(n) && n >= MIN_MS) ? n : DEFAULT_MS;
    }

    return { pollIntervalMs: pollIntervalMs, DEFAULT_MS: DEFAULT_MS, MIN_MS: MIN_MS };
});
