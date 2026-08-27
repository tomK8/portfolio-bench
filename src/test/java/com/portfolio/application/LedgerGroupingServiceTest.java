package com.portfolio.application;

import com.portfolio.application.LedgerGroupingService.GroupBy;
import com.portfolio.domain.SectorClassifier;
import com.portfolio.domain.grouping.HoldingGroup;
import com.portfolio.domain.model.AggHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerGroupingServiceTest {

    /** Sector is the only option that reads the repository, and these cases never select it. */
    private final LedgerGroupingService service =
            new LedgerGroupingService(new SectorGroupKeyProvider(new SectorClassifier(), null));

    private static AggHolding held(String symbol, String sources, String valueGbp) {
        return new AggHolding(symbol, BigDecimal.ONE, null, new BigDecimal(valueGbp),
                BigDecimal.TEN, null, BigDecimal.ZERO, BigDecimal.TEN, null,
                Currency.getInstance("GBP"), sources, null, null, null, null);
    }

    @Test
    void parsesEachOptionById() {
        assertEquals(GroupBy.NONE, service.parse("none"));
        assertEquals(GroupBy.SECTOR, service.parse("sector"));
        assertEquals(GroupBy.ACCOUNT, service.parse("account"));
        assertEquals(GroupBy.ACCOUNT, service.parse("  ACCOUNT "));
    }

    /** A hand-edited request must show the portfolio, not an error page. */
    @Test
    void anAbsentOrUnknownSelectionDegradesToUngrouped() {
        assertEquals(GroupBy.NONE, service.parse(null));
        assertEquals(GroupBy.NONE, service.parse(""));
        assertEquals(GroupBy.NONE, service.parse("by-astrological-sign"));
    }

    /**
     * NONE returns no groups, which the fragment reads as "render the flat table" — so the
     * ungrouped view stays the markup it has always been rather than a one-group special case.
     */
    @Test
    void noneProducesNoGroups() {
        assertTrue(service.group(List.of(held("AAA", "Roth IRA", "100")), GroupBy.NONE).isEmpty());
    }

    @Test
    void emptyOrNullHoldingsProduceNoGroups() {
        assertTrue(service.group(List.of(), GroupBy.ACCOUNT).isEmpty());
        assertTrue(service.group(null, GroupBy.ACCOUNT).isEmpty());
    }

    @Test
    void accountGroupingRunsEndToEnd() {
        List<HoldingGroup> groups = service.group(List.of(
                held("AAA", "Roth IRA", "100"),
                held("BBB", "Roth IRA", "300"),
                held("CCC", "II SIPP", "250")), GroupBy.ACCOUNT);

        assertEquals(2, groups.size());
        assertEquals("Roth IRA", groups.get(0).label());   // largest first
        assertEquals(0, new BigDecimal("400").compareTo(groups.get(0).marketValueGbp()));
    }

    /** The dropdown is generated from this list, so a new key type needs no template edit. */
    @Test
    void optionsCoverEveryDeclaredKeyTypeInOrder() {
        assertEquals(List.of(GroupBy.NONE, GroupBy.SECTOR, GroupBy.ACCOUNT), service.options());
        assertEquals(List.of("none", "sector", "account"),
                service.options().stream().map(GroupBy::id).toList());
    }
}
