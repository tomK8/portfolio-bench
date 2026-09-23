# Share-class switches

The third group type on the Open orders tab, beside ladders. `SwitchService` +
`persistence/OrderGroupRepository` (shared with ladders) + `domain/Switches` (pure arithmetic) +
`domain/ShareClasses` + `web/SwitchesController`.

**What it is for.** EQQQ is the sterling line of a fund and EQQU is its dollar line: same fund, same
ISIN, same holdings, and nothing but the trading currency, the exchange line and the account differ.
Moving the position from one to the other changes *nothing* about what is owned, and every shape the
app already had says otherwise.

- **A ladder cannot hold it.** A ladder is rungs on one security laid out on one price spine, and
  £530 and $716 do not share a price axis. Drawn on one, the pair shows a 35% gap where there is no
  gap at all — because the ratio between the two lines **is** GBP/USD.
- **It is not a rotation.** A rotation moves money from one investment into another and the exposure
  changes. Here it does not, which is why the declaration also folds the two listings into one
  holding everywhere exposure is measured (below), and why a move between two *different* companies
  is deliberately a different shape that this does not try to cover.

## Declared, never detected

A sell of EQQQ beside a buy of EQQU is two orders until the user says otherwise. Nothing infers
same-fund-ness from tickers, names, prices or dates: no feed here publishes an ISIN, and a guess
would quietly stop counting a real rotation as a change of exposure — the one error that would make
every concentration and currency figure wrong without looking wrong. `twoOrdersThatLookLikeASwitchAreNotOne`
pins it.

The same rule the ladder has about intents holds here and matters more: **an intended leg is never
auto-promoted**. On a switch the buy leg is normally unplaceable for days, so an intent is the
*ordinary* state rather than the exception, and a feed match that was sometimes right would attach
one decision's reasoning to a different order. `markPlaced` is the only path across.

## Which side an order is on

**By side; the listing decides only what it is measured against.** A sell is on the side being sold
and a buy on the side being bought, and **either side may sit on either listing**. Limits go out on
both lines at once in the ordinary way of working one of these — sells above on each, buys below on
each — and which line a fill lands on is whichever was dearer on the day. The old rule (first symbol
sells, second symbol buys) made that unrecordable, which is the case the panel is for.

The declared pair therefore no longer places legs. It does two other jobs: it is the **check** that
an assigned order is one of the two listings, and its order names which listing the holding is
counted under everywhere exposure is measured (below). An order on **neither** line is a
mis-assignment — kept, because the user assigned it, named in the panel, counted in nothing. So is
one whose export never said which way it goes. That is all `offShape` means now.

Because the side no longer names a listing, an intent **stores** one (`order_group_intent.symbol`,
additive and nullable). A row written before that carries none and is read under the rule it was
written under — sell is the line being left, buy the line being entered — because re-reading it any
other way would move an existing leg onto the other listing and price it against the wrong market.
`anIntendedLegWithNoListingFallsBackToTheRuleItWasRecordedUnder` pins it.

## One yardstick

Two prices in two currencies say nothing side by side, so every figure converts to **GBP**, the
book's own currency, before comparing anything. The panel prints both: the native price, which is
what the broker screen shows and what the user typed, and the GBP one, which is the only pair of
numbers that can be read against each other.

**A side that spans both listings has no native average**, and none is shown — £584.80 and $682.35
have no mean, and a blended figure would print as money and be none. The GBP column still answers,
which is the whole reason the two columns exist. The implied cross rate goes with it: a rate implied
by *your two prices* needs one currency on each side. The leg tables are likewise **ordered on the
yardstick**, not on the raw figure; £584.80 is *below* $682.35 once the rate is divided out, and
sorting the printed numbers would stack every sterling row above every dollar one and call that a
price order.

Each leg converts on its own currency and the **GBP** figures are then averaged — not the other way
round, which would need a currency the side may not have.

Resting-and-intended legs and filled legs are averaged **separately**. Mixing a limit that has not
traded with a price that has would be an average of nothing; the two answer different questions
("what am I about to do" and "what did I do") and both are worth a row.

Averages are weighted by quantity. Three legs of 10, 10 and 300 units are not three equal opinions
about the price.

## The split, and why it is exact

The gap between the two prices mixes the fund and the exchange rate, and the user has to be able to
tell them apart. With `S` the average on the line being sold, `B` on the line being bought,
`Pout`/`Pin` the two lines' current market prices, and `gbp` meaning converted at today's rate:

