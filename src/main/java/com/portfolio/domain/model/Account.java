package com.portfolio.domain.model;

import java.util.List;

/**
 * Brokerage accounts. The {@code dbValue} is the legacy mixed-case string persisted in
 * {@code cash_transactions.account}; the column's CHECK constraint is derived from these
 * constants at DDL time, so adding one here is all a new account needs — the repository
 * migrates an existing database on the next start.
 *
 * <p>{@code manual} marks an account with no downloadable broker export, whose ledger rows
 * are typed in on the Manual positions screen rather than imported from a file. It is a
 * property of the account rather than a list held elsewhere, so the screen's broker choices
 * and the holdings-file projection both read the same declaration.
 */
public enum Account {
    AJBELL("AJBell", false),
    /**
     * AJ Bell Dealing account — a second AJ Bell portfolio behind the same login. Its exports are
     * byte-identical in shape to the SIPP's and carry no account marker, so which account a
     * {@code cashstatements*.csv} or {@code activeorders*.csv} belongs to is decided by
     * {@link com.portfolio.domain.AjBellPortfolios} rather than read off the file.
     */
    AJBELL_DEALING("AJBellDealing", false),
    ROTH_IRA("RothIRA", false),
    II("II", false),
    /** Employee share plan (Equate Plus). No export exists — positions are entered by hand. */
    EQUATE("Equate", true);

    private final String dbValue;
    private final boolean manual;

    Account(String dbValue, boolean manual) {
        this.dbValue = dbValue;
        this.manual = manual;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * The label this account is printed under everywhere in the UI, and the same string the order
     * parsers report as their {@code sourceName()} — so a broker name coming off an order and an
     * account name coming off the ledger are the same word and can be matched.
     *
     * <p>This is the single home for that mapping. The private copies several services carried
     * from before this method existed have all moved here — a second AJ Bell account was invisible
     * in every one of them, which is what finally forced it.
     */
    public String displayName() {
        return switch (this) {
            case AJBELL -> "AJ Bell SIPP";
            case AJBELL_DEALING -> "AJ Bell Dealing";
            case ROTH_IRA -> "Roth IRA";
            case II -> "II SIPP";
            case EQUATE -> "Equate";
        };
    }

    /**
     * The label for a stored {@code account} value, or the value itself when it names no account —
     * so a row written by a future constant still prints something rather than vanishing.
     */
    public static String displayNameOf(String dbValue) {
        for (Account a : values()) {
            if (a.dbValue.equals(dbValue)) return a.displayName();
        }
        return dbValue;
    }

    /** The account a display label names, or null when it names none. */
    public static Account fromDisplayName(String label) {
        if (label == null) return null;
        for (Account a : values()) {
            if (a.displayName().equalsIgnoreCase(label.trim())) return a;
        }
        return null;
    }

    /** Whether this account's ledger rows are hand-entered rather than parsed from an export. */
    public boolean isManual() {
        return manual;
    }

    /** The hand-entered accounts, in declaration order. */
    public static List<Account> manualAccounts() {
        return java.util.Arrays.stream(values()).filter(Account::isManual).toList();
    }

    public static Account fromDbValue(String value) {
        for (Account a : values()) {
            if (a.dbValue.equals(value)) return a;
        }
        throw new IllegalArgumentException("Unknown account: " + value);
    }
}
