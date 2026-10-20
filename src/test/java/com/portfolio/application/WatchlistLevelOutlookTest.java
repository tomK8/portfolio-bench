package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.Outlook;
import com.portfolio.application.WatchlistService.Row;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two readings that follow each alert level: how far the market still has to move to reach it,
 * and the chance it is touched inside the table's horizon.
 *
 * <p>What is worth pinning here is not the exact probability — that is {@code VolForecastTest}'s
 * job — but the three decisions this service makes around it: the move is signed the same way on
 * both sides, a level the market is already through is certainty rather than an estimate priced
 * backwards, and the horizon is one stored figure the payload carries so the header cannot name an
 * N the cells were not priced at.
 */
class WatchlistLevelOutlookTest {

    @TempDir
    Path dbDir;

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "USD");
    }

    /**
     * 300 sessions of a mildly noisy walk ending at 100.00, so there is an RV250 anchor as well as
     * a current-state window. Seeded, so the probabilities are the same on every run.
     */
    private static List<PriceBar> series(String sym) {
        List<PriceBar> bars = new ArrayList<>();
        Random rnd = new Random(42);
        double[] closes = new double[300];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100.0 * Math.exp(0.012 * rnd.nextGaussian());
        }
        closes[closes.length - 1] = 100.0;                 // a round last price to measure against
        LocalDate d = LocalDate.now();
        for (int i = closes.length - 1; i >= 0; i--) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.minusDays(1);
            }
            bars.add(0, bar(sym, d, closes[i]));
            d = d.minusDays(1);
        }
        return bars;
    }

    private WatchlistService service(java.util.function.Consumer<WatchlistRepository> setup) {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(series("AAPL"));
        setup.accept(wlRepo);
        return new WatchlistService(wlRepo, priceRepo, new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv), kv);
    }

    private Row watched(BigDecimal above, BigDecimal below) {
        return service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO, above, below))
                .row("AAPL");
    }

    /**
     * One definition of the move for both sides: what the price still has to do. An Above level
     * reads positive and a Below level negative, which is what lets the column be sorted and read
     * without having to remember which half of the table it is in.
     */
    @Test
    void theMoveIsSignedTheSameWayOnBothSides() {
        Row r = watched(new BigDecimal("110"), new BigDecimal("90"));

        assertEquals(0, new BigDecimal("0.100000").compareTo(r.aboveOutlook().movePct()),
                "110 from 100 is a +10% rise");
        assertEquals(0, new BigDecimal("-0.100000").compareTo(r.belowOutlook().movePct()),
                "90 from 100 is a -10% fall");
        assertFalse(r.aboveOutlook().through());
        assertFalse(r.belowOutlook().through());
    }

    /**
     * A level the market has gone through reads with the wrong sign for its side — the one way an
     * already-true trigger is visible in a column of distances.
     */
    @Test
    void aLevelAlreadyThroughIsCertaintyNotAProbabilityPricedBackwards() {
        // Above at 90 against a price of 100: the barrier was touched on the way here.
        Row r = watched(new BigDecimal("90"), new BigDecimal("110"));

        Outlook above = r.aboveOutlook();
        assertTrue(above.through());
        assertEquals(0, new BigDecimal("-0.100000").compareTo(above.movePct()),
                "wrong-signed for its side is how 'already through' shows");
        assertEquals(0, BigDecimal.ONE.compareTo(above.probability()),
                "the barrier has been touched — not the chance of coming back to it");
        assertEquals(0, BigDecimal.ONE.compareTo(above.probabilityLow()));
        assertEquals(0, BigDecimal.ONE.compareTo(above.probabilityHigh()));

        Outlook below = r.belowOutlook();
        assertTrue(below.through());
        assertEquals(0, BigDecimal.ONE.compareTo(below.probability()));
    }

    /** No level is not a zero distance and not a zero probability — it is no reading at all. */
    @Test
    void aLevelThatIsNotSetHasNoOutlook() {
        Row r = watched(new BigDecimal("110"), null);

        assertNotNull(r.aboveOutlook());
        assertNull(r.belowOutlook(), "absent is never zero");
    }

    /** A row with no prices at all carries no outlook either, rather than a manufactured one. */
    @Test
    void anUnpricedRowCarriesNoOutlook() {
        Row r = service(repo -> repo.add("NOSUCH", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50"), new BigDecimal("10"))).row("NOSUCH");

        assertTrue(r.missingData());
        assertNull(r.aboveOutlook());
        assertNull(r.belowOutlook());
    }

    /**
     * A nearer barrier is likelier than a far one, and a longer horizon is likelier than a short
     * one. Two orderings rather than two magic numbers: they hold whatever the vol forecast says,
     * and they are what the column is actually read for.
     */
    @Test
    void theProbabilityFallsWithDistanceAndRisesWithTheHorizon() {
        BigDecimal near = service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("102"), null)).row("AAPL").aboveOutlook().probability();
        BigDecimal far = service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("140"), null)).row("AAPL").aboveOutlook().probability();
        assertTrue(near.compareTo(far) > 0, near + " should beat " + far);

        var svc = service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("115"), null));
        svc.setTouchSessions(10);
        BigDecimal shortHorizon = svc.row("AAPL").aboveOutlook().probability();
        svc.setTouchSessions(120);
        BigDecimal longHorizon = svc.row("AAPL").aboveOutlook().probability();
        assertTrue(longHorizon.compareTo(shortHorizon) > 0,
                longHorizon + " over 120 sessions should beat " + shortHorizon + " over 10");
    }

    /**
     * The horizon travels on the payload, and every cell on it names the same N. Without that the
     * header and the numbers beneath it are two answers to one question.
     */
    @Test
    void theHorizonIsStoredAndRidesOnThePayload() {
        var svc = service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("110"), new BigDecimal("90")));

        assertEquals(WatchlistService.DEFAULT_TOUCH_SESSIONS, svc.touchSessions());

        svc.setTouchSessions(63);
        var view = svc.view();
        assertEquals(63, view.touchSessions());
        Row r = view.rows().get(0);
        assertEquals(63, r.aboveOutlook().sessions());
        assertEquals(63, r.belowOutlook().sessions());

        // An empty list still names the horizon — the box above the table has to show something.
        var empty = new WatchlistService(new WatchlistRepository(
                new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir)),
                new PriceHistoryRepository(new JdbcConnectionFactory(dbDir)),
                new IntradayPriceRepository(new JdbcConnectionFactory(dbDir)),
                new FundamentalsRepository(new JdbcConnectionFactory(dbDir)),
                new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir)),
                () -> Map.of(), new YahooTickerMap(), null, new KeyValueStore(dbDir));
        assertEquals(63, empty.view(9999).touchSessions());
    }

    /** Out of range is clamped, not refused — a stray keystroke moves the horizon, never breaks it. */
    @Test
    void anOutOfRangeHorizonIsClamped() {
        var svc = service(repo -> { });

        svc.setTouchSessions(0);
        assertEquals(1, svc.touchSessions());
        svc.setTouchSessions(99999);
        assertEquals(WatchlistService.MAX_TOUCH_SESSIONS, svc.touchSessions());
    }

    /** The near-real-time poll carries the outlooks too: both halves are read off the live price. */
    @Test
    void theQuotePollCarriesTheOutlooksSoTheyTrackTheLivePrice() {
        var svc = service(repo -> repo.add("AAPL", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("110"), null));

        var q = svc.quotes().rows().get(0);
        assertNotNull(q.aboveOutlook());
        assertEquals(0, new BigDecimal("0.100000").compareTo(q.aboveOutlook().movePct()));
        assertNull(q.belowOutlook());
    }
}
