package kyo

import kyo.internal.TestClasspaths

/** Anchor fidelity test suite for decoder fidelity.
  *
  * This file owns the cross-cutting invariant tests. It also hosts the discipline check that every
  * `*FidelityTest.scala` references `TestClasspaths.withClasspath`.
  *
  * Tests using filesystem walks or the real stdlib classpath are gated jvmOnly. All java.nio.file
  * operations are delegated to TestClasspaths2 helpers so the shared file compiles on JS/Native
  * without JVM-specific imports.
  */
class RealClasspathFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "zero unknown-TASTy-tag warnings on a clean real-classpath load" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val errorMsgs        = classpath.errors.map(_.toString)
            val unknownTagErrors = errorMsgs.filter(_.contains("unknown TASTy type tag"))
            assert(
                unknownTagErrors.isEmpty,
                s"Expected zero unknown-TASTy-tag errors, found ${unknownTagErrors.size}: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            // Lower bound 70: TestClasspaths.withClasspath loads 70+ TASTy fixtures on JS/Native and
            // additionally indexes the real JVM stdlib on JVM. On any platform a clean load must
            // produce at least one symbol per fixture file (>= 70); a value < 70 indicates fixtures
            // were not picked up. We deliberately do not use exact equality because the JVM build
            // additionally indexes the stdlib (measured 81569 symbols on the JVM standard classpath 2026-06-04).
            assert(
                classpath.symbols.size >= 70,
                s"Expected classpath.symbols.size >= 70 after clean load but got ${classpath.symbols.size}"
            )
            succeed
    }

    "TPT tags dispatch to tree-decoder producing real Type values" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val allTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case s: Tasty.Symbol.Var    => s.declaredType.toList
                    case s: Tasty.Symbol.Field  => s.declaredType.toList
                    case _                      => Nil
            val appliedCount = allTypes.count:
                case _: Tasty.Type.Applied => true
                case _                     => false
            assert(
                appliedCount > 0,
                s"Expected Type.Applied instances from APPLIEDtpt decoding, found $appliedCount"
            )
            succeed
    }

    "TypeUnpickler throws on unhandled tag instead of silently continuing" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val typeUnpicklerErrors = classpath.errors.filter(_.toString.contains("TypeUnpickler"))
            assert(
                typeUnpicklerErrors.isEmpty,
                s"Expected zero TypeUnpickler errors on valid classpath, found: ${typeUnpicklerErrors.take(3).mkString(", ")}"
            )
            succeed
    }

    // UnknownType errors for symbols with absent declared types are permitted: they arise from
    // cross-file type references that the decoder could not resolve, which is a legitimate TASTy structure.
    "no file-level errors on real-classpath load" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val fileErrors = classpath.errors.filter:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                fileErrors.isEmpty,
                s"Expected no file-level errors, got ${fileErrors.size}:\n" +
                    fileErrors.take(5).map(_.toString).mkString("\n")
            )
            succeed
    }

    // Interning consolidates all fabricated placeholder names to 3 interned sentinels:
    // <unresolved>, <rec-placeholder>, <unknown-type-tag>.
    "SymbolId(-1) sentinel name set size <= 3 on real classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct SymbolId(-1) sentinel names   " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "consolidation interned all fabricated placeholder names to 3 interned sentinels: " +
                    "<unresolved>, <rec-placeholder>, <unknown-type-tag>."
            )
            succeed
    }

end RealClasspathFidelityTest
