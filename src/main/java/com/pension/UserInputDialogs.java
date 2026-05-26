package com.pension;

import java.awt.HeadlessException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;

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
}
