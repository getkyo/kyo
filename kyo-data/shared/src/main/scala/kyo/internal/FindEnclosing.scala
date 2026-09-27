package kyo.internal

import kyo.Maybe
import scala.annotation.tailrec
import scala.quoted.*

private[kyo] object FindEnclosing:

    private val testFileSuffixes = Set("Test.scala", "Spec.scala")

    // A published `-tests` module carries its conformance suite in main sources, so another project can extend it. Anchored to the
    // module's own `<platform>/src/main/` so a checkout that happens to sit under a `-tests` directory exempts nothing.
    private val conformanceSuiteSources = """-tests/[^/]+/src/main/""".r

    def isInternal(using Quotes): Boolean =
        val pos      = quotes.reflect.Position.ofMacroExpansion
        val fileName = pos.sourceFile.name
        if fileName.isEmpty || fileName.startsWith("<") then false // synthetic file, like scala-cli/repl
        else
            val path     = pos.sourceFile.path.replace('\\', '/')
            val excluded =
                ((path.contains("src/test/") || path.contains("src_managed/test/") ||
                    conformanceSuiteSources.findFirstIn(path).isDefined) &&
                    testFileSuffixes.exists(
                        fileName.endsWith
                    )) || fileName.endsWith("Bench.scala")
            apply(sym => sym.fullName.startsWith("kyo.") && !excluded).nonEmpty
        end if
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
