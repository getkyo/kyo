package kyo.internal

import kyo.*
import kyo.internal.LowerSupport.lowered
import scala.collection.mutable.ArrayBuffer

/** The lowering core on kyo-direct's markers, driven through `lowered` until `direct` switches to it. */
class LowerTest extends kyo.test.Test[Any]:

    "pure body" - {
        "keeps the value and lifts to A < Any" in {
            val value: Int < Any = lowered(1 + 1)
            assert(value.eval == 2)
        }
        "keeps a block with locals" in {
            val value: Int < Any = lowered {
                val x = 1
                val y = x + 1
                x + y
            }
            assert(value.eval == 3)
        }
    }

    "blocks" - {
        "binds a marker once and reuses the result" in {
            val value = lowered {
                val a = Var.update[Int](x => x + 1).now
                a + a
            }
            assert(Var.runTuple(0)(value).eval == (1, 2))
        }
        "a discarded marker still runs" in {
            val value = lowered {
                Var.update[Int](x => x + 1).now
                42
            }
            assert(Var.runTuple(0)(value).eval == (1, 42))
        }
        "a pure statement between markers keeps its position" in {
            val order = ArrayBuffer.empty[String]
            val value = lowered {
                order += "before"
                Sync.defer(order += "first").now
                order += "between"
                Sync.defer(order += "second").now
                order += "after"
                order.size
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 5)
            assert(order.toList == List("before", "first", "between", "second", "after"))
        }
        "a pure local between markers keeps its value" in {
            val value = lowered {
                val a    = Var.get[Int].now
                val pure = a * 10
                val b    = Var.update[Int](x => x + 1).now
                pure + b
            }
            assert(Var.runTuple(1)(value).eval == (2, 12))
        }
        "nested blocks" in {
            val value = lowered {
                val v =
                    val r = Sync.defer(1).now
                    r + 1
                v + 1
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 3)
        }
        "a marker inside the computation of another marker" in {
            def f(i: Int): Int < Var[Int] = Var.set(i)
            val value                     = lowered {
                f(Sync.defer(1).now).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.runTuple(-1)(value)) == (1, -1))
        }
        "an unused failure still aborts" in {
            val value = lowered {
                val unused = Abort.fail("failure").now
                42
            }
            assert(Abort.run(value).eval == Result.fail("failure"))
        }
    }

    "pass-through markers" - {
        "erased in value position" in {
            val value = lowered {
                val pending = Var.get[Int].later
                pending.now + 1
            }
            assert(Var.run(4)(value).eval == 5)
        }
        "erased inside a lambda" in {
            def f(i: Int): Boolean < Var[Int] = Var.update[Int](x => x + i).map(_ % 2 == 0)
            val stream                        = Stream.init(Seq(1, 2, 3))
            val value                         = lowered {
                stream.filter(x => f(x).later)
            }
            assert(Var.run(0)(Stream.unwrap(value).run).eval == Chunk(3))
        }
        "erased inside a local def" in {
            val value = lowered {
                def inc(i: Int): Int < Any = (i + 1).later
                inc(Sync.defer(1).now).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 2)
        }
        "erased inside another pass-through" in {
            val x: Int < Any              = 1
            def f[S](v: Int < S): Int < S = v.map(_ + 1)
            val value                     = lowered {
                val g = f(x.later).later
                g.now
            }
            assert(value.eval == 2)
        }
    }

    "conditionals" - {
        "effectful condition and branches" in {
            val value = lowered {
                if Var.get[Int].now == 0 then Var.update[Int](x => x + 1).now
                else Var.update[Int](x => x + 10).now
            }
            assert(Var.runTuple(0)(value).eval == (1, 1))
            assert(Var.runTuple(2)(value).eval == (12, 12))
        }
        "a conditional before more statements is joined, not duplicated" in {
            val value = lowered {
                val x =
                    if Var.get[Boolean].now then Var.get[Int].now
                    else 0
                x * 2
            }
            assert(Var.run(true)(Var.run(21)(value)).eval == 42)
            assert(Var.run(false)(Var.run(21)(value)).eval == 0)
        }
        "a branch with an implicit unit alternative" in {
            val value = lowered {
                val a = Var.get[Int].now
                if a > 5 then
                    Emit.value(a).now
                    ()
                "done"
            }
            assert(Emit.run(Var.run(8)(value)).eval == (Chunk(8), "done"))
            assert(Emit.run(Var.run(3)(value)).eval == (Chunk.empty, "done"))
        }
        "bindings inside branches" in {
            val value = lowered {
                val cond = Sync.defer(true).now
                if cond then
                    val x = Sync.defer(10).now
                    x + 1
                else 0
                end if
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 11)
        }
    }

    "boolean operators" - {
        def notExpected: Boolean = throw new IllegalStateException("evaluated")
        "&& short-circuits" in {
            val value = lowered {
                Var.get[Boolean].now && Sync.defer(notExpected).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.run(false)(value)) == false)
        }
        "&& evaluates the right side when needed" in {
            val value = lowered {
                Var.get[Boolean].now && Sync.defer(true).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.run(true)(value)))
        }
        "|| short-circuits" in {
            val value = lowered {
                Var.get[Boolean].now || Sync.defer(notExpected).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.run(true)(value)))
        }
        "pure left with effectful right" in {
            val value = lowered {
                "1".toInt == 1 && Sync.defer(false).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == false)
        }
    }

    "applications" - {
        "arguments evaluate in source order" in {
            def pair(a: Int, b: Int) = a * 10 + b
            val value                = lowered {
                pair(Var.update[Int](x => x + 1).now, Var.update[Int](x => x + 1).now)
            }
            assert(Var.runTuple(0)(value).eval == (2, 12))
        }
        "a pure argument before a marker keeps its position" in {
            val order                     = ArrayBuffer.empty[String]
            def record(name: String): Int =
                order += name
                order.size
            def pair(a: Int, b: Int) = a * 10 + b
            val value                = lowered {
                pair(record("pure"), Sync.defer(record("effect")).now)
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 12)
            assert(order.toList == List("pure", "effect"))
        }
        "a receiver evaluates before an effectful argument" in {
            val order = ArrayBuffer.empty[String]
            class Combiner:
                def combine(x: Int): Int = x + 1
            def make(): Combiner =
                order += "receiver"
                new Combiner
            val value = lowered {
                make().combine(Sync.defer(order += "argument").now.size)
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 3)
            assert(order.toList == List("receiver", "argument"))
        }
        "a method on the exposed result" in {
            val value = lowered(Var.get[String].now.length)
            assert(Var.run("hello")(value).eval == 5)
        }
        "an effectful element in a generic application" in {
            val value = lowered(List(Var.update[Int](x => x + 1).now))
            assert(Var.runTuple(0)(value).eval == (1, List(1)))
        }
        "a tuple with effectful elements and a block element" in {
            val value = lowered {
                val x = 1
                (
                    Sync.defer(x).now, {
                        val a = Sync.defer(2).now
                        a
                    },
                    Sync.defer(3).now
                )
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == (1, 2, 3))
        }
        "a curried call with the marker in the second list" in {
            def add(a: Int)(b: Int) = a + b
            val value               = lowered(add(1)(Var.get[Int].now))
            assert(Var.run(2)(value).eval == 3)
        }
        "an opaque receiver exposed by a marker" in {
            val maybe: Maybe[Int] < Sync = Maybe(1)
            val value                    = lowered(maybe.now.fold(2)(_ + 1))
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 2)
        }
    }

    "pattern matching" - {
        "effectful scrutinee and bodies" in {
            val value = lowered {
                Var.get[String].now match
                    case "a" => Sync.defer(2).now
                    case _   => Sync.defer(3).now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.run("a")(value)) == 2)
            assert(Sync.Unsafe.evalOrThrow(Var.run("b")(value)) == 3)
        }
        "a match before more statements is joined" in {
            val value = lowered {
                val n = Var.get[Int].now match
                    case 0 => Sync.defer(10).now
                    case n => n
                n + 1
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(Var.run(0)(value)) == 11)
            assert(Sync.Unsafe.evalOrThrow(Var.run(5)(value)) == 6)
        }
        "pure guards and bindings in case bodies" in {
            val value = lowered {
                val opt = Sync.defer(Option(5)).now
                opt match
                    case Some(v) if v > 3 =>
                        val doubled = Sync.defer(v * 2).now
                        doubled
                    case _ => 0
                end match
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 10)
        }
        "a pattern-bound pending identifier used through a marker" in {
            val value = lowered {
                val opt: Option[Int < Sync] = Option(Sync.defer(7).later)
                opt match
                    case Some(inner) => inner.now * 2
                    case None        => 0
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 14)
        }
        "a refutable val pattern" in {
            val value = lowered {
                val Some(a) = Sync.defer(Some(1)).now
                a
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 1)
        }
        "a tuple val pattern" in {
            val value = lowered {
                val (a, b) = (Sync.defer(1).now, Sync.defer(2).now)
                a + b
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 3)
        }
        "an effectful guard is rejected" in {
            typeCheckFailure(
                """
                lowered {
                    Sync.defer(1).now match
                        case n if Sync.defer(n > 0).now => n
                        case _ => 0
                }
                """
            )("An effect marker in a pattern guard is not supported")
        }
    }

    "loops" - {
        "effectful condition and body" in {
            val value = lowered {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 3 do
                    counter.incrementAndGet.now
                    ()
                counter.get.now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 3)
        }
        "compound condition" in {
            val value = lowered {
                val a = AtomicInt.init(0).now
                val b = AtomicInt.init(10).now
                while a.get.now < 5 && b.get.now > 5 do
                    a.incrementAndGet.now
                    b.decrementAndGet.now
                    ()
                end while
                (a.get.now, b.get.now)
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == (5, 5))
        }
        "a loop ended by Abort" in {
            val value = lowered {
                val counter = AtomicInt.init(0).now
                val result  = Abort.run[Int] {
                    lowered {
                        while true do
                            val current = counter.incrementAndGet.now
                            if current >= 3 then
                                Abort.fail(current).now
                            ()
                        end while
                        -1
                    }
                }.now
                result
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == Result.fail(3))
        }
        "stack safety over many iterations" in {
            val value = lowered {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 100000 do
                    counter.incrementAndGet.now
                    ()
                counter.get.now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 100000)
        }
        "nested conditionals inside a loop" in {
            val evens = ArrayBuffer.empty[Int]
            val value = lowered {
                val counter = AtomicInt.init(0).now
                while counter.get.now < 6 do
                    val current = counter.incrementAndGet.now
                    if Sync.defer(current % 2 == 1).now then ()
                    else
                        if current > 2 then Sync.defer(evens += current).now
                        else Sync.defer(evens += -current).now
                        ()
                    end if
                end while
                counter.get.now
            }
            import AllowUnsafe.embrace.danger
            assert(Sync.Unsafe.evalOrThrow(value) == 6)
            assert(evens.toList == List(-2, 4, 6))
        }
    }

    "nested boundaries" - {
        "a handler receives a nested boundary and keeps its effect out of the outer row" in {
            val value: Int < Var[Int] = lowered {
                val current = Var.get[Int].now
                val handled = Abort.run[String] {
                    lowered {
                        val n = Var.get[Int].now
                        if n > 0 then Abort.fail("positive").now else n + 1
                    }
                }.now
                handled.fold(_ => current + 1, _ => current - 1, _ => 0)
            }
            assert(Var.run(0)(value).eval == 1)
            assert(Var.run(3)(value).eval == 2)
        }
        "nested handlers in both orders" in {
            val inner = lowered {
                val n = Var.get[Int].now
                if n > 0 then Abort.fail("positive").now else n + 1
            }
            val abortOutside: Result[String, Int] < Any = lowered(Abort.run[String](Var.run(0)(inner)).now)
            val varOutside: Result[String, Int] < Any   = lowered(Var.run(1)(Abort.run[String](inner)).now)
            assert(abortOutside.eval == Result.succeed(1))
            assert(varOutside.eval == Result.fail("positive"))
        }
        "a user-defined method taking a computation" in {
            def handle[S](v: Int < (Var[Int] & S)): Int < S = Var.run(0)(v)
            val value: Int < Abort[String]                  = lowered {
                handle(lowered {
                    val n = Var.get[Int].now
                    if n > 0 then Abort.fail("positive").now else n + 1
                }).now
            }
            assert(Abort.run(value).eval == Result.succeed(1))
        }
        "the handled effect does not widen the outer row" in {
            typeCheckFailure(
                """
                val value: Int < Any = lowered {
                    Abort.run[String](lowered(Abort.fail("x").now)).now
                    Var.get[Int].now
                }
                """
            )("Required: Int < Any")
        }
    }

    "effect rows" - {
        "exactly the union of the markers' rows" in {
            val value: Int < (Var[Int] & Abort[String]) = lowered {
                val n = Var.get[Int].now
                if n > 0 then Abort.fail("positive").now else n + 1
            }
            assert(Abort.run(Var.run(0)(value)).eval == Result.succeed(1))
            typeCheckFailure(
                """
                val narrow: Int < Var[Int] = lowered {
                    val n = Var.get[Int].now
                    if n > 0 then Abort.fail("positive").now else n + 1
                }
                """
            )("Required: Int < kyo.Var[Int]")
        }
        "multi-shot effects resume the continuation once per choice" in {
            val value = lowered {
                val x = Choice.eval(1, 2, 3).now
                val y = Choice.eval("a", "b").now
                s"$x$y"
            }
            assert(Choice.run(value).eval == Seq("1a", "1b", "2a", "2b", "3a", "3b"))
        }
    }
end LowerTest
