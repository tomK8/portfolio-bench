package com.pension;

import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.IISippParser;
import com.pension.parser.RothIraParser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.HeadlessException;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {

    private static final Path INPUT_DIR  = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), "Documents");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String FX_URL =
            "https://api.frankfurter.dev/v1/latest?from=GBP&to=USD,EUR";

    // ---- Portfolio Raw sheet columns ----
    private static final int COL_SECURITY_ID   = 0;  // A
    private static final int COL_QUANTITY       = 1;  // B
    private static final int COL_AVG_PRICE      = 2;  // C
    private static final int COL_MKT_VALUE      = 3;  // D  (native currency)
    private static final int COL_MKT_VALUE_GBP  = 4;  // E
    private static final int COL_GAIN           = 5;  // F  Gain (£)
    private static final int COL_GAIN_PCT       = 6;  // G  Gain/Loss %
    private static final int COL_CURRENCY       = 7;  // H
    private static final int COL_SOURCE         = 8;  // I
    private static final int NUM_COLS           = 9;

    // ---- Aggregated Portfolio sheet columns ----
    private static final int AGG_ID      = 0;  // A
    private static final int AGG_QTY     = 1;  // B
    private static final int AGG_AVG     = 2;  // C
    private static final int AGG_CCY     = 3;  // D  Exchange Currency
    private static final int AGG_MKTGBP  = 4;  // E  Market Value GBP
    private static final int AGG_GAIN    = 5;  // F  Gain (£)
    private static final int AGG_GAINPCT = 6;  // G  Gain/Loss %
    private static final int AGG_SOURCES = 7;  // H
    private static final int AGG_COLS    = 8;

    private static final String II_SIPP = "II SIPP";

    private record SourceFile(AccountParser parser, Path file) {}

    private record AggHolding(
            String securityId,
            BigDecimal quantity,
            BigDecimal avgPricePaid,
            BigDecimal marketValueGbp,
            BigDecimal gainGbp,
            BigDecimal gainPct,      // decimal fraction: 0.125 = 12.5%
            Currency currency,
            String sources
    ) {}

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("Input directory not found: " + INPUT_DIR);
            return;
        }

        // Fetch live rates first so AJBellSippParser can use them during parsing
        Map<String, BigDecimal> gbpRates = fetchGbpRates();
        System.out.println("FX rates (per 1 GBP): " + gbpRates);

        List<AccountParser> parsers = List.of(
                new RothIraParser(),
                new AJBellSippParser(gbpRates),
                new IISippParser()
        );

        List<SourceFile> sources  = new ArrayList<>();
        List<Holding>    holdings = new ArrayList<>();

        for (AccountParser parser : parsers) {
            Optional<Path> file = findMostRecent(INPUT_DIR, parser);
            if (file.isPresent()) {
                System.out.println("Parsing: " + file.get().getFileName());
                holdings.addAll(parser.parse(file.get()));
                sources.add(new SourceFile(parser, file.get()));
            }
        }

        if (holdings.isEmpty()) {
            System.out.println("No holdings found — check that input files are present in " + INPUT_DIR);
            return;
        }

        BigDecimal iiSippCash = promptForIISippCash();
        List<AggHolding> aggregated = aggregate(holdings, gbpRates);

        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        // Main timestamped workbook
        Path mainOutput = OUTPUT_DIR.resolve("portfolio" + timestamp + ".xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            String portfolioInputRef =
                    writeAggregatedSheet(wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, wb);
            writePortfolioSheet(wb.createSheet("Portfolio Raw"), holdings, gbpRates,
                                portfolioInputRef, wb);
            for (SourceFile sf : sources)
                writeRawSheet(sf.file(), wb.createSheet(sf.parser().sourceName()));
            try (OutputStream os = Files.newOutputStream(mainOutput)) { wb.write(os); }
        }
        System.out.println("Written " + holdings.size() + " holdings to: " + mainOutput);

        // Portfolio Summary — standalone file, fixed name
        Path summaryOutput = OUTPUT_DIR.resolve("Portfolio Summary.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            writeAggregatedSheet(wb.createSheet("Portfolio"), aggregated, gbpRates, iiSippCash, wb);
            try (OutputStream os = Files.newOutputStream(summaryOutput)) { wb.write(os); }
        }
        System.out.println("Portfolio summary written to: " + summaryOutput);

        BigDecimal totalGbp = aggregated.stream()
                .map(AggHolding::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(iiSippCash);

        BigDecimal totalGainGbp = aggregated.stream()
                .filter(h -> h.gainGbp() != null)
                .map(AggHolding::gainGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCashGbp = aggregated.stream()
                .filter(h -> "CASH".equals(h.securityId()))
                .map(AggHolding::marketValueGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(iiSippCash);

        BigDecimal invested    = totalGbp.subtract(totalCashGbp);
        BigDecimal returnPct   = invested.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(invested, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalReturn = totalGbp.compareTo(BigDecimal.ZERO) != 0
                ? totalGainGbp.divide(totalGbp, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        saveSnapshot(totalGbp, totalGainGbp, totalCashGbp, returnPct, totalReturn, gbpRates);
    }

    // -------------------------------------------------------------------------
    // Database snapshot
    // -------------------------------------------------------------------------

    private static final Path DB_DIR  = Path.of(System.getProperty("user.home"), "Documents", "Investing");
    private static final Path DB_PATH = DB_DIR.resolve("portfolio.db");

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS portfolio_snapshots (
                snapshot_date      INTEGER PRIMARY KEY,
                snapshot_date_text TEXT    NOT NULL,
                total_value_gbp    REAL    NOT NULL,
                total_gain_gbp     REAL,
                total_cash_gbp     REAL,
                return_pct         REAL,
                total_return       REAL,
                gbpusd             REAL,
                gbpeur             REAL
            )""";

    private static void saveSnapshot(BigDecimal totalGbp, BigDecimal totalGainGbp,
                                     BigDecimal totalCashGbp, BigDecimal returnPct,
                                     BigDecimal totalReturn, Map<String, BigDecimal> gbpRates) {
        long   snapshotDate     = Instant.now().getEpochSecond();
        String snapshotDateText = LocalDate.now().toString();   // "2026-05-08"

        double gbpusd = gbpRates.getOrDefault("USD", BigDecimal.ZERO).doubleValue();
        double gbpeur = gbpRates.getOrDefault("EUR", BigDecimal.ZERO).doubleValue();

        try {
            Files.createDirectories(DB_DIR);

            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 Statement ddl = conn.createStatement()) {

                ddl.execute(CREATE_TABLE);

                // Migrate existing databases that pre-date newer columns
                for (String col : List.of(
                        "gbpusd REAL", "gbpeur REAL", "total_gain_gbp REAL",
                        "total_cash_gbp REAL", "return_pct REAL", "total_return REAL")) {
                    try { ddl.execute("ALTER TABLE portfolio_snapshots ADD COLUMN " + col); }
                    catch (SQLException ignored) {}
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO portfolio_snapshots " +
                        "(snapshot_date, snapshot_date_text, total_value_gbp, " +
                        " total_gain_gbp, total_cash_gbp, return_pct, total_return, gbpusd, gbpeur) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1,   snapshotDate);
                    ps.setString(2, snapshotDateText);
                    ps.setDouble(3, totalGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(4, totalGainGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(5, totalCashGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(6, returnPct.setScale(6, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(7, totalReturn.setScale(6, RoundingMode.HALF_UP).doubleValue());
                    ps.setDouble(8, gbpusd);
                    ps.setDouble(9, gbpeur);
                    ps.executeUpdate();
                }
            }
            System.out.printf("Snapshot saved to DB: %s → £%,.2f  gain £%,.2f  return %.2f%%%n",
                    snapshotDateText, totalGbp.doubleValue(),
                    totalGainGbp.doubleValue(), returnPct.multiply(BigDecimal.valueOf(100)).doubleValue());

        } catch (IOException | SQLException e) {
            System.err.println("Warning: could not save DB snapshot — " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // User input
    // -------------------------------------------------------------------------

    private static BigDecimal promptForIISippCash() {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        while (true) {
            try {
                String raw = javax.swing.JOptionPane.showInputDialog(
                        null,
                        "Enter II SIPP Cash balance (GBP):",
                        "II SIPP Cash",
                        javax.swing.JOptionPane.QUESTION_MESSAGE);

                if (raw == null) {
                    System.out.println("II SIPP cash input cancelled — using 0");
                    return BigDecimal.ZERO;
                }
                raw = raw.replace(",", "").replace("£", "").trim();
                if (raw.isEmpty()) return BigDecimal.ZERO;

                return new BigDecimal(raw);

            } catch (NumberFormatException e) {
                javax.swing.JOptionPane.showMessageDialog(
                        null,
                        "Please enter a valid number, e.g. 1234.56",
                        "Invalid input",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            } catch (HeadlessException e) {
                System.out.println("No display available — II SIPP cash set to 0");
                return BigDecimal.ZERO;
            }
        }
    }

    // -------------------------------------------------------------------------
    // File discovery
    // -------------------------------------------------------------------------

    private static Optional<Path> findMostRecent(Path dir, AccountParser parser) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(parser::supports)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        }
    }

    // -------------------------------------------------------------------------
    // FX rates
    // -------------------------------------------------------------------------

    private static Map<String, BigDecimal> fetchGbpRates() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(FX_URL)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("GBP", BigDecimal.ONE);
        Matcher m = Pattern.compile("\"([A-Z]{3})\":(\\d+\\.\\d+)").matcher(response.body());
        while (m.find()) rates.put(m.group(1), new BigDecimal(m.group(2)));
        return rates;
    }

    // -------------------------------------------------------------------------
    // Aggregation
    // -------------------------------------------------------------------------

    private static List<AggHolding> aggregate(List<Holding> holdings,
                                              Map<String, BigDecimal> gbpRates) {
        record Key(String id, String ccy) {}

        class Acc {
            String securityId;
            BigDecimal qty          = BigDecimal.ZERO;
            BigDecimal nativeCost   = BigDecimal.ZERO;
            boolean hasCost         = false;
            BigDecimal mktValGbp    = BigDecimal.ZERO;
            BigDecimal totalCostGbp = BigDecimal.ZERO;
            boolean hasCostGbp      = false;
            final Set<String> srcs  = new LinkedHashSet<>();
            Currency currency;
        }

        Map<Key, Acc> map = new LinkedHashMap<>();

        for (Holding h : holdings) {
            Key key = new Key(h.getSecurityId(), h.getCurrency().getCurrencyCode());
            Acc acc = map.computeIfAbsent(key, k -> {
                Acc a = new Acc();
                a.securityId = h.getSecurityId();
                a.currency   = h.getCurrency();
                return a;
            });
            acc.qty = acc.qty.add(h.getQuantity());
            acc.srcs.add(h.getSource());

            if (h.getAvgPricePaid() != null) {
                acc.nativeCost = acc.nativeCost.add(h.getQuantity().multiply(h.getAvgPricePaid()));
                acc.hasCost = true;
            }
            BigDecimal gbp = toGbp(h, gbpRates);
            if (gbp != null) acc.mktValGbp = acc.mktValGbp.add(gbp);

            BigDecimal costGbp = costInGbp(h, gbpRates);
            if (costGbp != null) { acc.totalCostGbp = acc.totalCostGbp.add(costGbp); acc.hasCostGbp = true; }
        }

        return map.values().stream().map(acc -> {
            BigDecimal avg = acc.hasCost && acc.qty.compareTo(BigDecimal.ZERO) > 0
                    ? acc.nativeCost.divide(acc.qty, 10, RoundingMode.HALF_UP) : null;
            BigDecimal gain = acc.hasCostGbp ? acc.mktValGbp.subtract(acc.totalCostGbp) : null;
            BigDecimal pct  = (acc.hasCostGbp && acc.totalCostGbp.compareTo(BigDecimal.ZERO) != 0)
                    ? gain.divide(acc.totalCostGbp, 10, RoundingMode.HALF_UP) : null;
            return new AggHolding(acc.securityId, acc.qty, avg, acc.mktValGbp, gain, pct,
                                  acc.currency, String.join(", ", acc.srcs));
        }).sorted(Comparator.comparingInt(Main::aggSection).thenComparing(Main::aggSortKey))
          .collect(Collectors.toList());
    }

    private static BigDecimal costInGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        if (h.getCostBasisGbp() != null) return h.getCostBasisGbp();
        if (h.getAvgPricePaid() == null) return null;
        BigDecimal native_ = h.getQuantity().multiply(h.getAvgPricePaid());
        BigDecimal rate    = gbpRates.getOrDefault(h.getCurrency().getCurrencyCode(), BigDecimal.ONE);
        return rate.compareTo(BigDecimal.ZERO) == 0 ? null
                : native_.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static int aggSection(AggHolding h) {
        if (isBond(h.securityId())) return 4;
        return switch (h.currency().getCurrencyCode()) {
            case "USD" -> 0; case "GBP" -> 1; case "EUR" -> 2; default -> 3;
        };
    }
    private static String aggSortKey(AggHolding h) { return h.securityId().equals("CASH") ? "~" : h.securityId(); }
    private static boolean isBond(String id) { return id.contains("%") || id.toUpperCase().startsWith("GILT"); }

    // -------------------------------------------------------------------------
    // Aggregated Portfolio sheet — returns cross-sheet ref to the II SIPP input cell
    // -------------------------------------------------------------------------

    private static String writeAggregatedSheet(Sheet sheet, List<AggHolding> rows,
                                               Map<String, BigDecimal> gbpRates,
                                               BigDecimal iiSippCash, Workbook wb) {
        CellStyle dataText  = wb.createCellStyle();
        CellStyle dataNum   = numericStyle(wb, false);
        CellStyle boldText  = boldTextStyle(wb);
        CellStyle boldNum   = numericStyle(wb, true);
        CellStyle inputCell = inputCellStyle(wb);

        List<AggHolding> equities = rows.stream().filter(h -> !"CASH".equals(h.securityId())).collect(Collectors.toList());
        List<AggHolding> cashList = rows.stream().filter(h ->  "CASH".equals(h.securityId())).collect(Collectors.toList());
        boolean hasCashGbp = cashList.stream().anyMatch(h -> "GBP".equals(h.currency().getCurrencyCode()));

        int nEquity = equities.size();
        int nCash   = cashList.size() + (hasCashGbp ? 0 : 1);

        int firstCashPoi    = nEquity + 3;
        int lastCashPoi     = nEquity + 2 + nCash;
        int totalCashPoiRow    = nEquity + 3 + nCash;
        int totalPoiRow        = nEquity + 5 + nCash;
        int returnPctPoiRow    = nEquity + 6 + nCash;
        int totalReturnPoiRow  = nEquity + 7 + nCash;
        int inputLabelPoi      = nEquity + 10 + nCash;
        int inputValuePoi      = nEquity + 11 + nCash;
        String inputCellRef    = "E" + (inputValuePoi + 1);

        int firstEquityExcel = 2;
        int lastEquityExcel  = nEquity + 1;
        int firstCashExcel   = firstCashPoi + 1;
        int lastCashExcel    = lastCashPoi  + 1;

        // Header
        String[] headers = { "Security ID", "Quantity", "Avg Price Paid", "Exchange Currency",
                              "Market Value GBP", "Gain (£)", "Gain/Loss %", "Sources" };
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(hdr, i, headers[i], boldText);
        sheet.setAutoFilter(new CellRangeAddress(0, nEquity, 0, AGG_COLS - 1));

        // Equity rows
        int rowNum = 1;
        for (AggHolding h : equities)
            writeAggRow(sheet.createRow(rowNum++), h, dataText, dataNum, wb);

        // Cash section header
        styledCell(sheet.createRow(nEquity + 2), AGG_ID, "Cash", boldText);

        // Cash rows
        rowNum = firstCashPoi;
        boolean wroteGbpCash = false;
        for (AggHolding h : cashList) {
            Row row = sheet.createRow(rowNum++);
            boolean isGbpCash = "GBP".equals(h.currency().getCurrencyCode());
            styledCell(row, AGG_ID,  h.securityId(),                dataText);
            styledCell(row, AGG_CCY, h.currency().getCurrencyCode(), dataText);
            if (isGbpCash) {
                String existing = h.marketValueGbp().setScale(2, RoundingMode.HALF_UP).toPlainString();
                String formula  = existing + "+" + inputCellRef;
                formulaCell(row, AGG_QTY,    formula, dataNum);
                setNumeric(row, AGG_AVG, BigDecimal.ONE, dataNum);
                formulaCell(row, AGG_MKTGBP, formula, dataNum);
                String src = h.sources().contains(II_SIPP) ? h.sources() : h.sources() + ", " + II_SIPP;
                styledCell(row, AGG_SOURCES, src, dataText);
                wroteGbpCash = true;
            } else {
                setNumeric(row, AGG_QTY,     h.quantity(),      dataNum);
                setNumeric(row, AGG_AVG,     h.avgPricePaid(),  dataNum);
                setNumeric(row, AGG_MKTGBP,  h.marketValueGbp(), dataNum);
                styledCell(row, AGG_SOURCES, h.sources(),       dataText);
            }
        }
        if (!wroteGbpCash) {
            Row row = sheet.createRow(rowNum);
            styledCell(row, AGG_ID,  "CASH",   dataText);
            styledCell(row, AGG_CCY, "GBP",    dataText);
            formulaCell(row, AGG_QTY,    inputCellRef, dataNum);
            setNumeric(row, AGG_AVG, BigDecimal.ONE,   dataNum);
            formulaCell(row, AGG_MKTGBP, inputCellRef, dataNum);
            styledCell(row, AGG_SOURCES, II_SIPP,      dataText);
        }

        // Total Cash row (below cash data, above TOTAL)
        double gbpusd = gbpRates.getOrDefault("USD", BigDecimal.ONE).doubleValue();
        int totalCashExcelRow = totalCashPoiRow + 1;
        Row cashTotalRow = sheet.createRow(totalCashPoiRow);
        styledCell(cashTotalRow, AGG_ID, "Total Cash", boldText);
        // GBP: sum all cash E cells (picks up II SIPP via formula chain)
        formulaCell(cashTotalRow, AGG_MKTGBP,
                "SUM(E" + (firstCashPoi + 1) + ":E" + (lastCashPoi + 1) + ")", boldNum);
        // USD equivalent in col D (Exchange Currency repurposed for this summary row)
        formulaCell(cashTotalRow, AGG_CCY,
                "E" + totalCashExcelRow + "*" + gbpusd, boldNum);

        // TOTAL
        Row total = sheet.createRow(totalPoiRow);
        styledCell(total, AGG_ID, "TOTAL", boldText);
        formulaCell(total, AGG_MKTGBP,
                "SUBTOTAL(9,E" + firstEquityExcel + ":E" + lastEquityExcel + ")"
                + "+SUM(E" + firstCashExcel + ":E" + lastCashExcel + ")", boldNum);
        formulaCell(total, AGG_GAIN,
                "SUBTOTAL(9,F" + firstEquityExcel + ":F" + lastEquityExcel + ")"
                + "+SUM(F" + firstCashExcel + ":F" + lastCashExcel + ")", boldNum);

        // Return % row: Gain / (Total Value − Cash)
        int tExcel  = totalPoiRow     + 1;
        int tcExcel = totalCashPoiRow + 1;
        Row returnRow = sheet.createRow(returnPctPoiRow);
        styledCell(returnRow, AGG_ID, "Return %", boldText);
        Cell returnCell = returnRow.createCell(AGG_GAINPCT);
        returnCell.setCellFormula("F" + tExcel + "/(E" + tExcel + "-E" + tcExcel + ")");
        returnCell.setCellStyle(pctStyle(wb, true));

        // Total Return row: Gain / Total Value
        Row totalReturnRow = sheet.createRow(totalReturnPoiRow);
        styledCell(totalReturnRow, AGG_ID, "Total Return", boldText);
        Cell totalReturnCell = totalReturnRow.createCell(AGG_GAINPCT);
        totalReturnCell.setCellFormula("F" + tExcel + "/E" + tExcel);
        totalReturnCell.setCellStyle(pctStyle(wb, true));

        // II SIPP cash — pre-filled from the popup; yellow cell is still editable in Excel
        styledCell(sheet.createRow(inputLabelPoi), AGG_ID, "II SIPP Cash (GBP)", boldText);
        Row inputRow = sheet.createRow(inputValuePoi);
        setNumeric(inputRow, AGG_MKTGBP, iiSippCash, inputCell);

        autoSizeColumns(sheet, AGG_COLS);

        // Return the cross-sheet reference to this input cell (no leading =)
        return sheet.getSheetName() + "!E" + (inputValuePoi + 1);
    }

    private static void writeAggRow(Row row, AggHolding h,
                                    CellStyle textStyle, CellStyle numStyle, Workbook wb) {
        styledCell(row, AGG_ID,      h.securityId(),                  textStyle);
        setNumeric(row, AGG_QTY,     h.quantity(),                    numStyle);
        setNumeric(row, AGG_AVG,     h.avgPricePaid(),                numStyle);
        styledCell(row, AGG_CCY,     h.currency().getCurrencyCode(),  textStyle);
        setNumeric(row, AGG_MKTGBP,  h.marketValueGbp(),              numStyle);
        if (h.gainGbp() != null) {
            boolean g = h.gainGbp().compareTo(BigDecimal.ZERO) >= 0;
            setNumeric(row, AGG_GAIN, h.gainGbp(), gainLossNumStyle(wb, g));
        }
        if (h.gainPct() != null) {
            boolean g = h.gainPct().compareTo(BigDecimal.ZERO) >= 0;
            Cell c = row.createCell(AGG_GAINPCT);
            c.setCellValue(h.gainPct().setScale(4, RoundingMode.HALF_UP).doubleValue());
            c.setCellStyle(gainLossPctStyle(wb, g));
        }
        styledCell(row, AGG_SOURCES, h.sources(), textStyle);
    }

    // -------------------------------------------------------------------------
    // Portfolio Raw sheet
    // -------------------------------------------------------------------------

    private static void writePortfolioSheet(Sheet sheet, List<Holding> holdings,
                                            Map<String, BigDecimal> gbpRates,
                                            String portfolioInputRef, Workbook wb) {
        CellStyle dataText    = wb.createCellStyle();
        CellStyle dataNum     = numericStyle(wb, false);
        CellStyle boldText    = boldTextStyle(wb);
        CellStyle boldNum     = numericStyle(wb, true);
        CellStyle linkedCell  = linkedCellStyle(wb);
        CellStyle gainNum     = gainLossNumStyle(wb, true);
        CellStyle lossNum     = gainLossNumStyle(wb, false);
        CellStyle gainPct     = gainLossPctStyle(wb, true);
        CellStyle lossPct     = gainLossPctStyle(wb, false);

        String[] headers = { "Security ID", "Quantity", "Avg Price Paid",
                              "Market Value", "Market Value GBP", "Gain (£)", "Gain/Loss %",
                              "Currency", "Source" };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) styledCell(headerRow, i, headers[i], boldText);

        Map<String, List<Holding>> bySource = new LinkedHashMap<>();
        for (Holding h : holdings) bySource.computeIfAbsent(h.getSource(), k -> new ArrayList<>()).add(h);

        int numSources  = bySource.size();
        boolean hasII   = bySource.containsKey(II_SIPP);
        int numDataRows = holdings.size() + (numSources - 1) + (hasII ? 1 : 0);

        int dataAfter     = 1 + numDataRows;
        int currHdrPoi    = dataAfter + 2;
        int firstSubPoi   = currHdrPoi + 1;
        int grandTotalPoi = firstSubPoi + numSources + 1;
        int totalCashPoi   = grandTotalPoi + 1;
        int returnPctPoi   = totalCashPoi  + 1;
        int totalReturnPoi = returnPctPoi  + 1;
        int ratesLabelPoi  = totalReturnPoi + 2;
        int gbpusdPoi     = ratesLabelPoi + 1;
        int gbpeurPoi     = ratesLabelPoi + 2;
        String gbpusdRef  = "$B$" + (gbpusdPoi + 1);

        CellStyle rateStyle = rateNumStyle(wb);
        styledCell(sheet.createRow(ratesLabelPoi), COL_SECURITY_ID, "FX Rates (1 GBP =)", boldText);
        Row rusd = sheet.createRow(gbpusdPoi);
        styledCell(rusd, COL_SECURITY_ID, "USD", dataText);
        setRate(rusd, 1, gbpRates.getOrDefault("USD", BigDecimal.ONE), rateStyle);
        Row reur = sheet.createRow(gbpeurPoi);
        styledCell(reur, COL_SECURITY_ID, "EUR", dataText);
        setRate(reur, 1, gbpRates.getOrDefault("EUR", BigDecimal.ONE), rateStyle);

        Map<String, int[]> ranges = new LinkedHashMap<>();
        int rowNum = 1;
        boolean firstSource = true;

        for (Map.Entry<String, List<Holding>> entry : bySource.entrySet()) {
            if (!firstSource) rowNum++;
            firstSource = false;
            String src   = entry.getKey();
            int firstRow = rowNum;

            for (Holding h : entry.getValue())
                writeHoldingRow(sheet.createRow(rowNum++), h, gbpRates,
                                dataText, dataNum, gainNum, lossNum, gainPct, lossPct);

            if (II_SIPP.equals(src)) {
                Row ph    = sheet.createRow(rowNum);
                String eRef = "E" + (rowNum + 1);
                styledCell(ph, COL_SECURITY_ID, "CASH", dataText);
                formulaCell(ph, COL_QUANTITY,    eRef, dataNum);
                setNumeric(ph, COL_AVG_PRICE, BigDecimal.ONE, dataNum);
                formulaCell(ph, COL_MKT_VALUE,   eRef, dataNum);
                // Linked to Portfolio page input — light blue to indicate source
                formulaCell(ph, COL_MKT_VALUE_GBP, portfolioInputRef, linkedCell);
                styledCell(ph, COL_CURRENCY, "GBP", dataText);
                styledCell(ph, COL_SOURCE,   II_SIPP, dataText);
                rowNum++;
            }
            ranges.put(src, new int[]{ firstRow, rowNum - 1 });
        }

        // Currency label row above subtotals
        Row currHdrRow = sheet.createRow(currHdrPoi);
        styledCell(currHdrRow, COL_MKT_VALUE,     "USD", boldText);
        styledCell(currHdrRow, COL_MKT_VALUE_GBP, "GBP", boldText);
        styledCell(currHdrRow, COL_GAIN,           "GBP", boldText);

        // Per-source subtotal rows
        List<Integer> subPoiRows = new ArrayList<>();
        int subPoi = firstSubPoi;
        for (Map.Entry<String, int[]> re : ranges.entrySet()) {
            String src     = re.getKey();
            int fe         = re.getValue()[0] + 1;
            int le         = re.getValue()[1] + 1;
            int thisExcel  = subPoi + 1;

            Row row = sheet.createRow(subPoi);
            subPoiRows.add(subPoi);
            styledCell(row, COL_SECURITY_ID, src + " — Total", boldText);

            Cell usd = row.createCell(COL_MKT_VALUE);
            usd.setCellFormula("Roth IRA".equals(src)
                    ? "SUM(D" + fe + ":D" + le + ")"
                    : "E" + thisExcel + "*" + gbpusdRef);
            usd.setCellStyle(boldNum);

            formulaCell(row, COL_MKT_VALUE_GBP, "SUM(E" + fe + ":E" + le + ")", boldNum);
            formulaCell(row, COL_GAIN,           "SUM(F" + fe + ":F" + le + ")", boldNum);
            subPoi++;
        }

        // Grand total
        Row totalRow = sheet.createRow(grandTotalPoi);
        styledCell(totalRow, COL_SECURITY_ID, "PORTFOLIO TOTAL", boldText);
        formulaCell(totalRow, COL_MKT_VALUE,
                subPoiRows.stream().map(r -> "D" + (r+1)).collect(Collectors.joining("+")), boldNum);
        formulaCell(totalRow, COL_MKT_VALUE_GBP,
                subPoiRows.stream().map(r -> "E" + (r+1)).collect(Collectors.joining("+")), boldNum);
        formulaCell(totalRow, COL_GAIN,
                subPoiRows.stream().map(r -> "F" + (r+1)).collect(Collectors.joining("+")), boldNum);

        // Total Cash row — SUMIF picks up all CASH rows including the II SIPP linked cell
        int lastDataExcel  = numDataRows + 1;
        int totalCashExcel = totalCashPoi + 1;
        Row cashRow = sheet.createRow(totalCashPoi);
        styledCell(cashRow, COL_SECURITY_ID, "Total Cash", boldText);
        formulaCell(cashRow, COL_MKT_VALUE_GBP,
                "SUMIF($A$2:$A$" + lastDataExcel + ",\"CASH\",$E$2:$E$" + lastDataExcel + ")",
                boldNum);
        formulaCell(cashRow, COL_MKT_VALUE,
                "E" + totalCashExcel + "*" + gbpusdRef, boldNum);

        // Return % row: Gain / (Total Value GBP − Total Cash GBP)
        int gtExcel = grandTotalPoi + 1;
        Row rawReturnRow = sheet.createRow(returnPctPoi);
        styledCell(rawReturnRow, COL_SECURITY_ID, "Return %", boldText);
        Cell rawReturnCell = rawReturnRow.createCell(COL_GAIN_PCT);
        rawReturnCell.setCellFormula(
                "F" + gtExcel + "/(E" + gtExcel + "-E" + totalCashExcel + ")");
        rawReturnCell.setCellStyle(pctStyle(wb, true));

        // Total Return row: Gain / Total Value
        Row rawTotalReturnRow = sheet.createRow(totalReturnPoi);
        styledCell(rawTotalReturnRow, COL_SECURITY_ID, "Total Return", boldText);
        Cell rawTotalReturnCell = rawTotalReturnRow.createCell(COL_GAIN_PCT);
        rawTotalReturnCell.setCellFormula("F" + gtExcel + "/E" + gtExcel);
        rawTotalReturnCell.setCellStyle(pctStyle(wb, true));

        autoSizeColumns(sheet, NUM_COLS);
    }

    private static void writeHoldingRow(Row row, Holding h, Map<String, BigDecimal> gbpRates,
                                        CellStyle textStyle, CellStyle numStyle,
                                        CellStyle gainNum, CellStyle lossNum,
                                        CellStyle gainPct, CellStyle lossPct) {
        styledCell(row, COL_SECURITY_ID,   h.getSecurityId(),                 textStyle);
        setNumeric(row, COL_QUANTITY,       h.getQuantity(),                   numStyle);
        setNumeric(row, COL_AVG_PRICE,      h.getAvgPricePaid(),               numStyle);
        setNumeric(row, COL_MKT_VALUE,      h.getCurrentMarketValue(),         numStyle);
        BigDecimal gbpVal = toGbp(h, gbpRates);
        setNumeric(row, COL_MKT_VALUE_GBP,  gbpVal,                            numStyle);

        BigDecimal costGbp = costInGbp(h, gbpRates);
        if (costGbp != null && gbpVal != null) {
            BigDecimal g = gbpVal.subtract(costGbp);
            boolean pos  = g.compareTo(BigDecimal.ZERO) >= 0;
            setNumeric(row, COL_GAIN, g, pos ? gainNum : lossNum);
            if (costGbp.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal pct = g.divide(costGbp, 10, RoundingMode.HALF_UP);
                Cell c = row.createCell(COL_GAIN_PCT);
                c.setCellValue(pct.setScale(4, RoundingMode.HALF_UP).doubleValue());
                c.setCellStyle(pos ? gainPct : lossPct);
            }
        }

        styledCell(row, COL_CURRENCY, h.getCurrency().getCurrencyCode(), textStyle);
        styledCell(row, COL_SOURCE,   h.getSource(),                     textStyle);
    }

    // -------------------------------------------------------------------------
    // Raw input sheets
    // -------------------------------------------------------------------------

    private static void writeRawSheet(Path file, Sheet sheet) throws IOException {
        if (file.getFileName().toString().endsWith(".xlsx")) copyXlsxToSheet(file, sheet);
        else copyCsvToSheet(file, sheet);
    }

    private static void copyXlsxToSheet(Path file, Sheet target) throws IOException {
        try (Workbook src = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet srcSheet = src.getSheet("Holdings");
            if (srcSheet == null) srcSheet = src.getSheetAt(0);
            for (Row srcRow : srcSheet) {
                Row dest = target.createRow(srcRow.getRowNum());
                for (Cell srcCell : srcRow) {
                    Cell dc = dest.createCell(srcCell.getColumnIndex());
                    switch (srcCell.getCellType()) {
                        case STRING  -> dc.setCellValue(srcCell.getStringCellValue());
                        case NUMERIC -> dc.setCellValue(srcCell.getNumericCellValue());
                        case BOOLEAN -> dc.setCellValue(srcCell.getBooleanCellValue());
                        default      -> dc.setCellValue(srcCell.toString());
                    }
                }
            }
        }
    }

    private static void copyCsvToSheet(Path file, Sheet sheet) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        while (!content.isEmpty() && content.charAt(0) == '﻿') content = content.substring(1);
        try (CSVParser csv = CSVParser.parse(content, CSVFormat.DEFAULT)) {
            int rn = 0;
            for (CSVRecord record : csv) {
                Row row = sheet.createRow(rn++);
                for (int i = 0; i < record.size(); i++) row.createCell(i).setCellValue(record.get(i));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal toGbp(Holding h, Map<String, BigDecimal> gbpRates) {
        if (h.getCurrentMarketValueGbp() != null) return h.getCurrentMarketValueGbp();
        BigDecimal mktVal = h.getCurrentMarketValue();
        if (mktVal == null) return null;
        BigDecimal rate = gbpRates.get(h.getCurrency().getCurrencyCode());
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return null;
        return mktVal.divide(rate, 10, RoundingMode.HALF_UP);
    }

    private static void setNumeric(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(2, RoundingMode.HALF_UP).doubleValue());
        cell.setCellStyle(style);
    }

    /** Writes a rate value at full precision (no rounding). */
    private static void setRate(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void styledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void formulaCell(Row row, int col, String formula, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellFormula(formula);
        if (style != null) cell.setCellStyle(style);
    }

    /** Auto-sizes all columns then adds padding so AutoFilter arrows don't obscure header text. */
    private static void autoSizeColumns(Sheet sheet, int numCols) {
        for (int i = 0; i < numCols; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1024); // ~4 extra chars
        }
    }

    // -------------------------------------------------------------------------
    // Cell styles
    // -------------------------------------------------------------------------

    private static CellStyle boldTextStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont(); f.setBold(true); s.setFont(f);
        return s;
    }

    private static CellStyle numericStyle(Workbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        if (bold) { Font f = wb.createFont(); f.setBold(true); s.setFont(f); }
        return s;
    }

    private static CellStyle pctStyle(Workbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        if (bold) { Font f = wb.createFont(); f.setBold(true); s.setFont(f); }
        return s;
    }

    /** Format for FX rates — shows up to 4 decimal places without rounding to 2dp. */
    private static CellStyle rateNumStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.0000"));
        return s;
    }

    private static CellStyle inputCellStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    /** Light blue — indicates the cell's value is linked from the Portfolio page. */
    private static CellStyle linkedCellStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private static final byte[] GREEN = { (byte) 0,   (byte) 176, (byte) 80  };
    private static final byte[] RED   = { (byte) 255, (byte) 0,   (byte) 0   };

    private static CellStyle gainLossNumStyle(Workbook wb, boolean isGain) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        XSSFFont f = (XSSFFont) wb.createFont();
        f.setColor(new XSSFColor(isGain ? GREEN : RED, null));
        s.setFont(f);
        return s;
    }

    private static CellStyle gainLossPctStyle(Workbook wb, boolean isGain) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        XSSFFont f = (XSSFFont) wb.createFont();
        f.setColor(new XSSFColor(isGain ? GREEN : RED, null));
        s.setFont(f);
        return s;
    }
}
