package kyo.test

// Updated imports: using kyo equivalents
import kyo.*
import kyo.test.ExecutionEvent.RuntimeFailure
import kyo.test.ExecutionEvent.SectionEnd
import kyo.test.ExecutionEvent.SectionStart
import kyo.test.ExecutionEvent.Test
import kyo.test.ExecutionEvent.TestStarted
import kyo.test.ExecutionEvent.TopLevelFlush
import kyo.test.TestAnnotationRenderer
import kyo.test.render.ConsoleRenderer

object SummaryBuilder:

    def buildSummary(reporterEvent: ExecutionEvent, oldSummary: Summary)(using trace: Trace): Summary =
        val success = countTestResults(reporterEvent) {
            case Right(TestSuccess.Succeeded(_)) => true
            case _                               => false
        }
        val fail = countTestResults(reporterEvent) {
            case Right(_) => false
            case _        => true
        }
        val ignore = countTestResults(reporterEvent) {
            case Right(TestSuccess.Ignored(_)) => true
            case _                             => false
        }
        val failures = extractFailures(reporterEvent)

        val rendered: String =
            ConsoleRenderer
                .renderForSummary(failures.flatMap(ConsoleRenderer.renderEvent(_, true)), TestAnnotationRenderer.silent)
                .mkString("\n")

        val newSummary = Summary(success, fail, ignore, rendered)
        oldSummary.add(newSummary)
    end buildSummary

    private def countTestResults(
        executedSpec: ExecutionEvent
    )(pred: Either[TestFailure[_], TestSuccess] => Boolean): Int =
        executedSpec match
            case Test(_, test, _, _, _, _, _) =>
                if pred(test) then 1 else 0
            case RuntimeFailure(_, _, _, _) =>
                0
            case SectionStart(_, _, _) => 0
            case SectionEnd(_, _, _)   => 0
            case TopLevelFlush(_)      => 0
            case TestStarted(_, _, _, _, _) =>
                0

    private def extractFailures(reporterEvent: ExecutionEvent): Seq[ExecutionEvent] =
        reporterEvent match
            case Test(_, test, _, _, _, _, _) =>
                test match
                    case Left(_) =>
                        Seq(reporterEvent)
                    case _ =>
                        Seq.empty
            case RuntimeFailure(_, _, _, _) =>
                Seq(reporterEvent)
            case _ =>
                Seq.empty
end SummaryBuilder
