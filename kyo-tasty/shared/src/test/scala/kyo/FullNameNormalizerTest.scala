package kyo

import kyo.internal.tasty.symbol.FullNameNormalizer

/** Unit tests for FullNameNormalizer.canonicalSourceFullName and FullNameNormalizer.isSyntheticName.
  *
  * Full compiler-mangling normalization coverage: one case per mangling pattern, one case per
  * synthetic-name pattern. Cross-platform: pure string operations.
  */
class FullNameNormalizerTest extends kyo.test.Test[Any]:

    "FullNameNormalizer: pattern 1 opaque $package$. strips to source fully-qualified name" in {
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Maybe$package$.Maybe") == "kyo.Maybe")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.scheduler.Tasks$package$.T") == "kyo.scheduler.T")
    }

    "FullNameNormalizer: pattern 2 trailing $ stripped for objects" in {
        assert(FullNameNormalizer.canonicalSourceFullName("scala.Predef$") == "scala.Predef")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Tasty$") == "kyo.Tasty")
        assert(FullNameNormalizer.canonicalSourceFullName("scala.Some$") == "scala.Some")
    }

    "FullNameNormalizer: pattern 3 object-nested $. replaced by ." in {
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Tasty$.Symbol") == "kyo.Tasty.Symbol")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Tasty$.Symbol.Class") == "kyo.Tasty.Symbol.Class")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Tasty$.Type$.Named") == "kyo.Tasty.Type.Named")
    }

    "FullNameNormalizer: pattern 4 inner-class $ separator replaced by ." in {
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Foo$Bar") == "kyo.Foo.Bar")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Foo$Bar$Baz") == "kyo.Foo.Bar.Baz")
        assert(FullNameNormalizer.canonicalSourceFullName("scala.collection.Map$Entry") == "scala.collection.Map.Entry")
    }

    "FullNameNormalizer: no-op when fully-qualified name is already source form" in {
        assert(FullNameNormalizer.canonicalSourceFullName("scala.Option") == "scala.Option")
        assert(FullNameNormalizer.canonicalSourceFullName("kyo.Tasty") == "kyo.Tasty")
        assert(FullNameNormalizer.canonicalSourceFullName("java.lang.String") == "java.lang.String")
    }

    "FullNameNormalizer: ordering - trailing $ then inner $ yields clean fully-qualified name" in {
        assert(!FullNameNormalizer.canonicalSourceFullName("kyo.Outer$Inner$").endsWith("."))
        val result = FullNameNormalizer.canonicalSourceFullName("kyo.Outer$Inner$")
        assert(result == "kyo.Outer.Inner", s"Expected kyo.Outer.Inner but got $result")
    }

    "FullNameNormalizer: $anonfun$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$anonfun$1"))
        assert(!FullNameNormalizer.isSyntheticName("scala.Predef"))
    }

    "FullNameNormalizer: $proxy$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$proxy$3"))
    }

    "FullNameNormalizer: $_trait_ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$_trait_$bar"))
    }

    "FullNameNormalizer: $$Lambda$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$$Lambda$42"))
    }

    "FullNameNormalizer: $$anon$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$$anon$1"))
    }

    "FullNameNormalizer: $$anonfun$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$$anonfun$1"))
    }

    "FullNameNormalizer: $adapted$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$adapted$1"))
    }

    "FullNameNormalizer: $default$ is synthetic" in {
        assert(FullNameNormalizer.isSyntheticName("kyo.Foo$default$1"))
    }

    "FullNameNormalizer: opaque companion kyo.Maybe$package$.Maybe is NOT synthetic" in {
        assert(!FullNameNormalizer.isSyntheticName("kyo.Maybe$package$.Maybe"))
    }

    // Leading $ at index 0 is not replaced; no rule strips a leading $.
    "FullNameNormalizer: leading dollar at index 0 is preserved" in {
        assert(FullNameNormalizer.canonicalSourceFullName("$foo.bar") == "$foo.bar")
    }

    "FullNameNormalizer: trailing dollar stripped, inner dollar replaced" in {
        val result = FullNameNormalizer.canonicalSourceFullName("a$b$")
        assert(result == "a.b", s"Expected a.b but got $result")
    }

    // The implementation uses index checks, so spaces are ordinary chars.
    "FullNameNormalizer: space adjacent to dollar does not block replacement" in {
        val result = FullNameNormalizer.canonicalSourceFullName("foo bar$baz")
        assert(result == "foo bar.baz", s"Expected 'foo bar.baz' but got '$result'")
    }

    "FullNameNormalizer: double dollar is preserved" in {
        val expected = "a$$b"
        val result   = FullNameNormalizer.canonicalSourceFullName("a$$b")
        assert(result == expected, s"Expected $expected but got $result")
    }

end FullNameNormalizerTest
