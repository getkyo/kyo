package kyo.internal

import kyo.*

/** Phase 14 test leaves: JS cross-platform fixture helper verification. Updated in Phase 15 to include Shape fixture.
  *
  * Pins: F-F-001 (JS parity). All leaves use the embedded TASTy fixture helper and do not require a real JVM classpath. The `run {}` harness
  * (BaseKyoCoreTest) handles `Abort[Any] & Async & Scope`, so test bodies call `TestClasspaths.withClasspath().map(cp => ...)` directly.
  *
  * Leaves:
  *   1. js-embedded-fixture-loads: cp.allClassLike.size > 0 from embedded fixtures.
  *   2. js-symbols-non-empty: cp.symbols.size > 0 (includes methods and vals).
  *   3. js-fidelity-suite-compiles: compile+run parity leaf (running proves compilation succeeded).
  *   4. js-no-classpath-errors: cp.errors.isEmpty on well-formed embedded fixtures.
  *   5. js-enum-case-symbol-kind: at least one Symbol.EnumCase from shapeTasty (pins Phase 13 + 15 on JS).
  *   6. js-cross-file-resolution: BaseClass and ChildClass both findable by FQN (pins cross-file work on JS).
  */
class TestClasspathsJsTest extends Test:

    // Leaf 1: js-embedded-fixture-loads
    // Given: the embedded TASTy fixtures compiled into the JS test bundle.
    // When: calling TestClasspaths.withClasspath on JS.
    // Then: the resulting Classpath has at least one class-like symbol.
    // Pins: F-F-001 (JS parity).
    "js-embedded-fixture-loads: allClassLike non-empty from embedded fixtures" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // The fixture set adds 70+ TASTy files (see TestClasspaths.withClasspath). The exact total
            // count is fragile against decoder changes, so we assert specific class-likes are findable
            // by FQN: PlainClass, SomeTrait, BaseClass, and ChildClass must all be present.
            val classLikes = cp.allClassLike
            for fqn <- Seq("kyo.fixtures.PlainClass", "kyo.fixtures.SomeTrait", "kyo.fixtures.BaseClass", "kyo.fixtures.ChildClass") do
                assert(
                    cp.findClassLike(fqn).isDefined,
                    s"Expected $fqn to be findable by FQN; total class-likes loaded: ${classLikes.size}"
                )
            end for
            succeed
    }

    // Leaf 2: js-symbols-non-empty
    // Given: the embedded TASTy fixtures.
    // When: calling cp.symbols.
    // Then: size > 0 (includes methods, vals, classes).
    // Pins: F-F-001 (JS parity, broader symbol check).
    "js-symbols-non-empty: cp.symbols non-empty from embedded fixtures" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // The fixture set loads 70+ TASTy files, each contributing classes plus their members.
            // Asserting a strict lower bound (>= 70 symbols, i.e. one per file) is a meaningful
            // check that decoding produced more than a single root package symbol.
            assert(
                cp.symbols.size >= 70,
                s"Expected cp.symbols.size >= 70 (one per fixture TASTy file) but got ${cp.symbols.size}"
            )
            succeed
    }

    // Leaf 3: js-fidelity-suite-compiles
    // Given: the JS test source for fixture leaves.
    // When: scalac runs in JS mode.
    // Then: the suite compiles and runs without "compile error: class not found".
    // Pins: F-F-001 (compile-level parity). Running this test proves the JS bundle compiled.
    "js-fidelity-suite-compiles: test suite compiles and runs on JS" in run {
        // The fact that this test body executes proves compilation succeeded.
        // The isJS assertion verifies the runtime engine is JS (not JVM or Native).
        assert(kyo.internal.Platform.isJS, "Expected isJS to be true in JS test runner")
        succeed
    }

    // Leaf 4: js-no-classpath-errors
    // Given: the embedded TASTy fixtures (all well-formed, compiled from real Scala source).
    // When: loading via TestClasspaths.withClasspath with ErrorMode.SoftFail.
    // Then: cp.errors is empty (no parse errors on valid fixture bytes).
    // Pins: F-F-001 (correctness check beyond existence).
    "js-no-classpath-errors: no errors loading well-formed embedded fixtures" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(
                cp.errors.isEmpty,
                s"Expected no classpath errors but got ${cp.errors.size}"
            )
            succeed
    }

    // Leaf 5: js-enum-case-symbol-kind
    // Given: the embedded shapeTasty fixture (kyo.fixtures.Shape parametric enum with Circle/Square/Rectangle cases).
    // When: loading via TestClasspaths.withClasspath.
    // Then: at least one symbol is an instance of Symbol.EnumCase (class-form enum case from Shape).
    // Note: class-form enum cases like `case Circle(radius: Double)` produce Symbol.EnumCase, not Symbol.Val.
    //   This leaf pins that Symbol.EnumCase is decoded and round-trips correctly on JS (Phase 13 + 15).
    // Pins: F-E-007 on JS (Symbol.EnumCase class-form from Phase 13, tightened in Phase 15 with Shape fixture).
    "js-enum-case-symbol-kind: shapeTasty produces Symbol.EnumCase instances" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCaseSymbols = cp.symbols.filter(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseSymbols.size > 0,
                s"Expected at least one Symbol.EnumCase from shapeTasty but got ${enumCaseSymbols.size}"
            )
            succeed
    }

    // Leaf 6: js-cross-file-resolution
    // Given: both BaseClass.tasty and ChildClass.tasty embedded in the fixture set.
    // When: loading via TestClasspaths.withClasspath (both files loaded together).
    // Then: both kyo.fixtures.BaseClass and kyo.fixtures.ChildClass are findable by FQN.
    // Pins: cross-file TYPEREFpkg resolution from Phase 13, verified on JS.
    "js-cross-file-resolution: BaseClass and ChildClass both resolve by FQN" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val baseResult  = cp.findClassLike("kyo.fixtures.BaseClass")
            val childResult = cp.findClassLike("kyo.fixtures.ChildClass")
            assert(baseResult.isDefined, "Expected kyo.fixtures.BaseClass to be findable by FQN")
            assert(childResult.isDefined, "Expected kyo.fixtures.ChildClass to be findable by FQN")
            succeed
    }

end TestClasspathsJsTest
