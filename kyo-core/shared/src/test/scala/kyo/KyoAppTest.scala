package kyo

import kyo.internal.Platform
import scala.collection.mutable.ListBuffer
import scala.util.Try

class KyoAppTest extends kyo.test.Test[Any]:

    "main" in {
        val app = new KyoApp:
            run {
                for
                    _ <- Console.printLine(s"Starting with args [${args.mkString(", ")}]")
                yield "Exit!"
            }

        app.main(Array("arg1", "arg2"))
        succeed("main completes without error")
    }

    // KyoApp.main on Native initializes the full runtime per call — "ordered runs"
    // takes ~10 min on Windows Native, so 3 sequential main() calls exceed the timeout.
    "multiple runs".notJs in {
        assume(!Platform.isNative, "KyoApp.main too slow on Native for repeated calls")
        {
            for
                ref <- AtomicInt.init(0)
                app = new KyoApp:
                    run { ref.getAndIncrement }
                    run { ref.getAndIncrement }
                    run { ref.getAndIncrement }

                _    <- Sync.defer(app.main(Array.empty))
                runs <- ref.get
            yield assert(runs == 3)
        }
    }

    // KyoApp.main on Native initializes the full runtime per call — takes
    // ~10 minutes on Windows Native, which is prohibitively expensive.
    "ordered runs" in {
        assume(!Platform.isNative, "KyoApp.main too slow on Native")
        val x       = new ListBuffer[Int]
        val promise = scala.concurrent.Promise[Unit]()
        val app = new KyoApp:
            run { Async.delay(10.millis)(Sync.defer(x += 1)) }
            run { Async.delay(10.millis)(Sync.defer(x += 2)) }
            run { Async.delay(10.millis)(Sync.defer(x += 3)) }
            run { Sync.defer(promise.complete(Try(assert(x.toList == List(1, 2, 3))))) }
        app.main(Array.empty)
        Async.fromFuture(promise.future)
    }

    "effects".notJs in {
        def run: Int < (Async & Scope & Abort[Throwable]) =
            for
                _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                i <- Random.nextInt
                _ <- Console.printLine(s"$i")
                _ <- Clock.now
                _ <- Scope.ensure(())
                _ <- Async.sleep(1.second)
            yield 1

        import AllowUnsafe.embrace.danger
        assert(KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) == Result.succeed(1))
    }

    "effects in JS".notJs in {
        val promise = scala.concurrent.Promise[Unit]()
        val app = new KyoApp:
            run {
                for
                    _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                    i <- Random.nextInt
                    _ <- Console.printLine(s"$i")
                    _ <- Clock.now
                    _ <- Scope.ensure(())
                    _ <- Async.sleep(1.second)
                yield promise.complete(Try(()))
            }
        app.main(Array.empty)
        Async.fromFuture(promise.future).map(_ => succeed("all effects complete without error"))
    }

    "exit on error".notJs in {
        var exitCode = -1
        def app(fail: Boolean): KyoApp = new KyoApp:
            override def exit(code: Int)(using AllowUnsafe): Unit = exitCode = code
            run(Abort.when(fail)(new IllegalArgumentException("Aborts!")))
        val result = Result.catching[IllegalArgumentException](app(fail = true).main(Array.empty))
        assert(result.isFailure)
        assert(exitCode == -1) // exit is only called on non-throwable errors
        exitCode = -1
        app(fail = false).main(Array.empty)
        assert(exitCode == -1)
    }

    "failing effects".notJs in {
        def run: Unit < (Async & Scope & Abort[Throwable]) =
            for
                _ <- Clock.now
                _ <- Random.nextInt
                _ <- Abort.fail(new RuntimeException("Aborts!"))
            yield ()

        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) match
            case Result.Failure(exception: RuntimeException) => assert(exception.getMessage == "Aborts!")
            case _                                           => fail("Unexpected Success...")
    }

    "non-throwable aborts".notJs in {
        val app = new KyoApp:
            run(Abort.fail("Aborts!"))

        assert(Result.catching[KyoApp.FailureException](app.main(Array.empty)).isFailure)
    }

    "unsafe non-throwable aborts".notJs in {
        def run: Unit < (Async & Scope & Abort[String]) =
            for
                _ <- Clock.now
                _ <- Random.nextInt
                _ <- Abort.fail("Aborts!")
            yield ()

        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) match
            case Result.Failure(exception: KyoApp.FailureException) => assert(exception.error.toString == "Aborts!")
            case _                                                  => fail("Unexpected Success...")
    }

    "effect mismatch" in {
        typeCheckFailure("""
            new KyoApp:
                run(1: Int < Var[Int])
        """)(
            "Found:    Int < kyo.Var[Int]"
        )
    }

    "indirect effect mismatch" in {
        typeCheckFailure("""
            new KyoApp:
                run(Choice.run(1: Int < Var[Int]))
        """)(
            "Found:    Int < kyo.Var[Int]"
        )
    }

end KyoAppTest
