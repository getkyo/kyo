package kyo

import Tagged.*
import org.scalatest.compatible.Assertion
import scala.collection.mutable.ListBuffer
import scala.util.Try

class KyoAppTest extends Test:

    "main" in {
        val app = new KyoApp:
            run {
                for
                    _ <- Console.printLine(s"Starting with args [${args.mkString(", ")}]")
                yield "Exit!"
            }

        app.main(Array("arg1", "arg2"))
        succeed
    }

    "multiple runs" in runNotJS {
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

    "ordered runs" in {
        val x       = new ListBuffer[Int]
        val promise = scala.concurrent.Promise[Assertion]()
        val app = new KyoApp:
            run { Async.delay(10.millis)(Sync.defer(x += 1)) }
            run { Async.delay(10.millis)(Sync.defer(x += 2)) }
            run { Async.delay(10.millis)(Sync.defer(x += 3)) }
            run { Sync.defer(promise.complete(Try(assert(x.toList == List(1, 2, 3))))) }
        app.main(Array.empty)
        promise.future
    }

    "effects" in runNotJS {
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

    "effects in JS" in runNotJS {
        val promise = scala.concurrent.Promise[Assertion]()
        val app = new KyoApp:
            run {
                for
                    _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                    i <- Random.nextInt
                    _ <- Console.printLine(s"$i")
                    _ <- Clock.now
                    _ <- Scope.ensure(())
                    _ <- Async.sleep(1.second)
                yield promise.complete(Try(succeed))
            }
        app.main(Array.empty)
        promise.future
    }

    "exit on error" in runNotJS {
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

    "failing effects" in runNotJS {
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

    "env" in runNotJS {
        type F1[S] = (Int, String) => String < S
        type F2[S] = String => Int < S

        def f1Impl(num: Int, str: String): String < Async = num.toString + str
        def f2Impl(str: String): Int < Abort[String] = str.toIntOption match
            case Some(num) => num
            case None      => Abort.fail(str)

        def fn[S](num: Int, str: String) =
            for
                str <- Env.use[F1[S]](_(num, str))
                res <- Env.use[F2[S]](_(str))
            yield res

        object Main extends KyoApp:
            run:
                Env.run(f1Impl)(Env.run(f2Impl)(Abort.run[String](fn(4, "something"))))
        Main.main(Array.empty)
        succeed
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