```
1 + cost = (1 + inVsMarket) / (1 + outVsMarket) × (1 + basis)

  cost         = B_gbp/S_gbp − 1     what the switch costs per unit
  inVsMarket   = B/Pin   − 1         where the buy limit sits against its own market
  outVsMarket  = S/Pout  − 1         where the sell limit sits against its own market
  basis        = Pin_gbp/Pout_gbp − 1   the two lines against the exchange rate
```

where `Pout`/`Pin` are each **side's own** market price: the quantity-weighted blend, in GBP, of the
listings actually on that side. On a one-way switch a side sits on one line, the blend *is* that
line, and every figure is what it always was to the last decimal place
(`aOneWaySwitchSplitsOnTheLinesExactlyAsItAlwaysDid`).

That is an **identity**, not an approximation — substitute `B = (1+inVsMarket)·Pin` and the same for
`S` and the terms cancel, for *any* `Pout`/`Pin` used in both terms, which is exactly why the blend
is safe. `theThreeTermsMultiplyToTheTotalExactly` checks it numerically, and
`theThreeTermsStillMultiplyExactlyWhenASideSpansBothListings` checks it with both lines on one side.
An approximation here would be the panel quietly attributing part of a gap the user *chose* to the
exchange rate, which is the one thing it exists to separate.

A leg on a listing that has never priced takes its **whole side's** market blend with it rather than
being dropped from the blend alone: a blend over a different set of legs than the average it divides
into would stop being an identity. The average still prints; only the comparison to market goes.

Its three factors are exactly the three things that have to be told apart, and the third one is the
FX term. On a genuine share-class pair it is ~0: the pair's price ratio *is* the rate, so once both
lines are in GBP they are the same number. **Anything the panel reports as a gap is therefore a gap
the user chose** — where they put their two limits — and the headline says so in the other reading
as well: *your two prices imply 1.3509 USD per GBP; the market is 1.3376*, and
`impliedCross / marketCross − 1` is `cost` exactly.

**The basis is the only live evidence the pair is the pair.** Nothing here stores an ISIN and nothing
looks one up; two listings that move together at today's rate are the check, and it is self-checking
rather than maintained. The **block** that prints it is always the two declared *lines* against the
rate, untouched by any leg — which is what keeps it evidence. The split's third term is the two
*sides'* markets, identical to it whenever each side sits on one line; the two are carried
separately so the three printed factors multiply to the headline exactly in both cases. Past half a percent the panel says the two are not tracking and names the
three things it can be: a stale quote, a pair that is not the pair, or lines that are not one unit
for one unit — which every figure counted in units assumes.

Both listings are priced whether or not either carries an order (`OpenOrdersService.linePrices`,
published into the fetch universe), because a line the user has only just declared has nothing else
keeping its feed warm. A London quote arrives in pence and is folded to pounds **in
`Switches.LinePrice`'s constructor** — once, where the state cannot be represented otherwise. Left in
pence it would convert correctly and still *display* as £53,860 beside its own £538 limit, which
reads as a broken pair rather than a unit mismatch.

## The sequencing

The part no broker will do. Proceeds arrive in one account in one currency, the purchase needs a
different account in a different one, nothing chains the two orders, and moving money between
brokers takes days. The buy leg is therefore usually blocked on plumbing rather than on a decision,
and the panel's job is to say which piece. Five steps, and the first that is not done is named as the
blocker:

1. **Sell the old line** — how many of the units this switch covers have traded.
2. **Proceeds settle as cash** — **reported, never judged.** This tool reads order exports and a cash
   ledger; neither says "that trade settled". What it can do is print the ledger's own balance for
   the account the proceeds land in, *with the date that ledger was last imported*, and leave the
   conclusion to the reader. The step never blocks anything.
3. **Move the cash** — `NOT_NEEDED` when both legs are in one account, and the step still prints
   rather than disappearing: that the two share a wrapper is the fact worth reading.
4. **Convert the currency** — likewise, with the amount needed and today's cross rate.
5. **Buy the new line** — `SHORT` when the ledger says the buying account does not hold enough of the
   buying currency, with the shortfall and the ledger's date. A claim that the user is short has to
   be checkable, which is why `CashTransactionRepository.latestTransactionDate` exists.

