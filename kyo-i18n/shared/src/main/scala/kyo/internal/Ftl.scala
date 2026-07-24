package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** One segment of a parsed message pattern.
  *
  * A message value is a flat sequence of parts: literal text interleaved with variable references. This is
  * the interpolation-only subset of Fluent the parser supports. A `Select` case (plural / gender branching)
  * is intentionally absent: when a count-bearing string first needs it, it becomes a new `Part` case handled
  * here, not a change to any message that already parses.
  */
private[kyo] enum Part derives CanEqual:
    case Literal(text: String)
    case VarRef(name: String)

/** A single parsed message: its pattern as a sequence of [[Part]]s.
  *
  * [[render]] resolves variable references against already-stringified arguments. A reference with no matching
  * argument renders as `{$name}` so a missing interpolation is visible rather than silently blank.
  */
final private[kyo] case class Message(parts: Chunk[Part]):
    def render(args: Map[String, String]): String =
        val sb = new StringBuilder
        parts.foreach {
            case Part.Literal(text) => sb.append(text)
            case Part.VarRef(name)  => sb.append(args.getOrElse(name, s"{$$$name}"))
        }
        sb.toString
    end render
end Message

/** A parsed translation bundle: message id to [[Message]]. One bundle holds one locale's translations. */
final private[kyo] case class Bundle(messages: Map[String, Message]):
    def get(key: String): Maybe[Message] = messages.get(key).fold(Absent: Maybe[Message])(Present(_))

private[kyo] object Bundle:
    def parse(ftl: String): Bundle = Bundle(FtlParser.parse(ftl))

/** Parser for the flat, interpolation-only subset of Fluent (`.ftl`) this module supports.
  *
  * It reads `id = pattern` messages, joins indented continuation lines, and skips comments (`#`) and blank
  * lines. Constructs outside the subset (col-0 `-term` definitions, `.attribute` lines) end the current
  * message and are ignored. Within a pattern only `{ $name }` placeholders are recognized; any other `{...}`
  * is kept as literal text, so an unparseable brace degrades to visible output rather than an error.
  */
private[kyo] object FtlParser:

    private val KeyLine = "^([A-Za-z][A-Za-z0-9_-]*)[ \\t]*=[ \\t]*(.*)$".r

    final private case class St(key: Maybe[String], buf: String, done: Map[String, String])

    def parse(ftl: String): Map[String, Message] =
        // A value-less message (blank after `=`) is treated as absent, so lookup renders the miss marker
        // rather than an empty string, matching Fluent's handling of a message with no value.
        def flush(st: St): Map[String, String] =
            st.key match
                case Present(k) if st.buf.trim.nonEmpty => st.done.updated(k, st.buf)
                case _                                  => st.done
        val end = ftl.linesIterator.foldLeft(St(Absent, "", Map.empty)) { (st, line) =>
            if line.isBlank || line.trim.startsWith("#") then St(Absent, "", flush(st))
            else
                line match
                    case KeyLine(key, value) => St(Present(key), value, flush(st))
                    case _ =>
                        st.key match
                            case Present(_) if line.startsWith(" ") || line.startsWith("\t") =>
                                st.copy(buf = s"${st.buf} ${line.trim}")
                            case _ => St(Absent, "", flush(st))
        }
        flush(end).view.mapValues(v => Message(parsePattern(v))).toMap
    end parse

    private def parsePattern(raw: String): Chunk[Part] =
        @tailrec
        def loop(i: Int, acc: Vector[Part]): Vector[Part] =
            if i >= raw.length then acc
            else
                val open = raw.indexOf('{', i)
                if open < 0 then acc :+ Part.Literal(raw.substring(i))
                else
                    val close = raw.indexOf('}', open)
                    if close < 0 then acc :+ Part.Literal(raw.substring(i))
                    else
                        val inner = raw.substring(open + 1, close).trim
                        if inner.startsWith("$") && isIdent(inner.drop(1)) then
                            val lit = if open > i then Vector(Part.Literal(raw.substring(i, open))) else Vector.empty
                            loop(close + 1, (acc ++ lit) :+ Part.VarRef(inner.drop(1)))
                        else
                            loop(open + 1, acc :+ Part.Literal(raw.substring(i, open + 1)))
                        end if
                    end if
                end if
        Chunk.from(loop(0, Vector.empty))
    end parsePattern

    private def isIdent(s: String): Boolean =
        s.nonEmpty && s.head.isLetter && s.forall(c => c.isLetterOrDigit || c == '_' || c == '-')
end FtlParser
