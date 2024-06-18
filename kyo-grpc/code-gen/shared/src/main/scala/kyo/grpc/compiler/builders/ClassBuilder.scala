package kyo.grpc.compiler.builders

import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final case class ClassBuilder(
    override val id: String,
    override val annotations: Vector[Doc] = Vector.empty,
    override val mods: Vector[Doc] = Vector.empty,
    // TODO: The first parent could be a constructor.
    override val parents: Vector[Doc] = Vector.empty,
    override val body: Doc = Doc.empty
) extends TemplateBuilder {

    override protected def keyword: String = "class"

    def appendAnnotations(annotations: Seq[String]): ClassBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): ClassBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendParents(parents: Seq[String]): ClassBuilder =
        copy(parents = this.parents ++ parents.map(Doc.text))

    def setBody(body: PrinterEndo): ClassBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): ClassBuilder =
        copy(body = body)

    // TODO: Add type parameters.
    override protected def preamble: Doc = super.preamble
}
