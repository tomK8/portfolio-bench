package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * A named group of orders the user decided belong together — a ladder, today.
 *
 * <p><b>Membership is never inferred.</b> Three buys on one symbol a day apart are not a ladder
 * because they look like one; they are a ladder when the user says so. Symbol, date and price
 * proximity are all available here and all deliberately unused: the whole value of the group is
 * that it records an intention the feed cannot see, and a guess would put the user's reasoning on
 * orders that never carried it.
 *
 * @param symbol the one security the group's rungs trade. Held on the group rather than derived
 *               from its members so an empty ladder — created before any order is placed, which is
 *               the normal way one starts — still knows what it is about. For a future
 *               {@link GroupType} whose rungs span securities this becomes the lead symbol or null;
 *               nothing else in the schema has to move.
 */
public record OrderGroup(long id, GroupType type, String name, String symbol, String note,
                         LocalDate createdAt) {
}
