package kyo.internal

import scala.annotation.tailrec
import scala.quoted.*

opaque type Position = String

object Position:

    inline given derive: Position = ${ Position.fileNameWithLine }

    private def fileNameWithLine(using Quotes): Expr[Position] =
        val expansion = quotes.reflect.Position.ofMacroExpansion
        val name      = expansion.sourceFile.name
        val line      = expansion.startLine + 1
        Expr(s"$name:$line - $owner")
    end fileNameWithLine

    private def owner(using Quotes) =
        import quotes.reflect.*
        @tailrec def loop(owner: Symbol): Symbol =
            if owner.name.startsWith("<") ||
                owner.name.startsWith("$") ||
                owner.name.startsWith("macro")
            then
                loop(owner.owner)
            else
                owner
        loop(Symbol.spliceOwner)
    end owner

end Position
