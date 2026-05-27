package com.pension.application;

/** Outcome of importing one cash source (one statement file). */
public record ImportCashResult(String source, Status status, int inserted, String detail) {

    public enum Status { NOT_FOUND, NO_NEW_DATA, IMPORTED }

    public static ImportCashResult notFound(String source) {
        return new ImportCashResult(source, Status.NOT_FOUND, 0, null);
    }

    public static ImportCashResult noNewData(String source) {
        return new ImportCashResult(source, Status.NO_NEW_DATA, 0, null);
    }

    public static ImportCashResult imported(String source, int inserted, String archivedTo) {
        return new ImportCashResult(source, Status.IMPORTED, inserted, archivedTo);
    }
}
