package kyo

import scala.quoted._

object frames {

  opaque type Frame[+T] <: AnyRef = String

  extension [T <: String](frame: Frame[T]) {
    def toStackTraceElement() =
      val (preemptable :: location :: op :: file :: line :: column :: Nil) =
        frame.split('|').toList
      val parts  = location.split('.').toList
      val cls    = parts.take(parts.length - 1).mkString(".")
      val method = parts.lastOption.getOrElse("<unknown>")
      new StackTraceElement(cls, s"$method@$op:$column", file, line.toInt)
    inline def preemptable: Boolean =
      frame.charAt(0) == '1'
  }

  inline given [T <: String](using
      inline op: ValueOf[T]
  ): Frame[T] = ${ Macro('{ op.value }) }

  private object Macro {

    def id(frame: Expr[String])(using Quotes): Expr[Boolean] =
      Expr(false)

    def apply(op: Expr[String])(using Quotes): Expr[String] =
      Expr(frame(op.valueOrAbort))

    private def frame(op: String)(using Quotes): String =
      try {
        import sourcecode._
        import quotes.reflect._
        val pos    = quotes.reflect.Position.ofMacroExpansion
        val file   = pos.sourceFile.jpath.getFileName.toString
        val line   = pos.startLine + 1
        val column = pos.startColumn + 1
        val location = Macros.enclosing(machine = false)(!Util.isSynthetic(_)).replaceAll(
            " ",
            ""
        ).replaceAll("#", ".")
        val frame = s"$location|$op|$file|$line|$column"
        if ((frame.hashCode() & 7) == 0)
          s"1|$frame"
        else
          s"0|$frame"
      } catch {
        case ex =>
          throw ex
      }
  }
}
