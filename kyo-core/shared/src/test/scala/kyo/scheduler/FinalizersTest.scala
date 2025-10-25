package kyo.scheduler

import kyo.*
import kyo.Result.Error

class FinalizersTest extends Test:

    "isEmpty" - {
        "zero" in {
            val f = Finalizers.empty
            assert(f.isEmpty)
        }

        "one" in {
            val finalizer = (_: Maybe[Error[Any]]) => ()
            val f         = Finalizers.empty.add(finalizer)
            assert(!f.isEmpty)
        }
        "multiple" in {
            val one = (_: Maybe[Error[Any]]) => ()
            val two = (_: Maybe[Error[Any]]) => ()
            val f   = Finalizers.empty.add(one).add(two)
            assert(!f.isEmpty)
        }
    }

    "add" - {
        "one" in {
            var called = List.empty[Int]
            val one    = (_: Maybe[Error[Any]]) => called = 1 :: called
            val f      = Finalizers.empty.add(one)
            assert(!f.isEmpty)
            assert(f.size() == 1)
            f.run(Absent)
            assert(called == List(1))
        }

        "two" in {
            var called = List.empty[Int]
            val one    = (_: Maybe[Error[Any]]) => called = 1 :: called
            val two    = (_: Maybe[Error[Any]]) => called = 2 :: called
            val f      = Finalizers.empty.add(one).add(two)
            assert(f.size() == 2)
            f.run(Absent)
            assert(called == List(2, 1))
        }

        "multiple" in {
            var called = List.empty[Int]
            val f      = (1 to 10).foldLeft(Finalizers.empty)((f, i) => f.add((_: Maybe[Error[Any]]) => called = i :: called))
            assert(!f.isEmpty)
            assert(f.size() == 10)
            f.run(Absent)
            assert(called == (1 to 10).toList.reverse)
        }

        "same finalizer" in {
            val f      = (_: Maybe[Error[Any]]) => ()
            val result = Finalizers.empty.add(f).add(f)
            assert(result.size() == 1)
        }

        "multiple of same finalizer" in {
            var called = List.empty[Int]
            val one    = (_: Maybe[Error[Any]]) => called = 1 :: called
            val two    = (_: Maybe[Error[Any]]) => called = 2 :: called
            val f      = Finalizers.empty.add(one).add(two).add(one).add(two)
            assert(f.size() == 2)
            f.run(Absent)
            assert(called == List(2, 1))
        }
    }

    "remove" - {
        "empty" in {
            val f = Finalizers.empty.remove((_: Maybe[Error[Any]]) => ())
            assert(f.isEmpty)
        }

        "one of one" in {
            val one   = (_: Maybe[Error[Any]]) => ()
            val added = Finalizers.empty.add(one)
            val f     = added.remove(one)
            assert(f.isEmpty)
        }

        "one of two" in {
            var called = false
            val one    = (_: Maybe[Error[Any]]) => ()
            val two    = (_: Maybe[Error[Any]]) => called = true
            val added  = Finalizers.empty.add(one).add(two)
            val f      = added.remove(one)
            assert(f.size() == 1)
            f.run(Absent)
            assert(called)
        }

        "missing" in {
            var called = false
            val f1     = (_: Maybe[Error[Any]]) => called = true
            val f2     = (_: Maybe[Error[Any]]) => ()
            val added  = Finalizers.empty.add(f1)
            val result = added.remove(f2)
            assert(!result.isEmpty)
            assert(result.size() == 1)
        }

        "removing multiple occurrences" in {
            var called = false
            val f      = (_: Maybe[Error[Any]]) => called = true
            val added  = Finalizers.empty.add(f).add(f).add(f)
            val result = added.remove(f)
            assert(result.isEmpty)
        }
    }

    "size" - {
        "empty" in {
            val f = Finalizers.empty
            assert(f.size() == 0)
        }

        "one" in {
            val f      = (_: Maybe[Error[Any]]) => ()
            val result = Finalizers.empty.add(f)
            assert(result.size() == 1)
        }

        "multiple" in {
            var called                               = 0
            def finalizer: Maybe[Error[Any]] => Unit = _ => called += 1 // this is to avoid inlining the finalizer

            val f = (1 to 10).foldLeft(Finalizers.empty)((f0, i) => f0.add(finalizer))
            assert(f.size() == 10)
        }
    }

end FinalizersTest
