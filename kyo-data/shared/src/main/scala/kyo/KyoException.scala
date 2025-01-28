package kyo

import kyo.*
import kyo.Ansi.*
import kyo.internal.Environment
import scala.util.control.NoStackTrace

class KyoException private[kyo] (message: Text = "", cause: Text | Throwable = "")(using val frame: Frame)
    extends Exception(
        message.toString,
        cause match
            case cause: Throwable => cause
            case _                => null
    ) with NoStackTrace:

    override def getCause(): Throwable =
        cause match
            case cause: Throwable => cause
            case _                => null

    override def getMessage(): String =
        val detail =
            cause match
                case _: Throwable           => Absent
                case cause: Text @unchecked => Maybe(cause)

        if Environment.isDevelopment then
            val msg = frame.render(("⚠️ KyoException".red.bold :: Maybe(message).toList ::: detail.toList).map(_.show)*)
            s"\n$msg\n"
        else
            detail.map(_.toString).getOrElse(null)
        end if
    end getMessage

end KyoException
