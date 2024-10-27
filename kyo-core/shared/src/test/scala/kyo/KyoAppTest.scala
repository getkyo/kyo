package kyo

import Tagged.*
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch
import kyo.Clock.Unsafe

class KyoAppTest extends Test:

    "main" in runJVM {
        val app = new KyoApp:
            run {
                for
                    _ <- Console.println(s"Starting with args [${args.mkString(", ")}]")
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
    "effects" taggedAs jvmOnly in {
        def run: Int < KyoApp.Effects =
            for
                _ <- Timer.scheduleAtFixedRate(1.second, 1.second)(())
                i <- Random.nextInt
                _ <- Console.println(s"$i")
                _ <- Clock.now
                _ <- Resource.ensure(())
                _ <- Async.run(())
            yield 1

        import AllowUnsafe.embrace.danger
        assert(KyoApp.Unsafe.run(Duration.Infinity)(run) == 1)
    }
    "failing effects" taggedAs jvmOnly in {
        def run: Unit < KyoApp.Effects =
            for
                _ <- Clock.now
                _ <- Random.nextInt
                _ <- Abort.fail(new RuntimeException("Aborts!"))
            yield ()

        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.attempt(Duration.Infinity)(run) match
            case Result.Fail(exception) => assert(exception.getMessage == "Aborts!")
            case _                      => fail("Unexpected Success...")
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

    "custom services" taggedAs jvmOnly in run {
        for
            instantRef <- AtomicRef.init(Instant.Max)
            randomRef  <- AtomicRef.init("")
            testClock = new Clock:
                override def unsafe: Unsafe                            = ???
                override def now(using Frame)                          = Instant.Epoch
                override def deadline(duration: Duration)(using Frame) = ???
                override def stopwatch(using Frame)                    = ???
            testRandom = new Random:
                override def nextInt(using Frame): Int < IO = ???

                override def nextInt(exclusiveBound: Int)(using Frame): Int < IO = ???

                override def nextLong(using Frame): Long < IO = ???

                override def nextDouble(using Frame): Double < IO = ???

                override def nextBoolean(using Frame): Boolean < IO = ???

                override def nextFloat(using Frame): Float < IO = ???

                override def nextGaussian(using Frame): Double < IO = ???

                override def nextValue[A](seq: Seq[A])(using Frame): A < IO = ???

                override def nextValues[A](length: Int, seq: Seq[A])(using Frame): Seq[A] < IO = ???

                override def nextStringAlphanumeric(length: Int)(using Frame): String < IO = "FooBar"

                override def nextString(length: Int, chars: Seq[Char])(using Frame): String < IO = ???
                override def nextBytes(length: Int)(using Frame): Seq[Byte] < IO                 = ???

                override def shuffle[A](seq: Seq[A])(using Frame): Seq[A] < IO = ???

                override def unsafe = ???

            app = new KyoApp:
                override val log: Log       = Log(Log.Unsafe.ConsoleLogger("ConsoleLogger", Log.Level.Debug))
                override val clock: Clock   = testClock
                override val random: Random = testRandom
                run {
                    for
                        _ <- Clock.now.map(i => instantRef.update(_ => i))
                        _ <- Random.nextStringAlphanumeric(0).map(s => randomRef.update(_ => s))
                        _ <- Log.info("info")
                    yield ()
                }
            _    <- IO(app.main(Array.empty))
            time <- instantRef.get
            rand <- randomRef.get
        yield
            assert(time == Instant.Epoch)
            assert(rand == "FooBar")
        end for
    }
end KyoAppTest
