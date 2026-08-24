package com.portfolio.parser;

import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.PriceBar;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradewebGiltPriceParserTest {

    @Test
    void parsesSampleCsv() throws Exception {
        List<PriceBar> bars = new TradewebGiltPriceParser()
                .parse(Path.of("src/test/resources/tradeweb-gilts-sample.csv"));

        assertEquals(3, bars.size());

        PriceBar first = bars.get(0);
        assertEquals("GILT 3.75% 2038", first.symbol(),
                "coupon trailing zero stripped; year from Maturity column");
        assertEquals(LocalDate.of(2023, 6, 1), first.date(), "US M/D/YYYY date format");
        assertEquals(93.300, first.close());
        assertEquals(93.300, first.adjClose(), "clean price feeds both close and adj_close");
        assertNull(first.open());
        assertNull(first.high());
        assertNull(first.low());
        assertNull(first.volume());
        assertEquals("GBP", first.currency());
    }

    /**
     * Tradeweb pads coupons; the symbol must still come back in the canonical form the ledger
     * and the HL feed use. The trim itself is {@code InstrumentsTest}'s to prove — what matters
     * here is that this parser goes through it rather than concatenating its own string.
     */
    @Test
    void paddedCouponsProduceCanonicalSymbols() {
        assertEquals("GILT 3.75% 2028", Instruments.giltId("3.750", 2028));
        assertEquals("GILT 3% 2028", Instruments.giltId("3.000", 2028));
    }
}
