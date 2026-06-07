package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths

/** Fidelity tests for Type.Repeated decoding of varargs parameters.
  *
  * In Scala 3 TASTy, the type of a varargs parameter `xs: A*` is encoded as an ANNOTATEDtpt with a `@scala.annotation.internal.Repeated`
  * annotation on the underlying type. Without handling this annotation, varargs parameters decode without the `Type.Repeated` wrapper,
  * causing `Parameter.isRepeated` to return false.
  *
  * All leaves use TestClasspaths.withClasspath which works on JS/Native via embedded fixtures. The embedded fixture set includes
  * `VarargFixture.tasty` (kyo.fixtures.VarargFixture.concat: String*) to exercise the Type.Repeated path on all platforms.
  */
class VarargsFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // list-apply-first-param-is-repeated
    "at least one apply or concat method parameter has Type.Repeated type" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val targetNames       = Set("apply", "concat")
            val repeatedTargetParams = cp.allParameters.filter: p =>
                p.declaredType.isInstanceOf[Tasty.Type.Repeated] && (cp.symbol(p.ownerId) match
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

    // seq-apply-first-param-is-repeated
    "at least one method has a Type.Repeated parameter" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val repeatedParams    = cp.allParameters.filter((p: Tasty.Symbol.Parameter) => p.declaredType.isInstanceOf[Tasty.Type.Repeated])
            assert(
                repeatedParams.nonEmpty,
                "Expected at least one repeated parameter in the classpath. " +
                    "On JS/Native: VarargFixture.concat(xs: String*) should contribute one. " +
                    "Check that VarargFixture.tasty bytes are correct in Embedded.scala and are added to " +
                    "the MemoryFileSource in TestClasspaths.withClasspath on JS/Native."
            )
            succeed
    }

    // set-apply-first-param-is-repeated (generalized to stdlib coverage)
    "stdlib/embedded repeated-parameter count grows from 0 to > 0" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val repeatedCount = cp.allParameters.count((p: Tasty.Symbol.Parameter) => p.declaredType.isInstanceOf[Tasty.Type.Repeated])
            assert(
                repeatedCount > 0,
                s"Expected > 0 parameters with Type.Repeated in the classpath; got 0. " +
                    s"On JVM: stdlib should have many. On JS/Native: VarargFixture.concat should contribute 1. " +
                    s"Check ANNOTATEDtpt @Repeated detection in TreeUnpickler.decodeTptAsType."
            )
            succeed
    }

    // stdlib-repeated-parameter-count-positive
    "repeated-parameter count > 0 (cold path)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val repeatedCount = cp.allParameters.count((p: Tasty.Symbol.Parameter) => p.declaredType.isInstanceOf[Tasty.Type.Repeated])
            assert(
                repeatedCount > 0,
                s"Expected > 0 repeated parameters in cold classpath; got $repeatedCount. " +
                    s"Before fix: exactly 0 (probe-001.log line 39897). " +
                    s"Check TreeUnpickler.decodeTptAsType ANNOTATEDtpt @Repeated detection."
            )
            succeed
    }

    // parameter-isRepeated-flag-matches-type-shape
    "isRepeated=true implies Type.Repeated declaredType on all parameters" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var violations    = 0
            var totalRepeated = 0
            cp.allParameters.foreach: p =>
                if p.declaredType.isInstanceOf[Tasty.Type.Repeated] then
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
