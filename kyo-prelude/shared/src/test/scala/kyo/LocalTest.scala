package kyo

import kyo.*
import kyo.kernel.Boundary

class LocalTest extends Test:

    def withBuilder[A](f: ((=> Int) => Local[Int]) => Assertion) =
        "isolated" in f(Local.initIsolated)
        "regular" in f(Local.init)

    "default" - {
        "method" - withBuilder { b =>
            val l = b(10)
            assert(l.default == 10)
        }
        "get" - withBuilder { b =>
            val l = b(10)
            assert(
                l.get.eval == 10
            )
        }
        "effect + get" - withBuilder { b =>
            val l = b(10)
            assert(
                Env.run(1)(Env.use[Int](_ => l.get)).eval == 10
            )
        }
        "effect + get + effect" - withBuilder { b =>
            val l = b(10)
            assert(
                Env.run(42)(Env.use(_ => l.get).map(i => Env.use[Int](_ + i))).eval ==
                    52
            )
        }
        "multiple" - withBuilder { b =>
            val l1 = b(10)
            val l2 = b(20)
            assert(
                Kyo.zip(l1.get, l2.get).eval == (10, 20)
            )
        }
        "lazy" - withBuilder { b =>
            var invoked = 0
            def default =
                invoked += 1
                10
            val l = b(default)
            assert(invoked == 0)
            assert(l.default == 10)
            assert(l.default == 10)
            assert(invoked == 1)
        }
    }

    "let" - {
        "get" in {
            val l = Local.init(10)
            assert(
                l.let(20)(l.get).eval ==
                    20
            )
        }
        "effect + get" in {
            val l = Local.init(10)
            assert(
                Env.run(1)(Env.use(_ => l.let(20)(l.get))).eval == 20
            )
        }
        "effect + get + effect" in {
            val l = Local.init(10)
            assert(
                Env.run(1)(Env.use(_ => l.let(20)(l.get).map(i => Env.use[Int](_ + i)))).eval == 21
            )
        }
        "multiple" in {
            val l1 = Local.init(10)
            val l2 = Local.init(20)
            assert(
                Kyo.zip(l1.let(30)(l1.get), l2.let(40)(l2.get)).eval ==
                    (30, 40)
            )
        }
    }

    "update" - {
        "get" in {
            val l = Local.init(10)
            assert(
                l.update(_ + 10)(l.get).eval == 20
            )
        }
        "effect + get" in {
            val l = Local.init(10)
            assert(
                Env.run(1)(Env.use(_ => l.update(_ + 20)(l.get))).eval == 30
            )
        }
        "effect + get + effect" in {
            val l = Local.init(10)
            assert(
                Env.run(1)(Env.use(_ => l.update(_ + 20)(l.get).map(i => Env.use[Int](_ + i)))).eval == 31
            )
        }
        "multiple" in {
            val l1 = Local.init(10)
            val l2 = Local.init(20)
            assert(
                Kyo.zip(l1.update(_ + 10)(l1.get), l2.update(_ + 10)(l2.get)).eval ==
                    (20, 30)
            )
        }
    }

    "isolated" - {
        "isolation and context inheritance" in {
            val isolatedLocal    = Local.initIsolated(10)
            val nonIsolatedLocal = Local.init("test")

            val boundary = Boundary.derive[Any, Any]

            val context =
                isolatedLocal.let(20)(nonIsolatedLocal.let("modified")(boundary { (trace, context) => context })).eval

            val nonIsolatedContext = context.get(Tag[Local.internal.State])
            assert(!nonIsolatedContext.contains(isolatedLocal))
            assert(nonIsolatedContext.contains(nonIsolatedLocal))

            assert(!context.contains(Tag[Local.internal.IsolatedState]))
        }

        "nested boundaries" in {
            val isolatedLocal    = Local.initIsolated(10)
            val nonIsolatedLocal = Local.init("test")

            val outerBoundary = Boundary.derive[Any, Any]
            val innerBoundary = Boundary.derive[Any, Any]

            val context =
                isolatedLocal.let(20)(
                    nonIsolatedLocal.let("outer")(
                        outerBoundary { (outerTrace, outerContext) =>
                            isolatedLocal.let(30)(
                                nonIsolatedLocal.let("inner")(
                                    innerBoundary { (innerTrace, innerContext) => innerContext }
                                )
                            )
                        }
                    )
                ).eval

            val nonIsolatedContext = context.get(Tag[Local.internal.State])
            assert(!nonIsolatedContext.contains(isolatedLocal))
            assert(nonIsolatedContext.contains(nonIsolatedLocal))
            assert(nonIsolatedContext.get(nonIsolatedLocal).contains("inner"))

            assert(!context.contains(Tag[Local.internal.IsolatedState]))
        }

    }

end LocalTest
