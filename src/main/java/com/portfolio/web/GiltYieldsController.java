package com.portfolio.web;

import com.portfolio.application.GiltYieldService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The gilt-yield table on the Reference page. The page itself is static and renders at once; htmx
 * loads this fragment into it and re-polls while the page is visible. Thin — all work is in
 * {@link GiltYieldService}.
 */
@Controller
public class GiltYieldsController {

    private final GiltYieldService giltYields;

    public GiltYieldsController(GiltYieldService giltYields) {
        this.giltYields = giltYields;
    }

    @GetMapping("/reference/gilt-yields")
    public String giltYields(Model model) {
        model.addAttribute("report", giltYields.current());
        return "fragments/gilt-yields :: table";
    }
}
