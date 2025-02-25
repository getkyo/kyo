package kyo.test

import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.annotation.tailrec

// Placeholder definitions for types used in logging. In an actual conversion these should be imported from appropriate modules.

// Base trait for loggers, assuming a similar signature as in the Kyo version.
trait ZLogger[-Message, +Output]:
    def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
    ): Output
end ZLogger

// The converted ZTestLogger that writes logs to an internal data structure.
sealed trait ZTestLogger[-Message, +Output] extends ZLogger[Message, Output]:
    def logOutput: Chunk[LogEntry] < (Env[Any] & IO & Abort[Nothing])

object ZTestLogger:
    // Placeholder for a function to retrieve loggers from the environment. In the actual conversion, this should interface with Kyo's environment.
    def loggersWith[A](f: List[Any] => A): A = f(Nil) // Placeholder implementation

    // A layer that constructs a new ZTestLogger and sets up the logging environment.
    val default: Layer[Unit, Nothing] = Layer.scoped {
        for testLogger <- make
        // Note: In the original Kyo version, the current loggers were updated via Local.currentLoggers.locallyScopedWith(_ + testLogger).
        // This part is omitted in this conversion.
        yield ()
    }

    // Provides access to the log output of the current test logger.
    val logOutput: Chunk[LogEntry] < (Env[Any] & IO & Abort[Nothing]) =
        loggersWith { loggers =>
            loggers.collectFirst { case l: ZTestLogger[?, ?] => l.logOutput }
                .getOrElse(Abort.fail("Defect: ZTestLogger is missing"))
        }

    // Captures all details of a log message.
    final case class LogEntry(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
    ):
        def call[A](zlogger: ZLogger[String, A]): A =
            zlogger(trace, fiberId, logLevel, message, cause, context, spans, annotations)
    end LogEntry

    // Constructs a new ZTestLogger that accumulates log entries in an atomic reference.
    private def make: ZTestLogger[String, Unit] < (Env[Any] & IO & Abort[Nothing]) =
        val _logOutput = new AtomicReference[Chunk[LogEntry]](Chunk.empty)
        new ZTestLogger[String, Unit]:
            @tailrec
            def apply(
                trace: Trace,
                fiberId: FiberId,
                logLevel: LogLevel,
                message: () => String,
                cause: Cause[Any],
                context: FiberRefs,
                spans: List[LogSpan],
                annotations: Map[String, String]
            ): Unit =
                val newEntry = LogEntry(trace, fiberId, logLevel, message, cause, context, spans, annotations)
                val oldState = _logOutput.get
                if !_logOutput.compareAndSet(oldState, oldState :+ newEntry) then
                    apply(trace, fiberId, logLevel, message, cause, context, spans, annotations)
                else ()
            end apply

            val logOutput: Chunk[LogEntry] < (Env[Any] & IO & Abort[Nothing]) = _logOutput.get
        end new
    end make
end ZTestLogger
