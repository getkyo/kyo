package kyo

import kyo.internal.tasty.symbol.FqnNormalizer

/** Unit tests for FqnNormalizer.canonicalSourceFqn and FqnNormalizer.isSyntheticName.
  *
  * Full compiler-mangling normalization coverage: one case per mangling pattern, one case per
  * synthetic-name pattern. Cross-platform: pure string operations.
  */
class FqnNormalizerTest extends kyo.test.Test[Any]:

    "FqnNormalizer: pattern 1 opaque $package$. strips to source FQN" in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Maybe$package$.Maybe") == "kyo.Maybe")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.scheduler.Tasks$package$.T") == "kyo.scheduler.T")
    }

    "FqnNormalizer: pattern 2 trailing $ stripped for objects" in {
        assert(FqnNormalizer.canonicalSourceFqn("scala.Predef$") == "scala.Predef")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$") == "kyo.Tasty")
        assert(FqnNormalizer.canonicalSourceFqn("scala.Some$") == "scala.Some")
    }

    "FqnNormalizer: pattern 3 object-nested $. replaced by ." in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Symbol") == "kyo.Tasty.Symbol")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Symbol.Class") == "kyo.Tasty.Symbol.Class")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Type$.Named") == "kyo.Tasty.Type.Named")
    }

    "FqnNormalizer: pattern 4 inner-class $ separator replaced by ." in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Foo$Bar") == "kyo.Foo.Bar")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Foo$Bar$Baz") == "kyo.Foo.Bar.Baz")
        assert(FqnNormalizer.canonicalSourceFqn("scala.collection.Map$Entry") == "scala.collection.Map.Entry")
    }

    "FqnNormalizer: no-op when FQN is already source form" in {
        assert(FqnNormalizer.canonicalSourceFqn("scala.Option") == "scala.Option")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty") == "kyo.Tasty")
        assert(FqnNormalizer.canonicalSourceFqn("java.lang.String") == "java.lang.String")
    }

    "FqnNormalizer: ordering - trailing $ then inner $ yields clean FQN" in {
        assert(!FqnNormalizer.canonicalSourceFqn("kyo.Outer$Inner$").endsWith("."))
        val result = FqnNormalizer.canonicalSourceFqn("kyo.Outer$Inner$")
        assert(result == "kyo.Outer.Inner", s"Expected kyo.Outer.Inner but got $result")
    }

    "FqnNormalizer: $anonfun$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$anonfun$1"))
        assert(!FqnNormalizer.isSyntheticName("scala.Predef"))
    }

    "FqnNormalizer: $proxy$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$proxy$3"))
    }

    "FqnNormalizer: $_trait_ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$_trait_$bar"))
    }

    "FqnNormalizer: $$Lambda$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$Lambda$42"))
    }

    "FqnNormalizer: $$anon$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$anon$1"))
    }

    "FqnNormalizer: $$anonfun$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$anonfun$1"))
    }

    "FqnNormalizer: $adapted$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$adapted$1"))
    }

    "FqnNormalizer: $default$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$default$1"))
    }

    "FqnNormalizer: opaque companion kyo.Maybe$package$.Maybe is NOT synthetic" in {
        assert(!FqnNormalizer.isSyntheticName("kyo.Maybe$package$.Maybe"))
    }

    // Leading $ at index 0 is not replaced; no rule strips a leading $.
    "FqnNormalizer: leading dollar at index 0 is preserved" in {
        assert(FqnNormalizer.canonicalSourceFqn("$foo.bar") == "$foo.bar")
    }

    "FqnNormalizer: trailing dollar stripped, inner dollar replaced" in {
        val result = FqnNormalizer.canonicalSourceFqn("a$b$")
        assert(result == "a.b", s"Expected a.b but got $result")
    }

    // The implementation uses index checks, so spaces are ordinary chars.
    "FqnNormalizer: space adjacent to dollar does not block replacement" in {
        val result = FqnNormalizer.canonicalSourceFqn("foo bar$baz")
        assert(result == "foo bar.baz", s"Expected 'foo bar.baz' but got '$result'")
    }

    "FqnNormalizer: double dollar is preserved" in {
        val expected = "a$$b"
        val result   = FqnNormalizer.canonicalSourceFqn("a$$b")
        assert(result == expected, s"Expected $expected but got $result")
    }

end FqnNormalizerTest
