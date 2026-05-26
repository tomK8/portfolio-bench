package com.pension.application;

/** Outcome of a cash-statement import. */
public record ImportCashResult(Status status, int inserted, String detail) {

    public enum Status { NOT_FOUND, NO_NEW_DATA, IMPORTED }

    public static ImportCashResult notFound() {
        return new ImportCashResult(Status.NOT_FOUND, 0, null);
    }

    public static ImportCashResult noNewData() {
        return new ImportCashResult(Status.NO_NEW_DATA, 0, null);
    }

    public static ImportCashResult imported(int inserted, String archivedTo) {
        return new ImportCashResult(Status.IMPORTED, inserted, archivedTo);
    }
}
