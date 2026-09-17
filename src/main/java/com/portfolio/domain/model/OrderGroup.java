package com.portfolio.domain.model;

import java.time.LocalDate;

/**
 * A named group of orders the user decided belong together — a ladder, or a share-class switch.
 *
 * <p><b>Membership is never inferred.</b> Three buys on one symbol a day apart are not a ladder
 * because they look like one, and a sell of EQQQ beside a buy of EQQU is not a switch because the
 * two would net out. They are a group when the user says so. Symbol, date and price proximity are
 * all available here and all deliberately unused: the whole value of the group is that it records
 * an intention the feed cannot see, and a guess would put the user's reasoning on orders that never
 * carried it.
 *
 * @param symbol   the security the group is about. For a {@link GroupType#LADDER} it is the one
 *                 security its rungs trade. For a {@link GroupType#SHARE_CLASS_SWITCH} it is the
 *                 line the holding is <em>leaving</em>. Held on the group rather than derived from
 *                 its members so an empty group — created before any order is placed, which is the
 *                 normal way one starts — still knows what it is about.
 * @param symbolTo the second listing, for a switch: the line the holding is moving <em>into</em>.
 *                 Null on a ladder, which has one security by definition. Two columns rather than a
 *                 side table because a switch has exactly two legs and always will — a move across
 *                 three listings is not a thing, and a move between two different <em>investments</em>
 *                 is a different shape that would not reuse this record anyway.
 */
public record OrderGroup(long id, GroupType type, String name, String symbol, String symbolTo,
                         String note, LocalDate createdAt) {

    public boolean isLadder() {
        return type == GroupType.LADDER;
    }

    public boolean isSwitch() {
        return type == GroupType.SHARE_CLASS_SWITCH;
    }
}
