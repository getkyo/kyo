package kyo.internal

import kyo.Maybe
import scala.annotation.tailrec
import scala.quoted.*

private[kyo] object FindEnclosing:

    private val allowKyoFileSuffixes = Set("Test.scala", "Spec.scala", "Bench.scala")

    def isInternal(using Quotes): Boolean =
        val pos      = quotes.reflect.Position.ofMacroExpansion
        val fileName = pos.sourceFile.name
        apply(sym => sym.fullName.startsWith("kyo") && !allowKyoFileSuffixes.exists(fileName.endsWith)).nonEmpty
    end isInternal

    def apply(using Quotes)(predicate: quotes.reflect.Symbol => Boolean): Maybe[quotes.reflect.Symbol] =
        import quotes.reflect.*

        @tailrec def findSymbol(sym: Symbol): Maybe[Symbol] =
            if predicate(sym) then Maybe(sym)
            else if sym.isNoSymbol then Maybe.empty
            else findSymbol(sym.owner)

        findSymbol(Symbol.spliceOwner)
    end apply
end FindEnclosing
