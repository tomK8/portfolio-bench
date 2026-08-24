package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentsTest {

    @Test
    void giltPrefixIsRecognised() {
        assertTrue(Instruments.isBond("GILT 0.875% 2033"));
        assertTrue(Instruments.isBond("GILT 2025"), "GILT prefix alone is enough");
    }

    @Test
    void couponMarkerIsRecognised() {
        assertTrue(Instruments.isBond("TREASURY 2.75% 2024"),
                "any string containing % counts — defensive catch-all for unnormalised bond rows");
    }

    @Test
    void equitiesAndCashAreNotBonds() {
        assertFalse(Instruments.isBond("NVDA"));
        assertFalse(Instruments.isBond("CASH"));
        assertFalse(Instruments.isBond("BRK.B"));
    }

    @Test
    void nullIsNotABond() {
        assertFalse(Instruments.isBond(null));
    }

    // ---- asset class (the drift term in the open-orders touch model) -------

    @Test
    void giltsAndGiltFundsCarryNoDrift() {
        assertEquals(AssetClass.GILT, Instruments.assetClassOf("GILT 4.75% 2038"));
        assertEquals(AssetClass.GILT, Instruments.assetClassOf("TREASURY 2.75% 2024"));
        // A gilt wrapped in an ETF is still a gilt — isBond only sees the direct holdings.
        assertEquals(AssetClass.GILT, Instruments.assetClassOf("VGOV"));
        assertEquals(0.0, AssetClass.GILT.annualDrift());
    }

    @Test
    void bullionEtcsCarryNoDrift() {
        assertEquals(AssetClass.COMMODITY, Instruments.assetClassOf("SGLN"));
        assertEquals(AssetClass.COMMODITY, Instruments.assetClassOf("sgln"), "case-insensitive");
        assertEquals(AssetClass.COMMODITY, Instruments.assetClassOf("PHGP"));
        assertEquals(0.0, AssetClass.COMMODITY.annualDrift());
    }

    @Test
    void anythingElseIsAnEquityAndNothingStatedIsUnknown() {
        assertEquals(AssetClass.EQUITY, Instruments.assetClassOf("NVDA"));
        assertEquals(AssetClass.EQUITY, Instruments.assetClassOf("LSEG"));
        // No fund list is maintained: an equity ETF lands on EQUITY, which carries the same drift.
        assertEquals(AssetClass.EQUITY, Instruments.assetClassOf("EQQQ"));
        assertEquals(AssetClass.EQUITY.annualDrift(), AssetClass.ETF.annualDrift());

        assertEquals(AssetClass.UNKNOWN, Instruments.assetClassOf(null));
        assertEquals(AssetClass.UNKNOWN, Instruments.assetClassOf("  "));
        assertEquals(0.0, AssetClass.UNKNOWN.annualDrift(), "nothing stated can never inject drift");
    }

    @Test
    void driftConvertsToTheSameSessionClockTheVolatilityScalingUses() {
        assertEquals(0.06, AssetClass.EQUITY.annualDrift());
        // Carried at 10 dp: enough that the rounding is ~2e-7 of the figure (invisible in any
        // probability) and few enough digits that a calibration line stays readable.
        assertEquals(0.06 / 252.0, AssetClass.EQUITY.dailyDrift().doubleValue(), 1e-9);
        assertEquals(252.0, AssetClass.SESSIONS_PER_YEAR, "σ scales by √252; drift divides by 252");
    }

    @Test
    void everyZeroSectorOverrideInSectorsPropertiesIsAlsoZeroDrift() throws Exception {
        // Two lists of the same judgement is how they drift apart. sectors.properties is where a
        // bullion ETC or a gilt fund gets declared for the allocation view; if one is added there
        // and forgotten in Instruments, it would silently start carrying an equity's drift.
        Properties p = new Properties();
        try (InputStream in = InstrumentsTest.class.getResourceAsStream("/sectors.properties")) {
            p.load(in);
        }
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("override.")) continue;
            String bucket = p.getProperty(key).trim();
            if (!bucket.equals("COMMODITIES") && !bucket.equals("FIXED_INCOME")) continue;
            String symbol = key.substring("override.".length());
            assertEquals(0.0, Instruments.assetClassOf(symbol).annualDrift(),
                    symbol + " is " + bucket + " in sectors.properties but carries drift as "
                            + Instruments.assetClassOf(symbol) + " — add it to Instruments");
        }
    }

    @Test
    void matchKeyCollapsesPunctuationSoAKeywordMatchesAPunctuatedName() {
        // The regression this exists for: the dashboard suggested "legal general group plc" for
        // "Legal & General Group PLC", stored it, and the parsers tested it against the raw
        // lowercased name — which does not contain it. Both sides go through here now.
        assertEquals("legal general group plc", Instruments.matchKey("Legal & General Group PLC"));
        assertTrue(Instruments.matchKey("Legal & General Group PLC")
                .contains(Instruments.matchKey("Legal & General Group PLC")));
        assertTrue(Instruments.matchKey("Legal & General Group PLC").contains("legal general"));

        assertEquals("b a e systems plc", Instruments.matchKey("B.A.E. Systems PLC"));
        assertEquals("smith nephew plc", Instruments.matchKey("Smith  &  Nephew   PLC"));
        assertEquals("", Instruments.matchKey(null));
        assertEquals("", Instruments.matchKey("  &&  "));
    }

    @Test
    void matchKeyIsIdempotent() {
        // Keywords are normalised on save and again on read; a second pass must not change them.
        for (String s : new String[]{"Legal & General Group PLC", "iShares Physical Gold ETC GBP",
                "HICL Infrastructure PLC Ord", "3M Co."}) {
            assertEquals(Instruments.matchKey(s), Instruments.matchKey(Instruments.matchKey(s)));
        }
    }
}
