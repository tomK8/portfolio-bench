# Table grouping (Cash ledger + Live)


Two tables have a **Group by** dropdown that collapses their rows into groups, each a header row
carrying the aggregate above its (initially collapsed) members: Holdings → **From cash ledger**
(None / Sector / Account) and Holdings → **Live** (None / Sector). `LedgerGroupingService` resolves
the ledger's selection; the maths is `domain/grouping/HoldingGrouper` for the ledger and
`application/LiveGrouper` for Live.

**The key is a resolver, not a branch — `domain/grouping/HoldingGroupKey<T>`.** One method,
`Group keyFor(T row)`. The endpoint, the fragment, the header rows, the weighted percentages,
the sort and the expand/collapse are all written against it, so **a new way to group the book is a
constant in `LedgerGroupingService.GroupBy` plus a resolver class and nothing else** — currency and
a permanent/rotation label are both wanted next. The dropdown is generated from `options()`
(`RootController` puts it in the model), so even the template needs no edit. If a future key needs
a change outside its own class, the abstraction has been broken. The only key-shaped thing
`HoldingGrouper` acts on is `Group.residual()`, and that is a property the resolver *declares*
about its own group — it pins the group last, so the unplaced never sit mid-ranking as though they
had been classified.

**`T` is the row type, because the two tables aggregate different columns.** The ledger groups
`AggHolding`s and weights Gain % by cost; Live groups `LivePricesService.Row`s, which carry no cost
basis anywhere and instead weight a day move and five trailing windows. Generifying the *key*
rather than the aggregation is the split that matches reality — how a position is classified is
genuinely shared, while what a header row can honestly say is a property of the table it sits in.
`SectorGroupKey.groupFor(symbol, gbp)` is the shared classification core and the `keyFor` overrides
are adapters, so a symbol cannot be one sector on one screen and another on the other.
`SectorGroupKeyProvider` holds the loaded `fundamentals_snapshot` behind a **2-minute TTL** and is
shared by both: `loadAll()` scans the whole table and deserialises four JSON columns per row, which
is fine per dropdown click and absurd every 15 seconds on the Live tick. The TTL is short because
`FundamentalsWarmer` fills gaps on a 5-minute cooldown — a longer pin would keep showing an
Unclassified group for minutes after the sector had arrived.

**Exactly one group per holding, which is what makes a collapsed table readable.** Nothing is
dropped, so the groups' market values sum to the ungrouped total to the penny
(`groupValuesReconcileToTheUngroupedTotal`). A resolver that cannot place something must return a
group saying so — never null. That invariant is what forces the two shape decisions below.

**Which columns aggregate, and why the rest are blank.** Sum: Market Value, Portfolio %, Gain £,
Dividends £, Total Gain £. Blank: Quantity, Price, Ccy, Sources, Security — no meaning across
instruments (200,000 of gilt nominal plus 40 shares of Alphabet is not a number about anything).
**Gain % and Total Gain % are `Σgain ÷ Σcost`, never the mean of the members' percentages**: a £5
position up 300% beside a £50,000 one down 2% averages to +149%, and what the money did was −2%
(pinned by `gainPctIsCostWeightedNotTheMeanOfTheMembers`). `AggHolding` carries no cost field, so
cost is derived as `Σ(marketValue − gain)` — exact, because the ledger view builds
`gain = value − cost`. Cost accumulates **only alongside a known gain**, so a CASH row adds value
without diluting the group's return, and a group with no cost basis reports **null, not zero**:
"there is no cost basis here" and "broke even" are different statements and cash is the first.

**Annualised return is blank at group level and an approximation would be worse than the blank.**
The column is an IRR over each position's own dated flows; members hold for different periods over
different flow histories, so no weighting of their rates — by value, by cost or by period — means
anything. The right figure is a single money-weighted IRR over the *union* of the group's dated
flows, which is a different calculation over data the aggregated rows do not carry (it needs the
ledger rows). That is a separate backlog item; until it exists the cell carries a tooltip saying
why, because a plausible wrong rate in a column of right ones is the outcome worth avoiding.

