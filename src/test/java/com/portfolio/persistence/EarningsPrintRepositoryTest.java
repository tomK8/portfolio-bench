package com.portfolio.persistence;

import com.portfolio.persistence.EarningsPrintRepository.ConsensusCapture;
import com.portfolio.persistence.EarningsPrintRepository.PrintDetail;
import com.portfolio.persistence.EarningsPrintRepository.PriorPrint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EarningsPrintRepositoryTest {

    private static final LocalDate PRINT = LocalDate.parse("2026-11-02");

    private static EarningsPrintRepository repo(Path dir) {
        return new EarningsPrintRepository(new JdbcConnectionFactory(dir));
    }

    private static PrintDetail only(EarningsPrintRepository repo) {
        Map<String, PrintDetail> all = repo.details(List.of("ZZZA"),
                PRINT.minusDays(5), PRINT.plusDays(5));
        return all.get("ZZZA|" + PRINT);
    }

    @Test
    void thePrePrintConsensusSurvivesTheWriteThatBringsTheReportedFigures(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.saveConsensus("ZZZA", PRINT, 1.98, 112_500_000_000.0, "USD");
        // Days later the revenue arrives, and that write knows nothing about the consensus. If it
        // replaced the row, the one figure no feed can hand back would be gone for good.
        repo.saveReported("ZZZA", PRINT, "Sep 2026", 114_477_000_000.0, "USD");

        PrintDetail d = only(repo);
        assertEquals(112_500_000_000.0, d.revenueConsensus(), 1e-6);
        assertEquals(114_477_000_000.0, d.revenueActual(), 1e-6);
        assertEquals("Sep 2026", d.fiscalQuarter());
        assertEquals(1.98, d.epsConsensus(), 1e-9);
        assertNotNull(d.consensusCapturedAt());
    }

    @Test
    void whetherAConsensusWasReadBeforeThePrintIsJudgedOnTheDayItWasRead() {
        assertTrue(new PrintDetail("ZZZA", PRINT, null, null, 1.0, "USD", null,
                java.time.Instant.parse("2026-11-01T23:59:00Z"), null, null)
                .consensusIsPrePrint());
        assertFalse(new PrintDetail("ZZZA", PRINT, null, null, 1.0, "USD", null,
                java.time.Instant.parse("2026-11-02T00:01:00Z"), null, null)
                .consensusIsPrePrint());
        assertFalse(new PrintDetail("ZZZA", PRINT, null, null, 1.0, "USD", null, null, null, null)
                .consensusIsPrePrint());
    }

    @Test
    void aSecondConsensusWriteDoesNotRestampWhenItWasFirstRead(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.saveConsensus("ZZZA", PRINT, 1.98, 112_500_000_000.0, "USD");
        java.time.Instant first = only(repo).consensusCapturedAt();
        repo.saveConsensus("ZZZA", PRINT, 1.98, 112_500_000_000.0, "USD");
        assertEquals(first, only(repo).consensusCapturedAt());
    }

    @Test
    void anAttemptMarkerDoesNotDisturbTheFiguresAlreadyStored(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.saveConsensus("ZZZA", PRINT, 1.98, 112_500_000_000.0, "USD");
        repo.markAttempted("ZZZA", PRINT, LocalDate.parse("2026-11-03"));
        PrintDetail d = only(repo);
        assertEquals(112_500_000_000.0, d.revenueConsensus(), 1e-6);
        assertEquals(LocalDate.parse("2026-11-03"), d.attemptedOn());
    }

    @Test
    void aKnownSessionIsNotErasedByALaterReadThatCouldNotEstablishOne(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.savePriorPrints(List.of(new PriorPrint("ZZZA", PRINT, "Sep 2026", 2.05, 1.98, "AMC")));
        repo.savePriorPrints(List.of(new PriorPrint("ZZZA", PRINT, "Sep 2026", 2.05, 1.98, null)));
        assertEquals("AMC", repo.priorPrints(List.of("ZZZA")).get("ZZZA").get(0).session());
    }

    @Test
    void previousPrintsAccumulateAcrossQuartersRatherThanStayingFourDeep(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.savePriorPrints(List.of(
                new PriorPrint("ZZZA", LocalDate.parse("2026-01-29"), "Dec 2025", 2.84, 2.65, "AMC"),
                new PriorPrint("ZZZA", LocalDate.parse("2026-04-30"), "Mar 2026", 2.01, 1.92, "AMC")));
        repo.savePriorPrints(List.of(
                new PriorPrint("ZZZA", LocalDate.parse("2026-07-30"), "Jun 2026", 1.91, 1.88, "AMC"),
                new PriorPrint("ZZZA", PRINT, "Sep 2026", 2.05, 1.98, "AMC")));
        List<PriorPrint> all = repo.priorPrints(List.of("ZZZA")).get("ZZZA");
        assertEquals(4, all.size());
        assertEquals(PRINT, all.get(0).reported());   // newest first
    }

    @Test
    void consensusReadingsAreKeptPerDayAndPerPeriod(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        repo.saveCaptures(List.of(
                new ConsensusCapture("ZZZA", LocalDate.parse("2026-10-30"), "Q", "Dec 2026", 2.80, 6, 1, 0),
                new ConsensusCapture("ZZZA", LocalDate.parse("2026-11-03"), "Q", "Dec 2026", 2.94, 6, 3, 1),
                new ConsensusCapture("ZZZA", LocalDate.parse("2026-11-03"), "Y", "Sep 2027", 9.56, 11, 3, 2)));
        // Re-reading the same day replaces that day's row rather than adding a second.
        repo.saveCaptures(List.of(
                new ConsensusCapture("ZZZA", LocalDate.parse("2026-11-03"), "Q", "Dec 2026", 2.95, 7, 4, 1)));
        List<ConsensusCapture> all = repo.captures(List.of("ZZZA")).get("ZZZA");
        assertEquals(3, all.size());
        assertEquals(LocalDate.parse("2026-10-30"), all.get(0).capturedOn());   // oldest first
        assertEquals(2.95, all.stream()
                .filter(c -> c.capturedOn().equals(LocalDate.parse("2026-11-03")) && c.periodKind().equals("Q"))
                .findFirst().orElseThrow().consensus(), 1e-9);
    }

    @Test
    void readsDegradeToEmptyRatherThanThrowing(@TempDir Path dir) {
        EarningsPrintRepository repo = repo(dir);
        assertTrue(repo.details(List.of(), PRINT, PRINT).isEmpty());
        assertTrue(repo.priorPrints(List.of()).isEmpty());
        assertTrue(repo.captures(List.of()).isEmpty());
    }
}
