package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.application.AllocationService.AllocationTimeline;
import com.portfolio.domain.ShareClasses;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class AllocationServiceTest {

    /** Frankfurter convention: foreign per 1 GBP. Flat 1.25 USD/GBP, no EUR. */
    private static final HistoricalFxRateProvider FLAT_USD_FX = (ccy, from, to) -> {
        TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if ("USD".equals(ccy)) out.put(from, new BigDecimal("1.25"));
        return out;
    };

    @TempDir
    Path dbDir;

    private AllocationService service() {
        return service(ShareClassRegistry.NONE);
    }

    private AllocationService service(ShareClassRegistry shareClasses) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        return new AllocationService(
                new CashTransactionRepository(cf, kv),
                new PriceHistoryRepository(cf),
                FLAT_USD_FX,
                new YahooTickerMap(),
                kv,
                shareClasses);
    }

    private CashTransactionRepository repo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private PriceHistoryRepository prices() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private static CashTransaction trade(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.TRANSACTION, symbol,
                qty, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static CashTransaction contribGbp(String date, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.CONTRIBUTION, "GBP",
                0.0, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static PriceBar bar(String symbol, String date, double close) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                close, close, 1.0, null, "GBP");
    }

    private static PriceBar barUsd(String symbol, String date, double close) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                close, close, 1.0, null, "USD");
    }

    @Test
    void emptyLedgerYieldsEmptyTimeline() {
        AllocationTimeline t = service().timeline();
        assertTrue(t.points().isEmpty());
    }

    @Test
    void cashAndInvestedSumToTotal() {
        // Contributed £10k, spent £6k on MU at £100, MU climbs to £120.
        // At a sample after the trade: cash £4k, invested £7,200 (60 × £120), total £11,200.
        repo().saveII(List.of(
                contribGbp("2026-01-05", 10000),
                trade("2026-01-06", "MU", 60, -6000)));
        prices().upsertPriceBars(List.of(
                bar("MU", "2026-01-06", 100.0),
                bar("MU", "2026-03-01", 120.0)));

        AllocationTimeline t = service().timeline();
        assertFalse(t.points().isEmpty());

        AllocationPoint last = t.points().get(t.points().size() - 1);
        // The closing sample always runs through today's transactions; the last point's
        // total = cash + invested by construction.
        assertEquals(last.totalGbp().doubleValue(),
                last.cashGbp().add(last.investedGbp()).doubleValue(), 0.01);
        assertTrue(last.symbolGbp().containsKey("MU"));
    }

    @Test
    void onlyNonZeroPositionsAppearInPayload() {
        // Bought then sold completely → final qty 0 → not in symbolGbp.
        repo().saveII(List.of(
                contribGbp("2026-01-05", 10000),
                trade("2026-01-06", "MU", 60, -6000),
                trade("2026-02-06", "MU", 60, 7200)));
        prices().upsertPriceBars(List.of(
                bar("MU", "2026-01-06", 100.0),
                bar("MU", "2026-02-06", 120.0)));

        AllocationTimeline t = service().timeline();
        AllocationPoint last = t.points().get(t.points().size() - 1);
        assertFalse(last.symbolGbp().containsKey("MU"),
                "fully exited positions disappear from the latest snapshot");
        assertEquals(0, last.investedGbp().signum(),
                "no held positions → invested = 0");
    }

    @Test
    void cashGrowsAsSellsCreditTheLedger() {
        // £10k contributed, £6k spent on MU, sold for £7.2k → cash £11.2k (10k − 6k + 7.2k).
        repo().saveII(List.of(
                contribGbp("2026-01-05", 10000),
                trade("2026-01-06", "MU", 60, -6000),
                trade("2026-02-06", "MU", 60, 7200)));
        prices().upsertPriceBars(List.of(bar("MU", "2026-01-06", 100.0)));

        AllocationTimeline t = service().timeline();
        AllocationPoint last = t.points().get(t.points().size() - 1);
        assertEquals(11200.0, last.cashGbp().doubleValue(), 0.01);
    }

    @Test
    void timelineEndsAtTodayEvenMidWeek() {
        repo().saveII(List.of(
                contribGbp("2026-01-05", 1000),
                trade("2026-01-06", "MU", 10, -1000)));
        prices().upsertPriceBars(List.of(bar("MU", "2026-01-06", 100.0)));

        AllocationTimeline t = service().timeline();
        assertEquals(LocalDate.now().toString(),
                t.points().get(t.points().size() - 1).date(),
                "last sample is always today, so the date-picker snapshot reflects current state");
    }

    /**
     * A holding half-moved between two listings of one fund is one series of the full size, not two
     * of half. Counted the other way the chart shows one line falling as another rises while
     * nothing owned has changed — and concentration, which reads these very points, calls the book
     * more diversified than it is.
     *
     * <p>Each listing is still valued on its own price and its own currency; only the GBP results
     * are added together, so nothing here assumes the two lines' units are interchangeable.
     */
    @Test
    void twoListingsOfOneFundAreOneSeries() {
        repo().saveII(List.of(
                contribGbp("2026-01-05", 20000),
                trade("2026-01-06", "EQQQ", 20, -10000),
                trade("2026-02-06", "EQQU", 20, -10000)));
        // The sterling line is priced in pounds under its mapped ticker; the dollar line in dollars
        // under its own. Each is valued on its own quote and only the GBP results are added.
        prices().upsertPriceBars(List.of(
                bar("EQQQ.L", "2026-01-06", 500.0),
                barUsd("EQQU", "2026-02-06", 625.0)));

        AllocationPoint split = last(service().timeline());
        assertEquals(2, split.symbolGbp().size(), "undeclared, they are two positions");

        ShareClasses declared = ShareClasses.of(
                List.of(new ShareClasses.Pair("EQQQ", "EQQU")));
        AllocationPoint folded = last(service(() -> declared).timeline());

        assertEquals(1, folded.symbolGbp().size());
        assertEquals(10000.0 + 10000.0, folded.symbolGbp().get("EQQQ").doubleValue(), 0.01);
        assertFalse(folded.symbolGbp().containsKey("EQQU"),
                "the second line is not a second position");
        assertEquals(split.investedGbp().doubleValue(), folded.investedGbp().doubleValue(), 0.01,
                "the fold moves value between rows, never into or out of the book");
    }

    private static AllocationPoint last(AllocationTimeline t) {
        return t.points().get(t.points().size() - 1);
    }
}
