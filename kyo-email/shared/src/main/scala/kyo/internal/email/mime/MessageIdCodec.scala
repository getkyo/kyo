package kyo.internal.email.mime

import kyo.*
import kyo.Email.MessageId
import kyo.internal.mime.Grammar

/** The message ids of `Message-ID`, `In-Reply-To` and `References` (RFC 5322 sections 3.6.4 and 4.5.4), reading and writing.
  *
  * Each `<...>` outside a quoted string and a comment is one id: its content with white space and comments removed, since the obsolete
  * syntax allows CFWS inside an id and section 4.5.4 says it is not part of it. Quoted strings inside the brackets are kept as written. An
  * id that [[Email.MessageId]] rejects, `<>` among them, is skipped, and an unclosed `<` runs to the end of the value. Phrases between
  * ids are ignored (section 4.5.4). A value with no `<` at all is one id when, CFWS removed, it is a single word, which is how some
  * mailers write a bare id. Reading never fails.
  */
private[kyo] object MessageIdCodec:

    def parse(value: String): Chunk[MessageId] =
        val ids = ChunkBuilder.init[MessageId]
        @scala.annotation.tailrec
        def loop(at: Int, opened: Boolean): Boolean =
            if at >= value.length then opened
            else
                value.charAt(at) match
                    case '"' => loop(Grammar.quotedString(value, at)._2, opened)
                    case '(' => loop(commentOrEnd(value, at), opened)
                    case '<' =>
                        val close = closeOf(value, at + 1)
                        MessageId.read(content(value, at + 1, close)).foreach(id => discard(ids.addOne(id)))
                        loop(close + 1, true)
                    case _ => loop(at + 1, opened)
        if loop(0, false) then ids.result() else bare(value)
    end parse

    /** Each id in brackets, every one after the first starting with SP, as units for `HeaderCodec.fold`. */
    def render(ids: Chunk[MessageId]): Chunk[String] =
        Chunk.from(ids.zipWithIndex.map((id, i) => (if i == 0 then "<" else " <") + id.value + ">"))

    // The `>` closing an id whose content starts at `from`, quoted strings and comments skipped, or the end of the value.
    private def closeOf(value: String, from: Int): Int =
        @scala.annotation.tailrec
        def loop(at: Int): Int =
            if at >= value.length then value.length
            else
                value.charAt(at) match
                    case '>' => at
                    case '"' => loop(Grammar.quotedString(value, at)._2)
                    case '(' => loop(commentOrEnd(value, at))
                    case _   => loop(at + 1)
        loop(from)
    end closeOf

    private def content(value: String, from: Int, until: Int): String =
        val out = new java.lang.StringBuilder(until - from)
        @scala.annotation.tailrec
        def loop(at: Int): Unit =
            if at < until then
                value.charAt(at) match
                    case ' ' | '\t' => loop(at + 1)
                    case '('        => loop(math.min(commentOrEnd(value, at), until))
                    case '"'        =>
                        val end = math.min(Grammar.quotedString(value, at)._2, until)
                        discard(out.append(value, at, end))
                        loop(end)
                    case c =>
                        discard(out.append(c))
                        loop(at + 1)
        loop(from)
        out.toString
    end content

    private def bare(value: String): Chunk[MessageId] =
        val start = Grammar.skipCfws(value, 0)
        var end   = start
        while end < value.length && value.charAt(end) != ' ' && value.charAt(end) != '\t' && value.charAt(end) != '(' do end += 1
        if end == start || Grammar.skipCfws(value, end) != value.length then Chunk.empty
        else MessageId.read(value.substring(start, end)).toChunk
    end bare

    private def commentOrEnd(value: String, open: Int): Int =
        val close = Grammar.commentEnd(value, open, value.length)
        if close < 0 then value.length else close

end MessageIdCodec
