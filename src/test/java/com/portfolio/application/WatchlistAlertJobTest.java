package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.FundamentalsRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.persistence.WatchlistRepository;
import com.portfolio.port.AlertNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link WatchlistAlertJob#clearFiredFor(String)} — the re-arm hook called when a symbol's
 * triggers change so a corrected target can fire fresh immediately (and a stale firing for a
 * now-disabled trigger stops lingering) — the shape of the email itself, which is where a
 * symbol's note has to arrive for the note to be worth writing at all, and the dedup: a state
 * trigger mails on the crossing and nothing but an un-crossing re-arms it, while a move is keyed
 * by the session its price came from. {@code clearFiredFor} only touches the KV set, so those
 * tests leave the service/notifier deps null.
 */
class WatchlistAlertJobTest {

    private static WatchlistAlertJob job(KeyValueStore kv) {
        return new WatchlistAlertJob(null, null, kv);
    }

    @Test
    void clearFiredFor_dropsOnlyThatSymbolsKeys(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of(
                "GILT 4.5% 2028|ABOVE",
                "GILT 4.5% 2028|BELOW",
                "NVDA|MOVE|2026-08-05"));

        job(kv).clearFiredFor("GILT 4.5% 2028");

        Set<String> left = kv.getStringSet(WatchlistAlertJob.FIRED_KEY);
        assertEquals(Set.of("NVDA|MOVE|2026-08-05"), left,
                "only the mutated symbol's fired flags should be dropped");
    }

    @Test
    void clearFiredFor_isCaseAndWhitespaceInsensitive(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE"));

        job(kv).clearFiredFor("  nvda ");   // normalised to NVDA before matching

        assertTrue(kv.getStringSet(WatchlistAlertJob.FIRED_KEY).isEmpty());
    }

    @Test
    void clearFiredFor_noMatch_isQuietNoop(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE"));

        assertDoesNotThrow(() -> job(kv).clearFiredFor("TSLA"));
        assertDoesNotThrow(() -> job(kv).clearFiredFor(null));
        assertEquals(Set.of("NVDA|ABOVE"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY));
    }

    // ---- the email ------------------------------------------------------------

    /** Captures what was handed to the notifier instead of sending it. */
    private static final class CapturingNotifier implements AlertNotifier {
        private final List<String> subjects = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();

        @Override public boolean notify(String subject, String body) {
            subjects.add(subject);
            bodies.add(body);
            return true;
        }

        @Override public boolean isEnabled() {
            return true;
        }
    }

    /**
     * {@code PriceStats} discards weekend-dated bars as phantom gilt-scraper rows, so the fixture
     * is anchored on weekdays rather than on plain {@code now()} / {@code now() - 1}.
     */
    private static LocalDate weekdayOnOrBefore(LocalDate d) {
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static PriceBar bar(String sym, LocalDate date, double close) {
        return new PriceBar(sym, date, null, null, null, close, close, 1.0, null, "GBP");
    }

    /** A real service over a temp DB with AAPL at 100 → 110 (a +10% session), so the job's own
     *  rows are the ones the dashboard would render. */
    private static WatchlistService service(Path dir, KeyValueStore kv,
                                            java.util.function.Consumer<WatchlistRepository> setup) {
        return serviceEndingOn(dir, kv, weekdayOnOrBefore(LocalDate.now()), 100.0, 110.0, setup);
    }

    /**
     * The same, with the last bar on a named session. A fixture whose last session is not today
     * is the only way to tell a key anchored on the price's trading day from one anchored on the
     * wall clock — on a weekday the two agree, which is exactly how the bug survived.
     */
    private static WatchlistService serviceEndingOn(Path dir, KeyValueStore kv, LocalDate lastSession,
                                                    double prevClose, double lastClose,
                                                    java.util.function.Consumer<WatchlistRepository> setup) {
        var cf = new JdbcConnectionFactory(dir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        priceRepo.savePriceBars(List.of(
                bar("AAPL", weekdayOnOrBefore(lastSession.minusDays(1)), prevClose),
                bar("AAPL", lastSession, lastClose)));
        setup.accept(wlRepo);
        return new WatchlistService(wlRepo, priceRepo, new IntradayPriceRepository(cf),
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv));
    }

    @Test
    void theNoteIsPrintedOncePerSymbolAndIsNotCountedAsASignal(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        // Two triggers fire on one row: +10% clears the 3% move threshold, and 110 is above 105.
        WatchlistService svc = service(dir, kv, repo -> {
            repo.add("AAPL", null, new BigDecimal("3"), new BigDecimal("105"), null);
            repo.updateNote("AAPL", "Trim 25% in the SIPP; leave the Dealing lot alone");
        });
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        assertEquals(1, mail.bodies.size(), "a tick's firings are batched into one email");
        String body = mail.bodies.get(0);
        assertEquals(1, body.split("Trim 25% in the SIPP", -1).length - 1,
                "the note is the plan for the signal, not for each trigger of it: printed once");
        // It belongs under the firings it explains, not as a line that reads like another signal.
        int firstSignal = Math.min(body.indexOf("up 10.0% today"), body.indexOf("hit Above trigger"));
        assertTrue(firstSignal >= 0 && body.indexOf("Trim 25%") > firstSignal,
                "the note follows the symbol's own lines: " + body);
        assertEquals("Watchlist alert: 2 signals", mail.subjects.get(0),
                "two triggers fired; the note is not a third signal");
    }

    /**
     * Two numbers, and the email must not blur them: the <b>trigger</b> is what fired, the
     * <b>order target</b> is where the deal goes. Printing only the trigger — which is what the
     * line used to call "the target" — leaves the one figure the mail exists to act on out of it.
     */
    @Test
    void thePriceLineNamesTheTriggerThatFiredAndTheTargetToDealAt(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistService svc = service(dir, kv, repo -> {
            repo.add("AAPL", null, null, new BigDecimal("105"), null);   // price 110 → fires
            repo.updateTargets("AAPL", new BigDecimal("125"), null);
        });
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        String body = mail.bodies.get(0);
        assertTrue(body.contains("hit Above trigger 105 — now 110"), body);
        assertTrue(body.contains("order target 125"), body);
        assertFalse(body.contains("hit Above target"),
                "the level that fired is the trigger — the target is a different number");
    }

    /** No target declared, nothing extra said. A trigger is not restated as an order level. */
    @Test
    void aSideWithNoTargetPrintsNoOrderLine(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("AAPL", null, null, new BigDecimal("105"), null));
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        String body = mail.bodies.get(0);
        assertTrue(body.contains("hit Above trigger 105 — now 110"), body);
        assertFalse(body.contains("order target"), body);
    }

    @Test
    void aSymbolWithNoNoteMailsNothingExtra(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("AAPL", null, new BigDecimal("3"), null, null));
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        assertEquals("Watchlist alert: 1 signal", mail.subjects.get(0));
        assertFalse(mail.bodies.get(0).contains("\u21b3"), "no note, no note line");
    }

    /**
     * The note rides on the dedup, not beside it: a trigger already mailed today adds no line, so
     * the symbol contributes nothing — including no second copy of its note.
     */
    @Test
    void anAlreadyMailedSignalDoesNotReprintTheNote(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistService svc = service(dir, kv, repo -> {
            repo.add("AAPL", null, new BigDecimal("3"), null, null);
            repo.updateNote("AAPL", "Trim 25%");
        });
        CapturingNotifier mail = new CapturingNotifier();
        WatchlistAlertJob job = new WatchlistAlertJob(svc, mail, kv);

        job.run();
        job.run();

        assertEquals(1, mail.bodies.size(), "the second tick has nothing new to say");
    }

    // ---- the dedup: a crossing, not a calendar day ----------------------------

    /**
     * What this replaced: the key carried {@code LocalDate.now()}, so a level the market was still
     * through re-announced itself every morning — three days running for the same three names, the
     * last of them a Saturday reporting Friday's close. A state trigger's key carries no date at
     * all, which is what makes a new day unable to re-arm it.
     */
    @Test
    void aLevelStillThroughMailsOnceAndNoNewDayCanReArmIt(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("AAPL", null, null, new BigDecimal("105"), null));
        CapturingNotifier mail = new CapturingNotifier();
        WatchlistAlertJob job = new WatchlistAlertJob(svc, mail, kv);

        job.run();
        job.run();

        assertEquals(1, mail.bodies.size());
        assertEquals(Set.of("AAPL|ABOVE"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY),
                "a state trigger's key names no day, so no day can expire it");
    }

    /** The signal is the crossing, so coming back through the level is what re-arms it. */
    @Test
    void aLevelTheMarketComesBackThroughFiresAgainOnTheNextCrossing(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        LocalDate session = weekdayOnOrBefore(LocalDate.now());
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("AAPL", null, null, new BigDecimal("105"), null));
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dir));
        CapturingNotifier mail = new CapturingNotifier();
        WatchlistAlertJob job = new WatchlistAlertJob(svc, mail, kv);

        job.run();                                                  // 110 is through 105 → mails
        priceRepo.upsertPriceBars(List.of(bar("AAPL", session, 100.0)));   // last write wins
        job.run();                                                  // back under the level
        assertTrue(kv.getStringSet(WatchlistAlertJob.FIRED_KEY).isEmpty(),
                "an un-crossing drops the key on the quiet tick that sees it");
        priceRepo.upsertPriceBars(List.of(bar("AAPL", session, 112.0)));
        job.run();                                                  // through it again → news

        assertEquals(2, mail.bodies.size(), "the second crossing is a second signal");
    }

    /**
     * A move is an event belonging to one session, so its key names the session the price came
     * from. Keyed on the wall clock, Friday's move would mail again at one minute past midnight
     * on Saturday — and again on Sunday — with no session in between.
     */
    @Test
    void aBigMoveIsKeyedByTheSessionItsPriceCameFrom(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        LocalDate lastSession = weekdayOnOrBefore(LocalDate.now().minusDays(10));
        assertNotEquals(LocalDate.now(), lastSession, "the fixture must sit off the wall-clock day");
        WatchlistService svc = serviceEndingOn(dir, kv, lastSession, 100.0, 110.0,
                repo -> repo.add("AAPL", null, new BigDecimal("3"), null, null));
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        assertEquals(1, mail.bodies.size());
        assertEquals(Set.of("AAPL|MOVE|" + lastSession), kv.getStringSet(WatchlistAlertJob.FIRED_KEY),
                "the key names the price's trading day, not today");
    }

    /** An outage is not an un-crossing: a row with no price keeps what it has already mailed. */
    @Test
    void aRowWithNoPriceKeepsItsFiredKeys(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NODATA|BELOW"));
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("NODATA", null, null, null, new BigDecimal("50")));
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        assertEquals(Set.of("NODATA|BELOW"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY),
                "a symbol with no price re-arms nothing");
        assertTrue(mail.bodies.isEmpty());
    }

    /**
     * A key written by the earlier dated scheme records the same firing, so the change itself must
     * not mail every level the market is already through one last time.
     */
    @Test
    void aDatedKeyFromTheOldSchemeStillSuppressesTheFiringItRecorded(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("AAPL|ABOVE|2026-11-07"));
        WatchlistService svc = service(dir, kv,
                repo -> repo.add("AAPL", null, null, new BigDecimal("105"), null));
        CapturingNotifier mail = new CapturingNotifier();

        new WatchlistAlertJob(svc, mail, kv).run();

        assertTrue(mail.bodies.isEmpty(), "already mailed, under the shape the key had then");
        assertEquals(Set.of("AAPL|ABOVE"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY),
                "and it takes the current shape on the way through");
    }
}
