package com.portfolio.parser;

import com.portfolio.domain.model.CashTransaction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface CashTransactionParser {
    String accountName();

    boolean supports(Path file);

    List<CashTransaction> parse(Path file) throws IOException, ParseException;
}
