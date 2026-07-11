package kyo.stats.machine

import kyo.*

/** A tiny read-only text view over a borrowed byte span, with the field lookups the Linux decoders need.
  * Decodes the borrowed bytes to a `String` once per call (the borrowed span never escapes); the parse
  * helpers below are total and allocation-modest, sized to the small proc files they read.
  */
final private[machine] case class Text(value: String):
    def lines: Iterator[String] = value.linesIterator

    /** The whitespace tokens of the first line starting with `prefix`, or Absent. */
    def lineFields(prefix: String): Maybe[IndexedSeq[String]] =
        lines.find(_.startsWith(prefix)) match
            case Some(l) => Present(l.trim.split("\\s+").toIndexedSeq)
            case None    => Absent

    /** The first whitespace token of the whole content, or Absent. */
    def firstToken: Maybe[String] =
        value.trim.split("\\s+").headOption.filter(_.nonEmpty) match
            case Some(t) => Present(t)
            case None    => Absent

    /** All whitespace tokens of the whole content. */
    def tokens: IndexedSeq[String] = value.trim.split("\\s+").toIndexedSeq

    /** The kB integer value of a `Name:  <n> kB` meminfo line, or Absent. */
    def kbField(name: String): Maybe[Long] =
        lines.find(_.startsWith(name)) match
            case Some(l) =>
                l.stripPrefix(name).trim.split("\\s+").headOption.flatMap(_.toLongOption) match
                    case Some(v) => Present(v)
                    case None    => Absent
            case None => Absent

    /** The value after `key=` in a PSI line's fields (e.g. `avg10=1.23`), or Absent. */
    def psiField(fields: IndexedSeq[String], key: String): Maybe[String] =
        fields.find(_.startsWith(key)).map(_.stripPrefix(key)) match
            case Some(v) => Present(v)
            case None    => Absent

    /** The whitespace fields of the first line starting with `prefix`, or Absent. */
    def lineStartingWith(prefix: String): Maybe[IndexedSeq[String]] = lineFields(prefix)
end Text

private[machine] object Text:
    def fromSpan(bytes: Span[Byte], len: Int): Text =
        Text(new String(Span.toArrayUnsafe(bytes), 0, len, java.nio.charset.StandardCharsets.US_ASCII))
end Text
