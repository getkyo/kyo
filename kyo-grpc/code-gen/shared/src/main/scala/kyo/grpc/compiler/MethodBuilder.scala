package kyo.grpc.compiler

import org.typelevel.paiges.{Doc, Docx}
import org.typelevel.paiges.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import Builders.*

final case class MethodBuilder(
    mods: Seq[String],
    name: String,
    typeParameters: Vector[String] = Vector.empty,
    parameterLists: Vector[Seq[(String, String)]] = Vector.empty,
    implicitParameters: Vector[(String, String)] = Vector.empty,
    returnType: Option[String] = None,
    body: Option[PrinterEndo] = None
) {

    def appendTypeParameters(params: Seq[String]): MethodBuilder =
        copy(typeParameters = typeParameters ++ params)

    def appendParameterList(params: Seq[(String, String)]): MethodBuilder =
        copy(parameterLists = parameterLists :+ params)

    def appendImplicitParameters(params: Seq[(String, String)]): MethodBuilder =
        copy(implicitParameters = implicitParameters ++ params)

    def setReturnType(returnType: String): MethodBuilder =
        copy(returnType = Some(returnType))

    def setBody(body: PrinterEndo): MethodBuilder =
        copy(body = Some(body))

    def print(fp: FunctionalPrinter): FunctionalPrinter = {
        val modPrefixDoc = when(mods.nonEmpty)(Doc.spread(mods.map(Doc.text)) + Doc.space)

        val defNameDoc = Doc.text("def ") :+ name

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
            (modPrefixDoc +
                defNameDoc +
                typeParametersDoc +
                allParameterListsDoc +
                returnTypeDoc).grouped

        val doc = body.fold(signatureDoc) { f =>
            val bodyDoc = printToDoc(f)
            val bracketedBodyDoc = {
                if (bodyDoc.containsHardLine) Doc.text(" {") + Doc.hardLine + bodyDoc.indent(INDENT) + Doc.hardLine + Doc.char('}')
                else bodyDoc.hanging(INDENT)
            }
            (signatureDoc :+ " =") + bracketedBodyDoc
        }.grouped

        fp.addDoc(doc)
    }
}
