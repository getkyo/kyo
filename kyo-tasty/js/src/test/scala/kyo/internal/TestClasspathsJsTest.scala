package kyo.internal

import kyo.*

/** test leaves: JS cross-platform fixture helper verification. Updated in to include Shape fixture.
  *
  * (BaseKyoCoreTest) handles `Abort[Any] & Async & Scope`, so test bodies call `TestClasspaths.withClasspath().map(cp => ...)` directly.
  *
  * Leaves:
  *   1. js-embedded-fixture-loads: cp.allClassLike.size > 0 from embedded fixtures.
  *   2. js-symbols-non-empty: cp.symbols.size > 0 (includes methods and vals).
  *   3. js-fidelity-suite-compiles: compile+run parity leaf (running proves compilation succeeded).
  *   4. js-no-classpath-errors: cp.errors.isEmpty on well-formed embedded fixtures.
  *   5. js-enum-case-symbol-kind: at least one Symbol.EnumCase from shapeTasty (pins + 15 on JS).
  *   6. js-cross-file-resolution: BaseClass and ChildClass both findable by FQN (pins cross-file work on JS).
  */
class TestClasspathsJsTest extends kyo.test.Test[Any]:

    // Leaf 1: js-embedded-fixture-loads
    // Given: the embedded TASTy fixtures compiled into the JS test bundle.
    // When: calling TestClasspaths.withClasspath on JS.
    // Then: the resulting Classpath has at least one class-like symbol.
    "js-embedded-fixture-loads: allClassLike non-empty from embedded fixtures" in {
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
    // Then: size == 1010 (exact count for the full embedded fixture set including Java fixture).
    "js-symbols-non-empty: cp.symbols non-empty from embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // Exact count: the embedded fixture set (all kyo.fixtures.Embedded.* files including
            // JavaSimpleFixture added in) produces exactly 1010 symbols. This is
            // deterministic because MemoryFileSource loads fixed compiled bytes. Q-025 RI-008 measured 2026-06-04.
            assert(
                cp.symbols.size == 1010,
                s"Expected cp.symbols.size == 1010 (Q-025 RI-008 measured 2026-06-04) but got ${cp.symbols.size}"
            )
            succeed
    }

    // Leaf 3: js-fidelity-suite-compiles
    // Given: the JS test source for fixture leaves.
    // When: scalac runs in JS mode.
    // Then: the suite compiles and runs without "compile error: class not found".
    "js-fidelity-suite-compiles: test suite compiles and runs on JS" in {
        // The fact that this test body executes proves compilation succeeded.
        // The isJS assertion verifies the runtime engine is JS (not JVM or Native).
        assert(kyo.internal.Platform.isJS, "Expected isJS to be true in JS test runner")
        succeed
    }

    // Leaf 4: js-no-classpath-errors
    // Given: the embedded TASTy fixtures (all well-formed, compiled from real Scala source).
    // When: loading via TestClasspaths.withClasspath with ErrorMode.SoftFail.
    // Then: cp.errors is empty (no parse errors on valid fixture bytes).
    "js-no-classpath-errors: no errors loading well-formed embedded fixtures" in {
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
    //   Symbol.EnumCase is decoded and round-trips correctly on JS.
    "js-enum-case-symbol-kind: shapeTasty produces Symbol.EnumCase instances" in {
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
    "js-cross-file-resolution: BaseClass and ChildClass both resolve by FQN" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val baseResult  = cp.findClassLike("kyo.fixtures.BaseClass")
            val childResult = cp.findClassLike("kyo.fixtures.ChildClass")
            assert(baseResult.isDefined, "Expected kyo.fixtures.BaseClass to be findable by FQN")
            assert(childResult.isDefined, "Expected kyo.fixtures.ChildClass to be findable by FQN")
            succeed
    }

end TestClasspathsJsTest
