package com.portfolio.parser;

import com.portfolio.domain.model.Holding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two AJ Bell accounts export the same filename pattern. Only the {@code Portfolio} column
 * tells them apart, and it is the one AJ Bell export that names its account at all — which is why
 * everything else routes against it.
 */
class AJBellDealingParserTest {

    private static final String HEADER = "\"Investment\",\"Quantity\",\"Price\",\"Value (£)\","
            + "\"Cost (£)\",\"Change (£)\",\"Change (%)\",\"Price +/- today (%)\","
            + "\"Valuation currency\",\"Market currency\",\"Exchange rate\",\"Date\",\"Time\","
            + "\"Portfolio\",\"Ticker\"\n";

    private static final String DEALING = HEADER
            + "\"Barclays PLC (LSE:BARC)\",\"7,133\",\"4.4645\",\"31,845.28\",\"0.00\","
            + "\"31,845.28\",\"0.00\",\"0.69\",\"GBP\",\"GBP\",\"1\",\"05-Oct-26\",\"13:36\","
            + "\"Dealing account\",\"BARC\"\n"
            + "\"Cash GBP\",\"0\",\"1\",\"0\",\"0\",\"0\",\"0\",\"0\",\"GBP\",\"GBP\",\"1\","
            + "\"05-Oct-26\",\"13:36\",\"Dealing account\"\n";

    private static final String SIPP = HEADER
            + "\"AstraZeneca PLC (LSE:AZN)\",\"30\",\"125.14\",\"3,754.20\",\"3,903.18\","
            + "\"-148.98\",\"-3.82\",\"-1.23\",\"GBP\",\"GBP\",\"1\",\"23-Sep-26\",\"11:04\","
            + "\"SIPP\",\"AZN\"\n";

    private Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    // --- supports(): the Portfolio column, not the filename ---

    @Test
    void eachParserClaimsOnlyItsOwnPortfolio(@TempDir Path dir) throws IOException {
        Path dealing = write(dir, "portfolio-A50742D-Dealing account.csv", DEALING);
        Path sipp = write(dir, "portfolio-A50742S-SIPP (66).csv", SIPP);

        assertTrue(new AJBellDealingParser().supports(dealing));
        assertFalse(new AJBellDealingParser().supports(sipp));
        assertTrue(new AJBellSippParser().supports(sipp));
        assertFalse(new AJBellSippParser().supports(dealing));
    }

    /** A rename must not move a file between accounts — the column decides, not the name. */
    @Test
    void theColumnOutranksTheFilename(@TempDir Path dir) throws IOException {
        Path misnamed = write(dir, "portfolio-SIPP-looks-like-the-other-one.csv", DEALING);
        assertTrue(new AJBellDealingParser().supports(misnamed));
        assertFalse(new AJBellSippParser().supports(misnamed));
    }

    /** Archived exports predate the second account; they can only be the SIPP's. */
    @Test
    void anExportNamingNoPortfolioFallsToTheSipp(@TempDir Path dir) throws IOException {
        Path old = write(dir, "portfolio.csv",
                "\"Investment\",\"Quantity\",\"Value (£)\",\"Cost (£)\",\"Valuation currency\","
                        + "\"Exchange rate\",\"Ticker\"\n"
                        + "\"AstraZeneca PLC\",\"30\",\"3754.20\",\"3903.18\",\"GBP\",\"1\",\"AZN\"\n");
        assertTrue(new AJBellSippParser().supports(old));
        assertFalse(new AJBellDealingParser().supports(old));
    }

    // --- the registry the other exports are routed against ---

    @Test
    void tickersInReadsTheHoldingsThatDecideRouting(@TempDir Path dir) throws IOException {
        assertEquals(Set.of("BARC"), AJBellPortfolioParser.tickersIn(
                write(dir, "portfolio-dealing.csv", DEALING)));
        assertEquals(Set.of("AZN"), AJBellPortfolioParser.tickersIn(
                write(dir, "portfolio-sipp.csv", SIPP)));
    }

    // --- a zero cost is not a cost of zero ---

    /**
     * AJ Bell reports no basis for shares transferred in in specie — it never saw the purchase.
     * Read literally, the whole market value books as gain: £31,845.28 of pure profit on a row
     * whose own Change (%) column says 0.00. Absent is never zero.
     */
    @Test
    void aZeroCostAgainstARealValueIsUnknownNotZero(@TempDir Path dir) throws Exception {
        List<Holding> holdings = new AJBellDealingParser()
                .parse(write(dir, "portfolio-dealing.csv", DEALING));

        Holding barc = holdings.stream().filter(h -> "BARC".equals(h.getSecurityId()))
                .findFirst().orElseThrow();
        assertNull(barc.getCostBasisGbp(), "a basis the broker does not have must read as unknown");
        assertNull(barc.getAvgPricePaid(), "and so must the price derived from it");
        assertEquals(0, new BigDecimal("31845.28").compareTo(barc.getCurrentMarketValueGbp()));
    }

    /** A real cost is still a real cost, and a zero-value cash row is still a fact. */
    @Test
    void aRealCostAndAZeroCashBalanceAreBothKept(@TempDir Path dir) throws Exception {
        Holding azn = new AJBellSippParser().parse(write(dir, "portfolio-sipp.csv", SIPP)).get(0);
        assertEquals(0, new BigDecimal("3903.18").compareTo(azn.getCostBasisGbp()));

        Holding cash = new AJBellDealingParser()
                .parse(write(dir, "portfolio-dealing.csv", DEALING)).stream()
                .filter(h -> "CASH".equals(h.getSecurityId())).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(cash.getCostBasisGbp()));
    }

    @Test
    void sourceNamesMatchTheAccountDisplayNames(@TempDir Path dir) throws Exception {
        assertEquals("AJ Bell Dealing", new AJBellDealingParser().sourceName());
        assertEquals("AJ Bell Dealing", new AJBellDealingParser()
                .parse(write(dir, "portfolio-dealing.csv", DEALING)).get(0).getSource());
    }
}
