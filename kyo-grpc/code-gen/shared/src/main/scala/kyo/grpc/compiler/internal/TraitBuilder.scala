package kyo.grpc.compiler.internal

import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final private[compiler] case class TraitBuilder(
    override val id: String,
    override val annotations: Vector[Doc] = Vector.empty,
    override val mods: Vector[Doc] = Vector.empty,
    typeParameters: Vector[String] = Vector.empty,
    override val parents: Vector[Doc] = Vector.empty,
    override val body: Doc = Doc.empty
) extends TemplateBuilder {

    override protected def keyword: String = "trait"

    def appendAnnotations(annotations: Seq[String]): TraitBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): TraitBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendTypeParameters(params: Seq[String]): TraitBuilder =
        copy(typeParameters = typeParameters ++ params)

    def appendParents(parents: Seq[String]): TraitBuilder =
        copy(parents = this.parents ++ parents.map(Doc.text))

    def setBody(body: PrinterEndo): TraitBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): TraitBuilder =
        copy(body = body)

    override protected def preamble: Doc = {
        val typeParametersDocs = typeParameters.map(Doc.text)

        when(typeParametersDocs.nonEmpty)(
            "[" +: spreadList(typeParametersDocs) :+ "]"
        )
    }
}
