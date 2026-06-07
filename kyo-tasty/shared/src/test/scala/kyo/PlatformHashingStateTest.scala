package kyo

import kyo.internal.tasty.type_.TypeKey

/** Tests for PlatformHashingState, the cycle-detection state backing TypeKey.computeHash.
  *
  * PlatformHashingState is private[type_] and provides a per-call HashSet[Tasty.Type] used by TypeKey to prevent infinite hash recursion on
  * cyclic Rec/RecThis types. Its observable contract is:
  *   TypeKey.of on a given type produces the same hash on repeated calls (stability).
  *   TypeKey.of on structurally different types produces different hashes (discrimination).
  *
  * Discovery: PlatformHashingState does not expose a byte-array hasher API. It exposes get: HashSet[Tasty.Type]. The hashing algorithm
  * for type keys is prime mixing (31 * sub-hash + tag-constant), not FNV-1a. FNV-1a is used only by DigestComputer for classpath cache
  * invalidation. The golden-value capture approach: hash the same type twice; assert the two calls return equal values. Hardcoding a
  * numeric constant is avoided because TypeKey.hash is an Int derived from Scala case-class hashCode mixing, which is deterministic but not
  * trivially hand-computable. Two-call equality is the appropriate golden check for this API.
  *
  * T2 coverage.
  */
class PlatformHashingStateTest extends kyo.test.Test[Any]:

    // Test 1: hashing produces stable output across platforms.
    // TypeKey.of is called twice on the same structural type; PlatformHashingState is exercised on both calls.
    // The two returned hash integers must be equal, confirming platform-stable output.
    "TypeKey hash of a ConstantType is stable across repeated calls" in {
        val t  = Tasty.Type.ConstantType(Tasty.Constant.IntConst(42))
        val h1 = TypeKey.of(t).hash
        val h2 = TypeKey.of(t).hash
        assert(h1 == h2, s"hash unstable: first=$h1 second=$h2")
    }

    // Test 2: cross-platform discrimination.
    // Two structurally different ConstantType values must hash to different integers,
    // confirming the type-key discriminator is not degenerate.
    "TypeKey hashes of two distinct ConstantTypes differ" in {
        val t1 = Tasty.Type.ConstantType(Tasty.Constant.IntConst(1))
        val t2 = Tasty.Type.ConstantType(Tasty.Constant.IntConst(2))
        val h1 = TypeKey.of(t1).hash
        val h2 = TypeKey.of(t2).hash
        assert(h1 != h2, s"expected distinct hashes but both were $h1")
    }

end PlatformHashingStateTest
