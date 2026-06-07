package kyo.internal

import kyo.*

/** Verifies the JS-side cross-platform fixture helper using the embedded TASTy fixtures. */
class TestClasspathsJsTest extends kyo.test.Test[Any]:

    "js-embedded-fixture-loads: allClassLike non-empty from embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // The fixture set has 70+ TASTy files; the exact total count is fragile against decoder
            // changes, so we assert specific class-likes are findable by FQN.
            val classLikes = cp.allClassLike
            for fqn <- Seq("kyo.fixtures.PlainClass", "kyo.fixtures.SomeTrait", "kyo.fixtures.BaseClass", "kyo.fixtures.ChildClass") do
                assert(
                    cp.findClassLike(fqn).isDefined,
                    s"Expected $fqn to be findable by FQN; total class-likes loaded: ${classLikes.size}"
                )
            end for
            succeed
    }

    "js-symbols-non-empty: cp.symbols non-empty from embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // The embedded fixture set produces exactly 1010 symbols. The count is deterministic
            // because MemoryFileSource loads fixed compiled bytes.
            assert(
                cp.symbols.size == 1010,
                s"Expected cp.symbols.size == 1010 but got ${cp.symbols.size}"
            )
            succeed
    }

    "js-fidelity-suite-compiles: test suite compiles and runs on JS" in {
        // The fact that this test body executes proves compilation succeeded.
        // The isJS assertion verifies the runtime engine is JS (not JVM or Native).
        assert(kyo.internal.Platform.isJS, "Expected isJS to be true in JS test runner")
        succeed
    }

    "js-no-classpath-errors: no errors loading well-formed embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(
                cp.errors.isEmpty,
                s"Expected no classpath errors but got ${cp.errors.size}"
            )
            succeed
    }

    "js-enum-case-symbol-kind: shapeTasty produces Symbol.EnumCase instances" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCaseSymbols = cp.symbols.filter(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseSymbols.size > 0,
                s"Expected at least one Symbol.EnumCase from shapeTasty but got ${enumCaseSymbols.size}"
            )
            succeed
    }

    "js-cross-file-resolution: BaseClass and ChildClass both resolve by FQN" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val baseResult  = cp.findClassLike("kyo.fixtures.BaseClass")
            val childResult = cp.findClassLike("kyo.fixtures.ChildClass")
            assert(baseResult.isDefined, "Expected kyo.fixtures.BaseClass to be findable by FQN")
            assert(childResult.isDefined, "Expected kyo.fixtures.ChildClass to be findable by FQN")
            succeed
    }

end TestClasspathsJsTest
