package com.ticketfactory.integration.checks;

import java.util.Map;

/**
 * A tiny Python project with a real unit test, used as the target repo in checks tests. {@link #BROKEN_CALC} has a
 * known bug that {@code test_add_negative} catches. The sandbox image has Python, and no network is needed.
 */
public final class SampleRepo {

    public static final String FACTORY_YML = """
            checks:
              command: python3 -m unittest -v
              timeout: 2m
            """;

    public static final String CALC = """
            def add(a, b):
                return a + b
            """;

    /** Wrong for negative numbers: the failing test the checks must catch. */
    public static final String BROKEN_CALC = """
            def add(a, b):
                return abs(a) + b
            """;

    public static final String TEST = """
            import unittest

            from calc import add


            class CalcTest(unittest.TestCase):
                def test_add_small(self):
                    self.assertEqual(5, add(2, 3))

                def test_add_negative(self):
                    self.assertEqual(-1, add(-3, 2))


            if __name__ == "__main__":
                unittest.main()
            """;

    public static Map<String, String> passing() {
        return Map.of(".factory.yml", FACTORY_YML, "calc.py", CALC, "test_calc.py", TEST);
    }

    public static Map<String, String> withKnownFailingTest() {
        return Map.of(".factory.yml", FACTORY_YML, "calc.py", BROKEN_CALC, "test_calc.py", TEST);
    }

    private SampleRepo() {
    }
}
