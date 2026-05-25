package com.pension;

import com.pension.model.AggHolding;
import com.pension.model.CashTransaction;
import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellCashStatementParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.CashTransactionParser;
import com.pension.parser.IISippParser;
import com.pension.parser.ParseException;
import com.pension.parser.RothIraParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    private static final Path INPUT_DIR  = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), "Documents");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private record SourceFile(AccountParser parser, Path file) {}



    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("Input directory not found: " + INPUT_DIR);
            return;
        }

        // Fetch live rates first so AJBellSippParser can use them during parsing
        Map<String, BigDecimal> gbpRates = new FxRateClient().fetchRates();
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

        UserInputDialogs dialogs = new UserInputDialogs(DB);
        BigDecimal iiSippCash = dialogs.promptForIISippCash();
        List<AggHolding> aggregated = new PortfolioAggregator().aggregate(holdings, gbpRates);
        Map<String, BigDecimal> dividendsBySymbol = loadDividendsBySymbol();

        Files.createDirectories(OUTPUT_DIR);
        String date = LocalDateTime.now().format(DATE_FMT);

        Map<String, Path> rawSources = new LinkedHashMap<>();
        for (SourceFile sf : sources) rawSources.put(sf.parser().sourceName(), sf.file());

        ExcelReportWriter writer = new ExcelReportWriter();

        Path mainOutput = OUTPUT_DIR.resolve("portfolio" + date + ".xlsx");
        writer.writeFullReport(mainOutput, aggregated, holdings, rawSources, gbpRates, iiSippCash, dividendsBySymbol);
        System.out.println("Written " + holdings.size() + " holdings to: " + mainOutput);

        Path summaryOutput = OUTPUT_DIR.resolve("Portfolio Summary-" + date + ".xlsx");
        writer.writeSummaryReport(summaryOutput, aggregated, gbpRates, iiSippCash, dividendsBySymbol);
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
        dialogs.promptAndSaveDividends(gbpRates);
        importCashTransactions();
    }

    // -------------------------------------------------------------------------
    // Database snapshot
    // -------------------------------------------------------------------------

    private static final Path AJ_BELL_CASH_PATH =
            Path.of(System.getProperty("user.home"), "Downloads", "cashstatements.csv");

    private static final PortfolioDatabase DB = new PortfolioDatabase();

    private static void saveSnapshot(BigDecimal totalGbp, BigDecimal totalGainGbp,
                                     BigDecimal totalCashGbp, BigDecimal returnPct,
                                     BigDecimal totalReturn, Map<String, BigDecimal> gbpRates) {
        DB.saveSnapshot(totalGbp, totalGainGbp, totalCashGbp, returnPct, totalReturn, gbpRates);
    }

    private static Map<String, BigDecimal> loadDividendsBySymbol() {
        return DB.loadDividendsBySymbol();
    }

    private static void importCashTransactions() {
        if (!Files.exists(AJ_BELL_CASH_PATH)) {
            System.out.println("Cash statement not found at " + AJ_BELL_CASH_PATH + " — skipping");
            return;
        }
        try {
            CashTransactionParser parser = new AJBellCashStatementParser();
            List<CashTransaction> txns = parser.parse(AJ_BELL_CASH_PATH);
            int inserted = saveCashTransactions(txns);

            if (inserted > 0) {
                String dated = "cashstatements_" + LocalDate.now() + ".csv";
                Path dest = DB.dbDir.resolve(dated);
                Files.move(AJ_BELL_CASH_PATH, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Cash statement archived to " + dest);
            } else {
                Files.delete(AJ_BELL_CASH_PATH);
                System.out.println("Cash statement contained no new data — deleted");
            }
        } catch (IOException | ParseException e) {
            System.err.println("Warning: could not parse cash statement — " + e.getMessage());
        }
    }

    private static int saveCashTransactions(List<CashTransaction> transactions) {
        return DB.saveCashTransactions(transactions);
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

}
