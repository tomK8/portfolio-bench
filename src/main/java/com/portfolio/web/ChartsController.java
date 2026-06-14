package com.portfolio.web;

import com.portfolio.application.AllocationService;
import com.portfolio.application.AllocationService.AllocationTimeline;
import com.portfolio.application.AttributionService;
import com.portfolio.application.AttributionService.AttributionResult;
import com.portfolio.application.BenchmarkReturnService;
import com.portfolio.application.BenchmarkReturnService.BenchmarkTimeline;
import com.portfolio.application.ConcentrationService;
import com.portfolio.application.ConcentrationService.ConcentrationMetrics;
import com.portfolio.application.ContributionService;
import com.portfolio.application.ContributionService.ContributionTimeline;
import com.portfolio.application.CorrelationService;
import com.portfolio.application.CorrelationService.CorrelationReport;
import com.portfolio.application.CurrencyExposureService;
import com.portfolio.application.CurrencyExposureService.CurrencyExposure;
import com.portfolio.application.DividendAuditService;
import com.portfolio.application.DividendAuditService.AuditReport;
import com.portfolio.application.DividendIncomeService;
import com.portfolio.application.DividendIncomeService.DividendIncome;
import com.portfolio.application.PortfolioReturnService;
import com.portfolio.application.PortfolioReturnService.ReturnTimeline;
import com.portfolio.application.PortfolioRiskService;
import com.portfolio.application.PortfolioRiskService.RiskTimeline;
import com.portfolio.application.PortfolioValueService;
import com.portfolio.application.PortfolioValueService.ValueTimeline;
import com.portfolio.application.PositionDetailService;
import com.portfolio.application.PositionDetailService.PositionDetail;
import com.portfolio.application.TargetAllocationService;
import com.portfolio.application.TargetAllocationService.TargetReport;
import com.portfolio.persistence.CashTransactionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only JSON endpoints that feed the dashboard's Chart.js panels (Contributions,
 * Value over time, Returns, Risk, Allocation, Attribution, Dividends, Concentration,
 * Correlations, Currency, Position drill-down).
 *
 * <p>Two write endpoints live here too — risk-free-rate persistence and target-weight
 * saving — because they belong tightly to the read paths they configure, and splitting
 * them out would scatter the Risk and Allocation surfaces across two controllers.
 */
@Controller
public class ChartsController {

    private final ContributionService contributionService;
    private final PortfolioValueService portfolioValueService;
    private final PortfolioReturnService portfolioReturnService;
    private final PortfolioRiskService portfolioRiskService;
    private final BenchmarkReturnService benchmarkReturnService;
    private final AllocationService allocationService;
    private final TargetAllocationService targetAllocationService;
    private final AttributionService attributionService;
    private final DividendIncomeService dividendIncomeService;
    private final DividendAuditService dividendAuditService;
    private final ConcentrationService concentrationService;
    private final CorrelationService correlationService;
    private final CurrencyExposureService currencyExposureService;
    private final PositionDetailService positionDetailService;
    private final CashTransactionRepository cashRepo;

    public ChartsController(ContributionService contributionService,
                            PortfolioValueService portfolioValueService,
                            PortfolioReturnService portfolioReturnService,
                            PortfolioRiskService portfolioRiskService,
                            BenchmarkReturnService benchmarkReturnService,
                            AllocationService allocationService,
                            TargetAllocationService targetAllocationService,
                            AttributionService attributionService,
                            DividendIncomeService dividendIncomeService,
                            DividendAuditService dividendAuditService,
                            ConcentrationService concentrationService,
                            CorrelationService correlationService,
                            CurrencyExposureService currencyExposureService,
                            PositionDetailService positionDetailService,
                            CashTransactionRepository cashRepo) {
        this.contributionService = contributionService;
        this.portfolioValueService = portfolioValueService;
        this.portfolioReturnService = portfolioReturnService;
        this.portfolioRiskService = portfolioRiskService;
        this.benchmarkReturnService = benchmarkReturnService;
        this.allocationService = allocationService;
        this.targetAllocationService = targetAllocationService;
        this.attributionService = attributionService;
        this.dividendIncomeService = dividendIncomeService;
        this.dividendAuditService = dividendAuditService;
        this.concentrationService = concentrationService;
        this.correlationService = correlationService;
        this.currencyExposureService = currencyExposureService;
        this.positionDetailService = positionDetailService;
        this.cashRepo = cashRepo;
    }

    @GetMapping("/contributions")
    @ResponseBody
    public ContributionTimeline contributions() {
        return contributionService.timeline();
    }

    @GetMapping("/portfolio-value")
    @ResponseBody
    public ValueTimeline portfolioValue() {
        return portfolioValueService.timeline();
    }

    @GetMapping("/returns")
    @ResponseBody
    public ReturnTimeline returns() {
        return portfolioReturnService.timeline();
    }

    @GetMapping("/returns/mwr")
    @ResponseBody
    public PortfolioReturnService.MwrSummary moneyWeightedReturns() {
        return portfolioReturnService.moneyWeightedSummary();
    }

    @GetMapping("/risk")
    @ResponseBody
    public RiskTimeline risk() {
        return portfolioRiskService.timeline();
    }

