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
filter, Momentum, Mom. signals, Range floor) / Tools (the named watchlists and a `+`, then a
rule, then Reconcile, Journal, Earnings, Reminders, Manual positions, Transfers).

The watchlist tabs are the one place a *panel* is not one *tab*: they are server-rendered from
`watchlist_list` and every one carries `data-panel="panel-watchlist"`, differing only in
`data-list`. The derivation is untouched by that — it keys on `data-panel` — but the
active-marking loop must compare `data-list` **when the attribute is present**, or every list
tab lights up at once; tabs without it are unaffected. They are rendered server-side precisely
because the derivation runs while `dashboard.js` executes: a strip filled in later by `fetch()`
would be empty at that moment, and the restored-tab path would find no group for the saved panel.


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


## Sortable tables

`attachSort(table)` wires every `<th data-sort>` in a thead; `sortBody` does the ordering and
`reapplyActiveSort(table)` re-runs whatever the header currently claims. Three rules hold
wherever they are used:

- **The marker in the DOM is the sort.** `th.dataset.sortDir` is the single record of which
  column and which direction, and the click handler reads it at click time rather than keeping
  the state in its closure. That is what lets a sort be restored onto a rebuilt thead
  (`restoreOrdersSort`, after an htmx swap) or cleared from outside (the watchlist does it when a
  drag makes the visible order the manual one) without the next click flipping the wrong way.
- **Index by `cellIndex`, never by position among the sortable headers.** A table may carry a
  column that opts out of sorting — the watchlist's four alert-threshold inputs carry no
  `data-sort` — and from the first such column rightwards the two indices part company.
  `sortBody` reads `tr.cells[idx]`, so sorting by the filtered index silently sorted by the
  neighbouring column; where that neighbour was an `<input>` cell every row parsed as null and
  the click did visibly nothing. Only `cellIndex` lines the two up. (No sortable thead spans
  columns, so it is the visual column too.)
- **A sort is a property of the table, not an event that happened.** Any code that re-renders or
  patches rows in place has to call `reapplyActiveSort` afterwards, or the values move while the
  rows stay put and the header goes on claiming an order the table no longer has. The Live tab
  does it after a group layout, the fundamentals snapshot after a background refresh, and the
  watchlist after every quote poll, fundamentals fill and reconcile (see
  [watchlist.md](watchlist.md) for the two states where it stands down).