Cash is broken out **per account and currency and never netted across either** — the ladder's rule,
and on a switch it is the whole difficulty: £53,000 raised in the SIPP and $53,700 needed in the Roth
is not cash-neutral, it is two brokers and a conversion apart.

## How far through it is

**Three states, not two.** Units still in the old line, units in the new one, and — the one worth
having — units **sold and not yet bought back**. Those are cash: they are in neither line, they own
nothing, and the fund can move without them. That is the exposure a half-finished switch actually
carries and it is invisible in any view that counts positions.

`declared` — what the switch covers — is **derived from the legs**, the sell side's filled and
unfilled quantities together, rather than typed in at creation. A number entered once goes stale and
can only ever be wrong; extend the switch by recording another leg and it extends. An intended leg
counts, which is the whole reason intents exist.

**Complete means the holding has arrived**, not that the order screen is empty: everything declared
sold *and* the same number of units bought back. A sell that filled and a buy that never did leaves
nothing resting, and calling that complete would hide precisely the state the panel exists for
(`nothingRestingIsNotTheSameAsComplete`).

**Bought back more than went out** is reachable only on a switch worked from both ends, and it is not
a switch state at all — the holding grew rather than moved, and the three shares stop summing to the
whole. Named, never drawn: clamping it would paint a bar that says the move is finished
(`buyingBackMoreThanWasSoldIsNamedRatherThanDrawn`). The legend likewise only claims "still in EQQQ"
and "in EQQU" while the switch *is* one-way; worked from both ends it says "still to sell" and
"bought back", which is what those units are doing. Same three figures either way — `Switch.oneWay()`
decides what the label is allowed to claim and nothing else.

## One holding, everywhere exposure is measured

Declaring a switch is the same statement as declaring that the two listings are one fund, so
`SwitchService.shareClasses()` publishes the pair and `domain/ShareClasses` folds it.

Counted as two positions, a half-finished switch reads as two holdings of half the size: a
concentration figure saying the book is more diversified than it is (the one direction that must
never be wrong), an allocation chart with one line falling as another rises while nothing owned has
changed, and — worst — a currency mix swinging from GBP to USD when a fund's sterling line and its
dollar line hold exactly the same assets.

- **Applied in three places**, all of them "value each listing on its own quote, *then* merge the GBP
  results": `AllocationService.snapshot` (which the composition chart, sector, style, earnings sizes
  and both the concentration and currency *trends* all read), `ConcentrationService`'s headline
  snapshot, and `CurrencyExposureService`'s. Folding after valuation means nothing assumes one unit
  of the sterling line equals one unit of the dollar line, and the totals are identical either way —
  the fold moves value between rows, never into or out of the book.
- **The canonical is the line being left.** It is the listing with the price history, the ticker
  mapping and the sector/style classification already attached, so the folded row keeps working the
  moment the pair is declared rather than going unclassified; and the historical series stays
  unbroken, which is the point — the holding did not change, only where it is held. Chains resolve to
  a fixed point (EQQQ→EQQU then EQQU→XYZ lands all three on EQQQ); a cycle is reported and the pair
  left separate rather than resolved by picking a winner.
- **Quantities are not folded.** `AllocationService`'s `quantityBySymbol` stays keyed by listing: it
  is a unit count, and adding two listings' units would assert they are interchangeable. Its one
  consumer is the fixed-income yield, which needs a gilt's nominal, and a gilt has no second listing.
- **The pair outlives the move**, and only deleting the switch undoes it — which the delete
  confirmation says, because un-declaring it the moment the last unit lands would put the break back
  into every chart that had just stopped having one.
- **The exposure views depend on the question, not the answer.** `ShareClassRegistry` is a one-method
  interface bound to the switch service in `BeanConfiguration`; allocation, concentration and
  currency have no business knowing the Open orders tab exists.

## Storage, and what is shared with ladders

The same three tables. `order_group` gained one nullable `symbol_to` column and
`order_group_intent` one nullable `symbol` — both additive, so an existing database gets
`ALTER TABLE ADD COLUMN` on open and no rebuild. Every `order_group` row predating the first was a
ladder, whose second listing is null by definition; every intent predating the second is read under
the rule it was written under, which is what makes the null meaningful rather than missing. A ladder
still writes null there — its rungs are the group's one security, and storing it per rung would be a
second answer to a question the group already settles. `GroupType.SHARE_CLASS_SWITCH` is the
discriminator the column was put there for, and a group whose type this build does not know is still
dropped with a warning rather than read as one of the types that exist.

