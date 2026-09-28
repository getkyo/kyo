package kyo.internal

class FindEnclosingTest extends kyo.test.Test[Any]:

    "test sources" - {
        "a Test or Spec file under src/test is exempt" in {
            assert(FindEnclosing.isExempt("/r/kyo-core/shared/src/test/scala/kyo/FooTest.scala", "FooTest.scala"))
            assert(FindEnclosing.isExempt("/r/kyo-core/shared/src/test/scala/kyo/FooSpec.scala", "FooSpec.scala"))
        }

        "a Test file under src_managed/test is exempt" in {
            assert(FindEnclosing.isExempt("/r/app/target/scala-3/src_managed/test/kyo/FooTest.scala", "FooTest.scala"))
        }

        "a fixture under src/test that is not a Test or Spec file is not exempt" in {
            assert(!FindEnclosing.isExempt("/r/kyo-core/shared/src/test/scala/kyo/Fixtures.scala", "Fixtures.scala"))
        }
    }

    "conformance sources" - {
        "a Test file in a conformance module's main sources is exempt on every platform directory" in {
            assert(FindEnclosing.isExempt(
                "/r/kyo-flow-conformance/shared/src/main/scala/kyo/FlowStoreConformanceTest.scala",
                "FlowStoreConformanceTest.scala"
            ))
            assert(FindEnclosing.isExempt("/r/kyo-system-conformance/jvm/src/main/scala/kyo/FooTest.scala", "FooTest.scala"))
        }

        "a non-Test file in a conformance module's main sources is not exempt" in {
            assert(!FindEnclosing.isExempt(
                "/r/kyo-sql-conformance/shared/src/main/scala/kyo/SqlConformanceBackend.scala",
                "SqlConformanceBackend.scala"
            ))
        }

        "a Test file in an ordinary module's main sources is not exempt" in {
            assert(!FindEnclosing.isExempt("/r/kyo-flow/shared/src/main/scala/kyo/FlowTest.scala", "FlowTest.scala"))
        }

        "a checkout under a directory ending in -conformance exempts nothing" in {
            assert(!FindEnclosing.isExempt(
                "/home/me/my-conformance/kyo/kyo-flow/shared/src/main/scala/kyo/FlowTest.scala",
                "FlowTest.scala"
            ))
        }
    }

    "a Bench file is exempt wherever it is" in {
        assert(FindEnclosing.isExempt("/r/kyo-bench/src/main/scala/kyo/bench/FooBench.scala", "FooBench.scala"))
    }

end FindEnclosingTest
