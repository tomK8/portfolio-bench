package com.pension;

import com.pension.model.Holding;
import com.pension.parser.AccountParser;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.IISippParser;
import com.pension.parser.RothIraParser;
import java.math.RoundingMode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class Main {

    private static final Path INPUT_DIR  = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), "Documents");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final List<AccountParser> PARSERS = List.of(
            new RothIraParser(),
            new AJBellSippParser(),
            new IISippParser()
    );

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("Input directory not found: " + INPUT_DIR);
            return;
        }

        List<Holding> holdings = new ArrayList<>();

        for (AccountParser parser : PARSERS) {
            Optional<Path> file = findMostRecent(INPUT_DIR, parser);
            if (file.isPresent()) {
                System.out.println("Parsing: " + file.get().getFileName());
                holdings.addAll(parser.parse(file.get()));
            }
        }

        if (holdings.isEmpty()) {
            System.out.println("No holdings found — check that input files are present in " + INPUT_DIR);
            return;
        }

        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        Path output = OUTPUT_DIR.resolve("portfolio" + timestamp + ".xlsx");

        writeExcel(holdings, output);
        System.out.println("Written " + holdings.size() + " holdings to: " + output);
    }

    // -------------------------------------------------------------------------

    private static Optional<Path> findMostRecent(Path dir, AccountParser parser) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(parser::supports)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        }
    }

    private static void writeExcel(List<Holding> holdings, Path output) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Portfolio");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            CellStyle numericStyle = wb.createCellStyle();
            numericStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            String[] headers = {
                "Security ID", "Quantity", "Avg Price Paid", "Market Value", "Currency", "Source"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Holding h : holdings) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(h.getSecurityId());
                setNumeric(row, 1, h.getQuantity(),           numericStyle);
                setNumeric(row, 2, h.getAvgPricePaid(),       numericStyle);
                setNumeric(row, 3, h.getCurrentMarketValue(), numericStyle);
                row.createCell(4).setCellValue(h.getCurrency().getCurrencyCode());
                row.createCell(5).setCellValue(h.getSource());
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            try (OutputStream os = Files.newOutputStream(output)) {
                wb.write(os);
            }
        }
    }

    private static void setNumeric(Row row, int col, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(col);
        cell.setCellValue(value.setScale(2, RoundingMode.HALF_UP).doubleValue());
        cell.setCellStyle(style);
    }
}
