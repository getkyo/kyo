package kyo

import kyo.*

class KyoAppSignalTest extends Test:
    object TestSignalApp extends KyoApp:
        run {
            for
                _ <- Console.printLine("TestSignalApp started - waiting for signal")
                _ <- Scope.ensure(ex => Console.printLine(s"TestSignalApp finished: $ex"))
                _ <- Async.never
            yield ()
        }
    end TestSignalApp

    def testSignalInterruption(signal: String, exit: Int) =
        s"SIG$signal interruption" taggedAs jvmOnly in run {
            for
                process   <- Process.jvm.spawn(TestSignalApp.getClass, List.empty)
                _         <- Loop.whileTrue(process.isAlive.map(!_))(Async.sleep(20.millis))
                pid       <- process.pid
                _         <- Process.Command("kill", s"-$signal", pid.toString).waitFor
                completed <- process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                exitValue <- process.exitValue
            yield assert(completed && exitValue == exit)
        }

    testSignalInterruption("TERM", 143)
    testSignalInterruption("INT", 130)
end KyoAppSignalTest
