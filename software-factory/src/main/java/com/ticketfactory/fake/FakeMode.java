package com.ticketfactory.fake;

import java.util.Locale;

public enum FakeMode {
    SUCCEED,
    FAIL,
    /** Fail the first N calls for a ticket, then succeed. */
    FAIL_THEN_SUCCEED;

    /** Accepts {@code succeed}, {@code fail}, {@code fail-then-succeed} (any case, - or _). */
    public static FakeMode parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