**Sector: a fund spanning sectors gets its own group, not its largest one.** `SectorGroupKey` uses
the same `SectorClassifier` and `fundamentals_snapshot` cache as Allocation → Breakdown, so the two
cannot disagree about what a holding is — but the classifier's answer for a fund is a *split*, and
a row cannot be split without the totals ceasing to add up. Filing £212k of EQQQ (57.8% technology)
under Technology would report a concentration that is not held and would disagree with the
Technology row in the breakdown block, which is a genuine look-through partition. So multi-sector
funds group together and the note points at the panel that does decompose them; a fund whose
look-through lands wholly in one bucket (a sector ETF) is filed there like anything else.
**Cash is placed by the key, not left to the classifier** — it has no symbol Yahoo knows, so it
would land in the unclassified group and make the table's biggest "data gap" a balance that is not
one (`SectorBreakdownService` sidesteps the same trap by handling cash outside its classify loop).
Anything genuinely unplaceable lands in an explicit **Unclassified** group, flagged residual and
still counted in every total.

**Account: sorted, because `sources` order is trade history.** `AggHolding.sources()` is built from
a `LinkedHashSet` filled in trade order, so the same pair of accounts arrives as
`II SIPP, AJ Bell SIPP` on one position and `AJ Bell SIPP, II SIPP` on another according to which
broker saw the first trade — **the live book carries both orderings**. Keyed as-is, one set of
accounts would split into two groups a reader cannot tell apart, so `AccountGroupKey` sorts before
building the key and joins with `+`. A position held across accounts forms **its own** group rather
than being split between them: the ledger view aggregates a symbol across accounts into one row
(one quantity, one cost basis, one IRR episode walk), so there is no per-account half to file, and
the note says so.

**Grouping is done on the server, one re-sync per dropdown change.** That keeps `Σgain ÷ Σcost` in
a single implementation instead of a second copy in `dashboard.js` — the same reason `/orders/touch`
re-prices server-side rather than mirroring `erfc` into the browser. It is cheap: FX is TTL-cached
behind `CachingFxRateProvider` and the rest is local SQLite. An absent or unknown `groupBy` degrades
to the flat table rather than throwing. The `<select>` sits in the panel's form but **outside**
`#portfolio-cash-result`, so its value survives every htmx swap and the sync button re-submits it.

**Sorting became group-aware, and that is a change to shared code.** `attachSort` and
`reapplyActiveSort` now both delegate to one `sortBody(table, idx, type, asc)`; when the tbody
contains `tr.group-header` it sorts the *groups* by the header's own cell and each group's members
within their group, because sorting the rows as one flat list would strand every header away from
what it summarises. `groupBlocks` returns null for a tbody with no headers, which is what keeps
every other sortable table on the page behaving exactly as before. Blank header cells parse as null
and sort last, same as a blank member cell. It also returns any rows sitting **above the first
header** separately, and `sortBody` re-appends them ahead of the sorted blocks; they should never
exist (both tables lay themselves out header-then-member), but *discarding* them is what let a
stranded row stay stranded through every later sort — see the Live relayout below.

**Members render collapsed from the server** (`tr.group-member.collapsed`, CSS `display:none`)
rather than being hidden by script after paint, so the grouped table never flashes its full row set.
Both click handlers are delegated from `document.body` — the table is htmx-swapped on every
re-sync — and the toggle matches on the group **id**, not on "next siblings until the following
header", because sorting moves rows. The Expand-all/Collapse-all button hides itself when the table
is ungrouped.

**The Excel exports are deliberately ungrouped** — `POST /sync-from-cash/export` re-runs `sync()`
and `POST /live/export` takes a fresh `snapshot()`, both writing the flat sheet, where a reader has
pivot tables and a `Portfolio %` that is already a live formula. `Snapshot` keeps a 4-arg
constructor for exactly that (and for the tests), same idiom as `QuoteSummary`.

### Live grouping — what differs

**Every snapshot carries its sector groups whether anyone is grouping or not, and that is forced by
the transport.** `/live/stream` broadcasts **one** snapshot to every connected emitter, so there is
no per-client selection a query param could carry without giving each emitter its own state.
Computing it once for everybody is cheaper than that and keeps the weighted arithmetic on the
server; the browser only chooses whether to draw the header rows. So the Live dropdown posts
nothing — unlike the ledger's, whose every change is a re-sync — and its options are two static
`<option>`s rather than being generated from the enum. A second Live key is a second group set on
the snapshot plus an `<option>`. `sectorGroups` degrades to no groups on failure: a classification
blip must not cost the user their price table.

