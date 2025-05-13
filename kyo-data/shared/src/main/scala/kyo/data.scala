package kyo

import scala.quoted.*

private[kyo] inline def isNull[A](v: A): Boolean =
    v.asInstanceOf[AnyRef] eq null

private[kyo] inline def discard[A](inline v: => A): Unit =
    val _ = v
    ()

private[kyo] object bug:

    case class KyoBugException(msg: String) extends Exception(msg)

    def check(cond: Boolean): Unit =
        if !cond then throw new KyoBugException("Required condition is false.")

    def checkMacro(cond: Boolean, msg: String = "")(using Quotes): Unit =
        import quotes.reflect.*
        if !cond then
            report.errorAndAbort(message(msg))
    end checkMacro

    def apply(msg: String): Nothing =
        throw KyoBugException(message(msg))

    private def message(msg: String) =
        s"BUG $msg Please open an issue 🥹 https://github.com/getkyo/kyo/issues"
end bug
