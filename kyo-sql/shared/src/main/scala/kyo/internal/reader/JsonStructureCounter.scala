package kyo.internal.reader

import kyo.Chunk

/** Hand-rolled element / entry walkers for JSON arrays and objects.
  *
  * Used by both the PG `jsonb`/`json` and MySQL `JSON` row-reader paths to honour the [[kyo.Codec.Reader.mapStart]] and
  * [[kyo.Codec.Reader.arrayStart]] contracts, which return the structural element count. [[kyo.internal.JsonReader]] returns `-1` for
  * non-empty collections, these helpers walk the JSON text once to surface a real count without re-parsing the body.
  * [[splitArrayElements]] uses the same walk to hand back each top-level element's own text, which is what a column holding an array of
  * JSON documents decodes to.
  *
  * The walkers track nesting depth and string state so commas and colons inside nested arrays/objects or inside string literals are not
  * counted as top-level separators. Escape handling honours the JSON grammar (`\"`, `\\`, etc. consume the next character without ending a
  * string). Whitespace (` `, `\t`, `\n`, `\r`) before the first element is skipped.
  *
  * These walkers assume well-formed JSON. Adversarial inputs (unbalanced brackets, trailing commas) may produce inaccurate counts;
  * downstream JSON parsing surfaces the underlying syntax error separately.
  */
object JsonStructureCounter:

    /** Count top-level entries (key/value pairs) in a JSON object body.
      *
      * Walks the text after the opening `{`, counts the colons at depth 0 outside string literals. Each entry contributes exactly one
      * top-level colon (`"key":value`), so the colon count equals the entry count.
      *
      * @param json
      *   trimmed JSON text starting with `{`
      * @return
      *   number of top-level entries; 0 if the object is empty (`{}` or `{ }`)
      */
    def countObjectEntries(json: String): Int =
        val s     = json.trim
        val len   = s.length
        var i     = 1 // skip '{'
        var count = 0
        var depth = 0
        var inStr = false
        while i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r') do
            i += 1
        while i < len do
            val c = s.charAt(i)
            if inStr then
                if c == '\\' then i += 1
                else if c == '"' then inStr = false
            else
                c match
                    case '"'       => inStr = true
                    case '{' | '[' => depth += 1
                    case '}' | ']' =>
                        if depth == 0 then i = len - 1 // break, hit closing '}'
                        else depth -= 1
                    case ':' if depth == 0 => count += 1
                    case _                 => ()
            end if
            i += 1
        end while
        count
    end countObjectEntries

    /** Count top-level elements in a JSON array body.
      *
      * Walks the text after the opening `[`, counts top-level commas at depth 0 outside string literals and adds one for the first element.
      * Empty arrays (`[]` or `[ ]`) return 0.
      *
      * @param json
      *   trimmed JSON text starting with `[`
      * @return
      *   number of top-level elements
      */
    def countArrayElements(json: String): Int =
        val s     = json.trim
        val len   = s.length
        var i     = 1 // skip '['
        var count = 0
        var depth = 0
        var inStr = false
        while i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r') do
            i += 1
        if i < len && s.charAt(i) != ']' then count = 1
        while i < len do
            val c = s.charAt(i)
            if inStr then
                if c == '\\' then i += 1
                else if c == '"' then inStr = false
            else
                c match
                    case '"'       => inStr = true
                    case '{' | '[' => depth += 1
                    case '}' | ']' =>
                        if depth == 0 then i = len - 1 // break, hit closing ']'
                        else depth -= 1
                    case ',' if depth == 0 => count += 1
                    case _                 => ()
            end if
            i += 1
        end while
        count
    end countArrayElements

    /** Splits a JSON array body into the text of each top-level element.
      *
      * Walks the text after the opening `[` and cuts at every top-level comma, so an element that is itself an object or array comes back
      * whole. Each element is trimmed of surrounding whitespace; a nested element's own text is returned verbatim.
      *
      * @param json
      *   trimmed JSON text starting with `[`
      * @return
      *   one entry per top-level element, in order; empty for `[]`
      */
    def splitArrayElements(json: String): Chunk[String] =
        val s     = json.trim
        val len   = s.length
        val out   = Chunk.newBuilder[String]
        var i     = 1 // skip '['
        var start = 1
        var close = -1
        var depth = 0
        var inStr = false
        while i < len && close < 0 do
            val c = s.charAt(i)
            if inStr then
                if c == '\\' then i += 1
                else if c == '"' then inStr = false
            else
                c match
                    case '"'       => inStr = true
                    case '{' | '[' => depth += 1
                    case '}' | ']' =>
                        if depth == 0 then close = i
                        else depth -= 1
                    case ',' if depth == 0 =>
                        out += s.substring(start, i).trim
                        start = i + 1
                    case _ => ()
            end if
            i += 1
        end while
        val last = if close >= 0 then s.substring(start, close).trim else s.substring(start.min(len)).trim
        if last.nonEmpty then out += last
        out.result()
    end splitArrayElements

end JsonStructureCounter
