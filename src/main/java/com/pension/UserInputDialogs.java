package com.pension;

import com.pension.model.DividendEntry;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.HeadlessException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public class UserInputDialogs {

    private final PortfolioDatabase db;

    public UserInputDialogs(PortfolioDatabase db) { this.db = db; }

    // -------------------------------------------------------------------------

    public BigDecimal promptForIISippCash() {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        String lastSaved = "";
        try {
            if (Files.exists(db.lastIiCashFile))
                lastSaved = Files.readString(db.lastIiCashFile).trim();
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
                    Files.createDirectories(db.dbDir);
                    Files.writeString(db.lastIiCashFile, value.toPlainString());
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

    public void promptAndSaveDividends(Map<String, BigDecimal> gbpRates) {
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

            List<DividendEntry> entries = new ArrayList<>();
            List<String>        errors  = new ArrayList<>();
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
            if (!entries.isEmpty()) db.saveDividends(entries);

        } catch (HeadlessException e) {
            System.out.println("No display available — skipping dividend entry");
        }
    }
}
