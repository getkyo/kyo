package kyo.grpc.compiler.internal

import kyo.grpc.compiler.internal
import org.typelevel.paiges.Doc
import org.typelevel.paiges.internal.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final private[compiler] case class ClassBuilder(
    override val id: String,
    override val annotations: Vector[Doc] = Vector.empty,
    override val mods: Vector[Doc] = Vector.empty,
    typeParameters: Vector[String] = Vector.empty,
    parameterLists: Vector[Seq[Parameter]] = Vector.empty,
    implicitParameters: Vector[Parameter] = Vector.empty,
    override val parents: Vector[Doc] = Vector.empty,
    override val body: Doc = Doc.empty
) extends TemplateBuilder {

    override protected def keyword: String = "class"

    def appendAnnotations(annotations: Seq[String]): ClassBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): ClassBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendTypeParameters(params: Seq[String]): ClassBuilder =
        copy(typeParameters = typeParameters ++ params)

    def appendParameterList(params: Seq[Parameter]): ClassBuilder =
        copy(parameterLists = parameterLists :+ params)

    def appendImplicitParameters(params: Seq[Parameter]): ClassBuilder =
        copy(implicitParameters = implicitParameters ++ params)

    def appendParents(parents: Seq[String]): ClassBuilder =
        copy(parents = this.parents ++ parents.map(Doc.text))

    def setBody(body: PrinterEndo): ClassBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): ClassBuilder =
        copy(body = body)

    override protected def preamble: Doc = {
        val typeParametersDocs = typeParameters.map(Doc.text)

        val typeParametersDoc = when(typeParametersDocs.nonEmpty)(
            "[" +: spreadList(typeParametersDocs) :+ "]"
        )

        val parameterListsDoc = internal.parameterLists(parameterLists)

        val implicitParametersDoc = when(implicitParameters.nonEmpty) {
            stackList(implicitParameters.map(parameter))
                .tightBracketRightBy(Doc.text("(implicit"), Doc.char(')'))
        }

        val allParameterListsDoc = (parameterListsDoc + implicitParametersDoc).regrouped

        typeParametersDoc + allParameterListsDoc
    }
}
