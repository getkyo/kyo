package kyo

import caseapp.*
import caseapp.core.RemainingArgs
import kyo.internal.Platform
import org.scalatest.compatible.Assertion
import scala.collection.mutable.ListBuffer
import scala.util.Try

final case class GreetOptions(@Name("name") name: String = "world")

class KyoCaseAppTest extends Test:

    "main" in runNotJS {
        for
            ref <- AtomicRef.init[Maybe[(GreetOptions, RemainingArgs)]](Absent)
            app = new KyoCaseApp[GreetOptions]:
                run { (options, remainingArgs) =>
                    ref.set(Present((options, remainingArgs)))
                }
            _      <- Sync.defer(app.main(Array("--name", "test", "positional")))
            stored <- ref.get
        yield stored match
            case Present((opts, rem)) =>
                assert(opts.name == "test")
                assert(rem.remaining == Seq("positional"))
            case Absent =>
                fail("options were not captured")
    }

    "KyoCommand" in runNotJS {
        for
            ref <- AtomicRef.init[Maybe[(GreetOptions, RemainingArgs)]](Absent)
            cmd = new KyoCommand[GreetOptions]:
                override def name = "greet"
                run { (options, remainingArgs) =>
                    ref.set(Present((options, remainingArgs)))
                }
            _      <- Sync.defer(cmd.main(Array("--name", "cli", "rest")))
            stored <- ref.get
        yield stored match
            case Present((opts, rem)) =>
                assert(opts.name == "cli")
                assert(rem.remaining == Seq("rest"))
            case Absent =>
                fail("options were not captured")
    }

    "multiple runs" in {
        assume(!Platform.isNative, "KyoCaseApp.main too slow on Native for repeated calls")
        runNotJS {
            for
                ref <- AtomicInt.init(0)
                app = new KyoCaseApp[GreetOptions]:
                    run { (_, _) => ref.getAndIncrement }
                    run { (_, _) => ref.getAndIncrement }
                    run { (_, _) => ref.getAndIncrement }

                _    <- Sync.defer(app.main(Array.empty))
                runs <- ref.get
            yield assert(runs == 3)
        }
    }

    "multiple runs share parsed cli" in runNotJS {
        val captured = new ListBuffer[(String, Seq[String])]
        val app = new KyoCaseApp[GreetOptions]:
            run { (options, remainingArgs) =>
                Sync.defer(captured += ((options.name, remainingArgs.remaining)))
            }
            run { (options, remainingArgs) =>
                Sync.defer(captured += ((options.name, remainingArgs.remaining)))
            }
        for
            _ <- Sync.defer(app.main(Array("--name", "same", "positional")))
        yield assert(captured.toList == List(
            ("same", Seq("positional")),
            ("same", Seq("positional"))
        ))
    }

    "run without cli params" in runNotJS {
        val log = new ListBuffer[String]
        val app = new KyoCaseApp[GreetOptions]:
            run { Sync.defer(log += "no-cli") }
            run { (options, _) =>
                Sync.defer(log += s"with-cli:${options.name}")
            }
        for
            _ <- Sync.defer(app.main(Array("--name", "cli")))
        yield assert(log.toList == List("no-cli", "with-cli:cli"))
    }

    "mixed run overloads keep registration order" in runNotJS {
        val log = new ListBuffer[String]
        val app = new KyoCaseApp[GreetOptions]:
            run { Sync.defer(log += "a") }
            run { (_, _) => Sync.defer(log += "b") }
            run { Sync.defer(log += "c") }
            run { options => Sync.defer(log += s"d:${options.name}") }
        for
            _ <- Sync.defer(app.main(Array("--name", "cli")))
        yield assert(log.toList == List("a", "b", "c", "d:cli"))
    }

    "ordered runs" in {
        assume(!Platform.isNative, "KyoCaseApp.main too slow on Native")
        val x       = new ListBuffer[Int]
        val promise = scala.concurrent.Promise[Assertion]()
        val app = new KyoCaseApp[GreetOptions]:
            run { (_, _) => Async.delay(10.millis)(Sync.defer(x += 1)) }
            run { (_, _) => Async.delay(10.millis)(Sync.defer(x += 2)) }
            run { (_, _) => Async.delay(10.millis)(Sync.defer(x += 3)) }
            run { (_, _) => Sync.defer(promise.complete(Try(assert(x.toList == List(1, 2, 3))))) }
        app.main(Array.empty)
        promise.future
    }

    "empty run blocks" in runNotJS {
        var exitCode = -1
        val app = new KyoCaseApp[GreetOptions]:
            override def exitHook(code: Int)(using AllowUnsafe): Unit = exitCode = code
        app.main(Array.empty)
        assert(exitCode == 1)
    }

    "effect mismatch" in {
        typeCheckFailure("""
            new KyoCaseApp[GreetOptions]:
                run { (_, _) => 1: Int < Var[Int] }
        """)(
            "Found:    Int < kyo.Var[Int]"
        )
    }

end KyoCaseAppTest
