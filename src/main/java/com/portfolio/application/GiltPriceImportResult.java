package com.portfolio.application;

/**
 * Outcome of importing one Tradeweb gilt-prices CSV. {@code touched} counts insert + update rows
 * (idempotent re-imports often have {@code touched == row count} while inserting no new rows).
 * {@code detail} carries the archived file path for IMPORTED, otherwise null.
 */
public record GiltPriceImportResult(Status status, int touched, String sourceFile, String detail) {

    public static GiltPriceImportResult imported(int touched, String sourceFile, String archivedTo) {
        return new GiltPriceImportResult(Status.IMPORTED, touched, sourceFile, archivedTo);
    }

    public static GiltPriceImportResult failed(String sourceFile, String message) {
        return new GiltPriceImportResult(Status.FAILED, 0, sourceFile, message);
    }

    public static GiltPriceImportResult notFound() {
        return new GiltPriceImportResult(Status.NOT_FOUND, 0, null, null);
    }

    public enum Status {IMPORTED, FAILED, NOT_FOUND}
}
