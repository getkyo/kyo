package kyo.grpc.compiler.builders

import kyo.grpc.compiler.INDENT
import org.typelevel.paiges.Doc
import org.typelevel.paiges.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final case class MethodBuilder(
    id: String,
    annotations: Vector[Doc] = Vector.empty,
    mods: Vector[Doc] = Vector.empty,
    typeParameters: Vector[String] = Vector.empty,
    parameterLists: Vector[Seq[(String, String)]] = Vector.empty,
    implicitParameters: Vector[(String, String)] = Vector.empty,
    returnType: Option[String] = None,
    body: Option[Doc] = None
) {

    def appendAnnotations(annotations: Seq[String]): MethodBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): MethodBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendTypeParameters(params: Seq[String]): MethodBuilder =
        copy(typeParameters = typeParameters ++ params)

    def appendParameterList(params: Seq[(String, String)]): MethodBuilder =
        copy(parameterLists = parameterLists :+ params)

    def appendImplicitParameters(params: Seq[(String, String)]): MethodBuilder =
        copy(implicitParameters = implicitParameters ++ params)

    def setReturnType(returnType: String): MethodBuilder =
        copy(returnType = Some(returnType))

    def setBody(body: PrinterEndo): MethodBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): MethodBuilder =
        copy(body = Some(body))

    def result: Doc = {
        // Has trailing whitespace if non-empty.
        val annotationsDoc =
            if (annotations.isEmpty) Doc.empty
            else hardList(annotations) + Doc.hardLine

        val modPrefixDoc = when(mods.nonEmpty)(Doc.spread(mods) + Doc.space)

        val defNameDoc = Doc.text("def ") :+ id

        val typeParametersDocs = typeParameters.map(Doc.text)

        val typeParametersDoc = when(typeParametersDocs.nonEmpty)(
            "[" +: spreadList(typeParametersDocs) :+ "]"
        )

        val parameterListsDoc = when(parameterLists.nonEmpty) {
            val parametersDocs = parameterLists
                .map(_.map(typedName))
                .map(stackList)
                .map(_.tightBracketBy(Doc.char('('), Doc.char(')')))
            Doc.cat(parametersDocs)
        }

        val implicitParametersDoc = when(implicitParameters.nonEmpty) {
            stackList(implicitParameters.map(typedName))
                .tightBracketRightBy(Doc.text("(implicit"), Doc.char(')'))
        }

        val allParameterListsDoc = (parameterListsDoc + implicitParametersDoc).regrouped

        val returnTypeDoc = returnType.fold(Doc.empty) { s =>
            Doc.text(": ") :+ s
        }

        val signatureDoc =
            (annotationsDoc +
                modPrefixDoc +
                defNameDoc +
                typeParametersDoc +
                allParameterListsDoc +
                returnTypeDoc).grouped

        body.fold(signatureDoc) { bodyDoc =>
            val bracketedBodyDoc = {
                if (bodyDoc.containsHardLine) Doc.text(" {") + Doc.hardLine + bodyDoc.indent(INDENT) + Doc.hardLine + Doc.char('}')
                else bodyDoc.hanging(INDENT)
            }
            (signatureDoc :+ " =") + bracketedBodyDoc
        }.grouped
    }
}
