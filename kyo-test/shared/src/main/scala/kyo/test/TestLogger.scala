package kyo.test

import kyo.*

trait TestLogger extends Serializable:
    def logLine(line: String): Unit < IO

object TestLogger:

    def fromConsole(console: Console): Layer[TestLogger, Any] =
        Layer {
            new TestLogger:
                def logLine(line: String): Unit < IO = console.printLine(line).orPanic
        }

    def logLine(line: String): Unit < Env[TestLogger] =
        Env.serviceWith(_.logLine(line))
end TestLogger
