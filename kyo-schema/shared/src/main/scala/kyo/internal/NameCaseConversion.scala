package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Pure case-convention engine for schema wire-name derivation.
  *
  * Converts a Scala identifier to a wire name under one of the five [[Schema.NameCase]]
  * conventions. Tokenization is the acronym-aware two-pass lookahead used by the modern
  * serde ecosystem: an uppercase run is one word, but its LAST uppercase starts the next
  * word when a lowercase follows (`XMLHttp` to `XML`,`Http`), and a lower-or-digit to
  * upper transition begins a new word (`userId` to `user`,`Id`). Separators (`_`, `-`)
  * and letter-to-digit boundaries also split. Each convention then recases and rejoins
  * the words. The function is total: every identifier converts, and a duplicate result
  * across two names is a downstream collision, not a conversion failure. An acronym whose
  * original casing must survive a camel or Pascal target is served by an explicit
  * `variantNames` / `alias` override, not by this engine.
  *
  * {{{
  * val snake = NameCaseConversion.convert(Schema.NameCase.SnakeCase)
  * snake("HTTPServer") // "http_server"
  * snake("DList")      // "d_list"
  * }}}
  */
private[kyo] object NameCaseConversion:

    /** Returns the conversion function for a convention. */
    def convert(nameCase: Schema.NameCase): String => String =
        nameCase match
            case Schema.NameCase.CamelCase          => name => joinCamel(tokenize(name))
            case Schema.NameCase.SnakeCase          => name => tokenize(name).map(_.toLowerCase).mkString("_")
            case Schema.NameCase.KebabCase          => name => tokenize(name).map(_.toLowerCase).mkString("-")
            case Schema.NameCase.PascalCase         => name => tokenize(name).map(capitalize).mkString
            case Schema.NameCase.ScreamingSnakeCase => name => tokenize(name).map(_.toUpperCase).mkString("_")
    end convert

    /** Splits an identifier into words by the acronym-aware two-pass lookahead. A break is
      * taken at a `_`/`-` separator (the separator char is dropped) and BEFORE an index
      * where `breaksBefore` reports a word boundary from the surrounding char triple.
      */
    private def tokenize(name: String): Chunk[String] =
        @tailrec
        def loop(idx: Int, wordStart: Int, acc: Chunk[String]): Chunk[String] =
            if idx >= name.length then
                if wordStart < name.length then acc :+ name.substring(wordStart) else acc
            else
                val c = name.charAt(idx)
                if c == '_' || c == '-' then
                    val next = if wordStart < idx then acc :+ name.substring(wordStart, idx) else acc
                    loop(idx + 1, idx + 1, next)
                else if idx > wordStart && breaksBefore(name, idx) then
                    loop(idx + 1, idx, acc :+ name.substring(wordStart, idx))
                else
                    loop(idx + 1, wordStart, acc)
                end if
            end if
        end loop
        loop(0, 0, Chunk.empty)
    end tokenize

    /** A word break occurs BEFORE index `idx` (idx > 0) when, examining the prior char,
      * the char at `idx`, and the char after it:
      *   - acronym-run boundary (PASS 1): prev upper, cur upper, next lower
      *     (`([A-Z]+)([A-Z][a-z])`, so `XMLHttp` breaks before the final `H`);
      *   - camel hump (PASS 2): prev lower or digit, cur upper (`userId` breaks before `I`);
      *   - letter-to-digit or digit-to-letter transition (either direction).
      * An uppercase run with no trailing lowercase stays one word (`XML`, `HTTP`).
      */
    private def breaksBefore(name: String, idx: Int): Boolean =
        val prev = name.charAt(idx - 1)
        val cur  = name.charAt(idx)
        val acronymBoundary =
            prev.isUpper && cur.isUpper && idx + 1 < name.length && name.charAt(idx + 1).isLower
        val camelHump = (prev.isLower || prev.isDigit) && cur.isUpper
        val digitEdge = (prev.isLetter && cur.isDigit) || (prev.isDigit && cur.isLetter)
        acronymBoundary || camelHump || digitEdge
    end breaksBefore

    private def joinCamel(words: Chunk[String]): String =
        if words.isEmpty then ""
        else words.head.toLowerCase + words.tail.map(capitalize).mkString

    private def capitalize(word: String): String =
        if word.isEmpty then word
        else word.head.toUpper.toString + word.tail.toLowerCase

end NameCaseConversion
