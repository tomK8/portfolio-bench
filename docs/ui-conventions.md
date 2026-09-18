# UI conventions

## Stack & vendoring

UI: server-rendered Thymeleaf + htmx + Chart.js 4 + `chartjs-adapter-date-fns` + hammer.js +
`chartjs-plugin-zoom`, all **vendored** under `static/js/vendor/`. They were CDN-loaded until
2026-08-08, when a jsdelivr/unpkg blip left `Chart` undefined and every chart panel rendered
silently blank; a loopback-only tool gets no cache-sharing benefit from a CDN, so there was
nothing to weigh against that. They never auto-update: `scripts/vendor-manifest.txt` pins each
by exact URL (the URL *is* the version — no second list to drift) and `scripts/update-vendor.sh`
re-downloads them (`--check` for a dry run), staging and installing all-or-nothing so a
half-updated dir can't mix incompatible versions. Load order matters — `chart.umd.js` before the
adapter and the zoom plugin, `hammer.min.js` before the zoom plugin. Dashboard tabs:

## Tab groups

Primary tab groups are **derived from the DOM** — `PANEL_TO_GROUP` / `GROUP_DEFAULT_PANEL` are
built by scanning `.tabs-secondary[data-group]` strips, so moving a panel between groups is an
HTML-only edit (no JS to keep in sync). A saved tab whose group changed still restores correctly:
the per-group memory is validated with `PANEL_TO_GROUP[last] === g` before use. Groups are
Holdings / Performance / Risk / Quality / **Strategies** (the five backtests: Swing stats, Trend
filter, Momentum, Mom. signals, Range floor) / Tools (Reconcile, Journal, Watchlist, Earnings,
Manual positions, Transfers).


## Lazy chart loaders

Charts load lazily on first tab click, each behind a `xLoaded` latch set *before* the fetch so
it runs once per page. **Every such loader must end in a `.catch`** that releases its own latch
and calls `panelLoadFailed(panelId, err, retryFn)` (`dashboard.js`) — without one, a single
transient failure left the latch set, so the tab stayed blank with no message and re-clicking it
was a silent no-op until a full reload. `panelLoadFailed` destroys any half-built charts on the
panel first (Chart.js refuses to re-bind a live canvas, so a partly-failed loader would otherwise
throw "Canvas is already in use" on every retry), then renders a `.panel-load-error` box with a
Retry button; the paired `clearPanelError(panelId)` goes right after the latch. Because loaders
can now re-run, any `addEventListener` inside one must go through `bindOnce` or it stacks a
duplicate handler per retry. Loaders that own a sub-view (allocation's concentration/currency)
pass the **sub-view** id, not the panel id — `panelLoadFailed` queries descendants, so a
panel-scoped call would clear a sibling sub-view's box.

**The tab restore runs at DOM ready, not during script execution.** `dashboard.js` is the last
element in `<body>`, so its top-level code runs while `readyState` is still `"loading"`: htmx has
parsed but has not yet run its own `DOMContentLoaded` init. Calling `activatePanel` from there
fired the restored panel's loader in a materially different environment from a tab *click*, which
always happens long after init. `restoreActiveTab` is therefore deferred — htmx registers its
listener from `<head>`, before this one, so by the time the restore runs htmx is fully up and the
restore path and the click path are the same path. Open orders is where it showed: `loadOrders`
is a bare `htmx.ajax`, and its `if (window.htmx)` guard made the pre-init case a **silent
no-op** — the panel came up active and blank on every reload and filled only after a round trip
to another tab and back. A loader reached by restore must therefore never assume a vendored
library is initialised, and must report rather than swallow the case: `loadOrders` now ends in
the same `.catch` → `panelLoadFailed` every other loader has.

