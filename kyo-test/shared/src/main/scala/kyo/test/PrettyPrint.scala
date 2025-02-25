package kyo.test

import kyo.*
import kyo.Ansi.*

/** PrettyPrint will attempt to render a Scala value as the syntax used to create that value. This makes it easier to copy-paste from values
  * printed to the console during tests back into runnable code.
  */
private[kyo] object PrettyPrint extends PrettyPrintVersionSpecific:
    private val maxListLength = 10
    def apply(any: Any): String =
        any match
            case chunk: Chunk[?] =>
                prettyPrintIterator(chunk.iterator, chunk.size, "Chunk")
            case array: Array[?] =>
                prettyPrintIterator(array.iterator, array.length, "Array")

            case Some(a) => s"Some(${PrettyPrint(a)})"
            case None    => s"None"
            case Nil     => "Nil"

            case set: Set[?] =>
                prettyPrintIterator(set.iterator, set.size, className(set))

            case iterable: Seq[?] =>
                prettyPrintIterator(iterable.iterator, iterable.size, className(iterable))

            case map: Map[?, ?] =>
                val body = map.map { case (key, value) => s"${PrettyPrint(key)} -> ${PrettyPrint(value)}" }
                s"""Map(
${indent(body.mkString(",\n"))}
)"""

            case product: Product =>
                val name    = product.productPrefix
                val labels0 = labels(product)
                val body = labels0
                    .zip(product.productIterator)
                    .map { case (key, value) =>
                        s"${(key + " =").grey} ${PrettyPrint(value)}"
                    }
                    .toList
                    .mkString(",\n")
                val isMultiline  = body.split("\n").length > 1
                val indentedBody = indent(body, if isMultiline then 2 else 0)
                val spacer       = if isMultiline then "\n" else ""
                s"""$name($spacer$indentedBody$spacer)"""

            case string: String =>
                val surround = if string.split("\n").length > 1 then """""" else "\""
                string.replace("\"", "\\\"").mkString(surround, "", surround)

            case char: Char =>
                s"'${char.toString}'"

            case null => "<null>"

            case other => other.toString

    private def prettyPrintIterator(iterator: Iterator[?], length: Int, className: String): String =
        val suffix = if length > maxListLength then
            s" + ${length - maxListLength} more)"
        else
            ")"
        iterator.take(maxListLength).map(PrettyPrint.apply).mkString(s"${className}(", ", ", suffix)
    end prettyPrintIterator

    private def indent(string: String, n: Int = 2): String =
        string.split("\n").map((" " * n) + _).mkString("\n")

    private def className(any: Any): String = any match
        case _: List[?] => "List"
        case other      => other.getClass.getSimpleName
end PrettyPrint
