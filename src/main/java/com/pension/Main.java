package com.pension;

import com.pension.model.AggHolding;
import com.pension.model.CashTransaction;
import com.pension.model.DividendEntry;
import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellCashStatementParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.CashTransactionParser;
import com.pension.parser.IISippParser;
import com.pension.parser.ParseException;
import com.pension.parser.RothIraParser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.HeadlessException;
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

        BigDecimal iiSippCash = promptForIISippCash();
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
        promptAndSaveDividends(gbpRates);
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

    // -------------------------------------------------------------------------
    // User input
    // -------------------------------------------------------------------------

    private static BigDecimal promptForIISippCash() {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        String lastSaved = "";
        try {
            if (Files.exists(DB.lastIiCashFile))
                lastSaved = Files.readString(DB.lastIiCashFile).trim();
        } catch (IOException ignored) {}

        while (true) {
            try {
                Object result = javax.swing.JOptionPane.showInputDialog(
                        null,
                        "Enter II SIPP Cash balance (GBP):",
                        "II SIPP Cash",
                        javax.swing.JOptionPane.QUESTION_MESSAGE,
                        null, null,
                        lastSaved);

                if (result == null) {
                    System.out.println("II SIPP cash input cancelled — using 0");
                    return BigDecimal.ZERO;
                }
                String raw = result.toString().replace(",", "").replace("£", "").trim();
                if (raw.isEmpty()) return BigDecimal.ZERO;

                BigDecimal value = new BigDecimal(raw);

                try {
                    Files.createDirectories(DB.dbDir);
                    Files.writeString(DB.lastIiCashFile, value.toPlainString());
                } catch (IOException ignored) {}

                return value;

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

    private static void promptAndSaveDividends(Map<String, BigDecimal> gbpRates) {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        try {
            Object[] opts = {"Yes", "No"};
            int choice = javax.swing.JOptionPane.showOptionDialog(
                    null,
                    "Do you have dividends to record?",
                    "Dividend Entry",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.QUESTION_MESSAGE,
                    null, opts, opts[1]);
            if (choice != 0) return; // 0 = Yes

            String today = LocalDate.now().toString();
            String[] colNames = {"Date (YYYY-MM-DD)", "Account", "Symbol", "Currency", "Amount"};
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(colNames, 0) {
                @Override public boolean isCellEditable(int r, int c) { return true; }
            };
            model.addRow(new Object[]{today, "AJBELL", "", "GBP", ""});

            javax.swing.JTable table = new javax.swing.JTable(model);
            table.setRowHeight(24);
            table.getColumnModel().getColumn(0).setPreferredWidth(120);
            table.getColumnModel().getColumn(4).setPreferredWidth(90);

            javax.swing.JComboBox<String> accountCombo =
                    new javax.swing.JComboBox<>(new String[]{"AJBELL", "II", "ROTH_IRA"});
            table.getColumnModel().getColumn(1).setCellEditor(
                    new javax.swing.DefaultCellEditor(accountCombo));

            javax.swing.JComboBox<String> ccyCombo =
                    new javax.swing.JComboBox<>(new String[]{"GBP", "USD", "EUR"});
            table.getColumnModel().getColumn(3).setCellEditor(
                    new javax.swing.DefaultCellEditor(ccyCombo));

            javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table);
            scroll.setPreferredSize(new Dimension(700, 140));

            javax.swing.JButton addBtn = new javax.swing.JButton("+ Add Row");
            addBtn.addActionListener(e -> model.addRow(new Object[]{today, "AJBELL", "", "GBP", ""}));
            javax.swing.JButton removeBtn = new javax.swing.JButton("Remove Selected");
            removeBtn.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) model.removeRow(row);
            });

            javax.swing.JPanel btnPanel = new javax.swing.JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            btnPanel.add(addBtn);
            btnPanel.add(removeBtn);

            javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(0, 8));
            panel.add(new javax.swing.JLabel(
                    "<html>Enter dividend payments. Amount is in the selected currency — FX rates used: " +
                    "1 GBP = " + String.format("%.4f", gbpRates.getOrDefault("USD", BigDecimal.ONE)) + " USD, " +
                    String.format("%.4f", gbpRates.getOrDefault("EUR", BigDecimal.ONE)) + " EUR</html>"),
                    BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(btnPanel, BorderLayout.SOUTH);

            int result = javax.swing.JOptionPane.showConfirmDialog(
                    null, panel, "Record Dividends",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE);
            if (result != javax.swing.JOptionPane.OK_OPTION) return;

            if (table.isEditing()) table.getCellEditor().stopCellEditing();

            List<DividendEntry> entries  = new ArrayList<>();
            List<String>        errors   = new ArrayList<>();
            for (int r = 0; r < model.getRowCount(); r++) {
                String date   = String.valueOf(model.getValueAt(r, 0)).trim();
                String acct   = String.valueOf(model.getValueAt(r, 1)).trim();
                String symbol = String.valueOf(model.getValueAt(r, 2)).trim().toUpperCase();
                String ccy    = String.valueOf(model.getValueAt(r, 3)).trim().toUpperCase();
                String amtStr = String.valueOf(model.getValueAt(r, 4))
                                      .replace(",", "").replace("$", "").replace("£", "")
                                      .replace("€", "").trim();

                if (symbol.isEmpty() && amtStr.isEmpty()) continue;

                if (date.isEmpty() || symbol.isEmpty() || amtStr.isEmpty()) {
                    errors.add("Row " + (r + 1) + ": date, symbol and amount are required");
                    continue;
                }
                double amount;
                try {
                    amount = Double.parseDouble(amtStr);
                } catch (NumberFormatException e) {
                    errors.add("Row " + (r + 1) + ": invalid amount '" + amtStr + "'");
                    continue;
                }
                double fxToGbp = switch (ccy) {
                    case "USD" -> gbpRates.getOrDefault("USD", BigDecimal.ONE).doubleValue();
                    case "EUR" -> gbpRates.getOrDefault("EUR", BigDecimal.ONE).doubleValue();
                    default    -> 1.0;
                };
                double amountGbp = "GBP".equals(ccy) ? amount : amount / fxToGbp;
                entries.add(new DividendEntry(date, acct, symbol, ccy, amount, fxToGbp, amountGbp));
            }

            if (!errors.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(null,
                        String.join("\n", errors), "Validation Errors",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!entries.isEmpty()) saveDividends(entries);

        } catch (HeadlessException e) {
            System.out.println("No display available — skipping dividend entry");
        }
    }

    private static void saveDividends(List<DividendEntry> entries) {
        DB.saveDividends(entries);
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
