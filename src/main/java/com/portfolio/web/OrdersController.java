package com.portfolio.web;

import com.portfolio.ExcelReportWriter;
import com.portfolio.application.OpenOrdersService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Serves the Open orders tab — a read-only table of resting orders parsed from the latest broker
 * order-status exports, plus an Excel download of the same. Thin, like the sync endpoints: it
 * renders an htmx fragment / streams a workbook and delegates all work to {@link OpenOrdersService}.
 */
@Controller
public class OrdersController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OpenOrdersService openOrdersService;
    private final ExcelReportWriter excelReportWriter;

    public OrdersController(OpenOrdersService openOrdersService, ExcelReportWriter excelReportWriter) {
        this.openOrdersService = openOrdersService;
        this.excelReportWriter = excelReportWriter;
    }

    @GetMapping("/orders")
    public String orders(Model model) {
        model.addAttribute("orders", openOrdersService.load());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/orders :: result";
    }

    /** Streams the current open orders as an .xlsx download (re-parsed fresh, so always current). */
    @GetMapping("/orders/export")
    public ResponseEntity<byte[]> exportOrders() throws IOException {
        byte[] workbook = excelReportWriter.ordersWorkbook(openOrdersService.load().orders());
        String filename = "Open Orders-" + LocalDate.now().format(DATE_FMT) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbook);
    }
}
