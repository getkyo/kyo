package kyo.grpc.compiler.internal

import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter.PrinterEndo

final private[compiler] case class ObjectBuilder(
    override val id: String,
    override val annotations: Vector[Doc] = Vector.empty,
    override val mods: Vector[Doc] = Vector.empty,
    override val parents: Vector[Doc] = Vector.empty,
    override val body: Doc = Doc.empty
) extends TemplateBuilder {

    override protected def keyword: String = "object"

    def appendAnnotations(annotations: Seq[String]): ObjectBuilder =
        copy(annotations = this.annotations ++ annotations.map(Doc.text))

    def appendMods(mods: Seq[String]): ObjectBuilder =
        copy(mods = this.mods ++ mods.map(Doc.text))

    def appendParents(parents: Seq[String]): ObjectBuilder =
        copy(parents = this.parents ++ parents.map(Doc.text))

    def setBody(body: PrinterEndo): ObjectBuilder =
        setBody(printToDoc(body))

    def setBody(body: Doc): ObjectBuilder =
        copy(body = body)
}
