package kyo.test

import kyo.*
import kyo.service
import kyo.serviceWith
// Domain-specific imports (assumed to be converted similarly)
import kyo.test.ExecutionEvent
import kyo.test.ExecutionEventPrinter
import kyo.test.SuiteId
import kyo.test.TestDebug
import kyo.test.TestDebugFileLock
import kyo.test.TestReporters
import kyo.traverseDiscard
import scala.io.Source

private[test] trait TestOutput:
    def print(executionEvent: ExecutionEvent): Unit < IO

private[test] object TestOutput:
    val live: Layer[TestOutput, Env[ExecutionEventPrinter] & IO] =
        Layer {
            for
                executionEventPrinter <- Env.get[ExecutionEventPrinter]
                outputLive            <- TestOutputLive.make(executionEventPrinter, debug = false)
            yield outputLive
        }

    def print(executionEvent: ExecutionEvent): Unit < IO =
        serviceWith[TestOutput](_.print(executionEvent))

    case class TestOutputLive(
        output: Var[Map[SuiteId, Chunk[ExecutionEvent]]],
        reporters: TestReporters,
        executionEventPrinter: ExecutionEventPrinter,
        lock: TestDebugFileLock,
        debug: Boolean
    ) extends TestOutput:

        private def getAndRemoveSectionOutput(id: SuiteId): Chunk[ExecutionEvent] < IO =
            output.getAndUpdate(initial => updatedWith(initial, id)(_ => None))
                .map(_.getOrElse(id, Chunk.empty))

        def print(executionEvent: ExecutionEvent): Unit < IO =
            executionEvent match
                case end: ExecutionEvent.SectionEnd =>
                    printOrFlush(end)
                case flush: ExecutionEvent.TopLevelFlush =>
                    flushGlobalOutputIfPossible(flush)
                case other =>
                    printOrQueue(other)

        private def printOrFlush(end: ExecutionEvent.SectionEnd): Unit < IO =
            for
                suiteIsPrinting <- reporters.attemptToGetPrintingControl(end.id, end.ancestors)
                sectionOutput   <- getAndRemoveSectionOutput(end.id).map(_ :+ end)
                _ <- if suiteIsPrinting then
                    print(sectionOutput)
                else
                    end.ancestors.headOption match
                        case Some(parentId) => appendToSectionContents(parentId, sectionOutput)
                        case None           => Abort.panic("Suite tried to send its output to a nonexistent parent. ExecutionEvent: " + end)
                _ <- reporters.relinquishPrintingControl(end.id)
            yield ()

        private def flushGlobalOutputIfPossible(end: ExecutionEvent.TopLevelFlush): Unit < IO =
            for
                sectionOutput   <- getAndRemoveSectionOutput(end.id)
                _               <- appendToSectionContents(SuiteId.global, sectionOutput)
                suiteIsPrinting <- reporters.attemptToGetPrintingControl(SuiteId.global, List.empty)
                _ <- if suiteIsPrinting then
                    for
                        globalOutput <- getAndRemoveSectionOutput(SuiteId.global)
                        _            <- print(globalOutput)
                    yield ()
                else
                    (
                )
            yield ()

        private def printOrQueue(reporterEvent: ExecutionEvent): Unit < IO =
            for
                _               <- if debug then TestDebug.print(reporterEvent, lock) else ()
                _               <- appendToSectionContents(reporterEvent.id, Chunk(reporterEvent))
                suiteIsPrinting <- reporters.attemptToGetPrintingControl(reporterEvent.id, reporterEvent.ancestors)
                _ <- if suiteIsPrinting then
                    for
                        currentOutput <- getAndRemoveSectionOutput(reporterEvent.id)
                        _             <- print(currentOutput)
                    yield ()
                else
                    (
                )
            yield ()

        private def print(events: Chunk[ExecutionEvent]): Unit < IO =
            traverseDiscard(events)(event => executionEventPrinter.print(event))

        private def appendToSectionContents(id: SuiteId, content: Chunk[ExecutionEvent]): Unit < IO =
            output.update { outputNow =>
                updatedWith(outputNow, id)(previousSectionOutput =>
                    Some(previousSectionOutput.map(old => old ++ content).getOrElse(content))
                )
            }

        private def updatedWith(
            initial: Map[SuiteId, Chunk[ExecutionEvent]],
            key: SuiteId
        )(remappingFunction: Option[Chunk[ExecutionEvent]] => Option[Chunk[ExecutionEvent]])
            : Map[SuiteId, Chunk[ExecutionEvent]] =
            val previousValue = initial.get(key)
            val nextValue     = remappingFunction(previousValue)
            (previousValue, nextValue) match
                case (None, None)    => initial
                case (Some(_), None) => initial - key
                case (_, Some(v))    => initial.updated(key, v)
            end match
        end updatedWith
    end TestOutputLive

    object TestOutputLive:
        def make(executionEventPrinter: ExecutionEventPrinter, debug: Boolean): TestOutput < IO =
            for
                talkers <- TestReporters.make
                lock    <- TestDebugFileLock.make
                output  <- AtomicRef.init[Map[SuiteId, Chunk[ExecutionEvent]]](Map.empty)
            yield TestOutputLive(output, talkers, executionEventPrinter, lock, debug)
    end TestOutputLive
end TestOutput
