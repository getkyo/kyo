package kyo2

import java.io.Closeable
import kyo.*

class ResourceTest extends Test:

    case class TestResource(id: Int, var closes: Int = 0) extends Closeable derives CanEqual:
        var acquires = 0
        def apply() =
            acquires += 1
            this
        def close() = closes += 1
    end TestResource

    case class EffectfulResource(id: Int, closes: Atomic.OfInt):
        def close: Unit < IO =
            closes.incrementAndGet.unit

    end EffectfulResource
    object EffectfulResource:
        def apply(id: Int): EffectfulResource < IO =
            for
                cl <- Atomic.initInt(0)
            yield EffectfulResource(id, cl)
    end EffectfulResource

    "acquire + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Resource.run(Resource.acquire(r1()))
            .pipe(Resource.run)
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .pipe(IO.run)
            .eval
        assert(r1.closes == 1)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
    }

    "acquire + tranform + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Resource.acquire(r1()).map(_ => assert(r1.closes == 0))
            .pipe(Resource.run)
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .pipe(IO.run)
            .eval
        assert(r1.closes == 1)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
    }

    "acquire + effectful tranform + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val r =
            Resource.acquire(r1()).map { _ =>
                assert(r1.closes == 0)
                Env.get[Int]
            }.pipe(Resource.run)
                .pipe(IO.runLazy)
        assert(r1.closes == 0)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
        Async.runAndBlock(timeout)(r)
            .pipe(Env.run(1))
            .pipe(Abort.run(_))
            .pipe(IO.run)
            .eval
        assert(r1.closes == 1)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
    }

    "two acquires + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Resource.acquire(r1()).map(_ => Resource.acquire(r2()))
            .pipe(Resource.run)
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .pipe(IO.run)
            .eval
        assert(r1.closes == 1)
        assert(r2.closes == 1)
        assert(r1.acquires == 1)
        assert(r2.acquires == 1)
    }

    "two acquires + for-comp + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val io =
            for
                r1 <- Resource.acquire(r1())
                i1 = r1.id * 3
                r2 <- Resource.acquire(r2())
                i2 = r2.id * 5
            yield i1 + i2
        val r =
            io.pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .pipe(IO.run)
                .eval
        assert(r == Result.success(13))
        assert(r1.closes == 1)
        assert(r2.closes == 1)
        assert(r1.acquires == 1)
        assert(r2.acquires == 1)
    }

    "two acquires + effectful for-comp + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val io =
            for
                r1 <- Resource.acquire(r1())
                i1 <- Env.get[Int].map(_ * r1.id)
                r2 <- Resource.acquire(r2())
                i2 <- Env.get[Int].map(_ * r2.id)
            yield i1 + i2
        val r =
            io.pipe(Resource.run)
                .pipe(IO.runLazy)
        assert(r1.closes == 0)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
        r.pipe(Env.run(3))
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .pipe(IO.run)
            .eval
        assert(r1.closes == 1)
        assert(r2.closes == 1)
        assert(r1.acquires == 1)
        assert(r2.acquires == 1)
    }

    "nested" in run {
        val r1 = TestResource(1)
        val r =
            Resource.acquire(r1())
                .pipe(Resource.run)
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .pipe(IO.run)
                .eval
        assert(r == Result.success(r1))
        assert(r1.acquires == 1)
        assert(r1.closes == 1)
    }

    "effectful acquireRelease" in run {
        val io =
            for
                r          <- Resource.acquireRelease(EffectfulResource(1))(_.close)
                closeCount <- r.closes.get
            yield
                assert(closeCount == 0)
                r
        val finalizedResource =
            io.pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .pipe(IO.run)
                .eval
        finalizedResource.fold(_ => ???)(_.closes.get.map(i => assert(i == 1)))
    }

    "integration with other effects" - {

        "ensure" taggedAs jvmOnly in {
            var closes = 0
            Resource.ensure(Async.run(closes += 1).map(_.get).unit)
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .pipe(IO.run)
                .eval
            assert(closes == 1)
        }

        "acquireRelease" taggedAs jvmOnly in {
            var closes = 0
            // any effects in acquire
            val acquire = Abort.get(Some(42))
            // only Async in release
            def release(i: Int) =
                Async.run {
                    assert(i == 42)
                    closes += 1
                }.map(_.get)
            Resource.acquireRelease(acquire)(release)
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run[Timeout](_))
                .pipe(Abort.run[Maybe.Empty](_))
                .pipe(IO.run)
                .eval
            assert(closes == 1)
        }

        "acquire" taggedAs jvmOnly in {
            val r = TestResource(1)
            Resource.acquire(Async.run(r).map(_.get))
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .pipe(IO.run)
                .eval
            assert(r.closes == 1)
        }
    }
end ResourceTest
