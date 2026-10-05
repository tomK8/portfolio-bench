package com.portfolio.parser;

import com.portfolio.domain.Instruments;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.OpenOrder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses AJ Bell {@code activeorders*.csv} exports. One instance per AJ Bell account.
 *
 * <p>Header: {@code Investment,Reference,Type,Order type,Quantity,Amount,Price,Date entered,
 * Expiry date,Status}. The export names the instrument (no ticker), so the name is mapped to the
 * app's internal symbol by keyword; an <em>unmapped</em> name is passed straight through as the
 * {@code symbol} so it shows up in the (only) identifier column — the cue to add a mapping here and
 * fix it. Prices are London pence ("53800p") — normalised to pounds so the row carries a single GBP
 * currency alongside the pound-denominated Amount.
 *
 * <p><b>The export does not say which AJ Bell account it is for.</b> Two accounts behind one login
 * produce files identical in name, header and reference format, and the browser numbers them in
 * download order, so {@code activeorders (69).csv} may be either. {@code supports} therefore asks
 * {@link #router} — a file belongs to this parser's account when the instruments it names are that
 * account's, judged against the holdings exports. An unroutable file is claimed by no parser and
 * reported rather than guessed at; see {@code AjBellFileRouter}. With {@code supports} doing the
 * routing, the existing "newest supported file wins" lookup yields the newest file <em>per
 * account</em> for free.
 */
public class AJBellOrdersParser implements OrderParser {

    private static final Logger log = LoggerFactory.getLogger(AJBellOrdersParser.class);

    private static final Pattern FILENAME = Pattern.compile("activeorders.*\\.csv", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * AJ Bell's active-orders export names the instrument but carries no ticker, so map the
     * (long) investment name to the app's internal symbol by keyword — the same symbols used
     * elsewhere (YahooTickerMap adds the .L suffix: EQQQ→EQQQ.L, LSEG→LSEG.L, REL→REL.L).
     * First matching keyword wins.
     *
     * <p>This is the committed baseline only. User-added keywords come from
     * {@code instrument_aliases} via {@link #aliasSource} and are consulted <em>first</em>, so a
     * new instrument is mapped from the dashboard rather than from a source edit.
     */
    private static final Map<String, String> NAME_TO_SYMBOL = new LinkedHashMap<>();

    static {
        NAME_TO_SYMBOL.put("eqqq", "EQQQ");
        NAME_TO_SYMBOL.put("london stock exchange", "LSEG");
        NAME_TO_SYMBOL.put("relx", "REL");
        NAME_TO_SYMBOL.put("primary health", "PHP");
        NAME_TO_SYMBOL.put("londonmetric", "LMP");
        NAME_TO_SYMBOL.put("british land", "BLND");
        // The dealing account's only holding, and so the only evidence that routes its exports:
        // an order naming no mappable instrument cannot be attributed to an account at all.
        NAME_TO_SYMBOL.put("barclays", "BARC");
    }

    /**
     * User-added keyword → symbol aliases, longest keyword first. Read per parse (the export is
     * re-parsed on every tab click, so a mapping added on the dashboard takes effect on the next
     * one) and never cached here.
     */
    private final Supplier<Map<String, String>> aliasSource;

    /** Which AJ Bell account this instance reads for. */
    private final Account account;

    /**
     * The account a given export belongs to, or null when it cannot be told. Null here means "do
     * not route" — a single-account context (tests, and the app before the dealing account existed)
     * where every {@code activeorders*.csv} is this parser's.
     */
    private final Function<Path, Account> router;

    /** Committed keywords only — the default for tests and any alias-store-less context. */
    public AJBellOrdersParser() {
        this(Map::of);
    }

    public AJBellOrdersParser(Supplier<Map<String, String>> aliasSource) {
        this(Account.AJBELL, aliasSource, null);
    }

    /**
     * @param account the AJ Bell account this instance reads for
     * @param router  decides which account an export belongs to; null to claim every AJ Bell
     *                orders file, which is only correct when one AJ Bell account exists
     */
    public AJBellOrdersParser(Account account, Supplier<Map<String, String>> aliasSource,
                              Function<Path, Account> router) {
        this.account = account;
        this.aliasSource = aliasSource == null ? Map::of : aliasSource;
        this.router = router;
    }

    /**
     * Internal symbol for an AJ Bell investment name, or null when unmapped.
     *
     * <p>Matched on {@link Instruments#matchKey} rather than on the raw lowercased name, so
     * punctuation in the description cannot stop a keyword firing — see that method for the
     * ampersand case that made every such instrument un-mappable from the dashboard.
     */
    String symbolFor(String investmentName) {
        if (investmentName == null) return null;
        String lower = Instruments.matchKey(investmentName);
        Map<String, String> aliases = aliasSource.get();
        if (aliases != null) {
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (lower.contains(e.getKey())) return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : NAME_TO_SYMBOL.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * The identifier shown in the Symbol column: the mapped internal symbol when known, else the raw
     * investment name (so an unmapped name is visible and can be turned into a {@link #NAME_TO_SYMBOL}
     * entry rather than silently vanishing). It won't resolve to a live quote until mapped — fine.
     */
    String symbolOrName(String investmentName) {
        String mapped = symbolFor(investmentName);
        if (mapped != null) return mapped;
        // Visible in the Symbol column, but it resolves to no ticker — so the order shows no
        // Current price, no vs-limit and no touch probability. Say so at parse time; the column
        // being blank looks like a dead feed rather than a missing mapping. The Open orders tab
        // spots the same fall-through (symbol == name) and offers the mapping inline.
        log.warn("[AJBell orders] No symbol mapping for \"{}\" — the order will show without live "
                + "price columns. Add it from the Open orders tab, which offers the mapping "
                + "inline, or add a NAME_TO_SYMBOL keyword.", investmentName);
        return investmentName;
    }

    @Override
    public String sourceName() {
        return account.displayName();
    }

    @Override
    public boolean supports(Path file) {
        if (!FILENAME.matcher(file.getFileName().toString()).matches()) return false;
        return router == null || account == router.apply(file);
    }

    /** True for any AJ Bell active-orders export, whichever account it turns out to be for. */
    public static boolean isAjBellOrdersFile(Path file) {
        return FILENAME.matcher(file.getFileName().toString()).matches();
    }

    /**
     * Every instrument the export names, as internal symbols — the evidence the account routing is
     * decided on. Deliberately {@link #symbolFor} rather than {@link #symbolOrName}: an unmapped
     * name is dropped instead of passed through, because a long investment name matches no holdings
     * ticker and would only add noise, and because routing must not emit the "add a mapping"
     * warning for files it is merely sniffing.
     */
    public Set<String> symbolsIn(Path file) {
        Set<String> out = new LinkedHashSet<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            while (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            try (CSVParser csv = CSVParser.parse(content, format)) {
                for (CSVRecord r : csv) {
                    String symbol = symbolFor(get(r, "Investment"));
                    if (symbol != null) out.add(symbol.toUpperCase());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read instruments from {} — it cannot be routed to an account", file, e);
        }
        return out;
    }

    @Override
    public List<OpenOrder> parse(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        List<OpenOrder> orders = new ArrayList<>();
        try (CSVParser csv = CSVParser.parse(content, format)) {
            for (CSVRecord r : csv) {
                String name = get(r, "Investment");
                if (name == null || name.isBlank()) continue;
                BigDecimal qty = decimal(get(r, "Quantity"));
                if (qty == null) continue;

                orders.add(OpenOrder.parsed(
                        sourceName(),
                        symbolOrName(name),
                        name,
                        get(r, "Type"),
                        get(r, "Order type"),
                        qty,
                        price(get(r, "Price")),
                        false,
                        decimal(get(r, "Amount")),
                        "GBP",
                        date(get(r, "Date entered")),
                        date(get(r, "Expiry date")),
                        null,
                        get(r, "Status"),
                        get(r, "Reference")));
            }
        }
        return orders;
    }

    private static String get(CSVRecord r, String col) {
        return r.isMapped(col) ? r.get(col).trim() : null;
    }

    /** "53800p" → 538.00 (pence → pounds); a bare number is already pounds. */
    private static BigDecimal price(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        boolean pence = s.endsWith("p") || s.endsWith("P");
        BigDecimal v = decimal(pence ? s.substring(0, s.length() - 1) : s);
        return (v != null && pence) ? v.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP) : v;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replaceAll("[£$,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
