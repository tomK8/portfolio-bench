package com.portfolio.persistence;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * II back-dates rows into a day already imported, which shifts the Running Balance of everything
 * at or after them. Reproduces the live 2026-08-25 sequence that made the ledger report £20,631.74
 * of II cash against a real £718.38.
 */
class CashTransactionRepositoryIiBalanceTest {

    @TempDir
    Path dbDir;

    private CashTransactionRepository repo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private static CashTransaction ii(String date, TransactionType type, String symbol,
                                      double amount, Double balance) {
        return new CashTransaction(date, Account.II, type, symbol, 0.0, amount, "GBP", 1.0,
                amount, balance, balance, symbol + " " + type);
    }

    private static double iiGbp(CashTransactionRepository r) {
        return r.latestCashBalances().stream()
                .filter(b -> b.accountDbValue().equals("II") && b.currency().equals("GBP"))
                .mapToDouble(CashTransactionRepository.CashBalance::cashGbp).sum();
    }

    /**
     * First export: the gilt purchase has settled but that day's interest credit has not posted,
     * so the purchase closes the day at £691.93.
     */
    private static List<CashTransaction> firstExport() {
        return List.of(
                ii("2026-08-20", TransactionType.CHARGE, "XWIS", -2.99, 20_605.29),
                ii("2026-08-25", TransactionType.CHARGE, "GILT 4.375% 2030", 23.04, 691.93));
    }

    /**
     * Second export, two days later: the interest credit has appeared <em>before</em> the purchase
     * in the day's order, so every balance from it onward has moved — the purchase now closes at
     * £718.38 and the account's true balance is that.
     */
    private static List<CashTransaction> secondExport() {
        return List.of(
                ii("2026-08-20", TransactionType.CHARGE, "XWIS", -2.99, 20_605.29),
                ii("2026-08-25", TransactionType.INTEREST, "GBP", 26.45, 20_631.74),
                ii("2026-08-25", TransactionType.CHARGE, "GILT 4.375% 2030", 23.04, 718.38));
    }

    @Test
    void aBackDatedRowDoesNotBecomeTheReportedBalance() {
        var r = repo();
        r.saveII(firstExport());
        assertEquals(691.93, iiGbp(r), 1e-9);

        r.saveII(secondExport());

        // The interest row is inserted last and so has the highest rowid, but it is the *earlier*
        // row in the day. Reporting it would overstate the account by the whole gilt purchase.
        assertEquals(718.38, iiGbp(r), 1e-9);
    }

    /** A matched row must take the file's newer balance rather than keeping its stale one. */
    @Test
    void reImportRefreshesAStoredRowsBalance() {
        var r = repo();
        r.saveII(firstExport());
        r.saveII(secondExport());

        double stored = r.loadAllTransactions().stream()
                .filter(t -> t.type() == TransactionType.CHARGE
                        && "GILT 4.375% 2030".equals(t.symbol()))
                .mapToDouble(t -> t.cashBalanceGbp() == null ? 0 : t.cashBalanceGbp()).sum();
        assertEquals(718.38, stored, 1e-9,
                "the purchase's balance shifted when the interest credit landed before it");
    }

    @Test
    void reImportingAnUnchangedExportInsertsNothing() {
        var r = repo();
        r.saveII(secondExport());
        assertEquals(0, r.saveII(secondExport()));
        assertEquals(718.38, iiGbp(r), 1e-9);
    }

    /** Each currency's ledger is tracked separately inside the shared II account. */
    @Test
    void gbpAndUsdBalancesAreKeptApart() {
        var r = repo();
        r.saveII(List.of(
                ii("2026-08-25", TransactionType.INTEREST, "GBP", 26.45, 718.38),
                new CashTransaction("2026-08-25", Account.II, TransactionType.INTEREST, "USD",
                        0.0, 12.0, "USD", 1.35, 8.89, 74_739.15, 55_362.33, "usd interest")));

        assertEquals(718.38, iiGbp(r), 1e-9);
        var usd = r.latestCashBalances().stream()
                .filter(b -> b.accountDbValue().equals("II") && b.currency().equals("USD"))
                .findFirst().orElseThrow();
        assertEquals(74_739.15, usd.cashNative(), 1e-9);
    }
}
