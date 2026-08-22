package com.portfolio.web;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.IntradayPriceFetchJob;
import com.portfolio.application.ManualPositionService;
import com.portfolio.application.PriceFetchJob;
import com.portfolio.domain.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Positions at accounts with no downloadable export, entered by hand.
 *
 * <p>Every write is followed by a background price warm rather than an inline fetch. It matters
 * more here than elsewhere: the cash-ledger view drops any equity with no intraday quote, so a
 * freshly entered symbol nothing else in the app has ever named would be invisible on that tab
 * until the next polling pass came round to it. The render never waits — the position appears
 * immediately, its price a moment later.
 */
@RestController
public class ManualPositionsController {

    private static final Logger log = LoggerFactory.getLogger(ManualPositionsController.class);

    private final ManualPositionService service;
    private final PriceFetchJob priceFetchJob;
    private final IntradayPriceFetchJob intradayFetchJob;
    private final YahooTickerMap tickers;
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "manual-position-warm");
        t.setDaemon(true);
        return t;
    });

    public ManualPositionsController(ManualPositionService service,
                                     PriceFetchJob priceFetchJob,
                                     IntradayPriceFetchJob intradayFetchJob,
                                     YahooTickerMap tickers) {
        this.service = service;
        this.priceFetchJob = priceFetchJob;
        this.intradayFetchJob = intradayFetchJob;
        this.tickers = tickers;
    }

    @GetMapping("/positions/manual")
    public ManualPositionService.ManualView view() {
        return service.view();
    }

    @PostMapping("/positions/manual/buy")
    @ResponseBody
    public Map<String, Object> buy(@RequestParam("broker") String broker,
                                   @RequestParam("symbol") String symbol,
                                   @RequestParam("quantity") String quantity,
                                   @RequestParam("cost") String cost,
                                   @RequestParam("date") String date,
                                   @RequestParam(name = "note", required = false) String note) {
        Account account = account(broker);
        service.buy(account, symbol, decimal(quantity, "quantity"), decimal(cost, "cost"),
                date(date), note);
        warm(Set.of(symbol.trim().toUpperCase()));
        return Map.of("ok", true, "symbol", symbol.trim().toUpperCase());
    }

    @PostMapping("/positions/manual/sell")
    @ResponseBody
    public Map<String, Object> sell(@RequestParam("broker") String broker,
                                    @RequestParam("symbol") String symbol,
                                    @RequestParam("quantity") String quantity,
                                    @RequestParam("proceeds") String proceeds,
                                    @RequestParam("date") String date,
                                    @RequestParam(name = "note", required = false) String note) {
        Account account = account(broker);
        service.sell(account, symbol, decimal(quantity, "quantity"), decimal(proceeds, "proceeds"),
                date(date), note);
        return Map.of("ok", true, "symbol", symbol.trim().toUpperCase());
    }

    @PostMapping("/positions/manual/withdraw")
    @ResponseBody
    public Map<String, Object> withdraw(@RequestParam("broker") String broker,
                                        @RequestParam("amount") String amount,
                                        @RequestParam("date") String date,
                                        @RequestParam(name = "note", required = false) String note) {
        Account account = account(broker);
        service.withdraw(account, decimal(amount, "amount"), date(date), note);
        return Map.of("ok", true);
    }

    @PostMapping("/positions/manual/delete")
    @ResponseBody
    public Map<String, Object> delete(@RequestParam("broker") String broker,
                                      @RequestParam("rowid") long rowid) {
        boolean deleted = service.delete(account(broker), rowid);
        return Map.of("ok", deleted, "rowid", rowid);
    }

    /** Daily history plus an intraday quote, on a daemon thread; failures are logged, never thrown. */
    private void warm(Set<String> symbols) {
        if (symbols.isEmpty()) return;
        warmer.submit(() -> {
            try {
                for (String s : symbols) priceFetchJob.fetchSingle(s);
                intradayFetchJob.fetchNow(symbols.stream().map(tickers::tickerFor).toList());
            } catch (Exception e) {
                log.warn("Could not warm prices for manual position(s) {}", symbols, e);
            }
        });
    }

    private static Account account(String broker) {
        if (broker == null || broker.isBlank()) {
            throw new IllegalArgumentException("broker is required");
        }
        Account account;
        try {
            account = Account.fromDbValue(broker.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown broker '" + broker.trim() + "'");
        }
        if (!account.isManual()) {
            throw new IllegalArgumentException(account.dbValue()
                    + " is imported from broker exports — its rows cannot be entered by hand");
        }
        return account;
    }

    private static BigDecimal decimal(String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + " is required");
        try {
            return new BigDecimal(raw.trim().replace(",", "").replace("£", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    private static LocalDate date(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }
}
