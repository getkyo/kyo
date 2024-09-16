package kyo.grpc.compiler

import org.typelevel.paiges.Doc
import org.typelevel.paiges.Docx
import org.typelevel.paiges.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo

package object builders {

    def when(condition: Boolean)(doc: => Doc): Doc =
        if (condition) doc else Doc.empty

    def hardList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.hardLine, docs)

    def stackList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.char(',') + Doc.line, docs)

    def spreadList(docs: Iterable[Doc]): Doc =
        Doc.intercalate(Doc.text(", "), docs)

    def extendsList(docs: Iterable[Doc]): Doc =
        when(docs.nonEmpty) {
            (Doc.text("extends ") + Doc.intercalate(Doc.line + Doc.text("with "), docs)).hanging(INDENT * 2)
        }

    def typedName(parameter: (String, String)): Doc = {
        val (name, tpe) = parameter
        name +: (Doc.text(": ") + Doc.text(tpe))
    }

    def parameter(parameter: Parameter): Doc =
        typedName((parameter.name, parameter.typeName)) +
            parameter.default.fold(Doc.empty)(default => Doc.text(" = ") + Doc.text(default))

    def parameterLists(parameterss: Vector[Seq[Parameter]]): Doc =
        when(parameterss.nonEmpty) {
            val parametersDocs = parameterss
                .map(_.map(parameter))
                .map(stackList)
                .map(_.tightBracketBy(Doc.char('('), Doc.char(')')))
            Doc.cat(parametersDocs)
        }

    def printToDoc(f: PrinterEndo): Doc =
        Docx.literal(f(new FunctionalPrinter()).result())
}
