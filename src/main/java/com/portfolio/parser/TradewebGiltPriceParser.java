package com.portfolio.parser;

import com.portfolio.domain.model.PriceBar;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the FTSE/Tradeweb gilt close-price CSV that the user downloads manually from Tradeweb.
 * One file = one gilt across a date range. Columns of interest: {@code Gilt Name}, {@code Close of
 * Business Date} (M/D/YYYY, US format), {@code Coupon}, {@code Maturity} (M/D/YYYY) and
 * {@code Clean Price}. The resulting symbol matches {@code "GILT {coupon}% {year}"} produced by
 * {@code AJBellSippParser.extractBondId}, so the join key lines up with the rest of the system.
 *
 * <p>Only {@code close} / {@code adjClose} are populated; OHLV stay null. Currency is GBP.
 */
public class TradewebGiltPriceParser {

    private static final Logger log = LoggerFactory.getLogger(TradewebGiltPriceParser.class);

    private static final String COL_DATE = "Close of Business Date";
    private static final String COL_COUPON = "Coupon";
    private static final String COL_MATURITY = "Maturity";
    private static final String COL_CLEAN_PRICE = "Clean Price";

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/yyyy");
    private static final Pattern YEAR_TAIL = Pattern.compile("(\\d{4})\\s*$");
    private static final String CURRENCY = "GBP";

    public List<PriceBar> parse(Path file) throws IOException, ParseException {
        try (Reader reader = bomStripped(file)) {
            CSVFormat fmt = CSVFormat.DEFAULT.builder()
                    .setHeader().setSkipHeaderRecord(true).setTrim(true).build();
            try (CSVParser csv = CSVParser.parse(reader, fmt)) {
                List<PriceBar> bars = new ArrayList<>();
                for (CSVRecord r : csv) {
                    PriceBar bar = toBar(r);
                    if (bar != null) bars.add(bar);
                }
                if (bars.isEmpty()) {
                    throw new ParseException("No gilt price rows parsed from " + file.getFileName());
                }
                return bars;
            }
        }
    }

    private static PriceBar toBar(CSVRecord r) {
        try {
            String couponRaw = r.get(COL_COUPON);
            String maturity = r.get(COL_MATURITY);
            String dateRaw = r.get(COL_DATE);
            String priceRaw = r.get(COL_CLEAN_PRICE);
            if (couponRaw.isEmpty() || maturity.isEmpty() || dateRaw.isEmpty() || priceRaw.isEmpty()) {
                return null;
            }
            String coupon = normaliseCoupon(couponRaw);
            String year = maturityYear(maturity);
            if (year == null) {
                log.warn("Skipping row with unparseable maturity: {}", maturity);
                return null;
            }
            String symbol = "GILT " + coupon + "% " + year;
            LocalDate date = LocalDate.parse(dateRaw, US_DATE);
            double price = Double.parseDouble(priceRaw);
            return new PriceBar(symbol, date, null, null, null, price, price, null, CURRENCY);
        } catch (RuntimeException e) {
            log.warn("Skipping unparseable Tradeweb row: {}", r, e);
            return null;
        }
    }

    /** "3.750" → "3.75"; "0.875" → "0.875"; "3.000" → "3". Matches AJBellSippParser's coupon style. */
    static String normaliseCoupon(String raw) {
        String s = raw.trim();
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String maturityYear(String maturity) {
        Matcher m = YEAR_TAIL.matcher(maturity);
        return m.find() ? m.group(1) : null;
    }

    /** Tradeweb's CSV has a UTF-8 BOM; CSVParser would carry it into the first header name. */
    private static Reader bomStripped(Path file) throws IOException {
        BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        br.mark(1);
        int c = br.read();
        if (c != 0xFEFF) br.reset();
        return br;
    }
}
