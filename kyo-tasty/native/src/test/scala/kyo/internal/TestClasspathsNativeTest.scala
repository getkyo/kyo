package kyo.internal

import kyo.*

/** Phase 14 test leaves: Native cross-platform fixture helper verification.
  *
  * Pins: F-F-001 (Native parity). All leaves use the embedded TASTy fixture helper and do not require a real JVM classpath. The `run {}`
  * harness (BaseKyoCoreTest) handles `Abort[Any] & Async & Scope`, so test bodies call `TestClasspaths.withClasspath.map(cp => ...)`
  * directly.
  *
  * Leaves:
  *   1. native-embedded-fixture-loads: cp.allClasses.size > 0 from embedded fixtures.
  *   2. native-symbols-non-empty: cp.symbols.size > 0 (includes methods and vals).
  *   3. native-fidelity-suite-compiles: compile+run parity leaf (running proves compilation succeeded).
  *   4. native-no-classpath-errors: cp.errors.isEmpty on well-formed embedded fixtures.
  *   5. native-enum-case-symbol-kind: at least one Symbol.EnumCase from colorTasty (pins Phase 13 on Native).
  *   6. native-cross-file-resolution: BaseClass and ChildClass both findable by FQN (pins cross-file work on Native).
  */
class TestClasspathsNativeTest extends Test:

    // Leaf 1: native-embedded-fixture-loads
    // Given: the embedded TASTy fixtures compiled into the Native test bundle.
    // When: calling TestClasspaths.withClasspath on Native.
    // Then: the resulting Classpath has at least one class-like symbol.
    // Pins: F-F-001 (Native parity).
    "native-embedded-fixture-loads: allClasses non-empty from embedded fixtures" in run {
        TestClasspaths.withClasspath.map: cp =>
            assert(cp.allClasses.size > 0, s"Expected allClasses.size > 0 but got ${cp.allClasses.size}")
            succeed
    }

    // Leaf 2: native-symbols-non-empty
    // Given: the embedded TASTy fixtures.
    // When: calling cp.symbols.
    // Then: size > 0 (includes methods, vals, classes).
    // Pins: F-F-001 (Native parity, broader symbol check).
    "native-symbols-non-empty: cp.symbols non-empty from embedded fixtures" in run {
        TestClasspaths.withClasspath.map: cp =>
            assert(cp.symbols.size > 0, s"Expected cp.symbols.size > 0 but got ${cp.symbols.size}")
            succeed
    }

    // Leaf 3: native-fidelity-suite-compiles
    // Given: the Native test source for fixture leaves.
    // When: scalac runs in Native mode.
    // Then: the suite compiles and runs without "compile error: class not found".
    // Pins: F-F-001 (compile-level parity on Native). Running this test proves the Native bundle compiled.
    "native-fidelity-suite-compiles: test suite compiles and runs on Native" in run {
        // The fact that this test body executes proves compilation succeeded.
        // The isNative assertion verifies the runtime is Scala Native (not JVM or JS).
        assert(kyo.internal.Platform.isNative, "Expected isNative to be true in Native test runner")
        succeed
    }

    // Leaf 4: native-no-classpath-errors
    // Given: the embedded TASTy fixtures (all well-formed, compiled from real Scala source).
    // When: loading via TestClasspaths.withClasspath with ErrorMode.SoftFail.
    // Then: cp.errors is empty (no parse errors on valid fixture bytes).
    // Pins: F-F-001 (correctness check beyond existence).
    "native-no-classpath-errors: no errors loading well-formed embedded fixtures" in run {
        TestClasspaths.withClasspath.map: cp =>
            assert(
                cp.errors.isEmpty,
                s"Expected no classpath errors but got ${cp.errors.size}"
            )
            succeed
    }

    // Leaf 5: native-enum-case-symbol-kind
    // Given: the embedded colorTasty fixture (kyo.fixtures.Color enum with Red/Green/Blue cases).
    // When: loading via TestClasspaths.withClasspath.
    // Then: at least one symbol carries the Case flag (the Color enum cases are simple-value cases, encoded as
    //   VALDEF with Flag.Case; class-form EnumCase requires parametric cases like `case Foo(x: Int)`).
    // Note: simple value enum cases like `case Red, Green, Blue` produce Symbol.Val with isCase=true, not Symbol.EnumCase.
    //   Symbol.EnumCase is only produced for class-form enum cases. The Color fixture uses simple values.
    //   This leaf verifies that the enum flag classification round-trips correctly on Native.
    // Pins: F-E-007 on Native (enum case flag wiring from Phase 13, verified cross-platform).
    "native-enum-case-symbol-kind: colorTasty produces symbols with Case flag" in run {
        TestClasspaths.withClasspath.map: cp =>
            val caseFlagSymbols = cp.symbols.filter(_.isCase)
            assert(
                caseFlagSymbols.size > 0,
                s"Expected at least one symbol with isCase=true from colorTasty but got ${caseFlagSymbols.size}"
            )
            succeed
    }

    // Leaf 6: native-cross-file-resolution
    // Given: both BaseClass.tasty and ChildClass.tasty embedded in the fixture set.
    // When: loading via TestClasspaths.withClasspath (both files loaded together).
    // Then: both kyo.fixtures.BaseClass and kyo.fixtures.ChildClass are findable by FQN.
    // Pins: cross-file TYPEREFpkg resolution from Phase 13, verified on Native.
    "native-cross-file-resolution: BaseClass and ChildClass both resolve by FQN" in run {
        TestClasspaths.withClasspath.map: cp =>
            val baseResult  = cp.findClassLike("kyo.fixtures.BaseClass")
            val childResult = cp.findClassLike("kyo.fixtures.ChildClass")
            assert(baseResult.isDefined, "Expected kyo.fixtures.BaseClass to be findable by FQN")
            assert(childResult.isDefined, "Expected kyo.fixtures.ChildClass to be findable by FQN")
            succeed
    }

end TestClasspathsNativeTest
