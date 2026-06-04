package com.portfolio.domain.model;

/**
 * Brokerage accounts. The {@code dbValue} is the legacy mixed-case string persisted in
 * {@code cash_transactions.account} (kept identical so the existing CHECK constraint
 * and on-disk data are unchanged).
 */
public enum Account {
    AJBELL("AJBell"),
    ROTH_IRA("RothIRA"),
    II("II");

    private final String dbValue;

    Account(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Account fromDbValue(String value) {
        for (Account a : values()) {
            if (a.dbValue.equals(value)) return a;
        }
        throw new IllegalArgumentException("Unknown account: " + value);
    }
}
