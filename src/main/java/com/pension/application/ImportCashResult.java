package com.pension.application;

/**
 * Outcome of importing one cash source. One row per source: IMPORTED / NO_NEW_DATA when a
 * file was processed, NOT_FOUND when no matching file existed in the input dir.
 *
 * <p>{@code sourceFile} is the filename that was processed (without directory), or {@code null}
 * for NOT_FOUND. {@code detail} carries the archived file path for IMPORTED, otherwise null.
 */
public record ImportCashResult(String source, Status status, int inserted,
                               String sourceFile, String detail) {

    public static ImportCashResult noNewData(String source, String sourceFile) {
        return new ImportCashResult(source, Status.NO_NEW_DATA, 0, sourceFile, null);
    }

    public static ImportCashResult imported(String source, int inserted,
                                            String sourceFile, String archivedTo) {
        return new ImportCashResult(source, Status.IMPORTED, inserted, sourceFile, archivedTo);
    }

    public static ImportCashResult notFound(String source) {
        return new ImportCashResult(source, Status.NOT_FOUND, 0, null, null);
    }

    public enum Status {NO_NEW_DATA, IMPORTED, NOT_FOUND}
}
