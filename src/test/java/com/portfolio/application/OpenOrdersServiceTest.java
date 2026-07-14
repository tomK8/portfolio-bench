package com.portfolio.application;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.application.OpenOrdersService.OrdersReport;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.parser.AJBellOrdersParser;
import com.portfolio.parser.IIOrdersParser;
import com.portfolio.parser.RothOrdersParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OpenOrdersServiceTest {

    private OpenOrdersService service(Path inputDir) {
        return new OpenOrdersService(new HoldingFileLocator(inputDir),
                List.of(new AJBellOrdersParser(), new IIOrdersParser(), new RothOrdersParser()));
    }

    @Test
    void emptyWhenNoOrderFiles(@TempDir Path dir) {
        OrdersReport report = service(dir).load();
        assertTrue(report.empty());
        assertTrue(report.orders().isEmpty());
        assertTrue(report.sources().isEmpty());
    }

    @Test
    void keepsOnlyOpenOrdersAcrossAllBrokers(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "RELX PLC","44626CC3LTC","Sell","Limit","100","2596.5","2600p","01/07/2026","22/09/2026","In progress"
                "Invesco EQQQ NASDAQ-100 ETF GBP","44626CD2R9X","Sell","Limit","50","26901.41","53800p","14/07/2026","29/07/2026","Dealt"
                """);
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                14/07/2026 16:52,27726IDT248,SELL/LIMIT,NVDA,NVIDIA Corp,51,n/a,"$11,467.84",PENDING
                14/07/2026 15:06,27726IDTNZM,SELL/LIMIT,GOOG,Alphabet Inc Class C,45,15/07/2026,"$15,885.00",EXECUTED
                """);
        writeRothWorkbook(dir.resolve("Order+Status.xlsx"));

        OrdersReport report = service(dir).load();

        assertFalse(report.empty());
        assertEquals(3, report.sources().size(), "one source line per broker with a file");

        // 1 open per broker (Dealt / EXECUTED / Canceled excluded)
        assertEquals(3, report.orders().size());
        assertTrue(report.orders().stream().allMatch(o -> OpenOrdersService.isOpen(o.status())));

        List<String> brokers = report.orders().stream().map(OpenOrder::broker).collect(Collectors.toList());
        assertTrue(brokers.contains("AJ Bell SIPP"));
        assertTrue(brokers.contains("II SIPP"));
        assertTrue(brokers.contains("Roth IRA"));

        // Grouped by parser order: AJ Bell, then II, then Roth
        assertEquals("AJ Bell SIPP", brokers.get(0));
        assertEquals("II SIPP", brokers.get(1));
        assertEquals("Roth IRA", brokers.get(2));
    }

    @Test
    void isOpen_treatsTerminalStatusesAsClosed() {
        assertTrue(OpenOrdersService.isOpen("In progress"));
        assertTrue(OpenOrdersService.isOpen("PENDING"));
        assertTrue(OpenOrdersService.isOpen("Open"));
        assertTrue(OpenOrdersService.isOpen(null));
        assertFalse(OpenOrdersService.isOpen("Dealt"));
        assertFalse(OpenOrdersService.isOpen("EXECUTED"));
        assertFalse(OpenOrdersService.isOpen("Canceled"));
        assertFalse(OpenOrdersService.isOpen("cancelled"));
    }

    private static void writeRothWorkbook(Path file) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Order Status");
            sheet.createRow(1).createCell(1).setCellValue("Order Status");
            Row hdr = sheet.createRow(3);
            String[] cols = {"Date", "Transaction", "Security ID", "Quantity", "Order Type", "Price", "Duration", "Status"};
            for (int i = 0; i < cols.length; i++) hdr.createCell(1 + i).setCellValue(cols[i]);
            row(sheet, 4, "07/14/2026 11:13:37 AM", "Sell", "SNPS", "6", "Limit", "460.00", "GTC", "Open");
            row(sheet, 5, "07/14/2026 10:25:28 AM", "Sell", "ASML", "5", "Limit", "1780.00", "GTC", "Canceled");
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }
    }

    private static void row(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) row.createCell(1 + i).setCellValue(values[i]);
    }
}
