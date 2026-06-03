package com.pension.application;

/**
 * Outcome of importing one cash source (one statement file). Only emitted when
 * a file was actually processed — missing files produce no result.
 */
public record ImportCashResult(String source, Status status, int inserted, String detail) {

    public static ImportCashResult noNewData(String source) {
        return new ImportCashResult(source, Status.NO_NEW_DATA, 0, null);
    }

    public static ImportCashResult imported(String source, int inserted, String archivedTo) {
        return new ImportCashResult(source, Status.IMPORTED, inserted, archivedTo);
    }

    public enum Status {NO_NEW_DATA, IMPORTED}
}