    /**
     * Persist the risk-free rate used by Sharpe / Sortino / Calmar. The form posts a
     * percentage (e.g. "4" for 4%); we divide by 100 here and store the fraction so
     * {@link PortfolioRiskService} reads a consistent shape regardless of how the user typed
     * it. Clamps to [0, 1] to keep typos from poisoning the math.
     */
    @PostMapping("/risk/rate")
    @ResponseBody
    public Map<String, String> setRiskRate(@RequestParam("rate") String rate) {
        String clean = rate == null ? "" : rate.replace("%", "").trim();
        BigDecimal pct;
        try {
            pct = new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Risk-free rate '" + rate + "' is not a number.");
        }
        BigDecimal fraction = pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Risk-free rate must be between 0% and 100%.");
        }
        portfolioRiskService.setRiskFreeRate(fraction);
        return Map.of("rate", fraction.toPlainString());
    }

    /**
     * Cumulative return of a single benchmark ticker (any Yahoo-style symbol), anchored at the
     * portfolio's inception date. If {@code price_history} has no rows for the symbol the
     * response is empty with {@code missing=true}; the UI then prompts the user to confirm a
     * background Yahoo backfill via {@code POST /returns/benchmark/fetch}.
     */
    @GetMapping("/returns/benchmark")
    @ResponseBody
    public BenchmarkTimeline benchmarkReturns(@RequestParam("symbol") String symbol) {
        return benchmarkReturnService.timeline(symbol);
    }

    /** Held / previously-traded symbols, for the benchmark dropdown. */
    @GetMapping("/returns/symbols")
    @ResponseBody
    public List<String> tradedSymbols() {
        return cashRepo.distinctTradedSymbols();
    }

    /**
     * Weekly allocation timeline: per-symbol GBP value + aggregated cash at each sample.
     * Feeds the Allocation tab's three views (cash-vs-invested, per-symbol stacked,
     * date-picker snapshot).
     */
    @GetMapping("/allocation")
    @ResponseBody
    public AllocationTimeline allocation() {
        return allocationService.timeline();
    }

    @GetMapping("/allocation/targets")
    @ResponseBody
    public TargetReport allocationTargets() {
        return targetAllocationService.report();
    }

    /**
     * Save target weights. Body is a single textarea field {@code targets} containing one
     * {@code SYMBOL=PERCENT} entry per line. Empty lines and lines without {@code =} are
     * skipped; bad numbers throw {@code IllegalArgumentException} which the global advice
     * renders as a 400.
     */
    @PostMapping("/allocation/targets")
    @ResponseBody
    public TargetReport saveAllocationTargets(@RequestParam(name = "targets", defaultValue = "") String body) {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "Line '" + trimmed + "' is missing '=' — use SYMBOL=PERCENT format.");
            }
            String sym = trimmed.substring(0, eq).trim().toUpperCase();
            String val = trimmed.substring(eq + 1).trim().replace("%", "");
            if (sym.isEmpty()) {
                throw new IllegalArgumentException("Empty symbol on line: " + trimmed);
            }
            BigDecimal pct;
            try {
                pct = new BigDecimal(val);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad percent for " + sym + ": '" + val + "'");
            }
            if (pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(sym + " weight must be 0..100, got " + pct);
            }
            // Save as a fraction.
            targets.put(sym, pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        }
        targetAllocationService.saveTargets(targets);
        return targetAllocationService.report();
    }

    /**
     * Per-symbol GBP P&amp;L over {@code [from, to]}. {@code to} defaults to today when blank.
     * See {@link AttributionService} for the formula.
     */
    @GetMapping("/attribution")
    @ResponseBody
    public AttributionResult attribution(@RequestParam(name = "from") String from,
                                         @RequestParam(name = "to", required = false) String to) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = (to == null || to.isBlank()) ? LocalDate.now() : LocalDate.parse(to);
        return attributionService.attribute(fromDate, toDate);
    }

    @GetMapping("/dividends")
    @ResponseBody
    public DividendIncome dividends() {
        return dividendIncomeService.summary();
    }

    @GetMapping("/dividends/audit")
    @ResponseBody
    public AuditReport dividendsAudit() {
        return dividendAuditService.report();
    }

    @GetMapping("/concentration")
    @ResponseBody
    public ConcentrationMetrics concentration() {
        return concentrationService.metrics();
    }

    @GetMapping("/correlations")
    @ResponseBody
    public CorrelationReport correlations(@RequestParam(name = "window", required = false,
                                                       defaultValue = "90") int window) {
        return correlationService.compute(window);
    }

    @GetMapping("/currency")
    @ResponseBody
    public CurrencyExposure currency() {
        return currencyExposureService.summary();
    }

    /**
     * Per-symbol drill-down: FIFO open + closed lots, dividends, total return and price chart
     * data. Empty/unknown symbols return an empty payload (not an error) so the tab can
     * render the "pick a symbol" prompt.
     */
    @GetMapping("/position")
    @ResponseBody
    public PositionDetail position(@RequestParam(name = "symbol", required = false) String symbol) {
        return positionDetailService.detail(symbol == null ? "" : symbol);
    }
}
