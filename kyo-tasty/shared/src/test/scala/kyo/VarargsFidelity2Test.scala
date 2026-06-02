package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths

/** Fidelity tests for Type.Repeated decoding of varargs parameters (F-A2-013).
  *
  * In Scala 3 TASTy, the type of a varargs parameter `xs: A*` is encoded as an ANNOTATEDtpt with an `@scala.annotation.internal.Repeated`
  * annotation on the underlying type. Before this fix, TreeUnpickler.decodeTptAsType did not check for this annotation, so varargs
  * parameters decoded without the `Type.Repeated` wrapper. This caused `Parameter.isRepeated` to return false for every parameter in the
  * stdlib, yielding a count of 0 (probe-001.log line 39897).
  *
  * Phase 2.10: relocated from jvm/src/test to shared/src/test. All leaves use TestClasspaths.withClasspath which works on JS/Native
  * via embedded fixtures. The embedded fixture set is extended with `VarargFixture.tasty` (kyo.fixtures.VarargFixture.concat: String*)
  * to ensure that JS/Native have at least one varargs parameter to exercise the Type.Repeated path.
  *
  * Invariant produced: INV-105-DF2 (Repeated half).
  */
class VarargsFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Leaf 1 (F-A2-013): list-apply-first-param-is-repeated
    // Given: standard classpath (on JVM: scala.List$.apply; on JS/Native: kyo.fixtures.VarargFixture.concat)
    // When: finding a method whose name is "apply" or "concat" with a repeated parameter type
    // Then: post-fix at least one such parameter exists with Type.Repeated declaredType
    // Pins: F-A2-013 + INV-105-DF2
    "F-A2-013 leaf 1 (Phase 2.06): at least one apply or concat method parameter has Type.Repeated type" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            val targetNames       = Set("apply", "concat")
            val repeatedTargetParams = cp.allParameters.filter: p =>
                p.isRepeated && (cp.symbol(p.ownerId) match
                    case m: Tasty.Symbol.Method =>
                        import Tasty.Name.asString
                        targetNames.contains(m.name.asString)
                    case _ => false)
            assert(
                repeatedTargetParams.nonEmpty,
                "Expected at least one 'apply' or 'concat' method parameter with Type.Repeated in the classpath. " +
                    "On JVM: scala.List$.apply or similar. On JS/Native: kyo.fixtures.VarargFixture.concat(xs: String*). " +
                    "Check that VarargFixture.tasty is in the embedded fixture set (Embedded.varargFixtureTasty)."
            )
            succeed
    }

    // Leaf 2 (F-A2-013): seq-apply-first-param-is-repeated
    // Given: standard classpath
    // When: searching for varargs parameters in any methods
    // Then: at least one method with a Type.Repeated parameter
    // Pins: F-A2-013
    "F-A2-013 leaf 2 (Phase 2.06): at least one method has a Type.Repeated parameter" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            val repeatedParams    = cp.allParameters.filter(_.isRepeated)
            assert(
                repeatedParams.nonEmpty,
                "Expected at least one repeated parameter in the classpath. " +
                    "On JS/Native: VarargFixture.concat(xs: String*) should contribute one. " +
                    "Check that VarargFixture.tasty bytes are correct in Embedded.scala and are added to " +
                    "the MemoryFileSource in TestClasspaths.withClasspath on JS/Native."
            )
            succeed
    }

    // Leaf 3 (F-A2-013): set-apply-first-param-is-repeated (generalized to stdlib coverage)
    // Given: standard classpath
    // When: counting total repeated parameters
    // Then: count > 0; before fix == 0 (probe-001.log line 39897 baseline: 0)
    // Pins: F-A2-013
    "F-A2-013 leaf 3 (Phase 2.06): stdlib/embedded repeated-parameter count grows from 0 to > 0" in run {
        TestClasspaths.withClasspath().map: cp =>
            val repeatedCount = cp.allParameters.count(_.isRepeated)
            assert(
                repeatedCount > 0,
                s"Expected > 0 parameters with Type.Repeated in the classpath; got 0. " +
                    s"On JVM: stdlib should have many. On JS/Native: VarargFixture.concat should contribute 1. " +
                    s"Check ANNOTATEDtpt @Repeated detection in TreeUnpickler.decodeTptAsType."
            )
            succeed
    }

    // Leaf 4 (F-A2-013): stdlib-repeated-parameter-count-positive
    // Given: standard classpath
    // When: counting cp.allParameters.filter(_.isRepeated)
    // Then: post-fix count > 0; before fix exactly 0 (probe-001.log line 39897: repeatedParameters.count : 0)
    // Pins: INV-105-DF2 producer; F-A2-013
    "INV-105-DF2 leaf 4 (Phase 2.06): repeated-parameter count > 0 (cold path)" in run {
        TestClasspaths.withClasspath().map: cp =>
            val repeatedCount = cp.allParameters.count(_.isRepeated)
            assert(
                repeatedCount > 0,
                s"Expected > 0 repeated parameters in cold classpath; got $repeatedCount. " +
                    s"Before fix: exactly 0 (probe-001.log line 39897). " +
                    s"Check TreeUnpickler.decodeTptAsType ANNOTATEDtpt @Repeated detection."
            )
            succeed
    }

    // Leaf 5 (F-A2-013): parameter-isRepeated-flag-matches-type-shape
    // Given: every Parameter in the classpath
    // When: checking that isRepeated implies declaredType.isInstanceOf[Type.Repeated]
    // Then: zero divergence (flag and type shape are consistent)
    // Pins: F-A2-013 + INV-005 consistency
    "F-A2-013 leaf 5 (Phase 2.06): isRepeated=true implies Type.Repeated declaredType on all parameters" in run {
        TestClasspaths.withClasspath().map: cp =>
            var violations    = 0
            var totalRepeated = 0
            cp.allParameters.foreach: p =>
                if p.isRepeated then
                    totalRepeated += 1
                    p.declaredType match
                        case _: Tasty.Type.Repeated => ()
                        case _                      => violations += 1
            assert(
                violations == 0,
                s"Expected zero parameters where isRepeated=true but declaredType is not Type.Repeated; " +
                    s"found $violations violations out of $totalRepeated repeated parameters. " +
                    s"This indicates isRepeated and declaredType shape are diverged."
            )
            assert(
                totalRepeated > 0,
                "Expected at least one repeated parameter in the classpath; found 0. " +
                    "The isRepeated consistency leaf also serves as the count-positive guard."
            )
            succeed
    }

end VarargsFidelity2Test
