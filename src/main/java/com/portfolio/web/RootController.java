package com.portfolio.web;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.application.BackupService;
import com.portfolio.application.LedgerGroupingService;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.WatchlistRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;


/**
 * Landing-page controller. Renders the Thymeleaf {@code dashboard} template populated with
 * the last II SIPP cash values typed into the form (so the inputs remember what the user
 * entered across reloads). All other behavior lives in the JSON/POST endpoints under
 * {@link OperationsController}, {@link ChartsController} and {@link AnalysisController}.
 */
@Controller
public class RootController {

    private final KeyValueStore settings;
    private final LedgerGroupingService groupingService;
    private final WatchlistRepository watchlistRepo;

    /**
     * Whether this instance is running on generated demo data. Set on the command line by
     * {@code scripts/demo.sh}, which also points the three data directories at {@code demo/}.
     * A command-line argument outranks {@code ~/.portfolio-bench.properties}, so the banner
     * cannot be switched off from the home config while the demo directories are in use.
     */
    private final boolean demo;

    /**
     * The input directory as the locator actually resolved it, so the "reads …" hints
     * beside the import buttons name the directory being read. Hardcoding ~/Downloads
     * made those hints wrong in demo mode, in the one way that undercuts the banner.
     */
    private final String inputDir;
    private final String backupDir;

    public RootController(KeyValueStore settings, LedgerGroupingService groupingService,
                          HoldingFileLocator fileLocator,
                          BackupService backupService,
                          WatchlistRepository watchlistRepo,
                          @Value("${portfolio.demo:false}") boolean demo) {
        this.settings = settings;
        this.groupingService = groupingService;
        this.watchlistRepo = watchlistRepo;
        this.demo = demo;
        this.inputDir = abbreviateHome(fileLocator.inputDir().toString());
        this.backupDir = abbreviateHome(backupService.destination().toString());
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("appName", demo ? "Portfolio Bench — DEMO" : "Portfolio Bench");
        model.addAttribute("demo", demo);
        model.addAttribute("inputDir", inputDir);
        model.addAttribute("backupDir", backupDir);
        // One attribute per II SIPP cash box, named after the parameter the box posts under, so
        // the set of boxes is declared once (IiCashField) rather than here and in the template.
        WebSupport.storedIiCash(settings).forEach(model::addAttribute);
        // The Cash-ledger grouping dropdown is generated from the enum rather than hand-written
        // in the template, so a new key type is a constant plus a resolver and nothing else.
        model.addAttribute("groupOptions", groupingService.options());
        // The watchlists are rendered server-side because the tab machinery derives PANEL_TO_GROUP
        // from the DOM while dashboard.js runs — a strip filled in later by fetch() would be empty
        // at that moment, and the restored-tab path would find no group for the saved panel.
        model.addAttribute("watchlists", watchlistRepo.lists());
        return "dashboard";
    }

    // Both standalone templates with their own <head> — there is no layout dialect here, so
    // each needs the flag passed explicitly or the banner would vanish on these two pages.
    /** {@code /Users/me/x} as {@code ~/x} — how these hints have always read, and shorter. */
    private static String abbreviateHome(String path) {
        String home = System.getProperty("user.home");
        return (home != null && !home.isBlank() && path.startsWith(home))
                ? "~" + path.substring(home.length())
                : path;
    }

    @GetMapping("/reference")
    public String reference(Model model) {
        model.addAttribute("demo", demo);
        return "reference";
    }

    @GetMapping("/glossary")
    public String glossary(Model model) {
        model.addAttribute("demo", demo);
        return "glossary";
    }
}
