package com.portfolio.application;

import com.portfolio.application.PortfolioValueService.DailyValue;
import com.portfolio.application.PortfolioValueService.PeriodValues;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.HistoricalFxRateProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-weighted return (TWR) of the portfolio.
 *
 * <p>For each calendar day the daily return is
 * <pre>r_t = (V_t − V_{t-1} − C_t) / V_{t-1}</pre>
 * where {@code V_t} is the GBP portfolio value from {@link PortfolioValueService#dailyValues()}
 * and {@code C_t} is the net external GBP contribution on day {@code t} (sum of
 * {@code CONTRIBUTION} ledger rows in {@code amountGbp}, plus the Roth USD seed converted to
 * GBP at the FX rate on its earliest ledger date — same convention as {@link ContributionService}).
 *
 * <p>Cumulative growth-of-£1 is {@code G_t = G_{t-1} × (1 + r_t)}, anchored at {@code 1.0} on
 * the first day the portfolio has a non-zero value. Trailing 1y/3y/5y returns read
 * {@code G_now / G_then − 1} for the appropriate {@code then}; windows longer than a year are
 * annualised as {@code (G_now / G_then)^(365.25/days) − 1}.
 *
 * <p>Internal AJ Bell → II SIPP transfers do not distort this: II's "Trf from AJ Bell" row is
 * matched by a negative "Pension payment requested" / "Transfer Out" on the AJ Bell side, both
 * classified {@code CONTRIBUTION}, so the pair nets to zero in {@code C} (see
 * {@link ContributionService}). A transfer whose outbound leg hasn't been imported yet would
 * read as new money and depress the TWR on that date until the AJ Bell statement is imported.
 */
public class PortfolioReturnService {

    private static final int SCALE = 10;

    /** Windows shorter than this report no annualised return — see {@link #annualise}. */
    private static final int MIN_ANNUALIZE_DAYS = 30;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    private final PortfolioValueService valueService;
    private final CashTransactionRepository cashRepo;
    private final KeyValueStore settings;
    private final HistoricalFxRateProvider fxProvider;

    public PortfolioReturnService(PortfolioValueService valueService,
                                  CashTransactionRepository cashRepo,
                                  KeyValueStore settings,
                                  HistoricalFxRateProvider fxProvider) {
        this.valueService = valueService;
        this.cashRepo = cashRepo;
        this.settings = settings;
        this.fxProvider = fxProvider;
    }

    public ReturnTimeline timeline() {
        List<DailyValue> values = valueService.dailyValues();
        if (values.isEmpty()) {
            return new ReturnTimeline(List.of(), List.of(), List.of(), List.of(), Summary.empty());
        }

        Map<LocalDate, BigDecimal> contribByDate = contributionsByDate();
        Map<LocalDate, BigDecimal> investedFlowsByDate = investedFlowsByDate();

        List<ReturnPoint> growthPoints = new ArrayList<>();
        List<ContribPoint> contribPoints = new ArrayList<>();
        // Parallel chain on the invested-only sub-portfolio: ignores cash drag. Treats every
        // buy as a "contribution" into the invested pool and every sell / dividend as a
        // "withdrawal" out (sign-flipped CashTransaction.amountGbp on TRANSACTION + DIVIDEND
        // rows). With that flow correction the formula r = (V − Vprev − C) / Vprev captures
        // pure asset-mix performance — same definition as the total TWR, just restricted to
        // the positions side of the ledger.
        List<ReturnPoint> investedGrowthPoints = new ArrayList<>();
        BigDecimal growth = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal investedGrowth = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal cumContrib = BigDecimal.ZERO;
        BigDecimal prevV = null;
        BigDecimal prevInvested = null;
        boolean chainStarted = false;
        boolean investedChainStarted = false;

        for (DailyValue dv : values) {
            BigDecimal c = contribByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
            BigDecimal cInvested = investedFlowsByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
            cumContrib = cumContrib.add(c);

            if (!chainStarted) {
                // Wait for the portfolio to have value before starting the growth chain.
                if (dv.valueGbp().signum() > 0) {
                    chainStarted = true;
                    growthPoints.add(new ReturnPoint(dv.date().toString(), growth));
                    contribPoints.add(new ContribPoint(dv.date().toString(), cumContrib));
                    prevV = dv.valueGbp();
                }
            } else {
                if (prevV.signum() > 0) {
                    BigDecimal r = dv.valueGbp().subtract(prevV).subtract(c)
                            .divide(prevV, SCALE, RoundingMode.HALF_UP);
                    growth = growth.multiply(BigDecimal.ONE.add(r))
                            .setScale(SCALE, RoundingMode.HALF_UP);
                }
                growthPoints.add(new ReturnPoint(dv.date().toString(), growth));
                contribPoints.add(new ContribPoint(dv.date().toString(), cumContrib));
                prevV = dv.valueGbp();
            }

            // Invested-only chain starts the first day positions exist.
            if (!investedChainStarted) {
                if (dv.investedGbp().signum() > 0) {
                    investedChainStarted = true;
                    investedGrowthPoints.add(new ReturnPoint(dv.date().toString(), investedGrowth));
                    prevInvested = dv.investedGbp();
                }
            } else {
                if (prevInvested.signum() > 0) {
                    BigDecimal r = dv.investedGbp().subtract(prevInvested).subtract(cInvested)
                            .divide(prevInvested, SCALE, RoundingMode.HALF_UP);
                    investedGrowth = investedGrowth.multiply(BigDecimal.ONE.add(r))
                            .setScale(SCALE, RoundingMode.HALF_UP);
                }
                investedGrowthPoints.add(new ReturnPoint(dv.date().toString(), investedGrowth));
                prevInvested = dv.investedGbp();
            }
        }

        List<DrawdownPoint> drawdowns = drawdowns(growthPoints);
        List<AnnualReturn> annual = annualReturns(growthPoints, investedGrowthPoints);
        return new ReturnTimeline(growthPoints, contribPoints, drawdowns, annual,
                summarise(growthPoints, drawdowns));
    }

    /**
     * Diff the portfolio between two arbitrary dates, valuing both boundaries ourselves from
     * the cash ledger + stored daily closes + historical FX — the same engine behind the
     * Value-over-time and Returns charts. Nothing here reads {@code portfolio_snapshots}: the
     * user picks any two dates, not just the days a broker export happened to be synced, and
     * the two ends are valued on one consistent basis instead of two broker conventions.
     *
     * <p>Three different numbers answer "how did it do", and the panel shows all three because
     * they diverge whenever money moved:
     * <ul>
     *   <li><b>Value change</b> — raw {@code end − start}. Includes contributions, so it
     *       flatters any window you paid into.</li>
     *   <li><b>Gain</b> — {@code value change − external flows in the window}: the money the
     *       market actually made or lost. External flows are reported as two separate figures,
     *       because they are two different things: {@code contributionsGbp} is money paid in,
     *       {@code broughtForwardGbp} is an account's opening balance entering the ledger.
     *       Both must come off the gain; only the first is a contribution.</li>
     *   <li><b>Total return</b> — chain-linked daily TWR across the window,
     *       {@code Π(1 + (V_t − V_{t−1} − C_t) / V_{t−1}) − 1}. Contribution-neutral and
     *       therefore the figure to compare against an index. Same definition as the Returns
     *       tab, so a window that spans the whole history reproduces its since-inception
     *       number.</li>
     * </ul>
     *
     * <p>The start date's own contributions belong to the opening balance, not to the window,
     * so flows are counted over {@code (from, to]} — consistent with anchoring the TWR chain
     * at the start-date close.
     *
     * <p>Degrades rather than throws: an unusable window (reversed dates, no ledger rows, a
     * zero opening value that no return can be chained off) comes back with null figures and
     * a warning for the UI to render.
     */
    public PeriodDiff periodDiff(LocalDate from, LocalDate to) {
        List<String> warnings = new ArrayList<>();
        LocalDate today = LocalDate.now();
        if (to.isAfter(today)) {
            warnings.add("To-date is in the future — clamped to " + today + ".");
            to = today;
        }
        if (!from.isBefore(to)) {
            return PeriodDiff.unavailable(from, to, "From-date must be earlier than to-date.");
        }

        PeriodValues pv = valueService.dailyValuesIn(from, to);
        List<DailyValue> values = pv.values();
        if (values.size() < 2) {
            return PeriodDiff.unavailable(from, to,
                    "No ledger data in that window — the portfolio's history starts later.");
        }

        DailyValue anchor = values.get(0);
        DailyValue close = values.get(values.size() - 1);
        if (anchor.date().isAfter(from)) {
            warnings.add("Ledger history starts " + anchor.date()
                    + " — the window opens there rather than at " + from + ".");
        }
        if (close.date().isBefore(to)) {
            warnings.add("Valued through " + close.date() + " (latest available).");
        }
        if (!pv.unpricedSymbols().isEmpty()) {
            warnings.add("No price history for " + String.join(", ", pv.unpricedSymbols())
                    + " — held in this window and valued at zero on both ends.");
        }

        // Kept apart for reporting even though the return maths adds them: one is money paid
        // in, the other is an account's opening balance arriving in the ledger.
        Map<LocalDate, BigDecimal> contribByDate = ledgerContributionsByDate();
        Map<LocalDate, BigDecimal> broughtFwdByDate = broughtForwardByDate();
        Map<LocalDate, BigDecimal> investedFlowsByDate = investedFlowsByDate();

        BigDecimal one = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal contributions = BigDecimal.ZERO;
        BigDecimal broughtForward = BigDecimal.ZERO;
        BigDecimal growth = anchor.valueGbp().signum() > 0 ? one : null;
        BigDecimal prevV = anchor.valueGbp();

        // The positions-only chain anchors at the first day positions actually exist, which
        // need not be the window's first day — a window that opens on cash and buys later
        // (the whole history, for one) would otherwise report no positions return at all.
        // Its anchor is reported separately so the tile can say where it started.
        BigDecimal investedGrowth = anchor.investedGbp().signum() > 0 ? one : null;
        BigDecimal prevInvested = anchor.investedGbp();
        LocalDate investedFrom = investedGrowth == null ? null : anchor.date();

        for (int i = 1; i < values.size(); i++) {
            DailyValue dv = values.get(i);
            BigDecimal contrib = contribByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
            BigDecimal bf = broughtFwdByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
            BigDecimal c = contrib.add(bf);
            contributions = contributions.add(contrib);
            broughtForward = broughtForward.add(bf);
            if (growth != null && prevV.signum() > 0) {
                BigDecimal r = dv.valueGbp().subtract(prevV).subtract(c)
                        .divide(prevV, SCALE, RoundingMode.HALF_UP);
                growth = growth.multiply(BigDecimal.ONE.add(r)).setScale(SCALE, RoundingMode.HALF_UP);
            }
            prevV = dv.valueGbp();

            if (investedGrowth == null) {
                if (dv.investedGbp().signum() > 0) {
                    investedGrowth = one;
                    investedFrom = dv.date();
                    prevInvested = dv.investedGbp();
                }
                continue;   // no return on the anchor day itself
            }
            if (prevInvested.signum() > 0) {
                BigDecimal ci = investedFlowsByDate.getOrDefault(dv.date(), BigDecimal.ZERO);
                BigDecimal r = dv.investedGbp().subtract(prevInvested).subtract(ci)
                        .divide(prevInvested, SCALE, RoundingMode.HALF_UP);
                investedGrowth = investedGrowth.multiply(BigDecimal.ONE.add(r))
                        .setScale(SCALE, RoundingMode.HALF_UP);
            }
            prevInvested = dv.investedGbp();
        }

        if (growth == null) {
            warnings.add("Opening value was zero, so no return can be chained from "
                    + anchor.date() + ".");
        }

        BigDecimal externalFlows = contributions.add(broughtForward);
        if (broughtForward.signum() != 0) {
            warnings.add(String.format(
                    "£%,.0f of the external flows is an opening balance brought forward, not "
                    + "money paid in — an account's recorded history starts inside this window.",
                    broughtForward));
        }

        BigDecimal startValue = anchor.valueGbp();
        BigDecimal endValue = close.valueGbp();
        BigDecimal valueChange = endValue.subtract(startValue);
        BigDecimal valueChangePct = startValue.signum() > 0
                ? valueChange.divide(startValue, SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal totalReturn = growth == null ? null : growth.subtract(BigDecimal.ONE);
        BigDecimal investedReturn = investedGrowth == null
                ? null : investedGrowth.subtract(BigDecimal.ONE);
        long spanDays = ChronoUnit.DAYS.between(anchor.date(), close.date());

        Map<String, BigDecimal> usd = fxSeries("USD", anchor.date(), close.date());
        Map<String, BigDecimal> eur = fxSeries("EUR", anchor.date(), close.date());

        return new PeriodDiff(
                anchor.date().toString(), close.date().toString(), spanDays,
                endpoint(anchor, usd, eur), endpoint(close, usd, eur),
                valueChange, valueChangePct,
                contributions, broughtForward, valueChange.subtract(externalFlows),
                totalReturn, annualise(totalReturn, spanDays), investedReturn,
                investedFrom == null ? null : investedFrom.toString(),
                pv.unpricedSymbols(), warnings);
    }

    private static Endpoint endpoint(DailyValue dv,
                                     Map<String, BigDecimal> usd, Map<String, BigDecimal> eur) {
        return new Endpoint(dv.date().toString(),
                dv.valueGbp().setScale(2, RoundingMode.HALF_UP),
                dv.investedGbp().setScale(2, RoundingMode.HALF_UP),
                dv.valueGbp().subtract(dv.investedGbp()).setScale(2, RoundingMode.HALF_UP),
                usd.get(dv.date().toString()), eur.get(dv.date().toString()));
    }

    /**
     * The two boundary FX rates for one currency, keyed by date string. Returned as a map so a
     * missing rate is simply an absent key — an FX outage leaves the two FX rows blank instead
     * of failing the whole diff (the GBP totals never depended on these; they are display-only
     * context, the valuations having already been converted inside the daily series).
     */
    private Map<String, BigDecimal> fxSeries(String ccy, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> out = new HashMap<>();
        try {
            Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(ccy, from.minusDays(14), to);
            for (LocalDate d : List.of(from, to)) {
                BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series, d);
                if (rate != null) out.put(d.toString(), rate);
            }
        } catch (Exception e) {
            // Display-only context — the diff stands without it.
        }
        return out;
    }

    /**
     * Gross a period return up to a 365.25-day year. Null under {@link #MIN_ANNUALIZE_DAYS}
     * (compounding a two-week move 26× produces a number nobody should read) and on a
     * non-positive {@code 1 + r}, where the {@code pow} would go NaN. Same convention as
     * {@code AttributionService}.
     */
    private static BigDecimal annualise(BigDecimal periodReturn, long days) {
        if (periodReturn == null || days < MIN_ANNUALIZE_DAYS) return null;
        double base = 1.0 + periodReturn.doubleValue();
        if (base <= 0) return null;
        return BigDecimal.valueOf(Math.pow(base, 365.25 / days) - 1.0)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calendar-year returns derived from the growth series. For each year between the first
     * and last growth dates:
     * <ul>
     *   <li>Anchor = growth on or before the prior Dec 31 (for the first year, the very first
     *       growth point — so its return is partial-period).</li>
     *   <li>Close = growth on or before Dec 31 of the year (for the current year, the latest
     *       growth point — also partial).</li>
     *   <li>{@code return = close / anchor − 1}.</li>
     * </ul>
     * The same window is applied to {@code investedGrowth} to get the cash-drag-free
     * per-year return alongside the whole-portfolio one. {@code partial} flags the inception
     * year and the in-progress current year so the UI can render them differently.
     */
    private static List<AnnualReturn> annualReturns(List<ReturnPoint> growth,
                                                    List<ReturnPoint> investedGrowth) {
        if (growth.isEmpty()) return List.of();
        LocalDate firstDate = LocalDate.parse(growth.get(0).date());
        LocalDate lastDate = LocalDate.parse(growth.get(growth.size() - 1).date());
        List<AnnualReturn> out = new ArrayList<>();
        for (int year = firstDate.getYear(); year <= lastDate.getYear(); year++) {
            ReturnPoint anchor = (year == firstDate.getYear())
                    ? growth.get(0)
                    : findOnOrBefore(growth, LocalDate.of(year - 1, 12, 31));
            ReturnPoint close = (year == lastDate.getYear())
                    ? growth.get(growth.size() - 1)
                    : findOnOrBefore(growth, LocalDate.of(year, 12, 31));
            if (anchor == null || close == null
                    || anchor.growth().signum() <= 0
                    || anchor.date().equals(close.date())) continue;
            BigDecimal r = close.growth().divide(anchor.growth(), SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            BigDecimal investedR = investedReturnFor(investedGrowth, anchor.date(), close.date());
            boolean partial = (year == firstDate.getYear() && firstDate.getDayOfYear() > 1)
                    || (year == lastDate.getYear() && lastDate.getDayOfYear() < 365);
            out.add(new AnnualReturn(year, r, investedR, partial,
                    anchor.date(), close.date()));
        }
        return out;
    }

    /**
     * Window the invested-only growth series to the same anchor/close dates used for the
     * total return. The dates come from the total-growth series (one daily sample, dense),
     * so for the invested series we re-anchor by finding the latest invested point
     * on-or-before each. Returns {@code null} when invested anchor isn't positive yet —
     * typical when the inception year started with cash but no positions.
     */
    private static BigDecimal investedReturnFor(List<ReturnPoint> investedGrowth,
                                                String anchorDate, String closeDate) {
        if (investedGrowth.isEmpty()) return null;
        ReturnPoint a = findOnOrBefore(investedGrowth, LocalDate.parse(anchorDate));
        ReturnPoint c = findOnOrBefore(investedGrowth, LocalDate.parse(closeDate));
        if (a == null || c == null || a.growth().signum() <= 0
                || a.date().equals(c.date())) return null;
        return c.growth().divide(a.growth(), SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * Per-date GBP cash flows from / into the invested pool: TRANSACTION rows (buys =
     * cash → invested; sells = invested → cash) and DIVIDEND rows (invested → cash, via
     * the ex-div price drop the daily series already captures). Sign convention matches
     * the TWR formula's {@code C} term: positive = capital ADDED to the pool, negative =
     * capital WITHDRAWN. So we flip the cash-ledger sign (buys are cash-negative but
     * invested-positive; sells/divs are cash-positive but invested-negative).
     */
    private Map<LocalDate, BigDecimal> investedFlowsByDate() {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (CashTransaction t : cashRepo.loadDividendTransactions()) {
            BigDecimal flow = BigDecimal.valueOf(t.amountGbp()).negate();
            out.merge(LocalDate.parse(t.transactionDate()), flow, BigDecimal::add);
        }
        return out;
    }

    /**
     * Underwater curve: for each growth point, {@code (growth − running_peak) / running_peak}
     * — always in {@code [-1, 0]}, with 0 meaning "at all-time-high". Computed on the
     * growth-of-£1 series (TWR), so contributions don't masquerade as drawdown recoveries.
     */
    private static List<DrawdownPoint> drawdowns(List<ReturnPoint> growth) {
        List<DrawdownPoint> out = new ArrayList<>();
        BigDecimal peak = BigDecimal.ZERO;
        for (ReturnPoint p : growth) {
            if (p.growth().compareTo(peak) > 0) peak = p.growth();
            BigDecimal dd = peak.signum() > 0
                    ? p.growth().subtract(peak).divide(peak, SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            out.add(new DrawdownPoint(p.date(), dd));
        }
        return out;
    }

    /**
     * Every external GBP cash flow per date — the {@code C} term of the TWR formula. Two
     * different things land here and the TWR needs both: ledger {@code CONTRIBUTION} rows,
     * and the Roth USD brought-forward seed converted at the FX rate on Roth's earliest
     * ledger date. The seed is not money anyone paid in; it is an opening balance for an
     * account whose recorded history simply starts there. But the portfolio's value still
     * jumps by it for a non-market reason on that one day, so leaving it out would book the
     * whole opening balance as a one-day gain.
     *
     * <p>Reporting has to keep them apart even though the maths doesn't — see
     * {@link #ledgerContributionsByDate()} and {@link #broughtForwardByDate()}. Labelling the
     * combined figure "contributions" made a £138k account opening read as £138k paid in.
     */
    private Map<LocalDate, BigDecimal> contributionsByDate() {
        Map<LocalDate, BigDecimal> out = ledgerContributionsByDate();
        for (Map.Entry<LocalDate, BigDecimal> e : broughtForwardByDate().entrySet()) {
            out.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }
        return out;
    }

    /**
     * Ledger {@code CONTRIBUTION} rows only, in {@code amountGbp} (FX baked in at import).
     * Internal AJ Bell → II SIPP transfers appear on both sides — a negative "Pension payment
     * requested" / "Transfer Out" on AJ Bell against the matching "Trf from AJ Bell" on II —
     * so they net to zero here, provided both brokers' statements have been imported. A
     * transfer whose outbound leg is missing would overstate this figure until it is.
     */
    private Map<LocalDate, BigDecimal> ledgerContributionsByDate() {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (CashTransaction t : cashRepo.loadContributions()) {
            out.merge(LocalDate.parse(t.transactionDate()),
                    BigDecimal.valueOf(t.amountGbp()), BigDecimal::add);
        }
        return out;
    }

    /** The Roth USD opening balance as a single GBP step on Roth's earliest ledger date. */
    private Map<LocalDate, BigDecimal> broughtForwardByDate() {
        BigDecimal rothSeedUsd = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStartStr = cashRepo.earliestTransactionDate(Account.ROTH_IRA);
        if (rothSeedUsd.signum() <= 0 || rothStartStr == null) return Map.of();
        LocalDate rothStart = LocalDate.parse(rothStartStr);
        BigDecimal seedGbp = rothSeedToGbp(rothSeedUsd, rothStart);
        return seedGbp.signum() > 0 ? Map.of(rothStart, seedGbp) : Map.of();
    }

    /**
     * Money-weighted (IRR) return summary. Trailing windows treat the portfolio's GBP value
     * at window-start as a single outflow, every contribution during the window as a
     * further outflow on its date, and today's value as the recovery inflow — then bisect
     * for the annualised rate that makes the NPV of those flows zero. Since-inception
     * mirrors the same shape but with no window-start outflow.
     *
     * <p>Trailing windows beyond the data are returned as {@code null}.
     */
    public MwrSummary moneyWeightedSummary() {
        List<DailyValue> values = valueService.dailyValues();
        if (values.isEmpty()) return MwrSummary.empty();

        DailyValue last = values.get(values.size() - 1);
        LocalDate today = last.date();
        BigDecimal valueToday = last.valueGbp();

        Map<LocalDate, BigDecimal> contribsByDate = contributionsByDate();

        Double inception = xirrInception(contribsByDate, today, valueToday);
        Double t1y = xirrTrailing(values, contribsByDate, today, valueToday, 1);
        Double t3y = xirrTrailing(values, contribsByDate, today, valueToday, 3);
        Double t5y = xirrTrailing(values, contribsByDate, today, valueToday, 5);

        return new MwrSummary(bd(t1y), bd(t3y), bd(t5y), bd(inception));
    }

    private static BigDecimal bd(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return null;
        return BigDecimal.valueOf(d).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Cash flow series since the first contribution: all contributions are outflows (-),
     *  today's value is the inflow (+). */
    private static Double xirrInception(Map<LocalDate, BigDecimal> contribsByDate,
                                        LocalDate today, BigDecimal valueToday) {
        if (contribsByDate.isEmpty() || valueToday.signum() <= 0) return null;
        List<double[]> flows = new ArrayList<>();
        for (var e : contribsByDate.entrySet()) {
            flows.add(new double[]{epochDay(e.getKey()), -e.getValue().doubleValue()});
        }
        flows.add(new double[]{epochDay(today), valueToday.doubleValue()});
        return xirr(flows);
    }

    private static Double xirrTrailing(List<DailyValue> values,
                                       Map<LocalDate, BigDecimal> contribsByDate,
                                       LocalDate today, BigDecimal valueToday, int years) {
        LocalDate windowStart = today.minusYears(years);
        // Find the latest daily value on or before windowStart.
        BigDecimal startValue = null;
        for (DailyValue dv : values) {
            if (dv.date().isAfter(windowStart)) break;
            startValue = dv.valueGbp();
        }
        if (startValue == null || startValue.signum() <= 0) return null;

        List<double[]> flows = new ArrayList<>();
        flows.add(new double[]{epochDay(windowStart), -startValue.doubleValue()});
        for (var e : contribsByDate.entrySet()) {
            if (e.getKey().isAfter(windowStart) && !e.getKey().isAfter(today)) {
                flows.add(new double[]{epochDay(e.getKey()), -e.getValue().doubleValue()});
            }
        }
        flows.add(new double[]{epochDay(today), valueToday.doubleValue()});
        return xirr(flows);
    }

    private static double epochDay(LocalDate d) {
        return d.toEpochDay();
    }

    /**
     * Bisection on the annualised IRR. NPV at rate r =
     * Σ amount_i × (1+r)^(-(day_i − day0)/365.25). The amounts are signed (outflows
     * negative, inflows positive). Bisects in [-0.99, 10.0] for 100 iterations — fast and
     * robust for any return below 1000%. Returns {@code null} if NPV doesn't cross zero
     * (e.g. all-positive or all-negative flows — pathological input).
     */
    private static Double xirr(List<double[]> flows) {
        if (flows.size() < 2) return null;
        double t0 = flows.get(0)[0];
        for (double[] f : flows) if (f[0] < t0) t0 = f[0];
        double lo = -0.99;
        double hi = 10.0;
        double npvLo = npv(flows, t0, lo);
        double npvHi = npv(flows, t0, hi);
        if (Double.isNaN(npvLo) || Double.isNaN(npvHi)) return null;
        if (npvLo * npvHi > 0) return null;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            double npvMid = npv(flows, t0, mid);
            if (Math.abs(npvMid) < 1e-6) return mid;
            if (npvLo * npvMid < 0) { hi = mid; npvHi = npvMid; }
            else { lo = mid; npvLo = npvMid; }
        }
        return 0.5 * (lo + hi);
    }

    private static double npv(List<double[]> flows, double t0, double r) {
        double s = 0.0;
        double base = 1.0 + r;
        if (base <= 0) return Double.NaN;
        for (double[] f : flows) {
            double dt = (f[0] - t0) / 365.25;
            s += f[1] * Math.pow(base, -dt);
        }
        return s;
    }

    private BigDecimal rothSeedToGbp(BigDecimal seedUsd, LocalDate rothStart) {
        try {
            Map<LocalDate, BigDecimal> series = fxProvider.fetchRateSeries(
                    "USD", rothStart.minusDays(14), rothStart.plusDays(1));
            BigDecimal rate = HistoricalFxRateProvider.rateOnOrBefore(series, rothStart);
            if (rate == null || rate.signum() == 0) return BigDecimal.ZERO;
            return seedUsd.divide(rate, 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static Summary summarise(List<ReturnPoint> points, List<DrawdownPoint> drawdowns) {
        if (points.isEmpty()) return Summary.empty();
        ReturnPoint last = points.get(points.size() - 1);
        LocalDate now = LocalDate.parse(last.date());
        BigDecimal gNow = last.growth();
        BigDecimal maxDD = BigDecimal.ZERO;
        String maxDDDate = null;
        for (DrawdownPoint d : drawdowns) {
            if (d.drawdown().compareTo(maxDD) < 0) {
                maxDD = d.drawdown();
                maxDDDate = d.date();
            }
        }
        return new Summary(
                trailing(points, now.minusWeeks(1), gNow),
                trailing(points, now.minusMonths(1), gNow),
                trailing(points, now.minusMonths(3), gNow),
                trailing(points, now.minusMonths(6), gNow),
                trailing(points, now.minusYears(1), gNow),
                trailing(points, now.minusYears(3), gNow),
                trailing(points, now.minusYears(5), gNow),
                trailing(points, LocalDate.parse(points.get(0).date()), gNow),
                maxDD, maxDDDate);
    }

    /**
     * Trailing return as a fractional GBP gain (0.07 = +7%). For windows longer than a year
     * the result is annualised; for ≤1y it's the simple total return over the window.
     * Returns {@code null} when the portfolio history doesn't cover the window — i.e. the
     * latest point on or before {@code windowStart} sits more than {@link #WINDOW_TOLERANCE_DAYS}
     * earlier than the window itself (so any return computed against it would silently
     * span a longer period than the user asked for).
     */
    private static BigDecimal trailing(List<ReturnPoint> points, LocalDate windowStart, BigDecimal gNow) {
        ReturnPoint anchor = findOnOrBefore(points, windowStart);
        if (anchor == null) return null;
        LocalDate anchorDate = LocalDate.parse(anchor.date());
        if (anchorDate.isBefore(windowStart.minusDays(WINDOW_TOLERANCE_DAYS))) return null;
        BigDecimal gThen = anchor.growth();
        if (gThen.signum() <= 0) return null;
        BigDecimal ratio = gNow.divide(gThen, SCALE, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(anchorDate,
                LocalDate.parse(points.get(points.size() - 1).date()));
        if (days <= 366) {
            return ratio.subtract(BigDecimal.ONE);
        }
        // Annualise: ratio^(365.25/days) − 1, via doubles (BigDecimal has no pow with fractional exponent).
        double annual = Math.pow(ratio.doubleValue(),
                DAYS_PER_YEAR.doubleValue() / days) - 1.0;
        return BigDecimal.valueOf(annual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Maximum gap (days) between the window's start and the anchor data point. Dense daily data hits 0. */
    private static final int WINDOW_TOLERANCE_DAYS = 7;

    /** Latest point whose date is on or before {@code target}, or null if none. */
    private static ReturnPoint findOnOrBefore(List<ReturnPoint> points, LocalDate target) {
        ReturnPoint best = null;
        for (ReturnPoint p : points) {
            LocalDate d = LocalDate.parse(p.date());
            if (d.isAfter(target)) break;
            best = p;
        }
        return best;
    }

    // ---- DTOs ----------------------------------------------------------------

    public record ReturnPoint(String date, BigDecimal growth) {
    }

    public record ContribPoint(String date, BigDecimal cumulativeGbp) {
    }

    /**
     * Underwater point: {@code drawdown} is a non-positive fraction (e.g. −0.15 = 15% below
     * the running TWR peak); 0 means the portfolio is at all-time-high on that day.
     */
    public record DrawdownPoint(String date, BigDecimal drawdown) {
    }

    /**
     * Trailing returns as fractional GBP gains (0.07 = +7%). 1w/1m/3m/6m/1y are raw period
     * returns; 3y/5y/since are annualised. {@code maxDrawdown} is the deepest underwater
     * fraction across the entire history (negative, e.g. −0.25 = a 25% peak-to-trough drop),
     * and {@code maxDrawdownDate} is the day it bottomed. Any field is {@code null} when the
     * portfolio history is shorter than the corresponding window.
     */
    public record Summary(BigDecimal trailing1w, BigDecimal trailing1m,
                          BigDecimal trailing3m, BigDecimal trailing6m,
                          BigDecimal trailing1y, BigDecimal trailing3y,
                          BigDecimal trailing5y, BigDecimal sinceInception,
                          BigDecimal maxDrawdown, String maxDrawdownDate) {
        public static Summary empty() {
            return new Summary(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * One calendar year's TWR. {@code returnPct} is the whole-portfolio return (cash drag
     * included); {@code investedReturnPct} strips cash out — the difference between them
     * is the cash-drag opportunity cost. {@code partial} = true for the inception year and
     * the current YTD. {@code investedReturnPct} is {@code null} when the year had no
     * invested position to anchor against.
     */
    public record AnnualReturn(int year, BigDecimal returnPct, BigDecimal investedReturnPct,
                               boolean partial, String fromDate, String toDate) {
    }

    public record ReturnTimeline(List<ReturnPoint> growthPoints,
                                 List<ContribPoint> contributionPoints,
                                 List<DrawdownPoint> drawdownPoints,
                                 List<AnnualReturn> annualReturns,
                                 Summary summary) {
    }

    /**
     * Money-weighted return summary. Mirror shape of {@link Summary} but with IRR-based
     * trailing windows and since-inception — fields are annualised fractional returns,
     * {@code null} when the window's outside the data.
     */
    public record MwrSummary(BigDecimal trailing1y,
                             BigDecimal trailing3y,
                             BigDecimal trailing5y,
                             BigDecimal sinceInception) {

        public static MwrSummary empty() {
            return new MwrSummary(null, null, null, null);
        }
    }

    /**
     * One boundary of a {@link PeriodDiff}, valued from the ledger. {@code cashGbp} is the
     * residual {@code total − invested}; {@code gbpusd} / {@code gbpeur} are the historical
     * rates on that date (foreign per 1 GBP), null when FX was unavailable.
     */
    public record Endpoint(String date,
                           BigDecimal totalGbp,
                           BigDecimal investedGbp,
                           BigDecimal cashGbp,
                           BigDecimal gbpusd,
                           BigDecimal gbpeur) {
    }

    /**
     * Ledger-valued comparison of two dates. {@code from} / {@code to} echo the dates actually
     * used, which can differ from the ones requested when the window was clamped to the
     * available history (any clamp is spelled out in {@code warnings}). {@code investedFrom} is
     * the day the positions-only chain anchored, which lags {@code from} whenever the window
     * opened on cash. Returns are fractional (0.07 = +7%); every figure is nullable so an
     * unusable window degrades to warnings.
     */
    public record PeriodDiff(String from,
                             String to,
                             long spanDays,
                             Endpoint start,
                             Endpoint end,
                             BigDecimal valueChangeGbp,
                             BigDecimal valueChangePct,
                             BigDecimal contributionsGbp,
                             BigDecimal broughtForwardGbp,
                             BigDecimal gainGbp,
                             BigDecimal totalReturn,
                             BigDecimal annualisedReturn,
                             BigDecimal investedReturn,
                             String investedFrom,
                             List<String> unpricedSymbols,
                             List<String> warnings) {

        static PeriodDiff unavailable(LocalDate from, LocalDate to, String warning) {
            return new PeriodDiff(from.toString(), to.toString(), 0, null, null,
                    null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(warning));
        }
    }
}
