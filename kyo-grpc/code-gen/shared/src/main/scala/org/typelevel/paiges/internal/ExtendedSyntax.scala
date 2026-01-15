package org.typelevel.paiges.internal

import org.typelevel.paiges.*
import org.typelevel.paiges.Doc.*

// Workaround for https://github.com/typelevel/paiges/issues/628.
object ExtendedSyntax {

    implicit class DocOps(val doc: Doc) extends AnyVal {

        /** The width of `left` must be shorter or equal to this doc.
          */
        def bracketIfMultiline(left: Doc, right: Doc, indent: Int = 2): Doc = {
            // Long means not completely flat. orEmpty alone is not enough as a doc with hard lines is considered flat.
            // Flat needs to be broken down into minimal vs single-line.
            if (doc.containsHardLine) left + Doc.hardLine + doc.indent(indent) + Doc.hardLine + right
            else ((Docx.orEmpty(left + Doc.hardLine) + doc).nested(indent) + Docx.orEmpty(Doc.hardLine + right)).grouped
        }

        def tightBracketLeftBy(left: Doc, right: Doc, indent: Int = 2): Doc =
            Concat(left, Concat(Concat(Doc.lineBreak, doc).nested(indent), Concat(Doc.line, right)).grouped)

        def tightBracketRightBy(left: Doc, right: Doc, indent: Int = 2): Doc =
            Concat(left, Concat(Concat(Doc.line, doc).nested(indent), Concat(Doc.lineBreak, right)).grouped)

        def ungrouped: Doc =
            doc match {
                case Union(_, b)                           => b
                case FlatAlt(a, b)                         => FlatAlt(a.ungrouped, Doc.defer(b.ungrouped))
                case Concat(a, b)                          => Concat(a.ungrouped, b.ungrouped)
                case Nest(i, d)                            => Nest(i, d.ungrouped)
                case d @ LazyDoc(_)                        => Doc.defer(d.evaluated.ungrouped)
                case Align(d)                              => Align(d.ungrouped)
                case ZeroWidth(_) | Text(_) | Empty | Line => doc
            }

        def regrouped: Doc =
            doc.ungrouped.grouped

        def containsHardLine: Boolean =
            doc match {
                case Line                           => true
                case ZeroWidth(_) | Text(_) | Empty => false
                case Nest(_, d)                     => d.containsHardLine
                case Align(d)                       => d.containsHardLine
                case FlatAlt(_, b)                  => b.containsHardLine
                case Union(a, _)                    => a.containsHardLine
                case Concat(a, b)                   => a.containsHardLine || b.containsHardLine
                case d @ LazyDoc(_)                 => d.evaluated.containsHardLine
            }

        // This is unsafe as it violates the invariants of FlatAlt, but it seems to be OK for it is used.
        def hangingUnsafe(i: Int, sep: Doc = Doc.space): Doc = {
            FlatAlt(Doc.hardLine + Doc.spaces(i) + doc.aligned, sep + doc.flatten).grouped
        }
    }
}
