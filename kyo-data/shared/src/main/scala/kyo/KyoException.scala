package kyo

import KyoException.maxMessageLength
import kyo.*
import kyo.Ansi.*
import kyo.internal.Environment
import scala.util.control.NoStackTrace

/** Kyo's base exception class that provides enhanced error reporting and context.
  *
  * The exception's behavior changes based on the environment:
  *   - In development: Provides detailed, ANSI-colored error messages with context
  *   - In production: Returns minimal error messages for cleaner logs
  *
  * @param message
  *   The primary error message
  * @param cause
  *   Either a Text explanation or a Throwable that caused this exception
  * @param frame
  *   Implicit Frame providing context about where the exception was created
  */
class KyoException private[kyo] (
    message: Text = "",
    cause: Text | Throwable = ""
)(using val frame: Frame) extends Exception(
        message.toString,
        cause match
            case cause: Throwable => cause
            case _                => null
    ) with NoStackTrace:

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
      * In production mode, returns only the cause message if present, or null if no cause message was provided.
      *
      * @return
      *   The formatted exception message
      */
    override def getMessage(): String =
        val detail =
            cause match
                case _: Throwable           => Absent
                case cause: Text @unchecked => Maybe(cause.take(maxMessageLength))

        if Environment.isDevelopment then
            val msg = frame.render(
                ("⚠️ KyoException".red.bold :: Maybe(message).toList ::: detail.toList)
                    .map(_.show)*
            )
            s"\n$msg\n"
        else
            detail.map(_.toString).getOrElse(null)
        end if
    end getMessage
end KyoException

object KyoException:
    /** Maximum length for error messages to prevent excessive output. */
    val maxMessageLength = 1000