- **One order, one group — across types too.** `order_key` is the member table's primary key, so
  assigning an order to a switch moves it off whatever ladder it was on. It is one order and it was
  placed for one reason.
- **`GroupRungs` holds what both types share**: building a `Rung` from an order key, building the
  synthetic order an intent is priced through, reading a closed order's state, and the unassigned
  list every picker starts from. Two copies would be two answers eventually — one panel calling a
  part-filled order FILLED and the other not — and the panels sit inches apart. What is *not* shared
  is any rule about which orders belong in which group; there is no common path that could start
  inferring one.
- **The two fixed horizons are shared too** (`P(10)`/`P(30)`), for the reason they are fixed at all:
  a group's rungs are one decision read against each other, and on a switch the two sides have to be
  comparable. See [open-orders.md](open-orders.md).
- **A leg is never pruned**, by the same route a rung is not: `rungOrderKeys()` returns every grouped
  order regardless of type, and a switch's sell leg has to survive until the buy leg that completes
  it arrives — which can be months.

## The panel

- **Quiet when there are none**: one line and a create form, like the Ladders block, and it sits
  **outside** the "no order files found" guard. A switch is nearly always half intended, so hiding it
  whenever no export happens to be in `~/Downloads` would hide it exactly when it is doing its job.
- **Two tables, one per direction, and each row names its own listing.** The tables are the two
  *sides*, not the two lines, so both listings appear in both — ordered by GBP price, the only axis
  they share. The card's premise line says so, because a reader who skims must not read the tables
  as the lines.
- **Rendered from the same `OrdersReport` the table is**, so a leg's numbers are the row's numbers.
  Mutations re-render the block alone (`hx-target="#switches-result"`); `markPlaced` targets
  `#orders-result` for the ladder's reason — linking can move the intent's note onto the order, and
  that note is a cell of the table below.
- **`Download Excel` writes every switch** to `Share-Class Switches-{date}.xlsx`
  (`POST /switches/export`) — five sheets, one per block of the card: the switch with its progress
  and basis check, the GBP yardstick, the legs, the sequence, and cash by account. Re-derived on the
  press, and outside the order-files guard on the block's own argument — a switch whose buy leg is
  still an intent is exactly the state worth having a record of. See
  [excel-exports.md](excel-exports.md).
- **The premise is stated on every card** ("same fund, two listings — your exposure does not
  change"), because a reader who skims must not have to infer it.
- **An intended leg's price and quantity are editable in place** (`POST /switches/intents/update`),
  the ladder's cells and the ladder's rule: an intended leg is the user's own record, a leg that came
  from a broker's export is fixed. The shared machinery is in
  [open-orders.md](open-orders.md#editable-intended-rungs-the-price-and-quantity-cells).

  It is worth more here than on a ladder, twice over. The buy leg is an intent for *days* by
  construction — the cash is in the wrong account and the wrong currency — so it is both the leg most
  likely to need moving as the market does and the one an edit can actually reach. And the two limits
  are chosen against *each other* rather than against one market, so "where do these two have to sit"
  has two answers that only make sense together; moving one and reading what it does to the cost, the
  implied cross rate and the shortfall is the only way to settle them. Everything on the card is
  derived from the legs rather than stored, so all of it moves at once: the GBP yardstick, all three
  terms of the split, the progress in units, and which step of the sequence is blocking.

  What an edit does **not** move is the basis check. `basis` is the two *lines'* market prices
  against the exchange rate, not the limits, so it is untouched — which is right, because it is the
  panel's only live evidence that the pair is the pair, and evidence you can edit is not evidence.
  What it does move is the headline's other reading ("your two prices imply 1.3509 USD per GBP"), and
  that is exactly what it is for: an implied rate is a statement about where you have put your two
  limits.
- **An intended leg's side and listing are never touched by an edit.** Only price and quantity are
  editable, so a figure being tuned cannot move the leg onto the other line or turn a sell into a
  buy — either would re-price it against a different market while looking like a re-price against
  the same one.
- **Two adjacent columns measure in opposite directions**, deliberately and labelled: the yardstick's
  *Limit vs market* is where your price sits against the market, which is the term the split needs;
  the leg tables carry the orders table's own *vs mkt*, how far the market sits from the limit. Each
  tooltip names the other.
