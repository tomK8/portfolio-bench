package com.pension.web;

import com.pension.application.RawDividend;
import com.pension.application.RecordDividendsService;
import com.pension.application.RecordResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
public class DividendsController {

    private final RecordDividendsService recordService;

    public DividendsController(RecordDividendsService recordService) {
        this.recordService = recordService;
    }

    private static String at(List<String> list, int i) {
        return list != null && i < list.size() && list.get(i) != null ? list.get(i) : "";
    }

    @GetMapping("/dividends")
    public String page(Model model) {
        model.addAttribute("today", LocalDate.now().toString());
        return "dividends";
    }

    /**
     * Returns a single blank row, appended client-side by htmx.
     */
    @GetMapping("/dividends/row")
    public String row(Model model) {
        model.addAttribute("today", LocalDate.now().toString());
        return "fragments/dividends :: row";
    }

    @PostMapping("/dividends")
    public String record(@RequestParam(required = false) List<String> date,
                         @RequestParam(required = false) List<String> account,
                         @RequestParam(required = false) List<String> symbol,
                         @RequestParam(required = false) List<String> currency,
                         @RequestParam(required = false) List<String> amount,
                         Model model) {
        List<RawDividend> rows = new ArrayList<>();
        int count = date == null ? 0 : date.size();
        for (int i = 0; i < count; i++) {
            rows.add(new RawDividend(
                    at(date, i), at(account, i), at(symbol, i), at(currency, i), at(amount, i)));
        }
        RecordResult result = recordService.record(rows);
        model.addAttribute("result", result);
        return "fragments/dividends :: result";
    }
}
