# Calendar reminders

The Reminders tab (Tools). Two kinds of reminder on one screen, one delivery path, one email a day.
`GET /reminders` → JSON, rendered client-side; the edits are one `POST` each.

Unlike the [watchlist](watchlist.md), **nothing here is evaluated**. A watchlist trigger is a
condition tested against live prices and fires when the market says so; a reminder fires when the
**calendar** says so, and the `rule` and `action` are free text nothing parses. That division is the
whole point of having both: "tell me if it drops 8%" belongs to the watchlist, "on the 14th, decide
whether the thesis still holds" belongs here. A reminder that quietly re-interpreted its own free
text would be neither.

## The two kinds are deliberately asymmetric

|  | Hand-entered | Earnings |
|---|---|---|
| Where it comes from | typed by the user | derived from three live sets + two earnings feeds |
| What is stored | every field (`reminder`) | **only the mute** (`reminder_mute`) |
| When it starts | its own date | 7 days before the print |
| When it stops | never, until switched off or deleted | the day after the print, by itself |
| Handle for the toggle | the row id | the symbol |

**A hand-entered reminder is open-ended, and that is the feature.** It starts on its date and repeats
every day after it, for as long as it takes, because only the user knows when the thing is dealt
with — an end-date the app chose would be the app closing the matter on their behalf. `daysUntil`
goes negative and the row reads *sending daily, for 6 days*; being overdue is not a fault state.

**An earnings reminder is a window that closes by itself.** Both ends are read off the earnings date,
so when the company moves the date the window moves with it. `Reminders.earningsDue` requires
`today ≤ earnings ≤ today + 7`, and the **lower bound is the load-bearing half**: the fundamentals
snapshot leaves the last date it knows in place until Yahoo learns the next one (see
[earnings.md](earnings.md)), so without it a stale date would mail a warning about a print that
already happened, every day, until the feed caught up.

## Earnings reminders are derived, never stored

An earnings reminder is the join of two things that both move without the user — which symbols they
hold, watch or have an order resting on, and when those companies next report. A stored row per
reminder would be a hand-maintained copy of both, and **a maintained list can only ever be wrong**.
So the rows are recomputed on every read and the only thing persisted is the off switch.

**The universe is the union of three KV sets the app already publishes** — `held_symbols` (the last
/sync), `watchlist_symbols`, `order_symbols` (the Open orders tab) — which is exactly "in the
portfolio, on a watchlist, or under an order". No fourth source and no hand-kept list: a name reaches
this screen because it is already somewhere that matters, and leaves when it stops being there.

**The date comes from `EarningsCalendarService.nextEarnings`, which owns the precedence.** Same order
of strength as the Earnings tab's holdings section, and deliberately the same code rather than a
second copy: the market calendar first (it carries the session and the consensus estimate), the
fundamentals snapshot for everything the US-only calendar cannot see. It **reads only what is already
cached** — no fetch, no window walk. This is called from a job that runs every half hour, and a
hundred symbols behind a live feed would be a hundred HTTP calls to answer a question that changes
once a quarter. The calendar cache is kept current by `warmForward`, the snapshot by the four-hourly
`FundamentalsFetchJob`.

**`nextEarnings` returns three outcomes, not two.** `Lookup.dated` is a date; `Lookup.undated` is a
company neither source can date, which the tab names in a strip — the app-wide "excluded, and said
so" bargain, because a screen that simply omitted them would look identical to one where every
position is covered. Funds and bonds are in **neither** list: they report no earnings, so their
silence is the correct answer, and calling it a gap would bury the real gaps under every gilt in the
ledger. Only the method that knows which silence is which can draw that line, which is why the caller
is handed both lists instead of reading `undated` off the map's absences. (That was a real bug,
caught by `ReminderServiceTest.bondsNeverProduceAnEarningsRowOrAGapReport` before it shipped.)

**A mute is a standing instruction about a symbol, not about one print.** "Stop reminding me about
BARC earnings" survives the print it was given before — keying it to an occurrence would re-arm it
every ninety days behind the user's back. Muted symbols stay **listed**, struck through, so a
standing instruction is visible and one click to lift, rather than a row that silently vanished.

## Delivery: daily by dedup, not by schedule

`ReminderJob` reads the very same `ReminderReport` the tab renders, so screen and email can never
disagree about what is due — the same reason `WatchlistAlertJob` reads `WatchlistService`.

**The cadence is latency, not volume.** The job is called every 30 minutes and each item is keyed
`R|<id>|yyyy-MM-dd` / `E|<SYMBOL>|yyyy-MM-dd` in the `reminders_sent` KV set, so a tick with nothing
new does nothing at all. A `cron` at 08:00 would assume the laptop is awake and the app running at
08:00; this one usually is not, and a reminder that silently skips the day it was for is worse than
no reminder. Checking often and de-duplicating by day means it lands within half an hour of the
machine next being opened — the same due-by-elapsed-time reasoning as the [backup](backup.md), for
the same reason.

- **The sent-set is persisted only after a successful send.** A mail failure retries next tick rather
  than marking the day done and dropping the message — for a tool whose purpose is not to forget,
  that is the worst available failure.
- **An item that comes due later the same day is still sent.** The key is per item, so a second
  email carries only what the first did not.
- **The earnings key deliberately excludes the earnings date.** A print slipping Thursday → Friday
  while the window is open is the same warning about the same print; keying on the date would mail
  the move as though it were news.
- **Yesterday's keys are pruned every run**, including on a quiet day, or the file grows a line per
  reminder per day forever.
- **Nothing configured means nothing recorded.** With SMTP off the job returns before writing any
  key, so turning delivery on does not start by skipping the day it was turned on.

**The screen says whether anything is actually being delivered.** With no SMTP configured every row
is a note on a page that will never mail anything, and a due row that silently sends nothing is the
one failure this feature cannot afford — so `deliveryEnabled` rides on the payload and the tab prints
it at the top, not in a log. It reuses the watchlist's `portfolio.alert.mail.*` settings; there is no
second mail channel.

`POST /reminders/send` runs the same job on demand, dedup and all, so pressing it twice in a day
correctly sends nothing the second time.

## Storage

`reminder` and `reminder_mute`, both in [BackupService.TABLES](backup.md) — a typed date, rule and
action exist nowhere else, and a mute is a standing instruction that would be silently lost.

- Columns are `rule_text` / `action_text`: **`ACTION` is a SQLite keyword**, and a quoted identifier
  that only works while it stays quoted is a trap for the next query.
- Blank free text is stored as `null`. An empty rule is not a rule that says nothing, and clearing
  the action must actually clear it rather than leave a blank `Action:` line in the email.
- **The symbol is not editable.** A reminder about a different position is a different reminder, and
  re-pointing one would carry its history — today's already-sent key included — onto a name it was
  never about. Date, rule and action are the three the user asked to be updatable, and they are
  exactly the three that are.
- A blank date or a blank rule is **refused, not defaulted**. A date defaulted to today would start
  mailing the instant it was saved; a blank rule is a reminder that says nothing when it fires. Both
  come back as `ok:false` with the reason so the form shows it inline — mistyping a date is an
  ordinary thing to do and does not deserve a full-page error.
