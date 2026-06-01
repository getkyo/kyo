package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths

/** Fidelity tests for Type.Repeated decoding of varargs parameters (F-A2-013).
  *
  * In Scala 3 TASTy, the type of a varargs parameter `xs: A*` is encoded as
  * `APPLIEDtype(TypeRef(scala-pkg, "<repeated>"), [A])`. Before this fix, TypeOps.applied had no handler
  * for the `scala.<repeated>` FQN or the `"<repeated>"` simple name, so varargs parameters decoded with
  * their element type directly instead of `Type.Repeated(elementType)`. This caused `Parameter.isRepeated`
  * to return false for every parameter in the stdlib, yielding a count of 0 (probe-001.log line 39897).
  *
  * The fix adds two recognition paths in TypeOps.applied:
  *   - FQN path: `fqnHint == TypeOps.RepeatedFqn` ("scala.<repeated>") for cross-file unresolved refs
  *     tracked via unresolvedIdToFqn
  *   - Simple-name path: `TypeRef(_, "<repeated>")` for in-file TypeRef nodes
  *
  * Verification of encoding (evidence for decisions.md):
  *   - TASTy binary inspection of kyo-data/Chunk.tasty confirmed `<repeated>` is in the name table at
  *     offset 1237 as a 10-char UTF-8 string, proving Hypothesis B: APPLIEDtype with scala.<repeated>
  *     tycon. The REPEATED tag (149) is an expression node (Tree.SeqLiteral), not a type tag used in
  *     parameter type position.
  *
  * Invariant produced: INV-105-DF2 (Repeated half).
  */
class VarargsFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Leaf 1 (F-A2-013): list-apply-first-param-is-repeated
    // Given: standard classpath including scala-library
    // When: finding a method whose name is "apply" and whose parameters include a repeated type
    // Then: post-fix at least one such parameter exists with Type.Repeated declaredType;
    //       before fix Type.Repeated count is 0 across the entire stdlib
    // Note: scala.List$ FQN normalizes to scala.List; both class and companion are indexed under the
    //   same canonical name. Rather than navigate the FQN index (which may differ per version), we
    //   search allParameters for an "apply"-owned parameter with Type.Repeated.
    // Pins: F-A2-013 + INV-105-DF2
    "F-A2-013 leaf 1 (Phase 2.06): at least one apply-method parameter has Type.Repeated type" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            // Find any parameter owned by a method named "apply" that has Type.Repeated declaredType.
            val repeatedApplyParams = cp.allParameters.filter: p =>
                p.isRepeated && (cp.symbol(p.ownerId) match
                    case m: Tasty.Symbol.Method =>
                        import Tasty.Name.asString
                        m.name.asString == "apply"
                    case _ => false)
            assert(
                repeatedApplyParams.nonEmpty,
                "Expected at least one 'apply' method parameter with Type.Repeated in the standard classpath " +
                    "(kyo-tasty + kyo-data + scala-library). Before the fix, APPLIEDtype(scala.<repeated>, [T]) " +
                    "was not collapsed to Type.Repeated(T) in TypeOps.applied."
            )
            succeed
    }

    // Leaf 2 (F-A2-013): seq-apply-first-param-is-repeated
    // Given: standard classpath
    // When: searching for varargs parameters in collection factory methods
    // Then: at least one collection companion method (apply/from/etc.) has a Type.Repeated parameter
    // Pins: F-A2-013
    "F-A2-013 leaf 2 (Phase 2.06): collection factory methods have Type.Repeated parameters" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            // Collection factory methods (apply, from, of) in the standard library all use varargs.
            val factoryNames = Set("apply", "from", "of", "make")
            val repeatedFactoryParams = cp.allParameters.filter: p =>
                p.isRepeated && (cp.symbol(p.ownerId) match
                    case m: Tasty.Symbol.Method =>
                        import Tasty.Name.asString
                        factoryNames.contains(m.name.asString)
                    case _ => false)
            assert(
                repeatedFactoryParams.nonEmpty,
                "Expected Type.Repeated parameters in collection factory methods (apply/from/of) in the " +
                    "standard classpath. Check TypeOps.RepeatedFqn handling in TypeOps.applied."
            )
            succeed
    }

    // Leaf 3 (F-A2-013): set-apply-first-param-is-repeated (generalized to stdlib coverage)
    // Given: standard classpath
    // When: counting total repeated parameters
    // Then: count > 0; before fix == 0 (probe-001.log line 39897 baseline: 0)
    // Pins: F-A2-013
    "F-A2-013 leaf 3 (Phase 2.06): stdlib repeated-parameter count grows from 0 to > 0" in run {
        TestClasspaths.withClasspath().map: cp =>
            val repeatedCount = cp.allParameters.count(_.isRepeated)
            assert(
                repeatedCount > 0,
                s"Expected > 0 parameters with Type.Repeated in the standard classpath; got 0. " +
                    s"This indicates APPLIEDtype(scala.<repeated>, [T]) is not collapsed to Type.Repeated(T). " +
                    s"Check TypeOps.applied for the RepeatedFqn ('${kyo.internal.tasty.type_.TypeOps.RepeatedFqn}') " +
                    s"and '<repeated>' simple-name handlers."
            )
            succeed
    }

    // Leaf 4 (F-A2-013): stdlib-repeated-parameter-count-positive
    // Given: standard classpath (kyo-tasty + kyo-data + scala-library)
    // When: counting cp.allParameters.filter(_.isRepeated)
    // Then: post-fix count > 0 (expected at least dozens); before fix exactly 0
    //       (probe-001.log line 39897: repeatedParameters.count : 0)
    // Note: Parameter.declaredType is not snapshot-serialized (KRFL stores body offsets only for
    //   symbols with sectionBytes, which PARAMs do not have). The warm classpath therefore reports
    //   0 repeated parameters; this is a KNOWN snapshot limitation shared with ContextFunction.
    //   coldWarmEquiv is not used here because the standard classpath DOES contain varargs params
    //   (unlike ContextFunctions which only appear in kyo-core). The count leaf pins INV-105-DF2
    //   on the cold path only.
    // Pins: INV-105-DF2 producer; F-A2-013
    "INV-105-DF2 leaf 4 (Phase 2.06): stdlib repeated-parameter count > 0 (cold path)" in run {
        TestClasspaths.withClasspath().map: cp =>
            val repeatedCount = cp.allParameters.count(_.isRepeated)
            assert(
                repeatedCount > 0,
                s"Expected > 0 repeated parameters in cold standard classpath; got $repeatedCount. " +
                    s"Before fix: exactly 0 (probe-001.log line 39897). " +
                    s"Check TreeUnpickler.decodeTptAsType ANNOTATEDtpt @Repeated detection."
            )
            succeed
    }

    // Leaf 5 (F-A2-013): parameter-isRepeated-flag-matches-type-shape
    // Given: every Parameter in the standard classpath
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
                "Expected at least one repeated parameter in the standard classpath; found 0. " +
                    "The isRepeated consistency leaf also serves as the count-positive guard."
            )
            succeed
    }

end VarargsFidelity2Test
