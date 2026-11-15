package com.portfolio.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stored form of a gilt's identifiers. Encoding and decoding sit on the record because the
 * job that writes the {@code gilt_refs} map and the service that reads it are two classes, and a
 * format with two spellings is a format that drifts.
 *
 * <p>Decoding is deliberately tolerant. This feeds a picker: a field that fails to decode must
 * cost a search handle, never the entry itself, and an entry written by an older build with
 * fewer fields still has to come back.
 */
class GiltRefTest {

    private static final GiltRef REF = new GiltRef("GILT 4.875% 2036", "GB00BWBR1N39", "BWBR1N3",
            LocalDate.of(2036, 7, 31));

    @Test
    void roundTripsThroughTheStoredForm() {
        assertEquals("GB00BWBR1N39|BWBR1N3|2036-07-31", REF.toKvValue());
        assertEquals(REF, GiltRef.fromKv(REF.symbol(), REF.toKvValue()));
    }

    @Test
    void anAbsentFieldStaysAbsentInBothDirections() {
        GiltRef bare = new GiltRef("GILT 4.25% 2036", null, null, null);
        assertEquals("||", bare.toKvValue());
        assertEquals(bare, GiltRef.fromKv(bare.symbol(), bare.toKvValue()));
        assertEquals(bare, GiltRef.fromKv(bare.symbol(), null), "never stored at all");
        assertEquals(bare, GiltRef.fromKv(bare.symbol(), "  |  |  "), "blanks are not values");
    }

    @Test
    void aShortOrUnreadableValueDecodesToWhatItDoesCarry() {
        // An entry from a build that stored fewer fields, and a date that is no longer a date.
        GiltRef shorter = GiltRef.fromKv("GILT 4.875% 2036", "GB00BWBR1N39");
        assertEquals("GB00BWBR1N39", shorter.isin());
        assertNull(shorter.sedol());
        assertNull(shorter.maturity());

        GiltRef badDate = GiltRef.fromKv("GILT 4.875% 2036", "GB00BWBR1N39|BWBR1N3|31 July 2036");
        assertEquals("BWBR1N3", badDate.sedol(), "the fields beside it still decode");
        assertNull(badDate.maturity());
    }
}
