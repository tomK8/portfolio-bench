package com.portfolio.adapter;

import com.portfolio.domain.model.Account;
import com.portfolio.parser.AJBellOrdersParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two AJ Bell accounts behind one login export files identical in name, header and format. These
 * are the two things that has to not break: the right account, and <em>both</em> accounts.
 */
class AjBellFileRouterTest {

    private static final String HOLDINGS_HEADER = "\"Investment\",\"Quantity\",\"Price\","
            + "\"Value (£)\",\"Cost (£)\",\"Change (£)\",\"Change (%)\",\"Price +/- today (%)\","
            + "\"Valuation currency\",\"Market currency\",\"Exchange rate\",\"Date\",\"Time\","
            + "\"Portfolio\",\"Ticker\"\n";

    private static final String ORDERS_HEADER = "\"Investment\",\"Reference\",\"Type\","
            + "\"Order type\",\"Quantity\",\"Amount\",\"Price\",\"Date entered\",\"Expiry date\","
            + "\"Status\"\n";

    private static final String SIPP_ORDERS = ORDERS_HEADER
            + "\"London Stock Exchange Group PLC\",\"44626CJX676\",\"Sell\",\"Limit\",\"50\","
            + "\"4494.5\",\"8996p\",\"09/09/2026\",\"08/12/2026\",\"In progress\"\n"
            + "\"British Land Co PLC\",\"44626CK840N\",\"Sell\",\"Limit\",\"1150\",\"4820.75\","
            + "\"419.5p\",\"25/09/2026\",\"24/12/2026\",\"In progress\"\n";

    private static final String DEALING_ORDERS = ORDERS_HEADER
            + "\"Barclays PLC\",\"44626CLF8NN\",\"Sell\",\"Limit\",\"2400\",\"11963.8\",\"498.7p\","
            + "\"30/09/2026\",\"29/12/2026\",\"In progress\"\n";

    @TempDir
    Path dir;

    private AjBellFileRouter router;
    private AJBellOrdersParser names;

    @BeforeEach
    void setUp() throws IOException {
        router = new AjBellFileRouter(new HoldingFileLocator(dir));
        names = new AJBellOrdersParser();
        holdings("portfolio-A50742S-SIPP (66).csv", "SIPP", "LSEG", "BLND");
        holdings("portfolio-A50742D-Dealing account.csv", "Dealing account", "BARC");
    }

    private void holdings(String name, String portfolio, String... tickers) throws IOException {
        StringBuilder sb = new StringBuilder(HOLDINGS_HEADER);
        for (String t : tickers) {
            sb.append("\"").append(t).append(" PLC (LSE:").append(t).append(")\",\"100\",\"1\","
                    + "\"100\",\"90\",\"10\",\"1\",\"0\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\","
                    + "\"13:36\",\"").append(portfolio).append("\",\"").append(t).append("\"\n");
        }
        Files.writeString(dir.resolve(name), sb.toString());
    }

