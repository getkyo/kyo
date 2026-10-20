package kyo

import KyoException.maxMessageLength
import kyo.*
import kyo.Ansi.*
import kyo.internal.Environment
import scala.util.control.NoStackTrace

/** Kyo's base exception class that provides enhanced error reporting and context.
  *
  * The exception's behavior changes based on the environment:
  *
  *   - In development: Provides detailed, ANSI-colored error messages with context
  *   - In production: Returns minimal error messages for cleaner logs
  *
  * @param message
  *   The primary error message
  * @param cause
  *   Either a String explanation or a Throwable that caused this exception
  * @param frame
  *   Implicit Frame providing context about where the exception was created
  */
class KyoException private[kyo] (
    message: => String = "",
    cause: String | Throwable = ""
)(using val frame: Frame) extends Exception with NoStackTrace:

    /** Returns the underlying cause of this exception. Overriding this method is important for performance since the method is synchronized
      * in the super classes.
      *
      * @return
      *   The Throwable cause if one was provided, null otherwise
      */
    override def getCause(): Throwable =
        cause match
            case cause: Throwable => cause
            case _                => null

    /** Formats and returns the exception message.
      *
      * In development mode, the message and the cause's detail framed by the source lines around [[frame]], in ANSI color; otherwise
      * [[show]].
      *
      * @return
      *   The formatted exception message
      */
    override def getMessage(): String =
        if Environment.isDevelopment then
            val msg = frame.render(
                ("⚠️ KyoException".red.bold :: message :: detail.toList)*
            )
            s"\n$msg\n"
        else show
        end if
    end getMessage

    /** The message and the cause's detail as plain text, the same whether or not `kyo.development` is set: no color, no frame. */
    final def show: String = message + detail.fold("")(" " + _)

    private def detail: Maybe[String] =
        cause match
            case t: Throwable =>
                Maybe(s"${t.getClass.getSimpleName}: ${Maybe(t.getMessage).getOrElse("")}".take(maxMessageLength))
            case cause: String @unchecked => Maybe(cause.take(maxMessageLength)).filter(_.nonEmpty)
end KyoException

object KyoException:
    /** Maximum length for error messages to prevent excessive output. */
    val maxMessageLength = 1000
