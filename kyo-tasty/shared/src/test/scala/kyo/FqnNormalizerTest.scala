package kyo

import kyo.internal.tasty.symbol.FqnNormalizer

/** Unit tests for FqnNormalizer.canonicalSourceFqn and FqnNormalizer.isSyntheticName.
  *
  * Pins HARD RULE 10 (comprehensive compiler-mangling normalization). One leaf per mangling pattern,
  * one leaf per synthetic-name pattern. Cross-platform: pure string operations.
  */
class FqnNormalizerTest extends Test:

    // canonicalSourceFqn: pattern 1 - opaque-type companion $package$.
    "FqnNormalizer: pattern 1 opaque $package$. strips to source FQN" in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Maybe$package$.Maybe") == "kyo.Maybe")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.scheduler.Tasks$package$.T") == "kyo.scheduler.T")
    }

    // canonicalSourceFqn: pattern 2 - trailing $ on companion objects
    "FqnNormalizer: pattern 2 trailing $ stripped for objects" in {
        assert(FqnNormalizer.canonicalSourceFqn("scala.Predef$") == "scala.Predef")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$") == "kyo.Tasty")
        assert(FqnNormalizer.canonicalSourceFqn("scala.Some$") == "scala.Some")
    }

    // canonicalSourceFqn: pattern 3 - object-nested member $. to .
    "FqnNormalizer: pattern 3 object-nested $. replaced by ." in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Symbol") == "kyo.Tasty.Symbol")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Symbol.Class") == "kyo.Tasty.Symbol.Class")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty$.Type$.Named") == "kyo.Tasty.Type.Named")
    }

    // canonicalSourceFqn: pattern 4 - inner-class $ to .
    "FqnNormalizer: pattern 4 inner-class $ separator replaced by ." in {
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Foo$Bar") == "kyo.Foo.Bar")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Foo$Bar$Baz") == "kyo.Foo.Bar.Baz")
        assert(FqnNormalizer.canonicalSourceFqn("scala.collection.Map$Entry") == "scala.collection.Map.Entry")
    }

    // canonicalSourceFqn: no-op on source-form FQN
    "FqnNormalizer: no-op when FQN is already source form" in {
        assert(FqnNormalizer.canonicalSourceFqn("scala.Option") == "scala.Option")
        assert(FqnNormalizer.canonicalSourceFqn("kyo.Tasty") == "kyo.Tasty")
        assert(FqnNormalizer.canonicalSourceFqn("java.lang.String") == "java.lang.String")
    }

    // canonicalSourceFqn: ordering - trailing $ + inner $ resolves correctly
    "FqnNormalizer: ordering - trailing $ then inner $ yields clean FQN" in {
        // Outer$ -> strip trailing $ first, then handle remaining separators
        assert(!FqnNormalizer.canonicalSourceFqn("kyo.Outer$Inner$").endsWith("."))
        val result = FqnNormalizer.canonicalSourceFqn("kyo.Outer$Inner$")
        assert(result == "kyo.Outer.Inner", s"Expected kyo.Outer.Inner but got $result")
    }

    // isSyntheticName: $anonfun$ pattern
    "FqnNormalizer: $anonfun$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$anonfun$1"))
        assert(!FqnNormalizer.isSyntheticName("scala.Predef"))
    }

    // isSyntheticName: $proxy$ pattern
    "FqnNormalizer: $proxy$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$proxy$3"))
    }

    // isSyntheticName: $_trait_ pattern
    "FqnNormalizer: $_trait_ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$_trait_$bar"))
    }

    // isSyntheticName: $$Lambda$ pattern
    "FqnNormalizer: $$Lambda$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$Lambda$42"))
    }

    // isSyntheticName: $$anon$ pattern
    "FqnNormalizer: $$anon$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$anon$1"))
    }

    // isSyntheticName: $$anonfun$ pattern (alternative form)
    "FqnNormalizer: $$anonfun$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$$anonfun$1"))
    }

    // isSyntheticName: $adapted$ pattern
    "FqnNormalizer: $adapted$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$adapted$1"))
    }

    // isSyntheticName: $default$ pattern
    "FqnNormalizer: $default$ is synthetic" in {
        assert(FqnNormalizer.isSyntheticName("kyo.Foo$default$1"))
    }

    // isSyntheticName: opaque companion is NOT synthetic
    "FqnNormalizer: opaque companion kyo.Maybe$package$.Maybe is NOT synthetic" in {
        assert(!FqnNormalizer.isSyntheticName("kyo.Maybe$package$.Maybe"))
    }

end FqnNormalizerTest
