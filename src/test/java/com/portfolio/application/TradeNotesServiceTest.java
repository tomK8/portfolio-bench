package com.portfolio.application;

import com.portfolio.application.TradeNotesService.TradeJournal;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.TradeNotesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeNotesServiceTest {

    @TempDir
    Path dbDir;

    private static CashTransaction trade(String date, double amount) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                "AAPL", 1.0, amount, "GBP", 1.0, amount, amount, amount, "Trade");
    }

    private TradeNotesService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        return new TradeNotesService(
                new CashTransactionRepository(cf, new KeyValueStore(dbDir)),
                new TradeNotesRepository(cf));
    }

    @Test
    void emptyLedgerYieldsEmptyJournal() {
        TradeJournal j = service().journal();
        assertTrue(j.trades().isEmpty());
        assertEquals(0, j.summary().total());
        assertEquals(0, j.summary().annotated());
    }

    @Test
    void negativeAmountIsBuyPositiveIsSell() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(
                trade("2023-01-01", -500.0),   // cash out → BUY
                trade("2023-06-01", 600.0)));   // cash in  → SELL

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        TradeJournal j = svc.journal();
        assertEquals(2, j.trades().size());
        // trades are newest-first
        assertEquals("SELL", j.trades().get(0).side());
        assertEquals("BUY",  j.trades().get(1).side());
    }

    @Test
    void dividendsAndChargesAreExcludedFromTrades() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(
                new CashTransaction("2023-01-01", Account.AJBELL, TransactionType.DIVIDEND,
                        "AAPL", 0.0, 10.0, "GBP", 1.0, 10.0, 10.0, 10.0, "div"),
                trade("2023-02-01", -400.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        assertEquals(1, svc.journal().trades().size());
    }

    @Test
    void saveNoteAndTagsRoundTrip() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(trade("2023-01-01", -200.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        long rowid = svc.journal().trades().get(0).rowid();
        svc.save(rowid, "bought the dip", "conviction,fomo");

        TradeJournal j = svc.journal();
        var entry = j.trades().get(0);
        assertEquals("bought the dip", entry.note());
        assertTrue(entry.tags().contains("conviction"));
        assertTrue(entry.tags().contains("fomo"));
    }

    @Test
    void tagsAreNormalisedToLowercase() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(trade("2023-01-01", -200.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        long rowid = svc.journal().trades().get(0).rowid();
        svc.save(rowid, "", "FOMO,Greed");

        var entry = svc.journal().trades().get(0);
        assertTrue(entry.tags().contains("fomo"));
        assertTrue(entry.tags().contains("greed"));
    }

    @Test
    void correctionTradesDoNotCountTowardAnnotatedOrTagFrequency() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(trade("2023-01-01", -200.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        long rowid = svc.journal().trades().get(0).rowid();
        svc.save(rowid, "broker fix", "correction");

        TradeJournal j = svc.journal();
        assertEquals(0, j.summary().annotated());
        assertFalse(j.summary().tagCounts().containsKey("correction"));
    }

    @Test
    void blankNoteAndTagsDeletesAnnotation() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(trade("2023-01-01", -200.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        long rowid = svc.journal().trades().get(0).rowid();
        svc.save(rowid, "temporary", "fomo");
        svc.save(rowid, "", "");

        var entry = svc.journal().trades().get(0);
        assertTrue(entry.note().isBlank());
        assertTrue(entry.tags().isEmpty());
        assertEquals(0, svc.journal().summary().annotated());
    }

    @Test
    void tagFrequencySummarisedAcrossTrades() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(
                trade("2023-01-01", -100.0),
                trade("2023-02-01", -200.0)));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        var ids = svc.journal().trades().stream().map(TradeNotesService.TradeEntry::rowid).toList();
        svc.save(ids.get(0), "", "fomo");
        svc.save(ids.get(1), "", "fomo");

        assertEquals(2, svc.journal().summary().tagCounts().get("fomo"));
    }

    @Test
    void pricePerShareDividesCashAmountByQuantityIgnoringSigns() {
        // A buy books negative cash and (for some brokers) negative quantity; the price is
        // positive either way.
        assertEquals(25.0, TradeNotesService.pricePerShare(4.0, -100.0), 1e-9);
        assertEquals(25.0, TradeNotesService.pricePerShare(-4.0, 100.0), 1e-9);
        assertEquals(25.0, TradeNotesService.pricePerShare(4.0, 100.0), 1e-9);
    }

    @Test
    void pricePerShareIsNullWhereTheQuotientWouldBeMeaningless() {
        // "Stock Split Received": shares arrive for no cash. A £0.00 price would read as a
        // free purchase rather than as "not a priced trade".
        assertNull(TradeNotesService.pricePerShare(36.0, 0.0));
        // No quantity to divide by.
        assertNull(TradeNotesService.pricePerShare(0.0, 500.0));
        assertNull(TradeNotesService.pricePerShare(0.0, 0.0));
    }

    @Test
    void pricePerShareIsCarriedOntoTheJournalEntry() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        repo.saveAjBell(List.of(
                new CashTransaction("2023-01-01", Account.AJBELL, TransactionType.TRANSACTION,
                        "AAPL", 8.0, -1600.0, "GBP", 1.0, -1600.0, -1600.0, -1600.0, "Buy"),
                new CashTransaction("2023-02-01", Account.AJBELL, TransactionType.TRANSACTION,
                        "AAPL", 36.0, 0.0, "GBP", 1.0, 0.0, 0.0, 0.0, "Stock Split Received")));

        var svc = new TradeNotesService(repo, new TradeNotesRepository(cf));
        var trades = svc.journal().trades();
        // newest-first: the split row leads
        assertNull(trades.get(0).pricePerShare());
        assertEquals(200.0, trades.get(1).pricePerShare(), 1e-9);
        // the split row is still journalled, just without a price
        assertEquals(2, svc.journal().summary().total());
    }
}
