package com.ticketfactory.fake;

/** How a fake behaves for one ticket. {@code call} is 1 for the first call the fake gets for that ticket. */
public record FakeBehavior(FakeMode mode, int failuresBeforeSuccess) {

    public static final FakeBehavior SUCCEED = new FakeBehavior(FakeMode.SUCCEED, 0);
    public static final FakeBehavior FAIL = new FakeBehavior(FakeMode.FAIL, 0);

    public static FakeBehavior failThenSucceed(int failures) {
        return new FakeBehavior(FakeMode.FAIL_THEN_SUCCEED, failures);
    }

    public boolean shouldFail(int call) {
        return switch (mode) {
            case SUCCEED -> false;
            case FAIL -> true;
            case FAIL_THEN_SUCCEED -> call <= failuresBeforeSuccess;
        };
    }

    /** Parses {@code "fail-then-succeed 2"}, {@code "fail"}, {@code "succeed"}. */
    public static FakeBehavior parse(String text) {
        String[] parts = text.trim().split("\\s+");
        FakeMode mode = FakeMode.parse(parts[0]);
        int failures = parts.length > 1 ? Integer.parseInt(parts[1]) : (mode == FakeMode.FAIL_THEN_SUCCEED ? 1 : 0);
        return new FakeBehavior(mode, failures);
    }
}
