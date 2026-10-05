package com.portfolio.domain;

import com.portfolio.domain.AjBellPortfolios.Kind;
import com.portfolio.domain.AjBellPortfolios.Verdict;
import com.portfolio.domain.model.Account;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AjBellPortfoliosTest {

    private static final Map<Account, Set<String>> REGISTRY = registry(
            Set.of("EQQQ", "LSEG", "LGEN", "BLND", "AZN"),   // SIPP
            Set.of("BARC"));                                 // Dealing

    private static Map<Account, Set<String>> registry(Set<String> sipp, Set<String> dealing) {
        Map<Account, Set<String>> m = new LinkedHashMap<>();
        m.put(Account.AJBELL, sipp);
        m.put(Account.AJBELL_DEALING, dealing);
        return m;
    }

    @Test
    void routesToTheAccountHoldingWhatTheFileNames() {
        Verdict v = AjBellPortfolios.route(List.of("BARC"), REGISTRY);
        assertEquals(Kind.RESOLVED, v.kind());
        assertEquals(Account.AJBELL_DEALING, v.account());
    }

    @Test
    void routesTheOtherWayOnTheOtherAccountsHoldings() {
        Verdict v = AjBellPortfolios.route(List.of("LSEG", "LGEN", "EQQQ"), REGISTRY);
        assertEquals(Kind.RESOLVED, v.kind());
        assertEquals(Account.AJBELL, v.account());
    }

    @Test
    void oneExclusiveNameIsEnoughAmongNamesNeitherAccountHolds() {
        Verdict v = AjBellPortfolios.route(List.of("TSLA", "BARC", "NVDA"), REGISTRY);
        assertEquals(Account.AJBELL_DEALING, v.account());
        assertEquals(Set.of("BARC"), v.evidence().get(Account.AJBELL_DEALING));
    }

    /**
     * The whole point of "exclusively". A name both accounts hold cannot say which export this is,
     * so it is evidence for neither rather than evidence for both.
     */
    @Test
    void aSymbolHeldByBothAccountsIsNotEvidence() {
        Map<Account, Set<String>> shared = registry(Set.of("EQQQ", "BARC"), Set.of("BARC"));
        Verdict v = AjBellPortfolios.route(List.of("BARC"), shared);
        assertEquals(Kind.UNRECOGNISED, v.kind());
        assertNull(v.account());
    }

    /** Exactly the case the user asked to be flagged: the SIPP has started holding a dealing name. */
    @Test
    void namingBothAccountsHoldingsIsAConflictAndRoutesNowhere() {
        Verdict v = AjBellPortfolios.route(List.of("BARC", "LSEG"), REGISTRY);
        assertEquals(Kind.CONFLICTING, v.kind());
        assertNull(v.account());
        assertEquals(List.of(Account.AJBELL, Account.AJBELL_DEALING), v.claimants());
        assertTrue(v.explain().contains("AJ Bell SIPP holds LSEG"));
        assertTrue(v.explain().contains("AJ Bell Dealing holds BARC"));
    }

    @Test
    void aFileNamingNothingHeldIsUnrecognisedRatherThanDefaulted() {
        assertEquals(Kind.UNRECOGNISED, AjBellPortfolios.route(List.of("TSLA"), REGISTRY).kind());
        assertEquals(Kind.UNRECOGNISED, AjBellPortfolios.route(List.of(), REGISTRY).kind());
    }

    /**
     * An account whose holdings export is missing contributes nothing, which must not make its
     * files route to the other account — it makes them unroutable, and that is reported.
     */
    @Test
    void anAccountWithNoHoldingsExportClaimsNothing() {
        Map<Account, Set<String>> half = registry(Set.of("EQQQ", "LSEG"), Set.of());
        assertEquals(Kind.UNRECOGNISED, AjBellPortfolios.route(List.of("BARC"), half).kind());
        assertEquals(Account.AJBELL, AjBellPortfolios.route(List.of("EQQQ"), half).account());
    }

    @Test
    void matchingIsCaseAndWhitespaceInsensitive() {
        Verdict v = AjBellPortfolios.route(List.of(" barc "), REGISTRY);
        assertEquals(Account.AJBELL_DEALING, v.account());
    }

    @Test
    void nullsAndBlanksAreIgnoredRatherThanMatched() {
        Verdict v = AjBellPortfolios.route(java.util.Arrays.asList(null, "", "  ", "BARC"), REGISTRY);
        assertEquals(Account.AJBELL_DEALING, v.account());
    }
}
