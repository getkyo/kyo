package kyo.grpc.compiler.internal

import org.typelevel.paiges.Doc

private[compiler] trait TemplateBuilder {

    def annotations: Iterable[Doc]
    def mods: Iterable[Doc]
    def id: String
    def parents: Iterable[Doc]
    def body: Doc

    protected def keyword: String

    /** The part between the id and the template.
      *
      * It must contain leading whitespace and no trailing whitespace.
      */
    protected def preamble: Doc = Doc.empty

    def result: Doc = {
        // Has trailing whitespace if non-empty.
        val annotationsDoc =
            if (annotations.isEmpty) Doc.empty
            else hardList(annotations) + Doc.hardLine

        val modsDoc = when(mods.nonEmpty)(Doc.spread(mods) + Doc.space)

        val idDoc = Doc.text(s"$keyword ") :+ id

        // Has leading whitespace.
        val parentsDoc = extendsList(parents)

        val headerDoc =
            (annotationsDoc +
                modsDoc +
                idDoc +
                preamble +
                parentsDoc).grouped

        if (body.isEmpty) headerDoc
        else (headerDoc :+ " {") + Doc.hardLine + body.indent(INDENT) + (Doc.hardLine :+ "}")
    }
}
