package kyo.internal

import scala.language.implicitConversions
import scala.quoted.*

opaque type FileNameWithLine <: String = String
object FileNameWithLine:
    def apply(value: String): FileNameWithLine   = value
    implicit inline def derive: FileNameWithLine = ${ Macros.fileNameWithLine }
end FileNameWithLine

object Macros:
    def fileNameWithLine(using Quotes): Expr[FileNameWithLine] =
        val expansion = quotes.reflect.Position.ofMacroExpansion
        val name      = expansion.sourceFile.name
        val line      = expansion.startLine + 1

        Expr(FileNameWithLine(s"$name:$line"))
    end fileNameWithLine

end Macros
