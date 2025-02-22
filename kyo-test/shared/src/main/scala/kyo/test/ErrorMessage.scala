package zio.test

import scala.io.AnsiColor
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.*
import zio.test.render.LogLine.Fragment
import zio.test.render.LogLine.Message

object ErrorMessage:

    def choice(success: String, failure: String): ErrorMessage = Choice(success, failure)
    def custom(string: String): ErrorMessage                   = Custom(string)
    def pretty(value: Any): ErrorMessage                       = Value(PrettyPrint(value))
    def text(string: String): ErrorMessage                     = choice(string, string)
    def throwable(throwable: Throwable): ErrorMessage          = ThrowableZIO(throwable)
    def value(value: Any): ErrorMessage                        = Value(value)

    val did: ErrorMessage    = choice("did", "did not")
    val equals: ErrorMessage = choice("was equal to", "was not equal to")
    val was: ErrorMessage    = choice("was", "was not")
    val had: ErrorMessage    = choice("had", "did not have")

    final private case class Choice(success: String, failure: String)                        extends ErrorMessage
    final private case class Combine(lhs: ErrorMessage, rhs: ErrorMessage, spacing: Int = 1) extends ErrorMessage
    final private case class CombineMessage(lhs: ErrorMessage, rhs: ErrorMessage)            extends ErrorMessage
    final private case class Custom(string: String)                                          extends ErrorMessage
    final private case class ThrowableZIO(throwable: Throwable)                              extends ErrorMessage
    final private case class Value(value: Any)                                               extends ErrorMessage
end ErrorMessage

sealed trait ErrorMessage:
    self =>
    def +(that: String): ErrorMessage        = ErrorMessage.Combine(self, ErrorMessage.text(that))
    def +(that: ErrorMessage): ErrorMessage  = ErrorMessage.Combine(self, that)
    def ++(that: ErrorMessage): ErrorMessage = ErrorMessage.CombineMessage(self, that)

    private[test] def render(isSuccess: Boolean): Message =
        self match
            case ErrorMessage.Custom(custom) =>
                Message(custom.split("\n").toIndexedSeq.map(Fragment(_).toLine))

            case ErrorMessage.Choice(success, failure) =>
                (if isSuccess then fr(success).ansi(AnsiColor.MAGENTA) else error(failure)).toLine.toMessage

            case ErrorMessage.Value(value) =>
                Message(value.toString.split("\n").toIndexedSeq.map(primary(_).bold.toLine))

            case ErrorMessage.Combine(lhs, rhs, spacing) =>
                (lhs.render(isSuccess) + (sp * spacing)) +++ rhs.render(isSuccess)

            case ErrorMessage.CombineMessage(lhs, rhs) =>
                lhs.render(isSuccess) ++ rhs.render(isSuccess)

            case ErrorMessage.ThrowableZIO(throwable) =>
                val stacktrace = throwable.getStackTrace.toIndexedSeq
                    .takeWhile(!_.getClassName.startsWith("zio.test.Arrow$"))
                    .map(s => LogLine.Line.fromString(s.toString))

                Message((error("ERROR:") + sp + bold(throwable.toString)) +: stacktrace)
end ErrorMessage
