package kyo.internal

import scala.annotation.tailrec
import scala.quoted.*

opaque type Position = String

object Position:

    extension (p: Position)
        inline def show: String = p

    inline given derive: Position = ${ Position.fileNameWithLine }

    object WithOwner:
        inline given derive: Position = ${ Position.withOwner }

    private[internal] inline def apply(s: String): Position = s

    private[internal] def infer(using Quotes): String =
        val expansion = quotes.reflect.Position.ofMacroExpansion
        val name      = expansion.sourceFile.name
        val line      = expansion.startLine + 1
        s"$name:$line"
    end infer

    private def fileNameWithLine(using Quotes): Expr[Position] =
        Expr(infer)

    private def withOwner(using Quotes): Expr[Position] =
        Expr(s"${fileNameWithLine.valueOrAbort} - $owner")

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
