package kyo

import kyo.*

class LocalTest extends Test:

    def withBuilder[A](f: ((=> Int) => Local[Int]) => Assertion) =
        "noninheritable" in f(Local.initNoninheritable)
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

    "non-inheritable" - {
        "context inheritance" in {
            val noninheritableLocal = Local.initNoninheritable(10)
            val inheritableLocal    = Local.init("test")

            val isolate = Isolate.Contextual[Any, Any]

            val context =
                noninheritableLocal.let(20)(inheritableLocal.let("modified")(isolate.runInternal { (trace, context) => context })).eval

            val inheritableContext = context.get(Tag[Local.internal.State])
            assert(!inheritableContext.contains(noninheritableLocal))
            assert(inheritableContext.contains(inheritableLocal))

            assert(!context.contains(Tag[Local.internal.NoninheritableState]))
        }

        "nested boundaries" in {
            val noninheritableLocal = Local.initNoninheritable(10)
            val inheritableLocal    = Local.init("test")

            val isolate = Isolate.Contextual[Any, Any]

            val context =
                noninheritableLocal.let(20)(
                    inheritableLocal.let("outer")(
                        isolate.runInternal { (outerTrace, outerContext) =>
                            noninheritableLocal.let(30)(
                                inheritableLocal.let("inner")(
                                    isolate.runInternal { (innerTrace, innerContext) => innerContext }
                                )
                            )
                        }
                    )
                ).eval

            val inheritableContext = context.get(Tag[Local.internal.State])
            assert(!inheritableContext.contains(noninheritableLocal))
            assert(inheritableContext.contains(inheritableLocal))
            assert(inheritableContext.get(inheritableLocal).contains("inner"))

            assert(!context.contains(Tag[Local.internal.NoninheritableState]))
        }

    }

end LocalTest
