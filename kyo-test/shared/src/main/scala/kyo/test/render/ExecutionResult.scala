package kyo.test.render

import kyo.test.SuiteId
import kyo.test.TestAnnotationMap
import kyo.test.render.ExecutionResult.ResultType
import kyo.test.render.ExecutionResult.Status
import kyo.test.render.ExecutionResult.Status.*
import kyo.test.render.LogLine.Line

case class ExecutionResult(
    resultType: ResultType,
    label: String,
    status: Status,
    offset: Int,
    annotations: List[TestAnnotationMap],
    streamingLines: List[Line],
    summaryLines: List[Line],
    duration: Option[Long]
):
    self =>

    def &&(that: ExecutionResult): ExecutionResult =
        (self.status, that.status) match
            case (Started, _)     => that
            case (Ignored, _)     => that
            case (_, Ignored)     => self
            case (Failed, Failed) => self.copy(streamingLines = self.streamingLines ++ that.streamingLines.tail)
            case (Passed, _)      => that
            case (_, Passed)      => self

    def ||(that: ExecutionResult): ExecutionResult =
        (self.status, that.status) match
            case (Started, _)     => that
            case (Ignored, _)     => that
            case (_, Ignored)     => self
            case (Failed, Failed) => self.copy(streamingLines = self.streamingLines ++ that.streamingLines.tail)
            case (Passed, _)      => self
            case (_, Passed)      => that

    def unary_! : ExecutionResult =
        self.status match
            case Started => self
            case Ignored => self
            case Failed  => self.copy(status = Passed)
            case Passed  => self.copy(status = Failed)

    def withAnnotations(annotations: List[TestAnnotationMap]): ExecutionResult =
        self.copy(annotations = annotations)
end ExecutionResult
object ExecutionResult:
    def withoutSummarySpecificOutput(
        resultType: ResultType,
        label: String,
        status: Status,
        offset: Int,
        annotations: List[TestAnnotationMap],
        lines: List[Line],
        duration: Option[Long]
    ): ExecutionResult =
        ExecutionResult(
            resultType,
            label,
            status,
            offset,
            annotations,
            lines,
            lines, // Re-uses lines when we don't have summary-specific output,
            duration
        )

    enum Status:
        case Started
        case Failed
        case Passed
        case Ignored
    end Status

    enum ResultType:
        case Test
        case Suite
        case Other
    end ResultType
end ExecutionResult
