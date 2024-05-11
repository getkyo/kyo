package kyoTest

import Tagged.*
import java.time.Instant
import kyo.*

class KyoAppTest extends KyoTest:

    "main" taggedAs jvmOnly in {
        val app = new KyoApp:
            run {
                for
                    _ <- Consoles.println(s"Starting with args [${args.mkString(", ")}]")
                yield "Exit!"
            }

        app.main(Array("arg1", "arg2"))
        succeed
    }
    "multiple runs" taggedAs jvmOnly in run {
        for
            ref <- Atomics.initInt(0)
            app = new KyoApp:
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }

            _    <- IOs(app.main(Array.empty))
            runs <- ref.get
        yield assert(runs == 3)
    }
    "effects" taggedAs jvmOnly in {
        def run: Int < KyoApp.Effects =
            for
                _ <- Timers.scheduleAtFixedRate(1.seconds, 1.seconds)(())
                i <- Randoms.nextInt
                _ <- Consoles.println(s"$i")
                _ <- Clocks.now
                _ <- Resources.ensure(())
                _ <- Fibers.init(())
            yield 1

        assert(KyoApp.run(Duration.Infinity)(run) == 1)
    }
    "failing effects" taggedAs jvmOnly in {
        def run: Unit < KyoApp.Effects =
            for
                _ <- Clocks.now
                _ <- Randoms.nextInt
                _ <- Aborts.fail(new RuntimeException("Aborts!"))
            yield ()

        KyoApp.attempt(Duration.Infinity)(run) match
            case scala.util.Failure(exception) => assert(exception.getMessage == "Aborts!")
            case _                             => fail("Unexpected Success...")
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
            instantRef <- Atomics.initRef(Instant.MAX)
            randomRef  <- Atomics.initRef("")
            testClock = new Clock:
                override def now: Instant < IOs = Instant.EPOCH
            testRandom = new Random:
                override def nextInt: Int < IOs = ???

                override def nextInt(exclusiveBound: Int): Int < IOs = ???

                override def nextLong: Long < IOs = ???

                override def nextDouble: Double < IOs = ???

                override def nextBoolean: Boolean < IOs = ???

                override def nextFloat: Float < IOs = ???

                override def nextGaussian: Double < IOs = ???

                override def nextValue[T](seq: Seq[T]): T < IOs = ???

                override def nextValues[T](length: Int, seq: Seq[T]): Seq[T] < IOs = ???

                override def nextStringAlphanumeric(length: Int): String < IOs = "FooBar"

                override def nextString(length: Int, chars: Seq[Char]): String < IOs = ???
                override def nextBytes(length: Int): Seq[Byte] < IOs                 = ???

                override def shuffle[T](seq: Seq[T]): Seq[T] < IOs = ???

                override def unsafe: Random.Unsafe = ???

            app = new KyoApp:
                override val log: Logs.Unsafe = Logs.Unsafe.ConsoleLogger("ConsoleLogger")
                override val clock: Clock     = testClock
                override val random: Random   = testRandom
                run {
                    for
                        _ <- Clocks.now.map(i => instantRef.update(_ => i))
                        _ <- Randoms.nextStringAlphanumeric(0).map(s => randomRef.update(_ => s))
                        _ <- Logs.info("info")
                    yield ()
                }
            _    <- IOs(app.main(Array.empty))
            time <- instantRef.get
            rand <- randomRef.get
        yield
            assert(time eq Instant.EPOCH)
            assert(rand == "FooBar")
        end for
    }
end KyoAppTest
