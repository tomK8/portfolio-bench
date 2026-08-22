package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.Holding;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Positions held at an account with no downloadable export — an employee share plan, say —
 * entered by hand and stored as ordinary {@code cash_transactions} rows.
 *
 * <p><b>Why the ledger and not a table of its own.</b> The app has two position pipelines: the
 * holdings files feed the Files tab alone, while the cash ledger feeds everything else —
 * Allocation, Value over time, Returns, Period diff, Attribution, sector and style breakdowns,
 * Live, Concentration, Currency exposure, Watchlist gain. The ledger replay is entirely generic
 * (cash keyed on {@code account|currency}, quantity derived from the sign of {@code amount}), so a
 * hand-entered row reaches all of them with no consumer written for it. A separate
 * {@code manual_positions} table would be a second source of truth that every one of those
 * consumers would have to learn about, and a sale would then have to be recorded twice.
 *
 * <p><b>Each acquisition is written as two rows.</b> A buy on its own would drive the account's
 * cash to minus the cost everywhere the replay sums {@code amount} forward, so it is paired with an
 * {@link TransactionType#OPENING_BALANCE} row funding it. That type — rather than
 * {@code CONTRIBUTION} — is what keeps the Contributions chart honest: shares that vested from an
 * employer are not money paid in, and every other read filter is an allowlist, so the row is
 * excluded from them by construction. See {@link TransactionType#OPENING_BALANCE}.
 *
 * <p>Manual rows settle in the account's own currency and carry no FX leg: what is entered is what
 * was paid, in that currency, on that date.
 */
public class ManualPositionService {

    private static final Logger log = LoggerFactory.getLogger(ManualPositionService.class);

    /** Non-security symbol for the cash-only rows, matching the ledger's existing convention. */
    private static final String CASH_SYMBOL = "GBP";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CashTransactionRepository cashRepo;
    private final IntradayPriceRepository intradayRepo;
    private final PriceHistoryRepository priceRepo;
    private final YahooTickerMap tickers;

    public ManualPositionService(CashTransactionRepository cashRepo,
                                 IntradayPriceRepository intradayRepo,
                                 PriceHistoryRepository priceRepo,
                                 YahooTickerMap tickers) {
        this.cashRepo = cashRepo;
        this.intradayRepo = intradayRepo;
        this.priceRepo = priceRepo;
        this.tickers = tickers;
    }

    /** One open position at a manual account, priced from the stored feeds. */
    public record ManualPosition(String symbol, String broker, BigDecimal quantity,
                                 BigDecimal costGbp, String currency,
                                 BigDecimal price, BigDecimal marketValueGbp,
                                 BigDecimal gainGbp, BigDecimal gainPct, String priceSource) {
    }

    /** One stored row, for the edit table — {@code rowid} is what a delete targets. */
    public record ManualRow(long rowid, String date, String broker, String type, String symbol,
                            BigDecimal quantity, BigDecimal amount, String currency,
                            String description) {
    }

    public record ManualView(List<ManualPosition> positions, List<ManualRow> rows,
                             List<String> brokers, List<String> warnings) {
    }

    // ------------------------------------------------------------------ reads

    public ManualView view() {
        List<CashTransactionRepository.LedgerRow> stored = cashRepo.loadManualRows();
        List<ManualPosition> positions = positions(stored);
        List<ManualRow> rows = new ArrayList<>();
        for (CashTransactionRepository.LedgerRow r : stored) {
            CashTransaction t = r.tx();
            rows.add(new ManualRow(r.rowid(), t.transactionDate(), t.account().dbValue(),
                    t.type().name(), t.symbol(), BigDecimal.valueOf(t.quantity()),
                    BigDecimal.valueOf(t.amount()), t.currency(), t.description()));
        }
        List<String> brokers = Account.manualAccounts().stream().map(Account::dbValue).toList();
        List<String> warnings = new ArrayList<>();
        for (ManualPosition p : positions) {
            if (p.marketValueGbp() == null) {
                warnings.add(p.symbol() + " has no stored price yet, so it is shown without a "
                        + "value. Prices are fetched in the background — reload in a minute.");
            }
        }
        return new ManualView(positions, rows, brokers, warnings);
    }

    private List<ManualPosition> positions(List<CashTransactionRepository.LedgerRow> stored) {
        List<CashTransaction> txs = new ArrayList<>();
        Map<String, Set<String>> brokersBySymbol = new LinkedHashMap<>();
        for (CashTransactionRepository.LedgerRow r : stored) {
            CashTransaction t = r.tx();
            // The reconstructor reads TRANSACTION rows only; the funding rows are cash, not shares.
            if (t.type() != TransactionType.TRANSACTION) continue;
            txs.add(t);
            brokersBySymbol.computeIfAbsent(t.symbol().toUpperCase(), k -> new LinkedHashSet<>())
                    .add(t.account().dbValue());
        }
        List<CashLedgerReconstructor.Position> reconstructed =
                new CashLedgerReconstructor().reconstruct(txs);

        Map<String, String> tickerBySymbol = new LinkedHashMap<>();
        for (CashLedgerReconstructor.Position p : reconstructed) {
            tickerBySymbol.put(p.securityId().toUpperCase(), tickerFor(p.securityId()));
        }
        Map<String, IntradayPrice> intraday =
                intradayRepo.loadLatestIntradayPrices(tickerBySymbol.values());
        Map<String, PriceBar> daily =
                priceRepo.loadPricesAsOf(tickerBySymbol.values(), LocalDate.now());

        List<ManualPosition> out = new ArrayList<>();
        for (CashLedgerReconstructor.Position p : reconstructed) {
            String symbol = p.securityId().toUpperCase();
            String ticker = tickerBySymbol.get(symbol);
            BigDecimal price = null;
            String source = null;
            IntradayPrice ip = intraday.get(ticker);
            if (ip != null) {
                price = priceInGbp(symbol, BigDecimal.valueOf(ip.close()), ip.currency());
                source = "intraday";
            } else {
                PriceBar bar = daily.get(ticker);
                if (bar != null) {
                    price = priceInGbp(symbol, BigDecimal.valueOf(bar.close()), bar.currency());
                    source = "close " + bar.date();
                }
            }
            BigDecimal value = price == null ? null : marketValue(symbol, price, p.quantity());
            BigDecimal gain = value == null ? null : value.subtract(p.costGbp());
            BigDecimal gainPct = (gain == null || p.costGbp().signum() == 0)
                    ? null : gain.divide(p.costGbp(), 6, RoundingMode.HALF_UP);
            String broker = String.join(", ",
                    brokersBySymbol.getOrDefault(symbol, Set.of()));
            out.add(new ManualPosition(p.securityId(), broker, p.quantity(), p.costGbp(),
                    p.tradeCurrency(), price, value, gain, gainPct, source));
        }
        return out;
    }

    /**
     * Manual positions as {@link Holding}s, for the holdings-file pipeline.
     *
     * <p>Derived from the ledger rather than stored twice, so the Files tab and every ledger view
     * cannot disagree about a position neither of them has an export for. Feeding them in here is
     * what puts the symbol into {@code held_symbols} (and so into the fundamentals, earnings and
     * price-fetch universes) and what stops the cash reconciliation panel reporting a position
     * present on one side only as a permanent drift.
     */
    public List<Holding> holdings() {
        List<Holding> out = new ArrayList<>();
        for (ManualPosition p : positions(cashRepo.loadManualRows())) {
            Currency ccy;
            try {
                ccy = Currency.getInstance(p.currency());
            } catch (IllegalArgumentException e) {
                log.warn("Manual position {} has an unusable currency '{}' — skipped",
                        p.symbol(), p.currency());
                continue;
            }
            Holding.Builder b = Holding.builder(p.symbol(), p.quantity(), ccy, p.broker())
                    .costBasisGbp(p.costGbp());
            // Left null when nothing has priced the symbol yet: a zero would be summed into the
            // portfolio total as a real figure, whereas a null is reported as unpriced.
            if (p.marketValueGbp() != null) b.currentMarketValueGbp(p.marketValueGbp());
            out.add(b.build());
        }
        return out;
    }

    // ----------------------------------------------------------------- writes

    /**
     * Record an acquisition: the funding row plus the purchase itself, both dated {@code date}.
     *
     * @param costGbp total consideration, not a per-share price — it is what left the account
     */
    public void buy(Account account, String symbol, BigDecimal quantity, BigDecimal costGbp,
                    LocalDate date, String note) {
        String sym = requireSymbol(symbol);
        requirePositive(quantity, "Quantity");
        if (costGbp == null || costGbp.signum() < 0) {
            throw new IllegalArgumentException("Cost must be zero or more");
        }
        requireDate(date);
        String reason = note == null || note.isBlank()
                ? "Manual entry: " + sym : note.trim();
        cashRepo.saveManual(account, List.of(
                row(account, date, TransactionType.OPENING_BALANCE, CASH_SYMBOL,
                        BigDecimal.ZERO, costGbp, "Opening balance for " + sym),
                row(account, date, TransactionType.TRANSACTION, sym,
                        quantity, costGbp.negate(), reason)));
        log.info("Manual buy: {} {} at {} for {}", quantity, sym, account.dbValue(), costGbp);
    }

    /**
     * Record a disposal. Proceeds stay as cash in the account — that is where they actually are;
     * use {@link #withdraw} once the money leaves the portfolio.
     */
    public void sell(Account account, String symbol, BigDecimal quantity, BigDecimal proceedsGbp,
                     LocalDate date, String note) {
        String sym = requireSymbol(symbol);
        requirePositive(quantity, "Quantity");
        if (proceedsGbp == null || proceedsGbp.signum() < 0) {
            throw new IllegalArgumentException("Proceeds must be zero or more");
        }
        requireDate(date);
        String reason = note == null || note.isBlank() ? "Manual sale: " + sym : note.trim();
        cashRepo.saveManual(account, List.of(
                row(account, date, TransactionType.TRANSACTION, sym,
                        quantity, proceedsGbp, reason)));
        log.info("Manual sell: {} {} at {} for {}", quantity, sym, account.dbValue(), proceedsGbp);
    }

    /**
     * Record cash leaving the account for good — sale proceeds paid out to a bank, say.
     *
     * <p>Written as a negative {@code OPENING_BALANCE}, the mirror of the funding row: the
     * portfolio's value steps down for a reason that is not the market, so the time-weighted
     * return must see it as an external flow rather than as a loss.
     */
    public void withdraw(Account account, BigDecimal amountGbp, LocalDate date, String note) {
        requirePositive(amountGbp, "Amount");
        requireDate(date);
        String reason = note == null || note.isBlank() ? "Withdrawal" : note.trim();
        cashRepo.saveManual(account, List.of(
                row(account, date, TransactionType.OPENING_BALANCE, CASH_SYMBOL,
                        BigDecimal.ZERO, amountGbp.negate(), reason)));
        log.info("Manual withdrawal: {} from {}", amountGbp, account.dbValue());
    }

    /** Remove one mis-typed row. */
    public boolean delete(Account account, long rowid) {
        return cashRepo.deleteManualRow(account, rowid);
    }

    /** The symbols currently held at manual accounts — what a caller should warm price feeds for. */
    public Set<String> heldSymbols() {
        Set<String> out = new LinkedHashSet<>();
        for (ManualPosition p : positions(cashRepo.loadManualRows())) out.add(p.symbol());
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private CashTransaction row(Account account, LocalDate date, TransactionType type,
                                String symbol, BigDecimal quantity, BigDecimal amount,
                                String description) {
        double amt = amount.doubleValue();
        // Manual rows settle in the reporting currency, so amount and amountGbp agree and the FX
        // leg is the identity. The balances are placeholders: saveManual recomputes the whole
        // chain, because a hand-entered row is nearly always back-dated.
        return new CashTransaction(date.toString(), account, type, symbol,
                quantity.doubleValue(), amt, "GBP", 1.0, amt, 0.0, 0.0, description);
    }

    private String tickerFor(String symbol) {
        return Instruments.isBond(symbol) ? symbol : tickers.tickerFor(symbol);
    }

    /** A stored quote in the symbol's own terms: pence become pounds, everything else stands. */
    private static BigDecimal priceInGbp(String symbol, BigDecimal price, String currency) {
        return "GBp".equals(currency) ? price.movePointLeft(2) : price;
    }

    private static BigDecimal marketValue(String symbol, BigDecimal price, BigDecimal qty) {
        BigDecimal v = price.multiply(qty);
        // Gilt prices are clean prices per £100 nominal, the same convention as everywhere else.
        return Instruments.isBond(symbol) ? v.divide(HUNDRED, 10, RoundingMode.HALF_UP) : v;
    }

    private static String requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Symbol required");
        String s = symbol.trim().toUpperCase();
        if (s.equals(CASH_SYMBOL) || s.equals("CASH")) {
            throw new IllegalArgumentException("'" + s + "' is the ledger's cash marker, not a symbol");
        }
        return s;
    }

    private static void requirePositive(BigDecimal v, String label) {
        if (v == null || v.signum() <= 0) {
            throw new IllegalArgumentException(label + " must be greater than zero");
        }
    }

    private static void requireDate(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Date required");
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Date is in the future: " + date);
        }
    }
}
