package kyo

import Tagged.*
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch
import kyo.Clock.Unsafe
import kyo.internal.LayerMacros.Validated.succeed
import org.scalatest.compatible.Assertion
import scala.collection.mutable.ListBuffer
import scala.util.Try

class KyoAppTest extends Test:

    "main" in runNotJS {
        val app = new KyoApp:
            run {
                for
                    _ <- Console.printLine(s"Starting with args [${args.mkString(", ")}]")
                yield "Exit!"
            }

        app.main(Array("arg1", "arg2"))
        succeed
    }
    "multiple runs" taggedAs jvmOnly in run {
        for
            ref <- AtomicInt.init(0)
            app = new KyoApp:
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }

            _    <- IO(app.main(Array.empty))
            runs <- ref.get
        yield assert(runs == 3)
    }
    "ordered runs" in {
        val x       = new ListBuffer[Int]
        val promise = scala.concurrent.Promise[Assertion]()
        val app = new KyoApp:
            run { Async.delay(10.millis)(IO(x += 1)) }
            run { Async.delay(10.millis)(IO(x += 2)) }
            run { Async.delay(10.millis)(IO(x += 3)) }
            run { IO(promise.complete(Try(assert(x.toList == List(1, 2, 3))))) }
        app.main(Array.empty)
        promise.future
    }
    "effects in JVM" taggedAs jvmOnly in {
        def run: Int < (Async & Resource & Abort[Throwable]) =
            for
                _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                i <- Random.nextInt
                _ <- Console.printLine(s"$i")
                _ <- Clock.now
                _ <- Resource.ensure(())
                _ <- Async.sleep(1.second)
            yield 1

        import AllowUnsafe.embrace.danger
        assert(KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) == Result.success(1))
    }
    "effects in JS" taggedAs jsOnly in {
        val promise = scala.concurrent.Promise[Assertion]()
        val app = new KyoApp:
            run {
                for
                    _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                    i <- Random.nextInt
                    _ <- Console.printLine(s"$i")
                    _ <- Clock.now
                    _ <- Resource.ensure(())
                    _ <- Async.sleep(1.second)
                yield promise.complete(Try(succeed))
            }
        app.main(Array.empty)
        promise.future
    }
    "failing effects" taggedAs jvmOnly in {
        def run: Unit < (Async & Resource & Abort[Throwable]) =
            for
                _ <- Clock.now
                _ <- Random.nextInt
                _ <- Abort.fail(new RuntimeException("Aborts!"))
            yield ()

        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) match
            case Result.Fail(exception: RuntimeException) => assert(exception.getMessage == "Aborts!")
            case _                                        => fail("Unexpected Success...")
    }

    "effect mismatch" taggedAs jvmOnly in {
        assertDoesNotCompile("""
            new KyoApp:
                run(1: Int < Options)
        """)
    }

    "indirect effect mismatch" taggedAs jvmOnly in {
        assertDoesNotCompile("""
            new KyoApp:
                run(Choices.run(1: Int < Options))
        """)
    }

end KyoAppTest
