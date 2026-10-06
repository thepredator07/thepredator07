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

    /** Throws unless {@code branch} is {@code factory/<something>} (so never main, master or anyone else's branch). */
    public static void validateBranch(String branch) {
        if (branch == null || !branch.startsWith(PREFIX) || branch.length() == PREFIX.length()
                || PROTECTED.contains(branch) || branch.contains("..") || branch.contains(" ")) {
            throw new IllegalArgumentException("Refusing to push branch '" + branch + "': must be " + PREFIX + "<id>");
        }
    }

    /** Throws if {@code head} is not a factory branch or the PR would not target a different base branch. */
    public static void validatePullRequest(String head, String base) {
        validateBranch(head);
        if (head.equals(base)) {
            throw new IllegalArgumentException("Refusing to open a PR from '" + head + "' into itself");
        }
    }
}
