package com.portfolio.domain;

import com.portfolio.domain.IndustryGroups.Group;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndustryGroupsTest {

    private static IndustryGroups groups() {
        return new IndustryGroups(
                Map.of("Banks - Diversified", "Banks",
                        "Banks - Regional", "Banks",
                        "Credit Services", "Payments"),
                Map.of("LGEN", "Insurance"));
    }

    // ---- the industry layer -------------------------------------------------

    @Test
    void severalYahooIndustriesCollapseIntoOneGroup() {
        assertEquals("Banks", groups().groupFor("HSBA", "Banks - Diversified").name());
        assertEquals("Banks", groups().groupFor("BNP", "Banks - Regional").name());
    }

    @Test
    void anIndustryMappingIsNotAnAssertionAboutTheSymbol() {
        // The point of keying on the industry: a name never seen before lands in the right group
        // with no edit, because the mapping asks the feed rather than a list of holdings.
        Group g = groups().groupFor("SOME-NEW-BANK", "Banks - Regional");
        assertEquals("Banks", g.name());
        assertFalse(g.bySymbol());
    }

    @Test
    void anUnmappedIndustryGetsNoGroupRatherThanAnOtherBin() {
        // Null is what makes the drill-down fall back to the raw industry, so a new industry
        // shows up as its own one-row group — the signal that a mapping is missing.
        assertNull(groups().groupFor("SHEL", "Oil & Gas Integrated"));
    }

    @Test
    void aMissingIndustryIsNotAGroup() {
        assertNull(groups().groupFor("XYZ", null));
        assertNull(groups().groupFor("XYZ", "   "));
    }

    // ---- the per-symbol layer -----------------------------------------------

    @Test
    void aPerSymbolOverrideBeatsTheIndustryAndSaysSo() {
        // Yahoo files LGEN under "Asset Management"; it is a life insurer. The flag is what lets
        // the UI mark the group as asserted rather than derived.
        Group g = groups().groupFor("LGEN", "Asset Management");
        assertEquals("Insurance", g.name());
        assertTrue(g.bySymbol());
    }

    @Test
    void anOverrideWinsEvenWhenTheIndustryItselfMapsElsewhere() {
        assertEquals("Insurance", groups().groupFor("LGEN", "Banks - Regional").name());
    }

    @Test
    void symbolLookupIsCaseAndWhitespaceInsensitive() {
        assertEquals("Insurance", groups().groupFor(" lgen ", "Asset Management").name());
    }

    // ---- slugging -----------------------------------------------------------

    @Test
    void oneKeyMatchesEveryPunctuationYahooUsesForTheSameIndustry() {
        // Yahoo has published the hyphen and the em dash for the same industry. A key that broke
        // on the difference would be a mapping that silently stopped applying.
        IndustryGroups g = groups();
        assertEquals("Banks", g.groupFor("X", "Banks - Diversified").name());
        assertEquals("Banks", g.groupFor("X", "Banks—Diversified").name());
        assertEquals("Banks", g.groupFor("X", "banks diversified").name());
        assertEquals("Banks", g.groupFor("X", "  Banks--Diversified  ").name());
    }

    @Test
    void sluggingStripsLeadingAndTrailingSeparators() {
        assertEquals("financial-data-stock-exchanges",
                IndustryGroups.slug("Financial Data & Stock Exchanges"));
        assertEquals("banks-regional", IndustryGroups.slug(" — Banks - Regional — "));
        assertEquals("", IndustryGroups.slug("&&&"));
    }

    // ---- the shipped file ---------------------------------------------------

    @Test
    void theShippedFileGroupsTheFinancialsBookAsConfigured() {
        IndustryGroups g = new IndustryGroups();
        assertEquals("Banks", g.groupFor("BNP", "Banks - Regional").name());
        assertEquals("Banks", g.groupFor("GLE", "Banks - Regional").name());
        assertEquals("Banks", g.groupFor("HSBA", "Banks - Diversified").name());
        assertEquals("Banks", g.groupFor("BAC", "Banks - Diversified").name());
        assertEquals("Insurance", g.groupFor("AV.", "Insurance - Diversified").name());
        assertEquals("Insurance", g.groupFor("BRK.B", "Insurance - Diversified").name());
        assertEquals("Payments", g.groupFor("MA", "Credit Services").name());
        assertEquals("Payments", g.groupFor("V", "Credit Services").name());
        assertEquals("Financial Data",
                g.groupFor("LSEG", "Financial Data & Stock Exchanges").name());
    }

    @Test
    void theShippedFileOverridesLegalAndGeneralIntoInsurance() {
        Group g = new IndustryGroups().groupFor("LGEN", "Asset Management");
        assertEquals("Insurance", g.name());
        assertTrue(g.bySymbol(), "must be marked as asserted, so the raw column is worth reading");
    }

    @Test
    void theShippedFileLeavesNonFinancialIndustriesAlone() {
        // The mapping is scoped to the sector it was written for; everything else keeps grouping
        // by its raw Yahoo industry exactly as before.
        IndustryGroups g = new IndustryGroups();
        assertNull(g.groupFor("AAPL", "Consumer Electronics"));
        assertNull(g.groupFor("NVDA", "Semiconductors"));
    }
}
