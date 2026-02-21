package org.typelevel.paiges.internal

import org.typelevel.paiges.*
import org.typelevel.paiges.Doc.*
import scala.annotation.tailrec

// Workaround for https://github.com/typelevel/paiges/issues/628.
object Docx {

    import ExtendedSyntax.*

    /** The width of `left` must be shorter or equal to this doc.
      */
    def bracketIfMultiline(left: Doc, doc: Doc, right: Doc, indent: Int = 2): Doc =
        doc.bracketIfMultiline(left, right, indent)

    /** Unsafe as it violates the invariant of FlatAlt `width(default) <= width(whenFlat)`.
      */
    private[paiges] def orEmpty(doc: Doc): Doc = {
        if (doc.isEmpty) doc
        else FlatAlt(doc, Doc.empty)
    }

    def literal(str: String): Doc = {
        def tx(i: Int, j: Int): Doc =
            if (i == j) Empty
            else if (i == j - 1) Doc.char(str.charAt(i))
            else Text(str.substring(i, j))

        // parse the string right-to-left, splitting at newlines.
        // this ensures that our concatenations are right-associated.
        @tailrec def parse(i: Int, limit: Int, doc: Doc): Doc =
            if (i < 0) {
                val next = tx(0, limit)
                if (doc.isEmpty) next else next + doc
            } else
                str.charAt(i) match {
                    case '\n' => parse(i - 1, i, hardLine + (tx(i + 1, limit) + doc))
                    case _    => parse(i - 1, limit, doc)
                }

        parse(str.length - 1, str.length, Empty)
    }
}
