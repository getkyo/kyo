package kyo

import scala.quoted._

object frames {

  extension [T <: String](frame: Frame[T]) {
    def toStackTraceElement() =
      val (location :: op :: file :: line :: column :: Nil) =
        frame.split('|').toList
      val parts  = location.split('.').toList
      val cls    = parts.take(parts.length - 1).mkString(".")
      val method = parts.lastOption.getOrElse("<unknown>")
      new StackTraceElement(cls, s"$method@$op:$column", file, line.toInt)
  }

  opaque type Frame[+T] = String

  inline given [T <: String](using
      inline op: ValueOf[T]
  ): Frame[T] = ${ Macro('{ op.value }) }

  private object Macro {
    def apply(op: Expr[String])(using Quotes): Expr[String] =
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
        Expr(s"$location|${op.valueOrAbort}|$file|$line|$column")
      } catch {
        case ex =>
          throw ex
      }
  }
}
