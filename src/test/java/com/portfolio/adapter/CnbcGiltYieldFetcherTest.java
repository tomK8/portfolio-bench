package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.portfolio.domain.model.GiltYield;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CnbcGiltYieldFetcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<GiltYield> sample() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/cnbc-gilt-yields-sample.json"));
        return CnbcGiltYieldFetcher.parse(MAPPER.readTree(json));
    }

    private static GiltYield tenor(int years) throws Exception {
        return sample().stream().filter(y -> y.tenorYears() == years).findFirst().orElseThrow();
    }

    @Test
    void keepsEveryGiltTenorThatCarriesAYield() throws Exception {
        // Six quotes: the 20-year has a blank yield, the 30-year is an unknown symbol (code 1)
        // and US10Y is not a gilt tenor — none of them may come back as a row.
        assertEquals(List.of(2, 5, 10), sample().stream().map(GiltYield::tenorYears).toList());
    }

    @Test
    void readsTheYieldAsPercentWithItsPrecision() throws Exception {
        assertEquals(0, new BigDecimal("4.25").compareTo(tenor(2).yieldPct()));
        assertEquals(0, new BigDecimal("4.235").compareTo(tenor(2).previousClosePct()));
    }

    /**
     * The move comes from the two yields. The 10-year's {@code change_pct} is {@code +0.0800%}
     * on a day its yield fell — it is the bond's price change — so reading it would flip the sign.
     */
    @Test
    void theMoveIsRecomputedFromTheYieldsNeverReadFromChangePct() throws Exception {
        assertEquals(0, new BigDecimal("1.5").compareTo(tenor(2).changeBp()));
        assertEquals(0, new BigDecimal("-1").compareTo(tenor(10).changeBp()));
    }

    @Test
    void aBlankPreviousCloseIsUnknownNotAFlatDay() throws Exception {
        assertNull(tenor(5).previousClosePct());
        assertNull(tenor(5).changeBp());
    }

    @Test
    void timestampsArriveInMoreThanOneZoneAndAllBecomeInstants() throws Exception {
        assertEquals(Instant.parse("2026-03-02T16:15:00Z"), tenor(2).asOf());   // -0500
        assertEquals(Instant.parse("2026-03-02T16:30:00Z"), tenor(5).asOf());   // +0000
        assertNull(tenor(10).asOf());                                           // unreadable
    }

    @Test
    void namesTheBenchmarkUnderTheAppsOwnGiltId() throws Exception {
        assertEquals("GILT 4% 2028", tenor(2).benchmark());       // "4.00%" — trailing zeros go
        assertEquals("GILT 4.125% 2031", tenor(5).benchmark());
        assertEquals("GILT 4.5% 2035", tenor(10).benchmark());
        assertEquals(LocalDate.of(2031, 3, 7), tenor(5).maturity());
    }

    @Test
    void aBenchmarkMissingHalfItsIdentityIsNotNamed() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"FormattedQuoteResult":{"FormattedQuote":
                  {"symbol":"GB10Y-GB","code":0,"last":"4.6%","coupon":"4.50%"}}}""");
        GiltYield y = CnbcGiltYieldFetcher.parse(root).get(0);
        assertNull(y.benchmark());
        assertNull(y.maturity());
    }

    @Test
    void aSingleQuoteMayArriveAsABareObject() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"FormattedQuoteResult":{"FormattedQuote":
                  {"symbol":"GB30Y-GB","code":0,"last":"5.1%","previous_day_closing":"5.0%"}}}""");
        List<GiltYield> yields = CnbcGiltYieldFetcher.parse(root);
        assertEquals(1, yields.size());
        assertEquals(30, yields.get(0).tenorYears());
    }

    @Test
    void aResponseWithoutTheQuoteEnvelopeIsAFailureNotAnEmptyAnswer() {
        assertThrows(IOException.class, () -> CnbcGiltYieldFetcher.parse(MAPPER.readTree("{}")));
        assertThrows(IOException.class, () -> CnbcGiltYieldFetcher.parse(
                MAPPER.readTree("{\"FormattedQuoteResult\":{\"FormattedQuote\":null}}")));
    }

    @Test
    void linksEachTenorToItsOwnQuotePage() throws Exception {
        assertEquals("https://www.cnbc.com/quotes/GB10Y-GB", tenor(10).sourceUrl());
    }

    @Test
    void asksForEveryTenorInOneRequest() {
        String url = CnbcGiltYieldFetcher.url(List.of(2, 5, 10, 30));
        assertTrue(url.contains("symbols=GB2Y-GB%7CGB5Y-GB%7CGB10Y-GB%7CGB30Y-GB&"), url);
        assertTrue(url.endsWith("&output=json"), url);
    }

    @Test
    void anUnreadableFigureIsNullNeverZero() {
        assertNull(CnbcGiltYieldFetcher.percent(new TextNode("UNCH")));
        assertNull(CnbcGiltYieldFetcher.percent(new TextNode("")));
        assertNull(CnbcGiltYieldFetcher.percent(MAPPER.missingNode()));
        assertEquals(0, new BigDecimal("1234.5")
                .compareTo(CnbcGiltYieldFetcher.percent(new TextNode("1,234.5%"))));
        assertNull(CnbcGiltYieldFetcher.date(new TextNode("2031-13-40")));
    }
}
