package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.InstrumentMappingRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ledger re-keying that follows a name mapping. Nothing here reaches the network — the ticker
 * half of the service is exercised through {@link YahooTickerMap} and the repository tests.
 */
class InstrumentMappingServiceTest {

    private CashTransactionRepository cashRepo;
    private InstrumentMappingRepository mappingRepo;

    private InstrumentMappingService service(Path dir) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dir);
        KeyValueStore kv = new KeyValueStore(dir);
        cashRepo = new CashTransactionRepository(cf, kv);
        mappingRepo = new InstrumentMappingRepository(cf);
        YahooTickerMap tickers = new YahooTickerMap(mappingRepo::loadTickerMappings);
        PriceFetchJob job = new PriceFetchJob(cashRepo, new PriceHistoryRepository(cf),
                new YahooPriceFetcher(), tickers, kv);
        return new InstrumentMappingService(mappingRepo, tickers, new YahooPriceFetcher(), job, cashRepo);
    }

    private static CashTransaction buy(String date, String symbol) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, 100.0, -4568.50, "GBP", 1.0, -4568.50, null, null, "buy");
    }

    /**
     * The case this exists for: the statement was imported before the mapping existed, so the
     * ledger is keyed by the cleaned-up description and the position agrees with nothing else.
     * Re-importing cannot fix it — the AJBell dedup key includes the symbol, so the same rows would
     * be inserted a second time and the position double-counted.
     */
    @Test
    void savingANameMappingReKeysLedgerRowsStrandedUnderTheLongName(@TempDir Path dir) {
        InstrumentMappingService svc = service(dir);
        cashRepo.saveAjBell(List.of(buy("2026-08-14", "XTRACKERS MSCI WORLD HEALTH CARE ETF 1C GBP")));

        var result = svc.saveAlias("xtrackers msci world health", "XWHS",
                "Xtrackers MSCI World Health Care ETF 1C GBP");

        assertTrue(result.ok());
        assertEquals(1, result.ledgerRowsUpdated());
        assertEquals(List.of("XWHS"), cashRepo.distinctTradedSymbols());
        assertTrue(result.message().contains("ledger row"), "the repair is reported, not silent");
    }

    /**
     * A rename is not undoable from the dashboard, so the re-key is deliberately narrow: only
     * stranded <em>descriptions</em> qualify, and a description always contains a space where a
     * ticker never does. Without that guard the keyword "bp" would re-key the genuine BP. position.
     */
    @Test
    void doesNotReKeyRealTickersThatHappenToContainTheKeyword(@TempDir Path dir) {
        InstrumentMappingService svc = service(dir);
        cashRepo.saveAjBell(List.of(buy("2026-08-14", "BP."), buy("2026-08-13", "BNP")));

        var result = svc.saveAlias("bp", "BP.", "BP PLC");

        assertEquals(0, result.ledgerRowsUpdated());
        assertEquals(List.of("BNP", "BP."), cashRepo.distinctTradedSymbols());
    }

    /** Gilt ids legitimately contain spaces; they are priced by their own scraper, never mapped. */
    @Test
    void doesNotReKeyGiltIds(@TempDir Path dir) {
        InstrumentMappingService svc = service(dir);
        cashRepo.saveAjBell(List.of(buy("2026-08-14", "GILT 4.25% 2032")));

        assertEquals(0, svc.saveAlias("gilt 4.25", "G425", "Treasury Gilt 4.25%").ledgerRowsUpdated());
        assertEquals(List.of("GILT 4.25% 2032"), cashRepo.distinctTradedSymbols());
    }

    @Test
    void aliasesReachTheParsersThroughTheRepository(@TempDir Path dir) {
        InstrumentMappingService svc = service(dir);
        svc.saveAlias("xtrackers msci world health", "XWHS", null);
        assertEquals("XWHS", svc.aliases().get("xtrackers msci world health"));
    }

    @Test
    void blankInputIsRejectedBeforeAnythingIsWritten(@TempDir Path dir) {
        InstrumentMappingService svc = service(dir);
        assertThrows(IllegalArgumentException.class, () -> svc.saveAlias("  ", "XWHS", null));
        assertThrows(IllegalArgumentException.class, () -> svc.saveAlias("xwhs", "", null));
        assertTrue(mappingRepo.loadAliases().isEmpty());
    }
}
