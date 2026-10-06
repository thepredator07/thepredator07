package com.ticketfactory.integration;

import java.util.Set;

/** The factory only ever writes to {@code factory/<ticket-id>} branches. Never to main. */
public final class BranchPolicy {

    public static final String PREFIX = "factory/";
    private static final Set<String> PROTECTED = Set.of("main", "master");

    private BranchPolicy() {
    }

    public static String branchFor(long ticketId) {
        return PREFIX + ticketId;
    }

    /** Throws if {@code head} is not a factory branch or the PR would not target a different base branch. */
    public static void validatePullRequest(String head, String base) {
        if (head == null || !head.startsWith(PREFIX) || head.length() == PREFIX.length()) {
            throw new IllegalArgumentException("Refusing to push branch '" + head + "': must be " + PREFIX + "<id>");
        }
        if (PROTECTED.contains(head) || head.equals(base)) {
            throw new IllegalArgumentException("Refusing to push to protected branch '" + head + "'");
        }
    }
}
