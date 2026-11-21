package com.buytogether.group;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum GroupStatus {

    /** Accepting members. */
    OPEN,
    /** Target size reached; members are planning the showroom visit. */
    FULL,
    /** Closed by the creator. */
    CLOSED,
    /** Deadline passed. */
    EXPIRED;

    public static final Set<GroupStatus> ACTIVE = Collections.unmodifiableSet(EnumSet.of(OPEN, FULL));

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
