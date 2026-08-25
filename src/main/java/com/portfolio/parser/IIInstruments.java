package com.portfolio.parser;

import com.portfolio.domain.Instruments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive Investor's instrument text, reduced to the internal ids the rest of the app joins
 * on. Currently one job: recognising a gilt and handing its coupon and maturity to
 * {@link Instruments#giltId}.
 *
 * <p>II names the same gilt three ways. The holdings export writes
 * {@code "UNITED KINGDOM 4.375 07/03/2030"} — coupon as a bare decimal, <b>no {@code %} sign</b>;
 * the order export and the website write {@code "4 3/8% TREASURY GILT 07/03/2030"} — the
 * eighths a gilt is actually quoted in. Both have to reduce to {@code "GILT 4.375% 2030"}, which
 * is the symbol {@code GiltPriceFetchJob} stores prices under, so a position joins its price
 * feed without a ticker map.
 *
 * <p>This sits beside {@code AJBellSippParser.extractBondId} and
 * {@code AJBellCashStatementParser.tryExtractGiltId} rather than merging with them: each broker
 * spells a gilt differently and the per-broker reader is where that difference belongs. What they
 * share — and what must never be duplicated — is {@link Instruments#giltId}, the one place the id
 * itself is built.
 */
final class IIInstruments {

    /**
     * Required before any date in a name is read as a maturity. Without it an equity whose name
     * happens to carry a date would be turned into a gilt, and a wrong bond id is worse than a
     * missing one — {@code Instruments.isBond} switches the whole position onto the
     * per-£100-nominal value formula.
     */
    private static final Pattern GILT_MARKER =
            Pattern.compile("(?i)UNITED\\s+KINGDOM|TREASURY|\\bGILT\\b");

    private static final Pattern MATURITY = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})");

    /** "4 3/8" — whole number then a vulgar fraction. Hunted before the plain decimal. */
    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)");

    /** "4.375", "0.875", "6". */
    private static final Pattern DECIMAL = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2200;
    private static final BigDecimal MAX_COUPON = new BigDecimal("25");

    private IIInstruments() {
    }

    /**
     * The canonical gilt id for an II instrument name, or {@code null} when the name is not a
     * gilt. Never throws — an unrecognised name is simply not a gilt.
     */
    static String giltIdFromName(String name) {
        if (name == null || name.isBlank()) return null;
        if (!GILT_MARKER.matcher(name).find()) return null;

        Matcher maturity = MATURITY.matcher(name);
        if (!maturity.find()) return null;
        String yr = maturity.group(3);
        int year = Integer.parseInt(yr);
        if (yr.length() == 2) year += 2000;
        if (year < MIN_YEAR || year > MAX_YEAR) return null;

        // The coupon is always stated before the maturity, and cutting the date off first is what
        // stops "07/03/2030" being read as the fraction 7 3/2030.
        BigDecimal coupon = coupon(name.substring(0, maturity.start()));
        if (coupon == null || coupon.signum() < 0 || coupon.compareTo(MAX_COUPON) > 0) return null;
        return Instruments.giltId(coupon.toPlainString(), year);
    }

    private static BigDecimal coupon(String beforeMaturity) {
        Matcher f = FRACTION.matcher(beforeMaturity);
        if (f.find()) {
            BigDecimal denominator = new BigDecimal(f.group(3));
            if (denominator.signum() != 0) {
                return new BigDecimal(f.group(1)).add(
                        new BigDecimal(f.group(2)).divide(denominator, 6, RoundingMode.HALF_UP));
            }
        }
        Matcher d = DECIMAL.matcher(beforeMaturity);
        return d.find() ? new BigDecimal(d.group(1)) : null;
    }
}
