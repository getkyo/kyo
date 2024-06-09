package kyo.grpc.compiler

import org.typelevel.paiges.Doc
import org.typelevel.paiges.ExtendedSyntax.*
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import Builders.*

final case class ObjectBuilder(
    mods: Seq[String],
    name: String,
    parents: Vector[String] = Vector.empty,
    body: Option[PrinterEndo] = None
) {

    def appendParents(parents: Seq[String]): ObjectBuilder =
        copy(parents = this.parents ++ parents)

    def setBody(body: PrinterEndo): ObjectBuilder =
        copy(body = Some(body))

    def print(fp: FunctionalPrinter): FunctionalPrinter = {
        val modPrefixDoc = when(mods.nonEmpty)(Doc.spread(mods.map(Doc.text)) + Doc.space)

        val objectNameDoc = Doc.text("object ") :+ name

        val parentsDoc = extendsList(parents.map(Doc.text))

        val signatureDoc =
            (modPrefixDoc +
                objectNameDoc +
                parentsDoc).grouped

        val doc = body.fold(signatureDoc) { f =>
            (signatureDoc :+ " {") + Doc.hardLine + printToDoc(f).indent(INDENT) + (Doc.hardLine :+ "}")
        }

        fp.addDoc(doc)
    }
}
