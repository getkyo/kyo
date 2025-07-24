package kyo

import scala.util.control.NoStackTrace

class LogTest extends Test:

    case object ex extends NoStackTrace

    "log" in run {
        for
            _ <- Log.trace("trace")
            _ <- Log.debug("debug")
            _ <- Log.info("info")
            _ <- Log.warn("warn")
            _ <- Log.error("error")
            _ <- Log.trace("trace", ex)
            _ <- Log.debug("debug", ex)
            _ <- Log.info("info", ex)
            _ <- Log.warn("warn", ex)
            _ <- Log.error("error", ex)
        yield succeed
        end for
    }

    "unsafe" in {
        import AllowUnsafe.embrace.danger
        Log.live.unsafe.trace("trace")
        Log.live.unsafe.debug("debug")
        Log.live.unsafe.info("info")
        Log.live.unsafe.warn("warn")
        Log.live.unsafe.error("error")
        Log.live.unsafe.trace("trace", ex)
        Log.live.unsafe.debug("debug", ex)
        Log.live.unsafe.info("info", ex)
        Log.live.unsafe.warn("warn", ex)
        Log.live.unsafe.error("error", ex)
        succeed
    }

    "withConsoleLogger" in {
        val output = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = output.append(b.toChar))) {
            import AllowUnsafe.embrace.danger
            val text: Text = "info message - hidden"
            Sync.Unsafe.evalOrThrow {
                for
                    _ <- Log.withConsoleLogger("test.logger", Log.Level.debug) {
                        for
                            _ <- Log.trace("won't show up")
                            _ <- Log.debug("test message")
                            _ <- Log.info(text.dropRight(9))
                            _ <- Log.warn("warning", new Exception("test exception"))
                        yield ()
                    }
                yield ()
            }
            val logs = output.toString.trim.split("\n")
            assert(logs.length == 3)
            assert(logs(0).matches("DEBUG test.logger -- \\[.*\\] test message"))
            assert(logs(1).matches("INFO test.logger -- \\[.*\\] info message"))
            assert(logs(2).matches("WARN test.logger -- \\[.*\\] warning java.lang.Exception: test exception"))
        }
    }
end LogTest
