package kyoTest

import Tagged.*
import kyo.*
import scala.concurrent.duration.*

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
                _ <- Timers.scheduleAtFixedRate(1.second, 1.second)(())
                i <- Randoms.nextInt
                _ <- Consoles.println(s"$i")
                _ <- Clocks.now
                _ <- Resources.ensure(())
                _ <- Fibers.init(())
            yield 1

        assert(KyoApp.run(Duration.Inf)(run) == 1)
    }
    "failing effects" taggedAs jvmOnly in {
        def run: Unit < KyoApp.Effects =
            for
                _ <- Clocks.now
                _ <- Randoms.nextInt
                _ <- Aborts[Throwable].fail(new RuntimeException("Aborts!"))
            yield ()

        KyoApp.attempt(Duration.Inf)(run) match
            case scala.util.Failure(exception) => assert(exception.getMessage == "Aborts!")
            case _                             => fail("Unexpected Success...")
    }

end KyoAppTest
