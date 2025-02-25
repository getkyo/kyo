package kyo.test

import kyo.*
import kyo.test.ConsoleUtils.*
import kyo.test.TestArrow.Span
import kyo.test.render.LogLine.Message
import scala.annotation.tailrec

sealed trait TResult[+A]:
    self =>
    def isFailOrDie: Boolean = self match
        case TResult.Fail       => true
        case TResult.Die(_)     => true
        case TResult.Succeed(_) => false

    def isDie: Boolean = self match
        case TResult.Die(_) => true
        case _              => false

    def zipWith[B, C](that: TResult[B])(f: (A, B) => C): TResult[C] =
        (self, that) match
            case (TResult.Succeed(a), TResult.Succeed(b)) => TResult.succeed(f(a, b))
            case (TResult.Fail, _)                        => TResult.fail
            case (_, TResult.Fail)                        => TResult.fail
            case (TResult.Die(err), _)                    => TResult.panic(err)
            case (_, TResult.Die(err))                    => TResult.panic(err)
end TResult

object TResult:
    def succeed[A](value: A): TResult[A] = Succeed(value)

    def fail: TResult[Nothing] = Fail

    def die(throwable: Throwable): TResult[Nothing] = Die(throwable)

    case object Fail                 extends TResult[Nothing]
    case class Die(err: Throwable)   extends TResult[Nothing]
    case class Succeed[+A](value: A) extends TResult[A]
end TResult

case class FailureCase(
    errorMessage: Message,
    codeString: String,
    location: String,
    path: Chunk[(String, String)],
    span: Span,
    nestedFailures: Chunk[FailureCase],
    result: Any,
    customLabel: Option[String]
)

object FailureCase:
    def highlight(
        string: String,
        span: Span,
        parentSpan: Option[Span] = None,
        color: String => String,
        normalColor: String => String = identity
    ): String =
        parentSpan match
            case Some(Span(pStart, pEnd)) if pStart <= span.start && pEnd >= span.end =>
                val part1 = string.take(pStart)
                val part2 = string.slice(pStart, span.start)
                val part3 = string.slice(span.start, span.end)
                val part4 = string.slice(span.end, pEnd)
                val part5 = string.drop(pEnd)
                part1 + bold(part2) + bold(color(part3)) + bold(part4) + part5
            case _ =>
                bold(normalColor(string.take(span.start))) + bold(color(string.slice(span.start, span.end))) + bold(
                    normalColor(string.drop(span.end))
                )

    @tailrec
    def rightmostNode(trace: TestTrace[Boolean]): TestTrace.Node[Boolean] = trace match
        case node: TestTrace.Node[Boolean] => node
        case TestTrace.AndThen(_, right)   => rightmostNode(right)
        case TestTrace.And(_, right)       => rightmostNode(right)
        case TestTrace.Or(_, right)        => rightmostNode(right)
        case TestTrace.Not(trace)          => rightmostNode(trace)

    def getPath(trace: TestTrace[?]): Chunk[(String, String)] =
        trace match
            case node: TestTrace.Node[?] =>
                Chunk(node.code -> PrettyPrint(node.renderResult))
            case TestTrace.AndThen(left, right) =>
                getPath(left) ++ getPath(right)
            case _ => Chunk.empty

    def fromTrace(trace: TestTrace[Boolean], path: Chunk[(String, String)]): Chunk[FailureCase] =
        trace match
            case node: TestTrace.Node[Boolean] =>
                Chunk(fromNode(node, path.reverse))
            case TestTrace.AndThen(left, right) =>
                fromTrace(right, path ++ getPath(left))
            case TestTrace.And(left, right) =>
                fromTrace(left, path) ++ fromTrace(right, path)
            case TestTrace.Or(left, right) =>
                fromTrace(left, path) ++ fromTrace(right, path)
            case TestTrace.Not(trace) =>
                fromTrace(trace, path)

    private def fromNode(node: TestTrace.Node[Boolean], path: Chunk[(String, String)]): FailureCase =
        val color = node.result match
            case TResult.Die(_) => red _
            case _              => yellow _

        FailureCase(
            errorMessage = node.message.render(node.isSuccess),
            codeString =
                node.completeCode match
                    case Some(completeCode) =>
                        val idx = completeCode.indexOf(node.code)
                        if idx >= 0 && node.code.nonEmpty then
                            highlight(completeCode, Span(idx, idx + node.code.length), None, color, (_: String).cyan)
                        else
                            completeCode
                        end if
                    case None =>
                        highlight(node.fullCode.getOrElse("<CODE>"), node.span.getOrElse(Span(0, 0)), node.parentSpan, color)
            ,
            location = node.location.getOrElse("<LOCATION>"),
            path = path,
            span = node.span.getOrElse(Span(0, 0)),
            nestedFailures = node.children.map(fromTrace(_, Chunk.empty)).getOrElse(Chunk.empty),
            result = node.result,
            customLabel = node.customLabel
        )
    end fromNode
end FailureCase
