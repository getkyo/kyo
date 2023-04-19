package kyo

import scala.quoted._

object frames {

  opaque type Frame[+T] <: AnyRef = String

  extension [T <: String](frame: Frame[T]) {
    def toStackTraceElement() =
      val (location :: op :: file :: line :: column :: Nil) =
        (frame.split('|').toList: @unchecked)
      val parts  = location.split('.').toList
      val cls    = parts.take(parts.length - 1).mkString(".")
      val method = parts.lastOption.getOrElse("<unknown>")
      StackTraceElement(cls, s"$method@$op:$column", file, line.toInt)
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
        val file   = pos.sourceFile.getJPath.get.getFileName.toString
        val line   = pos.startLine + 1
        val column = pos.startColumn + 1
        val location = Macros.enclosing(machine = false)(!Util.isSynthetic(_)).replaceAll(
            " ",
            ""
        ).replaceAll("#", ".")
        s"$location|$op|$file|$line|$column"
      } catch {
        case ex =>
          throw ex
      }
  }
}
