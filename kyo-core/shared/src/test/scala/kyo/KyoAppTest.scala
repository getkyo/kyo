package kyo

import kyo.internal.Platform
import scala.collection.mutable.ListBuffer
import scala.util.Try

class KyoAppTest extends kyo.test.Test[Any]:

    "activates the classpath-present stats providers before running the app's own code" in {
        // The application entrypoint is where kyo-core reaches kyo.Stat, and the only place it does: an app
        // whose own code never mentions a metric still gets a classpath-present host sampler collecting from
        // the start. Any hook broad enough to cover a non-KyoApp process would have to sit on the
        // fiber-creation path, which is not somewhere a stats concern belongs; such a host calls
        // Stat.activate() itself.
        val before = Stat.activationCount
        val app = new KyoApp:
            run(Sync.defer("done"))
        app.main(Array.empty)
        assert(Stat.activationCount > before)
    }

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

    // An application's stdout is its output contract, so a failure the run block did not catch belongs on stderr. It used to be rendered
    // to stdout, which put a non-JSON line on the channel a stdio server's host was parsing, and made the host's first read of the
    // connection garbage. No discipline in the run block avoided it: this is the framework's own terminal reporting.
    // notJs / notWasm: on those platforms KyoApp.main cannot block, so it returns before the run block writes anything and the redirect
    // scope has already closed. The reporting itself is shared, platform-independent code in KyoAppRunner.onResult; only observing it from
    // inside the same process needs main to have finished.
    "an uncaught failure renders to stderr, leaving stdout untouched".notJs.notWasm in {
        assume(!Platform.isNative, "KyoApp.main too slow on Native")
        val out = new java.io.ByteArrayOutputStream
        val err = new java.io.ByteArrayOutputStream
        val app = new KyoApp:
            run { Abort.fail("no url") }
        // main rethrows the failure as a FailureException after reporting it; the rethrow is the process's non-zero exit and is not what
        // this case is about.
        discard(Try(scala.Console.withOut(out)(scala.Console.withErr(err)(app.main(Array.empty)))))
        assert(out.toString == "")
        assert(err.toString.contains("no url"))
    }

    "a run block's value still renders to stdout".notJs.notWasm in {
        assume(!Platform.isNative, "KyoApp.main too slow on Native")
        val out = new java.io.ByteArrayOutputStream
        val err = new java.io.ByteArrayOutputStream
        val app = new KyoApp:
            run { "the value" }
        scala.Console.withOut(out)(scala.Console.withErr(err)(app.main(Array.empty)))
        assert(out.toString.contains("the value"))
        assert(err.toString == "")
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
