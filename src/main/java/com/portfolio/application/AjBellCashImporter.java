package com.portfolio.application;

import com.portfolio.adapter.AjBellFileRouter;
import com.portfolio.domain.model.Account;
import com.portfolio.parser.AJBellCashStatementParser;
import com.portfolio.persistence.CashTransactionRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AJ Bell {@code cashstatements*.csv}, one ledger per AJ Bell account.
 *
 * <p><b>The statement does not say which account it is.</b> Two accounts behind one login export
 * the same filename with the same header and no account number in it; the browser suffixes the
 * second download, so {@code cashstatements.csv} and {@code cashstatements (1).csv} are one of each
 * in an order nothing records. Taking the most recent file — what this did while one AJ Bell
 * account existed — would file whichever was downloaded last against the SIPP and lose the other
 * entirely.
 *
 * <p>So each candidate is routed by {@link AjBellFileRouter} on the instruments it names, and the
 * newest statement <em>per account</em> is imported. A statement that cannot be attributed is
 * {@link ImportCashResult#refused refused}: left on disk, reported, not read. Guessing is the one
 * unacceptable outcome — the AJ Bell dedup key is scoped per account, so a statement filed against
 * the wrong ledger corrupts two accounts at once and no later import repairs it.
 */
public class AjBellCashImporter extends AbstractCashImporter {

    private static final String ARCHIVE_PREFIX = "cashstatements";

    private final AJBellCashStatementParser parser;
    private final AjBellFileRouter router;

    /** Committed symbol rules only, and no routing — the single-AJ-Bell-account form, for tests. */
    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo) {
        this(archiveDir, repo, Map::of, null);
    }

    /**
     * @param aliasSource user-added description-keyword → symbol mappings, consulted before the
     *                    parser's committed rules so a name mapped on the dashboard is picked up by
     *                    the next import.
     */
    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo,
                              Supplier<Map<String, String>> aliasSource) {
        this(archiveDir, repo, aliasSource, null);
    }

    /**
     * @param router decides which AJ Bell account each statement belongs to; null imports the most
     *               recent statement as the SIPP's, which is only correct with one AJ Bell account
     */
    public AjBellCashImporter(Path archiveDir, CashTransactionRepository repo,
                              Supplier<Map<String, String>> aliasSource, AjBellFileRouter router) {
        super(archiveDir, repo);
        this.parser = new AJBellCashStatementParser(Account.AJBELL, aliasSource);
        this.router = router;
    }

    @Override
    public List<ImportCashResult> importFrom(Path inputDir) {
        if (router == null) {
            return mostRecent(inputDir, ARCHIVE_PREFIX + "*.csv")
                    .map(file -> importOne(Account.AJBELL, file))
                    .map(List::of)
                    .orElse(List.of(ImportCashResult.notFound(Account.AJBELL.displayName())));
        }

        AjBellFileRouter.Selection selection = router.select(
                inputDir, AJBellCashStatementParser::isAjBellCashFile, parser::symbolsIn);

        List<ImportCashResult> results = new ArrayList<>();
        for (Account account : AjBellFileRouter.accounts()) {
            Path file = selection.byAccount().get(account);
            results.add(file == null
                    ? ImportCashResult.notFound(account.displayName())
                    : importOne(account, file));
        }
        for (AjBellFileRouter.Unplaceable u : selection.unplaceable()) {
            results.add(ImportCashResult.refused("AJ Bell",
                    u.file().getFileName().toString(), u.warning()));
        }
        return results;
    }

    private ImportCashResult importOne(Account account, Path file) {
        AJBellCashStatementParser forAccount = parser.forAccount(account);
        int inserted = repo.saveAjBell(parse(forAccount, file));
        return archiveOrDelete(account.displayName(), file, inserted, archivePrefix(account));
    }

    /**
     * The SIPP keeps the bare {@code cashstatements_<date>.csv} archive name it has always had —
     * years of archived statements are already under it — and a second account gets its own, so two
     * statements archived on the same day cannot overwrite one another ({@code archiveOrDelete}
     * replaces an existing file of the same name).
     */
    private static String archivePrefix(Account account) {
        return account == Account.AJBELL
                ? ARCHIVE_PREFIX
                : ARCHIVE_PREFIX + "-" + account.dbValue().toLowerCase();
    }
}
