package kyo.test

import kyo.*
import kyo.Ansi.*
import scala.io.AnsiColor

object ErrorMessage:

    def choice(success: String, failure: String): ErrorMessage = Choice(success, failure)
    def custom(string: String): ErrorMessage                   = Custom(string)
    def pretty(value: Any): ErrorMessage                       = Value(kyo.Render.asText(value)) // Use kyo.Render.asText
    def text(string: String): ErrorMessage                     = choice(string, string)
    def throwable(throwable: Throwable): ErrorMessage          = ThrowableKyo(throwable)         // Renamed to ThrowableKyo
    def value(value: kyo.Text): ErrorMessage                   = Value(value)                    // Value now stores Text

    val did: ErrorMessage    = choice("did", "did not")
    val equals: ErrorMessage = choice("was equal to", "was not equal to")
    val was: ErrorMessage    = choice("was", "was not")
    val had: ErrorMessage    = choice("had", "did not have")

    final private case class Choice(success: String, failure: String)                        extends ErrorMessage
    final private case class Combine(lhs: ErrorMessage, rhs: ErrorMessage, spacing: Int = 1) extends ErrorMessage
    final private case class CombineMessage(lhs: ErrorMessage, rhs: ErrorMessage)            extends ErrorMessage
    final private case class Custom(string: String)                                          extends ErrorMessage
    final private case class ThrowableKyo(throwable: Throwable)                              extends ErrorMessage // Renamed to ThrowableKyo
    final private case class Value(value: Text)                                              extends ErrorMessage // Value now stores Text
end ErrorMessage

sealed trait ErrorMessage:
    self =>
    import ErrorMessage.*

    def +(that: String): ErrorMessage        = Combine(self, text(that))
    def +(that: ErrorMessage): ErrorMessage  = Combine(self, that)
    def ++(that: ErrorMessage): ErrorMessage = CombineMessage(self, that)

    private[test] def render(isSuccess: Boolean): Text =
        this match
            case Custom(custom) =>
                Text(custom) // Text is used directly

            case Choice(success, failure) =>
                if isSuccess then Text(success.magenta) else Text(failure.red)

            case Value(value) =>
                value.toString // Value already stores Text

            case Combine(lhs, rhs, spacing) =>
                lhs.render(isSuccess) + Text(" " * spacing) + rhs.render(isSuccess)

            case CombineMessage(lhs, rhs) =>
                lhs.render(isSuccess) + rhs.render(isSuccess)

            case ThrowableKyo(throwable) =>
                val stacktrace = Chunk.Indexed.from(throwable.getStackTrace)
                    .takeWhile(!_.getClassName.startsWith("zio.test.Arrow$"))
                    .map(s => Text(s.toString))

                Text("ERROR:".red + " " + throwable.toString.bold + stacktrace.mkString("\n"))
end ErrorMessage
