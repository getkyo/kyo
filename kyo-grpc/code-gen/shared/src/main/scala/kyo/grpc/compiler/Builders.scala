package kyo.grpc.compiler

import org.typelevel.paiges.{Doc, Docx}
import org.typelevel.paiges.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

object Builders {

    def when(condition: Boolean)(doc: => Doc): Doc =
        if (condition) doc else Doc.empty

    def stackList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.char(',') + Doc.line, docs)

    def spreadList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.text(", "), docs)

    def extendsList(docs: Iterable[Doc]): Doc =
        when(docs.nonEmpty) {
            (Doc.text("extends ") + Doc.intercalate(Doc.line + Doc.text("with "), docs)).hanging(INDENT * 2).grouped
        }

    def typedName(parameter: (String, String)): Doc = {
        val (name, tpe) = parameter
        name +: (Doc.text(": ") + Doc.text(tpe))
    }

    def printToDoc(f: PrinterEndo): Doc =
        Docx.literal(f(new FunctionalPrinter()).result())
}
