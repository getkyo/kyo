package kyo.internal

import scala.quoted.*

opaque type Position <: String = String
object Position:
    def apply(value: String): Position = value
    inline given derive: Position      = ${ Macros.fileNameWithLine }
end Position

object Macros:
    def fileNameWithLine(using Quotes): Expr[Position] =
        val expansion = quotes.reflect.Position.ofMacroExpansion
        val name      = expansion.sourceFile.name
        val line      = expansion.startLine + 1

        Expr(Position(s"$name:$line"))
    end fileNameWithLine

end Macros
