package com.portfolio.application;

import com.portfolio.application.ContributionService.AccountLine;
import com.portfolio.application.ContributionService.ContributionTimeline;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ContributionServiceTest {

    @TempDir
    Path dbDir;

    private static final com.portfolio.port.HistoricalFxRateProvider NO_FX = (ccy, from, to) -> new TreeMap<>();
    private static final com.portfolio.port.HistoricalFxRateProvider USD_1_25 = (ccy, from, to) -> {
        var m = new TreeMap<LocalDate, BigDecimal>();
        if ("USD".equals(ccy)) m.put(from, new BigDecimal("1.25"));
        return m;
    };

    private static CashTransaction contrib(String date, Account account, double amountGbp) {
        return new CashTransaction(date, account, TransactionType.CONTRIBUTION,
                "GBP", 0.0, amountGbp, "GBP", 1.0, amountGbp, amountGbp, amountGbp, "Contribution");
    }

    private ContributionService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        return new ContributionService(
                new CashTransactionRepository(cf, new KeyValueStore(dbDir)),
                new KeyValueStore(dbDir),
                NO_FX);
    }

    @Test
    void emptyLedgerYieldsEmptyTimeline() {
        ContributionTimeline t = service().timeline();
        assertTrue(t.lines().isEmpty());
    }

    @Test
    void singleContributionAppearsOnCorrectAccountLine() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        repo.saveII(java.util.List.of(contrib("2023-01-10", Account.II, 1000.0)));

        var svc = new ContributionService(repo, kv, NO_FX);
        ContributionTimeline t = svc.timeline();

        AccountLine ajLine = t.lines().stream()
                .filter(l -> l.label().equals("II SIPP"))
                .findFirst().orElseThrow();
        assertEquals(1, ajLine.points().size());
        assertEquals(0, new BigDecimal("1000.00").compareTo(ajLine.points().get(0).cumulativeGbp()));
    }

    @Test
    void cumulativeSumsAccumulateAcrossMultipleContributions() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        repo.saveII(java.util.List.of(
                contrib("2023-01-01", Account.II, 1000.0),
                contrib("2023-06-01", Account.II, 500.0)));

        ContributionTimeline t = new ContributionService(repo, kv, NO_FX).timeline();
        AccountLine aj = t.lines().stream().filter(l -> l.label().equals("II SIPP")).findFirst().orElseThrow();
        // Second point should be the running sum
        assertEquals(0, new BigDecimal("1500.00").compareTo(aj.points().get(1).cumulativeGbp()));
    }

    @Test
    void totalLineIncludesAllAccounts() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        // Use II for both since saveAjBell has a gap-check that blocks two separate calls.
        // Here we use RothIra and II to test two distinct accounts.
        repo.saveRothIra(java.util.List.of(contrib("2023-01-01", Account.ROTH_IRA, 1000.0)), java.math.BigDecimal.ZERO);
        repo.saveII(java.util.List.of(contrib("2023-02-01", Account.II, 2000.0)));

        ContributionTimeline t = new ContributionService(repo, kv, NO_FX).timeline();
        AccountLine total = t.lines().stream().filter(l -> l.label().equals("Total")).findFirst().orElseThrow();
        BigDecimal last = total.points().get(total.points().size() - 1).cumulativeGbp();
        assertEquals(0, new BigDecimal("3000.00").compareTo(last));
    }

    @Test
    void rothSeedConvertedToGbpAndAppearsOnRothLine() {
        var cf = new JdbcConnectionFactory(dbDir);
        var repo = new CashTransactionRepository(cf, new KeyValueStore(dbDir));
        // Give RothIRA a transaction so earliestTransactionDate returns something.
        repo.saveRothIra(java.util.List.of(
                new CashTransaction("2020-01-01", Account.ROTH_IRA, TransactionType.DIVIDEND,
                        "AAPL", 0.0, 10.0, "USD", 1.25, 8.0, 10.0, 8.0, "div")),
                BigDecimal.ZERO);

        var kv = new KeyValueStore(dbDir);
        // Seed 1250 USD, FX = 1.25 USD/GBP → 1000 GBP
        kv.putBigDecimal(CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, new BigDecimal("1250"));

        ContributionTimeline t = new ContributionService(repo, kv, USD_1_25).timeline();
        AccountLine roth = t.lines().stream().filter(l -> l.label().equals("Roth IRA")).findFirst().orElseThrow();
        assertFalse(roth.points().isEmpty());
        assertEquals(0, new BigDecimal("1000.00").compareTo(roth.points().get(0).cumulativeGbp()));
    }

    @Test
    void nonContributionTypesAreExcluded() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        var repo = new CashTransactionRepository(cf, kv);
        // Only a dividend (via II which has no gap-check on first import) — should not appear
        repo.saveII(java.util.List.of(
                new CashTransaction("2023-01-01", Account.II, TransactionType.DIVIDEND,
                        "BP", 0.0, 50.0, "GBP", 1.0, 50.0, 50.0, 50.0, "div")));

        ContributionTimeline t = new ContributionService(repo, kv, NO_FX).timeline();
        assertTrue(t.lines().isEmpty());
    }
}