    private Path orders(String name, String content, long mtime) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));
        return file;
    }

    @Test
    void theRegistryIsReadFromEachAccountsOwnHoldingsExport() {
        assertEquals(Set.of("LSEG", "BLND"), router.registry().get(Account.AJBELL));
        assertEquals(Set.of("BARC"), router.registry().get(Account.AJBELL_DEALING));
    }

    @Test
    void anOrdersFileRoutesToTheAccountHoldingWhatItNames() throws IOException {
        Path sipp = orders("activeorders (68).csv", SIPP_ORDERS, 1_000_000L);
        Path dealing = orders("activeorders (69).csv", DEALING_ORDERS, 2_000_000L);

        assertEquals(Account.AJBELL, router.accountFor(sipp, names::symbolsIn));
        assertEquals(Account.AJBELL_DEALING, router.accountFor(dealing, names::symbolsIn));
    }

    /**
     * The regression this exists for. "Newest file wins" was correct with one AJ Bell account; with
     * two it hands the dealing account's export to the SIPP and drops the SIPP's twelve resting
     * orders with no error anywhere. The dealing file here is deliberately the newer of the two.
     */
    @Test
    void thenewerAccountsFileDoesNotHideTheOthers() throws IOException {
        orders("activeorders (68).csv", SIPP_ORDERS, 1_000_000L);
        orders("activeorders (69).csv", DEALING_ORDERS, 2_000_000L);

        var selection = router.select(dir, AJBellOrdersParser::isAjBellOrdersFile, names::symbolsIn);

        assertEquals("activeorders (68).csv",
                selection.byAccount().get(Account.AJBELL).getFileName().toString());
        assertEquals("activeorders (69).csv",
                selection.byAccount().get(Account.AJBELL_DEALING).getFileName().toString());
        assertTrue(selection.unplaceable().isEmpty());
    }

    @Test
    void theNewestFilePerAccountWinsWithinThatAccount() throws IOException {
        orders("activeorders (60).csv", SIPP_ORDERS, 1_000_000L);
        orders("activeorders (68).csv", SIPP_ORDERS, 3_000_000L);
        orders("activeorders (69).csv", DEALING_ORDERS, 2_000_000L);

        var selection = router.select(dir, AJBellOrdersParser::isAjBellOrdersFile, names::symbolsIn);
        assertEquals("activeorders (68).csv",
                selection.byAccount().get(Account.AJBELL).getFileName().toString());
        assertEquals("activeorders (69).csv",
                selection.byAccount().get(Account.AJBELL_DEALING).getFileName().toString());
    }

    @Test
    void anUnplaceableNewFileIsReportedRatherThanGuessedAt() throws IOException {
        orders("activeorders (68).csv", SIPP_ORDERS, 1_000_000L);
        Path odd = orders("activeorders (70).csv", ORDERS_HEADER
                + "\"Tesla Inc\",\"REF\",\"Buy\",\"Limit\",\"1\",\"100\",\"100p\",\"01/10/2026\","
                + "\"01/01/2027\",\"In progress\"\n", 9_000_000L);

        var selection = router.select(dir, AJBellOrdersParser::isAjBellOrdersFile, names::symbolsIn);
        assertNull(router.accountFor(odd, names::symbolsIn));
        assertEquals(1, selection.unplaceable().size());
        assertEquals(odd, selection.unplaceable().get(0).file());
        assertTrue(selection.warnings().get(0).contains("activeorders (70).csv"));
    }

    /**
     * An input directory holds dozens of archived exports naming positions long since closed. Those
     * are unroutable and uninteresting; only a file newer than something that <em>could</em> be
     * placed is the "you just downloaded something I can't account for" signal.
     */
    @Test
    void anOldUnplaceableFileIsNotWorthMentioning() throws IOException {
        orders("activeorders (1).csv", ORDERS_HEADER
                + "\"Tesla Inc\",\"REF\",\"Buy\",\"Limit\",\"1\",\"100\",\"100p\",\"01/01/2020\","
                + "\"01/01/2021\",\"In progress\"\n", 500_000L);
        orders("activeorders (68).csv", SIPP_ORDERS, 1_000_000L);
        orders("activeorders (69).csv", DEALING_ORDERS, 2_000_000L);

        var selection = router.select(dir, AJBellOrdersParser::isAjBellOrdersFile, names::symbolsIn);
        assertTrue(selection.unplaceable().isEmpty());
    }

    /** The alarm the user asked for: one export naming both accounts' holdings is read as neither. */
    @Test
    void anExportNamingBothAccountsHoldingsIsRefused() throws IOException {
        Path both = orders("activeorders (71).csv", ORDERS_HEADER
                + "\"Barclays PLC\",\"A\",\"Sell\",\"Limit\",\"1\",\"1\",\"1p\",\"01/10/2026\","
                + "\"01/01/2027\",\"In progress\"\n"
                + "\"London Stock Exchange Group PLC\",\"B\",\"Sell\",\"Limit\",\"1\",\"1\",\"1p\","
                + "\"01/10/2026\",\"01/01/2027\",\"In progress\"\n", 9_000_000L);

        assertNull(router.accountFor(both, names::symbolsIn));
        var selection = router.select(dir, AJBellOrdersParser::isAjBellOrdersFile, names::symbolsIn);
        assertEquals(1, selection.unplaceable().size());
        assertTrue(selection.unplaceable().get(0).reason().contains("AJ Bell SIPP holds LSEG"));
        assertTrue(selection.unplaceable().get(0).reason().contains("AJ Bell Dealing holds BARC"));
    }

    /** The registry is part of the cache key: a new holdings export must re-decide old files. */
    @Test
    void aChangedHoldingsExportReRoutesFilesAlreadySeen() throws IOException {
        Path file = orders("activeorders (69).csv", DEALING_ORDERS, 2_000_000L);
        assertEquals(Account.AJBELL_DEALING, router.accountFor(file, names::symbolsIn));

        // BARC moves to the SIPP: the same file must now route the other way, not stay cached.
        holdings("portfolio-A50742S-SIPP (67).csv", "SIPP", "LSEG", "BARC");
        holdings("portfolio-A50742D-Dealing account.csv", "Dealing account", "AZN");
        assertEquals(Account.AJBELL, router.accountFor(file, names::symbolsIn));
    }
}
