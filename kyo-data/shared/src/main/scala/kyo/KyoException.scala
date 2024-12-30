package kyo

import kyo.*
import kyo.Ansi.*
import scala.util.control.NoStackTrace

class KyoException private[kyo] (
    message: Text | Null = null,
    cause: Text | Throwable | Null = null
)(using val frame: Frame) extends Exception(
        Option(message) match
            case Some(message) => message.toString; case _ => null,
        cause match
            case cause: Throwable => cause; case _ => null
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

        val msg = frame.render(("⚠️ KyoException".red.bold :: Maybe(message).toList ::: detail.toList).map(_.show)*)
        s"\n$msg\n"
    end getMessage

end KyoException
