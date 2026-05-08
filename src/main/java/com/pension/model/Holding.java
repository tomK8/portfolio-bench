package com.pension.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public class Holding {

    private final String securityId;
    private final BigDecimal quantity;
    private final BigDecimal avgPricePaid;       // cost basis / quantity; null when not computable
    private final BigDecimal currentMarketValue;    // in the holding's native currency
    private final BigDecimal currentMarketValueGbp; // pre-converted GBP from source; if null, convert via FX
    private final BigDecimal costBasisGbp;          // cost in GBP from source (AJ Bell); null → compute via FX
    private final Currency currency;
    private final String source;                 // e.g. "Roth IRA"

    private Holding(Builder builder) {
        this.securityId          = Objects.requireNonNull(builder.securityId,  "securityId");
        this.quantity            = Objects.requireNonNull(builder.quantity,     "quantity");
        this.avgPricePaid           = builder.avgPricePaid;
        this.currentMarketValue     = builder.currentMarketValue;
        this.currentMarketValueGbp  = builder.currentMarketValueGbp;
        this.costBasisGbp           = builder.costBasisGbp;
        this.currency               = Objects.requireNonNull(builder.currency,     "currency");
        this.source                 = Objects.requireNonNull(builder.source,       "source");
    }

    public String getSecurityId()              { return securityId; }
    public BigDecimal getQuantity()            { return quantity; }
    public BigDecimal getAvgPricePaid()        { return avgPricePaid; }
    public BigDecimal getCurrentMarketValue()    { return currentMarketValue; }
    public BigDecimal getCurrentMarketValueGbp() { return currentMarketValueGbp; }
    public BigDecimal getCostBasisGbp()          { return costBasisGbp; }
    public Currency getCurrency()                { return currency; }
    public String getSource()                  { return source; }

    @Override
    public String toString() {
        return String.format("Holding{id='%s', qty=%s, avgPrice=%s, mktVal=%s, ccy=%s, source='%s'}",
                securityId, quantity, avgPricePaid, currentMarketValue, currency.getCurrencyCode(), source);
    }

    public static Builder builder(String securityId, BigDecimal quantity, Currency currency, String source) {
        return new Builder(securityId, quantity, currency, source);
    }

    public static final class Builder {
        private final String securityId;
        private final BigDecimal quantity;
        private final Currency currency;
        private final String source;
        private BigDecimal avgPricePaid;
        private BigDecimal currentMarketValue;
        private BigDecimal currentMarketValueGbp;
        private BigDecimal costBasisGbp;

        private Builder(String securityId, BigDecimal quantity, Currency currency, String source) {
            this.securityId = securityId;
            this.quantity   = quantity;
            this.currency   = currency;
            this.source     = source;
        }

        public Builder avgPricePaid(BigDecimal v)          { this.avgPricePaid          = v; return this; }
        public Builder currentMarketValue(BigDecimal v)    { this.currentMarketValue    = v; return this; }
        public Builder currentMarketValueGbp(BigDecimal v) { this.currentMarketValueGbp = v; return this; }
        public Builder costBasisGbp(BigDecimal v)          { this.costBasisGbp          = v; return this; }

        public Holding build() { return new Holding(this); }
    }
}
