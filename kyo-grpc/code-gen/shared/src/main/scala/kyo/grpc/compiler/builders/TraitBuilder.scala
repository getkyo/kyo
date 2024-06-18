package kyo.grpc.compiler.builders

import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final case class TraitBuilder(
    override val id: String,
    override val annotations: Vector[Doc] = Vector.empty,
    override val mods: Vector[Doc] = Vector.empty,
    override val parents: Vector[Doc] = Vector.empty,
    override val body: Doc = Doc.empty
) extends TemplateBuilder {

    override protected def keyword: String = "trait"

    def appendAnnotations(annotations: Seq[String]): TraitBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): TraitBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendParents(parents: Seq[String]): TraitBuilder =
        copy(parents = this.parents ++ parents.map(Doc.text))

    def setBody(body: PrinterEndo): TraitBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): TraitBuilder =
        copy(body = body)

    // TODO: Add type parameters.
    override protected def preamble: Doc = super.preamble
}
