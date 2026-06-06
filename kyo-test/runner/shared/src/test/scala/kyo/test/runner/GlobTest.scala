package kyo.test.runner

import kyo.test.runner.internal.Glob

class GlobTest extends kyo.test.Test[Any]:

    "matches exact string" in {
        assert(Glob.matches("foo", "foo"))
        assert(!Glob.matches("foo", "bar"))
        assert(!Glob.matches("foo", "foobar"))
        assert(!Glob.matches("foo", ""))
    }

    "matches with single-segment wildcard" in {
        // * matches within a segment (no dot separator)
        assert(Glob.matches("foo.*", "foo.bar"))
        assert(!Glob.matches("foo.*", "foo.bar.baz"))
        assert(Glob.matches("foo.*", "foo.anything"))
        assert(!Glob.matches("foo.*", "foo."))
        assert(!Glob.matches("foo.*", "bar.baz"))
    }

    "matches with double-wildcard" in {
        // ** matches across segments
        assert(Glob.matches("foo.**", "foo.bar.baz"))
        assert(Glob.matches("foo.**", "foo.bar"))
        assert(Glob.matches("foo.**", "foo.x.y.z"))
        assert(!Glob.matches("foo.**", "bar.baz"))
    }

    "matches with ? for single char" in {
        assert(Glob.matches("f?o", "foo"))
        assert(Glob.matches("f?o", "fxo"))
        assert(!Glob.matches("f?o", "fo"))
        assert(!Glob.matches("f?o", "fooo"))
        // ? does not match the separator
        assert(!Glob.matches("f?o", "f.o"))
    }

    // ── Test 15: ** matches across all chars including separators and empty string (M31) ────────────

    "phase8-test-15: ** matches multi-segment path, **.suffix matches suffix, ** matches empty string" in {
        // ** compiles to .* which matches any char sequence including separators and /
        assert(Glob.matches("**", "a/b/c"))
        assert(Glob.matches("**.foo", "a/b.foo"))
        // pin the empty-string contract: ** matches empty string (.*$ matches "")
        assert(Glob.matches("**", ""))
    }

    // ── Test 16: * matches within a non-dot segment (M31 basic wildcard) ─────────────────────────

    "phase8-test-16: * wildcard matches within a segment delimited by non-dot chars" in {
        // Separator is '.'; '/' is treated as a regular char, so a/*/c matches a/<non-dot-seq>/c
        assert(Glob.matches("a/*/c", "a/b/c"))
        assert(!Glob.matches("a/*/c", "a/b/d"))
    }

end GlobTest
