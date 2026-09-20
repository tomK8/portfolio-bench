# Demo mode

Running the app in front of someone without showing real figures. Same jar, same code paths,
different data directories — the separation needs no code change because
`portfolio.input-dir`, `portfolio.db-dir` and `portfolio.output-dir` are already properties
threaded through every bean that touches disk.

```bash
python3 scripts/make-demo-data.py     # demo/input and demo/db
scripts/demo.sh                       # http://127.0.0.1:8081
scripts/demo-stop.sh
scripts/demo-reset.sh                 # all of the above, from scratch
```

`scripts/demo.sh` uses its own port, PID file and log, so it runs **beside** the real daemon —
`start.sh`'s single-instance guard is untouched.

## The generator profile is not in this repository

`make-demo-data.py` produces no figures of its own. It loads a generator profile from outside
the working tree — `~/.portfolio-bench-demo/profile.py` by default, or wherever
`PB_DEMO_PROFILE` points — and asks it for every value it writes. Without one, nothing builds.

That is deliberate, and it is the only rule in this file that matters: **how demo figures are
produced is not described here, in the code, in the commit history or in the UI.** Do not
document it, do not infer it in a comment, and do not add a fallback so the build "just works"
without a profile. The protection is thin by construction — this is obfuscation, not secrecy —
and the only thing keeping it worth anything is that nothing published states the method.

The profile interface is two functions:

```python
def magnitude(value): ...   # the demo figure for a real one
def describe():      ...    # a short label for logs, saying nothing
```

`magnitude` is called for sizes only. Prices, FX rates, dates and percentages never pass
through it, which is what keeps the demo internally consistent against live market data:
market value is still quantity × live price, and the ratios the app derives are ordinary
arithmetic on whatever it was handed.

## One script, two phases

**FILE PHASE → `demo/input`.** Holdings and orders. Holdings have no table at all —
`PortfolioGatherer` re-parses the broker exports on every sync — so a demo portfolio exists
only if these files exist. Orders need both halves: the table carries the closed legs of a
completed ladder round trip, long after they left the broker's export, while the live Open
orders tab is built purely from parsed files (`OpenOrdersService`). The two agree because the
same profile is applied on each side.

**DATABASE PHASE → `demo/db`.** Public market data copied verbatim — prices, intraday bars,
FX, dividends per share, earnings dates, fundamentals, ticker mappings, instrument aliases,
and the watchlist (tickers and thresholds, no sizes). None of it is anybody's private
information and it is expensive to re-fetch, which is what makes the charts, correlations and
the four backtests deep from the first boot with no network wait. The ledger, orders and
ladder/switch groups are rebuilt through the profile.

The ledger is built here rather than parsed from files because files cannot reproduce it: the
`TRANSFER` and `Equate` rows are created by endpoints and have no CSV path, and symbols
re-keyed through the Mappings tab do not survive a fresh parse.

`--files-only` and `--db-only` rebuild half of it, for when a fresh broker export lands or
upstream data has moved on.

### order_key must be rebuilt, or every rung orphans

`OpenOrder.identity()` prefers `broker|reference`, which carries no size and passes through
unchanged. Only the reference-less six-part composite embeds a quantity — Roth IRA orders,
which carry no broker reference. Those keys are regenerated, **and
`order_group_member.order_key` is remapped to match**, because that string is the join. Get it
wrong and the ladders render empty with no error.

## Rebuilds are total, and that is load-bearing

Both phases wipe and regenerate. There is no incremental path: everything under `demo/` is
either copied from public market data or written by the demo app, so a partial refresh could
leave the two disagreeing.

`demo/db` holds more than `portfolio.db`. The `KeyValueStore` `.txt` files live there, and so
do broker exports the demo app archived on an import. Several of those keys the app writes
itself — `held_symbols`, `order_symbols`, and after an Import cash, `cash_balance_II_*` — and
the builder deliberately never copies them from upstream, because they would carry a real
balance across. Left in place they outlive the data they describe, and `latestCashBalances`
**prefers an asserted `cash_balance_II_*` over any stored ledger row**, so a leftover would
quietly override a freshly built ledger. Wiping the directory is what makes a rebuild mean
what it says.

Because a rebuild deletes a database the demo may have open, the script refuses to run while
the demo is up and points at `demo-stop.sh`.

### Left empty on purpose

`portfolio_snapshots` (nothing reads it — `SnapshotRepository` has only `saveSnapshot`, and
`PortfolioReturnService` says so in a javadoc; it refills on the first demo sync) and
`trade_notes` (the journal).

## The banner

`templates/fragments/demo-banner.html`, carried by all three page templates. `reference.html`
and `glossary.html` are standalone with their own `<head>` — there is no layout dialect in this
project, so each needs the `demo` model attribute passed explicitly or the banner silently
vanishes on those two pages.

The banner is `position: fixed`, because htmx only ever swaps panel-sized targets and it has to
survive every tab switch. `html[data-demo]` on the root element pushes the body and the theme
toggle clear of it, so nothing else in 1800 lines of CSS needs to know the banner exists. It
carries its own `<style>` rather than duplicating the rules into three templates.

It says what the data is not, and nothing about where it came from. Keep it that way.

`--portfolio.demo=true` is passed on the **command line**, which outranks the optional
`~/.portfolio-bench.properties` that `application.properties` imports. Nothing in the home
config can point a demo at the real directories or switch the banner off. Mail is
force-disabled for the same reason — a demo must not be able to send a watchlist alert.

## demo/ is not committed

The generator is committed; its output is not, and neither is the profile that drives it. This
repository is public.
