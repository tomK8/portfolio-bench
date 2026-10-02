# Backup

An off-machine copy of the data that could not be reconstructed if this laptop were lost.
Written to iCloud Drive's Documents folder, monthly and unprompted, plus a **Back up now**
button for the moment before doing something irreversible.

```
~/Library/Mobile Documents/com~apple~CloudDocs/Documents/portfolio-backup-YYYY-MM-DD.zip
```

Override the location with `--portfolio.backup-dir=…`. About 40 KB — small enough to mail to
yourself without thinking about it.

## What goes in, and why so little

The database is ~280 MB and ~1.37 million rows. **All but about a thousand of those rows are
re-fetchable** from Yahoo, Frankfurter or SEC: prices, intraday bars, FX, dividends per share,
earnings dates, fundamentals. Copying them would turn a 40 KB archive that fits in an email
into a 200 MB one that does not, and buy nothing but an afternoon of backfill.

What cannot be re-fetched is the eleven tables in `BackupService.TABLES`:

| | Why it is irreplaceable |
|---|---|
| `cash_transactions` | The ledger, back to 2016-01-05. Brokers cap export windows, and it carries corrections — Mappings re-keying, recorded transfers, manual positions — that no re-download reproduces |
| `trade_notes` | Hand-written, keyed to ledger rowids |
| `order_history` | Includes closed orders the broker no longer lists — the filled legs of a completed ladder round trip live only here, along with each order's note and any verdict recorded about one the broker never accounted for. A hand-typed verdict is unrecoverable: the export it contradicts says nothing |
| `order_group{,_member,_intent}` | Ladders and switches encode an *intention*. Nothing observable implies them |
| `instrument_aliases`, `ticker_mappings` | Hand-curated. A rename re-keys ledger rows and is not undoable — see [instrument-mappings.md](instrument-mappings.md) |
| `watchlist` | Symbols and their thresholds |
| `dividend_events`, `portfolio_snapshots` | Cheap to carry, awkward to recreate for past dates |

The list is fixed rather than "everything except the big tables", deliberately: a new table
should have to be classified by hand, because getting it wrong in the silent direction means
discovering the omission during a restore.

**The `KeyValueStore` files go in too.** They are one `.txt` per key beside `portfolio.db`, not
a table, so a SQL-only dump misses them entirely — including
`roth_balance_brought_forward`, a hand-determined USD seed with nothing to derive it from. Lose
it and Roth's entire cash chain is wrong.

## The archive is plain SQL on purpose

Inside are `portfolio-essentials.sql` (schema + `INSERT`s against the real DDL), `keyvalue/*.txt`
and a `README.txt` carrying the restore steps and a row-count manifest.

Restoring is `sqlite3 portfolio.db < portfolio-essentials.sql` — no tooling of ours involved.
That matters more than convenience: a backup that can only be read by the program that wrote it
fails exactly when the program is what broke. ZIP because it is the one container both macOS
and the iOS Files app open by double-click, and `java.util.zip` is in the JDK.

The archive is written to a temp file and moved into place only once complete, so an interrupted
run cannot leave a truncated file where a good one used to be.

## Monthly, by elapsed time rather than a calendar date

`PriceFetchScheduler.backupIfDue` checks every six hours, and at startup, whether the newest
archive is more than 30 days old. A `@Scheduled` cron on the 1st would assume the machine is
awake and the app running at that instant; this is a laptop tool that is shut more often than
not, so a date-triggered job would skip whole months without saying so. Asking "how old is the
newest archive?" means the backup happens within hours of the laptop next being used, whatever
day that is, and re-running it is harmless.

There is no state to keep: the answer is read from the filenames in the backup directory.

`BackupService.run()` **throws** rather than degrading, unlike the price jobs — a backup that
quietly did nothing is worse than none, because it is indistinguishable from one that worked
right up until the day it was needed. The scheduler catches and logs at WARN, since nothing
later repairs it.

## Rotation keeps three

The new archive plus two older ones. Rotation matches `portfolio-backup-YYYY-MM-DD.zip` and
nothing else, so it can only ever delete files this class wrote — the backup folder is shared
with whatever else lives in iCloud Documents.