**`LiveGroup` is a separate record from `HoldingGroup`, not a shared one with null fields.** Sharing
would have meant a majority of nulls on both screens and no way to tell "this column has no
group-level value" from "this table does not have this column". `LiveGroup` also carries
`symbols` — membership rides on the group rather than a group id on every `Row`, which keeps `Row`
(hand-built by the Excel export and its tests) unchanged and gives the browser what it needs to
place each row under its header.

**Day Δ % recovers its own denominator.** A row's previous close in GBP is exactly
`positionGbp − dayChangeGbp`, because that is how `LivePricesService` built the change — so the
group figure is `Σ dayΔ ÷ Σ(position − dayΔ)` with no extra field and no second valuation path.
Same trick as the ledger recovering cost from `value − gain`. Grouping everything into one group
reproduces the totals row's own Day Δ %, which a test pins.

**The trailing windows are weighted by what each position was worth *then*** — recoverable from the
row as `then = positionGbp ÷ (1 + pct)`, so the group is `(Σnow − Σthen) ÷ Σthen`, the value-weighted
price change. It uses **today's quantities**, so a name bought mid-window is weighted as though held
throughout: this is "what today's holdings did over that window", not a realised return (that needs
dated flows — Attribution). The per-row figure has the same property, so the group is the honest
generalisation of the column it summarises rather than a different measure wearing its name; the
tooltip says so. A row missing a figure leaves that column's numerator **and** denominator, so
different columns of one header can rest on different subsets — two of the four gilts have no 6-month
history and the Fixed Income 6M cell is computed from the other two, which beats counting the
unknown ones as flat. A growth factor ≤ 0 (a price that went to zero) is skipped rather than allowed
to invert the group.

**Quote ts on a group header is the OLDEST member's quote, not the newest**, and it names the symbol
in its tooltip. A summary line is only as current as its stalest input: a group whose largest name
last printed an hour ago is an hour-old number however fresh the rest is, and the newest would hide
exactly the lagging feed worth seeing. This is the opposite of `Snapshot.latestPriceAt`, which
answers a different question ("has anything at all come in recently"). Live confirmed it works:
Funds — multi-sector read 13:03 (XWIS) against a 13:59 newest quote.

**Header rows are patched in place, like the member rows.** A frame arrives every 15s; rebuilding
would drop any flash animation mid-play, so group cells go through the same `setNumericCell`
change-detection under a `grp|<id>|` key prefix. Three consequences worth knowing:
- `applyLiveRows` collects existing rows with `tr[data-symbol]`, **not** `tr` — headers are rows of
  the same tbody, and keying them by an undefined symbol would collapse them onto one entry and then
  delete every one of them as unseen.
- **Expanded state lives in `liveState.expanded`, not in the DOM.** A frame re-orders rows without
  rebuilding them, and a user who opened a group does not expect it to shut on the next tick.
  Toggling touches the DOM directly rather than repainting, so an in-flight flash survives.
- **`layoutLiveGroups` re-appends header-then-members on every frame, sorted or not**, because it
  is the only thing that puts a member row under its header. It used to be skipped once the user
  had sorted a column, on the grounds that `sortBody` had already arranged the same blocks their
  way — true of blocks `sortBody` can *see*, but a header created **after** the sort is appended to
  the end of the tbody, below every member row. Sorting a column and then switching Group by →
  Sector did exactly that: all 13 headers landed under all 56 members. Collapsed members are
  invisible, so the table looked right until a group was expanded and its members appeared at the
  top of the table (2026-08-28) — and no later sort repaired it, because a row above the first
  header belongs to no block. Group and member order come from the server (value desc) while the
  table is unsorted and are **read back off the DOM** once it isn't, so a relayout preserves the
  user's ordering; a steady frame therefore computes the order it already has and returns false
  without touching the DOM, which is what keeps it from fighting the "respect their sort, don't
  re-order on every snapshot" rule above. When it *does* move rows (a repair, or a membership
  change) and a sort is active, `reapplyActiveSort` runs — the alternative is groups left in server
  order under a column header still claiming to be sorted. A row in no group at all is an invariant
  violation (`LiveGrouper` places everything, residual bucket included) but is kept and placed last
  rather than dropped: leaving it out would make the computed order permanently shorter than the
  tbody, so every frame would report a move and re-sort the table.

The tbody click handler branches on `dataset.groupHead` before `dataset.symbol`: a header toggles,
a member opens the shared price popup. CSS restates the header background at `#live-table` scope
because the market-state row tints are `#live-table`-scoped and would otherwise win — a header spans
several listings and has no single market state.

