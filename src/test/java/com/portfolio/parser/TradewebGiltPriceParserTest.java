package com.portfolio.parser;

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

    @Test
    void normaliseCouponStripsTrailingZerosButPreservesSignificantOnes() {
        assertEquals("3.75", TradewebGiltPriceParser.normaliseCoupon("3.750"));
        assertEquals("0.875", TradewebGiltPriceParser.normaliseCoupon("0.875"));
        assertEquals("1.5", TradewebGiltPriceParser.normaliseCoupon("1.500"));
        assertEquals("3", TradewebGiltPriceParser.normaliseCoupon("3.000"));
        assertEquals("4", TradewebGiltPriceParser.normaliseCoupon("4"));
    }
}
