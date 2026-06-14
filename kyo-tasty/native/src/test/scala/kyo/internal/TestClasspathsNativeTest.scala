package kyo.internal

import kyo.*

/** Verifies the Native-side cross-platform fixture helper using the embedded TASTy fixtures. */
class TestClasspathsNativeTest extends kyo.test.Test[Any]:

    "native-embedded-fixture-loads: allClassLike non-empty from embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            // The fixture set has 70+ TASTy files; the exact total count is fragile against decoder
            // changes, so we assert specific class-likes are findable by fully-qualified name.
            val classLikes = classpath.allClassLike
            for fullName <- Seq("kyo.fixtures.PlainClass", "kyo.fixtures.SomeTrait", "kyo.fixtures.BaseClass", "kyo.fixtures.ChildClass") do
                assert(
                    classpath.findClassLike(fullName).isDefined,
                    s"Expected $fullName to be findable by fully-qualified name; total class-likes loaded: ${classLikes.size}"
                )
            end for
            succeed
        }
    }

    "native-symbols-non-empty: classpath.symbols non-empty from embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            assert(
                classpath.symbols.nonEmpty,
                s"Expected non-empty classpath.symbols from embedded fixture set; got ${classpath.symbols.size}"
            )
            succeed
        }
    }

    "native-fidelity-suite-compiles: test suite compiles and runs on Native" in {
        // The fact that this test body executes proves compilation succeeded.
        // The isNative assertion verifies the runtime is Scala Native (not JVM or JS).
        assert(kyo.internal.Platform.isNative, "Expected isNative to be true in Native test runner")
        succeed
    }

    "native-no-classpath-errors: no errors loading well-formed embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            assert(
                classpath.errors.isEmpty,
                s"Expected no classpath errors but got ${classpath.errors.size}"
            )
            succeed
        }
    }

    "native-enum-case-symbol-kind: shapeTasty produces Symbol.EnumCase instances" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val enumCaseSymbols = classpath.symbols.filter(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseSymbols.size > 0,
                s"Expected at least one Symbol.EnumCase from shapeTasty but got ${enumCaseSymbols.size}"
            )
            succeed
        }
    }

    "native-cross-file-resolution: BaseClass and ChildClass both resolve by fully-qualified name" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val baseResult  = classpath.findClassLike("kyo.fixtures.BaseClass")
            val childResult = classpath.findClassLike("kyo.fixtures.ChildClass")
            assert(baseResult.isDefined, "Expected kyo.fixtures.BaseClass to be findable by fully-qualified name")
            assert(childResult.isDefined, "Expected kyo.fixtures.ChildClass to be findable by fully-qualified name")
            succeed
        }
    }

end TestClasspathsNativeTest
