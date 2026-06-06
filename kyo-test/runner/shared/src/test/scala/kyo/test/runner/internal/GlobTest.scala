package kyo.test.runner.internal

import kyo.test.runner.internal.Glob

/** Tests for [[Glob.matches]]: 4 leaves covering single-segment wildcard, multi-segment wildcard, and suffix-glob matching.
  */
class GlobTest extends kyo.test.Test[Any]:

    // ── Test 13: kyo.* matches kyo.Foo ───────────────────────────────────────────────────────

    "test-13: Glob.matches(\"kyo.*\", \"kyo.Foo\") == true" in {
        assert(Glob.matches("kyo.*", "kyo.Foo")): Unit
    }

    // ── Test 14: kyo.* does NOT match kyo.sub.Foo ────────────────────────────────────────────

    "test-14: Glob.matches(\"kyo.*\", \"kyo.sub.Foo\") == false" in {
        assert(!Glob.matches("kyo.*", "kyo.sub.Foo")): Unit
    }

    // ── Test 15: kyo.** matches kyo.sub.Foo ──────────────────────────────────────────────────

    "test-15: Glob.matches(\"kyo.**\", \"kyo.sub.Foo\") == true" in {
        assert(Glob.matches("kyo.**", "kyo.sub.Foo")): Unit
    }

    // ── Test 16: kyo.**Foo matches kyo.sub.MyFoo ─────────────────────────────────────────────

    "test-16: Glob.matches(\"kyo.**Foo\", \"kyo.sub.MyFoo\") == true" in {
        assert(Glob.matches("kyo.**Foo", "kyo.sub.MyFoo")): Unit
    }

end GlobTest
