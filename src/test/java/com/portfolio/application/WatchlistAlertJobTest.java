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
 * triggers change so a corrected target can fire fresh the same day (and a stale firing for a
 * now-disabled trigger stops lingering) — and the shape of the email itself, which is where a
 * symbol's note has to arrive for the note to be worth writing at all. {@code clearFiredFor} only
 * touches the KV set, so those tests leave the service/notifier deps null.
 */
class WatchlistAlertJobTest {

    private static WatchlistAlertJob job(KeyValueStore kv) {
        return new WatchlistAlertJob(null, null, kv);
    }

    @Test
    void clearFiredFor_dropsOnlyThatSymbolsKeys(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of(
                "GILT 4.5% 2028|ABOVE|2026-08-05",
                "GILT 4.5% 2028|BELOW|2026-08-05",
                "NVDA|MOVE|2026-08-05"));

        job(kv).clearFiredFor("GILT 4.5% 2028");

        Set<String> left = kv.getStringSet(WatchlistAlertJob.FIRED_KEY);
        assertEquals(Set.of("NVDA|MOVE|2026-08-05"), left,
                "only the mutated symbol's fired flags should be dropped");
    }

    @Test
    void clearFiredFor_isCaseAndWhitespaceInsensitive(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE|2026-08-05"));

        job(kv).clearFiredFor("  nvda ");   // normalised to NVDA before matching

        assertTrue(kv.getStringSet(WatchlistAlertJob.FIRED_KEY).isEmpty());
    }

    @Test
    void clearFiredFor_noMatch_isQuietNoop(@TempDir Path dir) {
        KeyValueStore kv = new KeyValueStore(dir);
        kv.putStringSet(WatchlistAlertJob.FIRED_KEY, List.of("NVDA|ABOVE|2026-08-05"));

        assertDoesNotThrow(() -> job(kv).clearFiredFor("TSLA"));
        assertDoesNotThrow(() -> job(kv).clearFiredFor(null));
        assertEquals(Set.of("NVDA|ABOVE|2026-08-05"), kv.getStringSet(WatchlistAlertJob.FIRED_KEY));
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
        var cf = new JdbcConnectionFactory(dir);
        var wlRepo = new WatchlistRepository(cf, kv);
        var priceRepo = new PriceHistoryRepository(cf);
        LocalDate today = weekdayOnOrBefore(LocalDate.now());
        priceRepo.savePriceBars(List.of(bar("AAPL", weekdayOnOrBefore(today.minusDays(1)), 100.0),
                bar("AAPL", today, 110.0)));
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
        int firstSignal = Math.min(body.indexOf("up 10.0% today"), body.indexOf("hit Above target"));
        assertTrue(firstSignal >= 0 && body.indexOf("Trim 25%") > firstSignal,
                "the note follows the symbol's own lines: " + body);
        assertEquals("Watchlist alert: 2 signals", mail.subjects.get(0),
                "two triggers fired; the note is not a third signal");
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
}
