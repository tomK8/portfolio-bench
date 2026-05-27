package com.pension.application;

import java.util.List;

/**
 * Outcome of recording dividends: how many were saved, or the validation errors that blocked the save.
 */
public record RecordResult(int savedCount, List<String> errors) {

    public boolean ok() {
        return errors.isEmpty();
    }
}
