package kyo

import caseapp.*
import caseapp.core.RemainingArgs
import scala.collection.mutable.ListBuffer

/** Compile-time and runtime checks that [[KyoCaseApp.run]] overloads resolve without explicit type annotations. */
class KyoCaseAppRunOverloadTest extends kyo.test.Test[Any]:

    "all run overloads compile and infer options type".notJs in {
        val log = new ListBuffer[String]

        val app = new KyoCaseApp[GreetOptions]:
            // by-name: => A < S (not a function literal)
            run {
                Sync.defer(log += "by-name")
            }

            // T => A < S — parameter type must be inferred as GreetOptions
            run { options =>
                val opts: GreetOptions = options
                Sync.defer(log += s"options-only:${opts.name}")
            }

            // (T, RemainingArgs) => A < S
            run { (options, remainingArgs) =>
                val opts: GreetOptions = options
                val rem: RemainingArgs = remainingArgs
                Sync.defer(log += s"full:${opts.name}:${rem.remaining.size}")
            }

        for
            _ <- Sync.defer(app.main(Array("--name", "cli", "extra")))
        yield assert(log.toList == List("by-name", "options-only:cli", "full:cli:1"))
    }

    "mixed overload registration order".notJs in {
        val log = new ListBuffer[String]
        val app = new KyoCaseApp[GreetOptions]:
            run { Sync.defer(log += "1") }
            run { o => Sync.defer(log += s"2:${o.name}") }
            run { (o, _) => Sync.defer(log += s"3:${o.name}") }
            run { Sync.defer(log += "4") }
        for
            _ <- Sync.defer(app.main(Array("--name", "mix")))
        yield assert(log.toList == List("1", "2:mix", "3:mix", "4"))
    }

    "KyoCommand run overloads infer without annotations".notJs in {
        val log = new ListBuffer[String]
        val cmd = new KyoCommand[GreetOptions]:
            override def name = "greet"
            run { options =>
                val opts: GreetOptions = options
                Sync.defer(log += s"cmd:${opts.name}")
            }
        for
            _ <- Sync.defer(cmd.main(Array("--name", "cmd")))
        yield assert(log.toList == List("cmd:cmd"))
    }

    "for-comprehension in by-name run infers effect".notJs in {
        val log = new ListBuffer[String]
        val app = new KyoCaseApp[GreetOptions]:
            run {
                for
                    _ <- Sync.defer(log += "for-by-name")
                yield ()
            }
            run { options =>
                for
                    _ <- Sync.defer(log += s"for-options:${options.name}")
                yield ()
            }
        for
            _ <- Sync.defer(app.main(Array("--name", "fc")))
        yield assert(log.toList == List("for-by-name", "for-options:fc"))
    }

    "effect mismatch on options-only overload" in {
        typeCheckFailure("""
            new KyoCaseApp[GreetOptions]:
                run { options => 1: Int < Var[Int] }
        """)(
            "Found:    Int < kyo.Var[Int]"
        )
    }

end KyoCaseAppRunOverloadTest
