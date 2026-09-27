package kyo.internal

import kyo.Maybe
import scala.annotation.tailrec
import scala.quoted.*

private[kyo] object FindEnclosing:

    private val testFileSuffixes = Set("Test.scala", "Spec.scala")

    // A published `-conformance` module carries its suites in main sources so an implementation outside kyo can extend
    // them. Anchored to the module's own `<platform>/src/main/` so a checkout that sits under some `-conformance`
    // directory exempts nothing.
    private val conformanceSources = """-conformance/[^/]+/src/main/""".r

    def isInternal(using Quotes): Boolean =
        val pos      = quotes.reflect.Position.ofMacroExpansion
        val fileName = pos.sourceFile.name
        if fileName.isEmpty || fileName.startsWith("<") then false // synthetic file, like scala-cli/repl
        else
            val excluded = isExempt(pos.sourceFile.path.replace('\\', '/'), fileName)
            apply(sym => sym.fullName.startsWith("kyo.") && !excluded).nonEmpty
        end if
    end isInternal

    /** Whether a source file may derive frames and dynamic tags inside package `kyo`: test files, conformance suites and benchmarks. */
    def isExempt(path: String, fileName: String): Boolean =
        val testFile = testFileSuffixes.exists(fileName.endsWith)
        val testTree = path.contains("src/test/") || path.contains("src_managed/test/") || conformanceSources.findFirstIn(path).isDefined
        (testTree && testFile) || fileName.endsWith("Bench.scala")
    end isExempt

    def apply(using Quotes)(predicate: quotes.reflect.Symbol => Boolean): Maybe[quotes.reflect.Symbol] =
        import quotes.reflect.*

        @tailrec def findSymbol(sym: Symbol): Maybe[Symbol] =
            if predicate(sym) then Maybe(sym)
            else if sym.isNoSymbol then Maybe.empty
            else findSymbol(sym.owner)

        findSymbol(Symbol.spliceOwner)
    end apply
end FindEnclosing
