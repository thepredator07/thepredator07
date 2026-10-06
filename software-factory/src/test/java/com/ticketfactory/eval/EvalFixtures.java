package com.ticketfactory.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ten small Python issues of mixed difficulty for the live agent evaluation. Each has tests on main that fail until
 * the issue is resolved; the checks command runs them. A ticket passes when it reaches DONE and the test file on its
 * branch is unchanged (the agent fixed the code, not the test).
 */
final class EvalFixtures {

    record Fixture(String name, String title, String body, Map<String, String> files, String testFile) {
    }

    static final String FACTORY_YML = """
            checks:
              command: python3 -m unittest -v
              timeout: 2m
            """;

    private static Fixture fixture(String name, String title, String body, String code, String codeFile,
                                   String test) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(".factory.yml", FACTORY_YML);
        files.put(".gitignore", "__pycache__/\n");
        files.put(codeFile, code);
        String testFile = "test_" + codeFile;
        files.put(testFile, test);
        return new Fixture(name, title, body, files, testFile);
    }

    static List<Fixture> all() {
        return List.of(
                fixture("fizzbuzz", "Implement fizzbuzz",
                        "`fizzbuzz(n)` should return the list of strings for 1..n: \"Fizz\" for multiples of 3, "
                                + "\"Buzz\" for multiples of 5, \"FizzBuzz\" for both, otherwise the number.",
                        "def fizzbuzz(n):\n    raise NotImplementedError\n", "fizz.py", """
                        import unittest
                        from fizz import fizzbuzz

                        class T(unittest.TestCase):
                            def test_first_fifteen(self):
                                self.assertEqual(["1", "2", "Fizz", "4", "Buzz", "Fizz", "7", "8", "Fizz", "Buzz",
                                                  "11", "Fizz", "13", "14", "FizzBuzz"], fizzbuzz(15))

                            def test_zero(self):
                                self.assertEqual([], fizzbuzz(0))
                        """),
                fixture("last-n", "last_n returns one item too many",
                        "`last_n([1, 2, 3, 4], 2)` returns `[2, 3, 4]` instead of `[3, 4]`.",
                        "def last_n(items, n):\n    if n <= 0:\n        return []\n    return items[-n - 1:]\n",
                        "lists.py", """
                        import unittest
                        from lists import last_n

                        class T(unittest.TestCase):
                            def test_two(self):
                                self.assertEqual([3, 4], last_n([1, 2, 3, 4], 2))

                            def test_more_than_length(self):
                                self.assertEqual([1, 2], last_n([1, 2], 5))

                            def test_zero(self):
                                self.assertEqual([], last_n([1, 2], 0))
                        """),
                fixture("slugify", "Add slugify",
                        "We need `slugify(text)` for URLs: lowercase, words joined by single hyphens, anything "
                                + "that isn't a letter or digit dropped. \"Hello, World!\" -> \"hello-world\".",
                        "def slugify(text):\n    return text\n", "text.py", """
                        import unittest
                        from text import slugify

                        class T(unittest.TestCase):
                            def test_basic(self):
                                self.assertEqual("hello-world", slugify("Hello, World!"))

                            def test_spaces_and_symbols(self):
                                self.assertEqual("a-b-c", slugify("  a -- b__c  "))

                            def test_digits(self):
                                self.assertEqual("release-2-0", slugify("Release 2.0"))
                        """),
                fixture("roman", "to_roman gets subtractive numerals wrong",
                        "`to_roman(4)` gives \"IIII\" and `to_roman(1994)` gives \"MDCCCCLXXXXIIII\". Expected "
                                + "\"IV\" and \"MCMXCIV\".",
                        """
                        NUMERALS = [(1000, "M"), (500, "D"), (100, "C"), (50, "L"), (10, "X"), (5, "V"), (1, "I")]


                        def to_roman(n):
                            out = ""
                            for value, letters in NUMERALS:
                                while n >= value:
                                    out += letters
                                    n -= value
                            return out
                        """, "roman.py", """
                        import unittest
                        from roman import to_roman

                        class T(unittest.TestCase):
                            def test_examples(self):
                                for n, r in [(1, "I"), (4, "IV"), (9, "IX"), (14, "XIV"), (40, "XL"), (90, "XC"),
                                             (400, "CD"), (1994, "MCMXCIV"), (3999, "MMMCMXCIX")]:
                                    self.assertEqual(r, to_roman(n), n)
                        """),
                fixture("dedupe", "dedupe loses the original order",
                        "`dedupe([3, 1, 3, 2, 1])` should give `[3, 1, 2]` (first occurrence wins), but the order "
                                + "is scrambled.",
                        "def dedupe(items):\n    return list(set(items))\n", "seq.py", """
                        import unittest
                        from seq import dedupe

                        class T(unittest.TestCase):
                            def test_order(self):
                                self.assertEqual([3, 1, 2], dedupe([3, 1, 3, 2, 1]))

                            def test_strings(self):
                                self.assertEqual(["b", "a"], dedupe(["b", "a", "b"]))
                        """),
                fixture("word-count", "word_count should ignore case and punctuation",
                        "`word_count(\"The cat. the CAT!\")` should be `{\"the\": 2, \"cat\": 2}`.",
                        """
                        def word_count(text):
                            counts = {}
                            for word in text.split(" "):
                                counts[word] = counts.get(word, 0) + 1
                            return counts
                        """, "words.py", """
                        import unittest
                        from words import word_count

                        class T(unittest.TestCase):
                            def test_case_and_punctuation(self):
                                self.assertEqual({"the": 2, "cat": 2}, word_count("The cat. the CAT!"))

                            def test_whitespace(self):
                                self.assertEqual({"a": 1, "b": 1}, word_count("  a\\n\\tb  "))

                            def test_empty(self):
                                self.assertEqual({}, word_count(""))
                        """),
                fixture("duration", "parse_duration only understands minutes",
                        "Support hours and seconds too: \"1h30m\" -> 5400, \"45s\" -> 45, \"2h\" -> 7200, "
                                + "\"1h1m1s\" -> 3661. Invalid input should raise ValueError.",
                        """
                        def parse_duration(text):
                            if not text.endswith("m"):
                                raise ValueError(text)
                            return int(text[:-1]) * 60
                        """, "duration.py", """
                        import unittest
                        from duration import parse_duration

                        class T(unittest.TestCase):
                            def test_units(self):
                                for text, seconds in [("10m", 600), ("1h30m", 5400), ("45s", 45), ("2h", 7200),
                                                      ("1h1m1s", 3661)]:
                                    self.assertEqual(seconds, parse_duration(text), text)

                            def test_invalid(self):
                                for text in ["", "abc", "10x", "h"]:
                                    with self.assertRaises(ValueError, msg=text):
                                        parse_duration(text)
                        """),
                fixture("missing-user", "get_email crashes for unknown users",
                        "`get_email(users, 99)` raises KeyError when the user doesn't exist. It should return None.",
                        "def get_email(users, user_id):\n    return users[user_id][\"email\"]\n", "users.py", """
                        import unittest
                        from users import get_email

                        class T(unittest.TestCase):
                            def test_found(self):
                                self.assertEqual("a@x.org", get_email({1: {"email": "a@x.org"}}, 1))

                            def test_missing(self):
                                self.assertIsNone(get_email({1: {"email": "a@x.org"}}, 99))
                        """),
                fixture("median", "median is wrong for even-length lists",
                        "`median([1, 2, 3, 4])` returns 2; it should be 2.5 (the mean of the two middle values).",
                        """
                        def median(values):
                            ordered = sorted(values)
                            return ordered[(len(ordered) - 1) // 2]
                        """, "stats.py", """
                        import unittest
                        from stats import median

                        class T(unittest.TestCase):
                            def test_odd(self):
                                self.assertEqual(2, median([3, 1, 2]))

                            def test_even(self):
                                self.assertEqual(2.5, median([4, 1, 3, 2]))
                        """),
                fixture("palindrome", "is_palindrome should ignore case and punctuation",
                        "\"A man, a plan, a canal: Panama\" should count as a palindrome.",
                        "def is_palindrome(text):\n    return text == text[::-1]\n", "pal.py", """
                        import unittest
                        from pal import is_palindrome

                        class T(unittest.TestCase):
                            def test_sentence(self):
                                self.assertTrue(is_palindrome("A man, a plan, a canal: Panama"))

                            def test_not(self):
                                self.assertFalse(is_palindrome("Hello"))

                            def test_simple(self):
                                self.assertTrue(is_palindrome("Racecar"))
                        """));
    }

    private EvalFixtures() {
    }
}
