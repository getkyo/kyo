package kyo.test

import kyo.*
import kyo.test.ExecutionEvent
import kyo.test.ExecutionEventPrinter
import kyo.test.ReporterEventRenderer
// These are assumed to be defined in the kyo-test project:
import kyo.test.Summary
import kyo.test.TestOutput

trait ExecutionEventSink:
    def getSummary: Summary < IO
    def process(event: ExecutionEvent): Unit < IO
end ExecutionEventSink

object ExecutionEventSink:
    def getSummary: Summary < (Env[ExecutionEventSink] & IO) =
        Env.get[ExecutionEventSink].flatMap(_.getSummary)

    def process(event: ExecutionEvent): Unit < (Env[ExecutionEventSink] & IO) =
        Env.get[ExecutionEventSink].flatMap(_.process(event))

    def ExecutionEventSinkLive(testOutput: TestOutput): ExecutionEventSink < IO =
        // TODO: val summary = Var.set[Summary](Summary.empty)
        new ExecutionEventSink:
            override def process(event: ExecutionEvent): Unit < IO =
                Var.update[Summary](_.add(event)) *> testOutput.print(event)
            override def getSummary: Summary < Var[Summary] = Var.get[Summary]

    def live(console: Console, eventRenderer: ReporterEventRenderer): Layer[ExecutionEventSink, IO] =
        Layer.init[ExecutionEventSink](
            ExecutionEventPrinter.live(console, eventRenderer),
            TestOutput.live,
            Layer(
                for
                    testOutput <- Env.get[TestOutput]
                    sink       <- ExecutionEventSinkLive(testOutput)
                yield sink
            )
        )

    val live: Layer[ExecutionEventSink, Env[TestOutput]] =
        Layer.init[ExecutionEventSink](
            Layer.from[TestLogger, ReporterEventRenderer, ExecutionEventConsolePrinter, Any]((logger, renderer) =>
                ExecutionEventPrinter.Live(logger, renderer)
            ),
            TestOutput.live,
            Layer(
                for
                    testOutput <- Env.get[TestOutput]
                    sink       <- ExecutionEventSinkLive(testOutput)
                yield sink
            )
        )

    val silent: Layer[ExecutionEventSink, Any] =
        Layer(
            new ExecutionEventSink:
                override def getSummary: Summary < IO                  = Kyo.suspend(Summary.empty)
                override def process(event: ExecutionEvent): Unit < IO = Kyo.suspend(())
        )
end ExecutionEventSink
