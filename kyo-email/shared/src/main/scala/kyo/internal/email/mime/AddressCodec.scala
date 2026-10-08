package kyo.internal.email.mime

import kyo.*
import kyo.internal.mime.Grammar

/** Address lists (`From`, `To`, `Cc`, `Reply-To`, ...: RFC 5322 section 3.4 with the obsolete forms of section 4.4, and UTF-8 in local
  * parts and domains as RFC 6532 section 3.2 allows), reading and writing.
  *
  * The list is split at `,` outside quoted strings, comments, domain literals and angle brackets. A `:` outside angle brackets, before
  * any `<` or `@` of its element, opens a group, whose name is dropped and whose members run to `;`, so groups flatten into their
  * members. An element of only white space and comments is skipped.
  *
  * A mailbox in angle brackets takes the phrase before them as its display name, each run of white space and comments between its words
  * read as one SP, quoted strings unquoted, then encoded words decoded as in unstructured text. Inside the brackets a route (`@a,@b:`) is
  * dropped. A mailbox without a display name that is followed by a comment takes the comment as its name, in both the bare and the
  * bracketed form (the RFC 822 form `ada@example.com (Ada Lovelace)`); every other comment is dropped. The address is the local part,
  * `@` and domain with white space and comments removed; a quoted local part keeps its quotes, and a domain literal its brackets.
  *
  * An element that is no mailbox is kept as an address of its own text, white space and comments at either end removed, as the model
  * keeps a value no strict parser accepts. An unquoted comma inside a display name (`Lovelace, Ada <ada@x>`) splits the name off as an
  * element holding neither `@` nor `<`; such elements are joined back with `, ` to the display name of a following bracketed mailbox.
  * Reading never fails.
  */
private[kyo] object AddressCodec:

    def parse(field: HeaderCodec.Field): Chunk[Email.Address] =
        val text   = field.value
        val tokens = tokenize(text)
        val out    = ChunkBuilder.init[Email.Address]
        // Elements holding neither `@` nor `<` that a following bracketed mailbox may take as the start of its display name, each as the
        // indexes of its first and last significant tokens.
        var names         = Chunk.empty[(Int, Int)]
        def flush(): Unit =
            names.foreach((first, last) => discard(out.addOne(Email.Address(unreadable(text, tokens, first, last)))))
            names = Chunk.empty
        def element(from: Int, until: Int): Unit =
            val significant = Chunk.from((from until until).filter(i => tokens(i).significant))
            for
                first <- significant.headMaybe
                last  <- significant.lastMaybe
            do
                mailbox(text, tokens, from, until) match
                    case Present((address, name, bracketed)) =>
                        if bracketed && names.nonEmpty then
                            val parts = names.map((f, l) => phraseText(text, tokens, f, l + 1)) ++ name.filter(_.nonEmpty).toChunk
                            names = Chunk.empty
                            discard(out.addOne(Email.Address(address, Present(parts.mkString(", ")))))
                        else
                            flush()
                            discard(out.addOne(Email.Address(address, name)))
                        end if
                    case Absent =>
                        if (from until until).exists(i => tokens(i).isSpecial(text, '@') || tokens(i).isSpecial(text, '<')) then
                            flush()
                            discard(out.addOne(Email.Address(unreadable(text, tokens, first, last))))
                        else names = names.append((first, last))
            end for
        end element
        @scala.annotation.tailrec
        def loop(i: Int, start: Int, bracketed: Boolean, addressSeen: Boolean): Unit =
            if i == tokens.size then
                element(start, i)
                flush()
            else
                val token = tokens(i)
                if token.kind != Kind.Special then loop(i + 1, start, bracketed, addressSeen)
                else
                    text.charAt(token.start) match
                        case '<' if !bracketed => loop(i + 1, start, true, true)
                        case '>' if bracketed  => loop(i + 1, start, false, addressSeen)
                        case '@' if !bracketed => loop(i + 1, start, false, true)
                        case ',' if !bracketed =>
                            element(start, i)
                            loop(i + 1, i + 1, false, false)
                        case ';' if !bracketed =>
                            element(start, i)
                            flush()
                            loop(i + 1, i + 1, false, false)
                        case ':' if !bracketed && !addressSeen =>
                            flush()
                            loop(i + 1, i + 1, false, false)
                        case _ => loop(i + 1, start, bracketed, addressSeen)
                end if
        loop(0, 0, false, false)
        out.result()
    end parse

    /** The addresses as units for `HeaderCodec.fold`: each an `addr-spec`, or its name as a phrase then ` <addr-spec>`, with `,` after
      * every address but the last. An address that [[isAddrSpec]] rejects fails with `InvalidAddress`, so no address can end a header line.
      */
    def render(addresses: Chunk[Email.Address]): Result[HeaderCodec.WriteFailure, Chunk[String]] =
        Maybe.fromOption(addresses.find(a => !isAddrSpec(a.address))) match
            case Present(invalid) => Result.fail(HeaderCodec.WriteFailure.InvalidAddress(invalid.address))
            case Absent           =>
                val units = ChunkBuilder.init[String]
                addresses.zipWithIndex.foreach { (address, i) =>
                    val own =
                        address.name match
                            case Absent      => Chunk(address.address)
                            case Present("") => Chunk("\"\"", " <" + address.address + ">")
                            case Present(n)  => HeaderCodec.phraseUnits(n) ++ Chunk(" <" + address.address + ">")
                    own.zipWithIndex.foreach { (unit, j) =>
                        val lead = if i > 0 && j == 0 then " " + unit else unit
                        discard(units.addOne(if i < addresses.size - 1 && j == own.size - 1 then lead + "," else lead))
                    }
                }
                Result.succeed(units.result())
    end render

    /** RFC 5322 section 3.4.1's `addr-spec` with no obsolete form and no white space or comment: a dot-atom or a quoted string, `@`, and a
      * dot-atom or a domain literal. `atext`, `qtext` and `dtext` also take every character from U+00A0 (RFC 6532 section 3.2), and no
      * control character is accepted.
      */
    def isAddrSpec(address: String): Boolean =
        val localEnd = if address.startsWith("\"") then quotedEnd(address) else dotAtomEnd(address, 0)
        if localEnd <= 0 || localEnd >= address.length || address.charAt(localEnd) != '@' then false
        else
            val domain = localEnd + 1
            if domain < address.length && address.charAt(domain) == '[' then
                address.length - domain >= 2 && address.charAt(address.length - 1) == ']' &&
                (domain + 1 until address.length - 1).forall(i => isDtext(address.charAt(i)))
            else dotAtomEnd(address, domain) == address.length
            end if
        end if
    end isAddrSpec

    private enum Kind derives CanEqual:
        case Space, Comment, Atom, Quoted, Literal, Special

    final private case class Token(kind: Kind, start: Int, end: Int) derives CanEqual:
        def significant: Boolean                      = kind != Kind.Space && kind != Kind.Comment
        def isWord: Boolean                           = kind == Kind.Atom || kind == Kind.Quoted || kind == Kind.Literal
        def isSpecial(text: String, c: Char): Boolean = kind == Kind.Special && text.charAt(start) == c
    end Token

    // RFC 5322 section 3.2.3's specials, with `[`, `(` and `"` opening their own tokens and `\` outside a quoted string a special of its
    // own.
    private val Specials = "()<>[]:;@\\,.\""

    private def tokenize(text: String): Chunk[Token] =
        val tokens = ChunkBuilder.init[Token]
        @scala.annotation.tailrec
        def loop(at: Int): Unit =
            if at < text.length then
                val (kind, end) =
                    text.charAt(at) match
                        case ' ' | '\t' =>
                            var end = at + 1
                            while end < text.length && (text.charAt(end) == ' ' || text.charAt(end) == '\t') do end += 1
                            (Kind.Space, end)
                        case '(' =>
                            val close = Grammar.commentEnd(text, at, text.length)
                            (Kind.Comment, if close < 0 then text.length else close)
                        case '"' => (Kind.Quoted, Grammar.quotedString(text, at)._2)
                        case '[' =>
                            val close = text.indexOf(']', at + 1)
                            (Kind.Literal, if close < 0 then text.length else close + 1)
                        case c if Specials.indexOf(c.toInt) >= 0 => (Kind.Special, at + 1)
                        case _                                   =>
                            var end = at + 1
                            while end < text.length && isAtomChar(text.charAt(end)) do end += 1
                            (Kind.Atom, end)
                discard(tokens.addOne(Token(kind, at, end)))
                loop(end)
        loop(0)
        tokens.result()
    end tokenize

    private def isAtomChar(c: Char): Boolean = c != ' ' && c != '\t' && Specials.indexOf(c.toInt) < 0

    // The address, the name, and whether the address was in angle brackets; Absent when the element is no mailbox.
    private def mailbox(text: String, tokens: Chunk[Token], from: Int, until: Int): Maybe[(String, Maybe[String], Boolean)] =
        Maybe.fromOption((from until until).find(i => tokens(i).isSpecial(text, '<'))) match
            case Present(open) =>
                Maybe.fromOption((open + 1 until until).find(i => tokens(i).isSpecial(text, '>'))) match
                    case Present(close)
                        if (close + 1 until until).forall(i => !tokens(i).significant) &&
                            (from until open).forall(i => !tokens(i).isSpecial(text, '>')) =>
                        val firstInside = Maybe.fromOption((open + 1 until close).find(i => tokens(i).significant))
                        val start       =
                            firstInside match
                                case Present(first) if tokens(first).isSpecial(text, '@') =>
                                    Maybe.fromOption((first until close).find(i => tokens(i).isSpecial(text, ':'))).map(_ + 1)
                                case Present(_) | Absent => Present(open + 1)
                        start.flatMap(addrSpec(text, tokens, _, close, colonAllowed = true)).map { (address, _) =>
                            val name =
                                if (from until open).exists(i => tokens(i).significant) then Present(phraseText(text, tokens, from, open))
                                else commentAfter(text, tokens, close + 1, until)
                            (address, name, true)
                        }
                    case Present(_) | Absent => Absent
                end match
            case Absent =>
                addrSpec(text, tokens, from, until, colonAllowed = false).map { (address, last) =>
                    (address, commentAfter(text, tokens, last + 1, until), false)
                }
        end match
    end mailbox

    // Words separated by `.`, one `@` with something on each side, and no two words meeting across removed white space or comments. With
    // the address, the index of its last token.
    private def addrSpec(text: String, tokens: Chunk[Token], from: Int, until: Int, colonAllowed: Boolean): Maybe[(String, Int)] =
        val indices = Chunk.from((from until until).filter(i => tokens(i).significant))
        val parts   = indices.map(tokens(_))
        val ats     = parts.count(_.isSpecial(text, '@'))
        val shapeOk =
            parts.forall { t =>
                (t.isWord && (t.kind != Kind.Literal || closedLiteral(text, t))) ||
                t.isSpecial(text, '.') || t.isSpecial(text, '@') || (colonAllowed && t.isSpecial(text, ':'))
            }
        val wordsApart = parts.size < 2 || (1 until parts.size).forall(i => !(parts(i - 1).isWord && parts(i).isWord))
        val atInside   = parts.headMaybe.exists(!_.isSpecial(text, '@')) && parts.lastMaybe.exists(!_.isSpecial(text, '@'))
        indices.lastMaybe.filter(_ => ats == 1 && shapeOk && wordsApart && atInside).map { last =>
            val out = new java.lang.StringBuilder(parts.foldLeft(0)((size, t) => size + t.end - t.start))
            parts.foreach(t => discard(out.append(text, t.start, t.end)))
            (out.toString, last)
        }
    end addrSpec

    private def closedLiteral(text: String, token: Token): Boolean = token.end - token.start >= 2 && text.charAt(token.end - 1) == ']'

    // The phrase's words, each run of white space and comments between two of them read as one SP, quoted strings unquoted, decoded.
    private def phraseText(text: String, tokens: Chunk[Token], from: Int, until: Int): String =
        val out = new java.lang.StringBuilder(tokens(until - 1).end - tokens(from).start)
        @scala.annotation.tailrec
        def loop(i: Int, gap: Boolean): Unit =
            if i < until then
                val token = tokens(i)
                if !token.significant then loop(i + 1, out.length > 0)
                else
                    if gap then discard(out.append(' '))
                    if token.kind == Kind.Quoted then discard(out.append(Grammar.quotedString(text, token.start)._1))
                    else discard(out.append(text, token.start, token.end))
                    loop(i + 1, false)
                end if
        loop(from, false)
        HeaderCodec.decodeUnstructured(out)
    end phraseText

    // The first comment in the range: its text with nested parentheses kept and quoted pairs resolved, decoded.
    private def commentAfter(text: String, tokens: Chunk[Token], from: Int, until: Int): Maybe[String] =
        Maybe.fromOption((from until until).find(i => tokens(i).kind == Kind.Comment)).map { i =>
            val token = tokens(i)
            val end   = if text.charAt(token.end - 1) == ')' && token.end - token.start >= 2 then token.end - 1 else token.end
            val out   = new java.lang.StringBuilder(end - token.start)
            @scala.annotation.tailrec
            def loop(at: Int): Unit =
                if at < end then
                    if text.charAt(at) == '\\' && at + 1 < end then
                        discard(out.append(text.charAt(at + 1)))
                        loop(at + 2)
                    else
                        discard(out.append(text.charAt(at)))
                        loop(at + 1)
            loop(token.start + 1)
            HeaderCodec.decodeUnstructured(out)
        }

    private def unreadable(text: String, tokens: Chunk[Token], first: Int, last: Int): String =
        text.substring(tokens(first).start, tokens(last).end)

    // The end of a dot-atom starting at `from`, or -1 when no dot-atom starts there.
    private def dotAtomEnd(text: String, from: Int): Int =
        @scala.annotation.tailrec
        def loop(at: Int, atomStart: Int): Int =
            if at < text.length && isAtext(text.charAt(at)) then loop(at + 1, atomStart)
            else if at == atomStart then -1
            else if at < text.length && text.charAt(at) == '.' then loop(at + 1, at + 1)
            else at
        loop(from, from)
    end dotAtomEnd

    // The end of the quoted string opening `text`, or -1 when it is not closed or holds a character `qtext` and `quoted-pair` do not allow.
    private def quotedEnd(text: String): Int =
        @scala.annotation.tailrec
        def loop(at: Int): Int =
            if at >= text.length then -1
            else
                val c = text.charAt(at)
                if c == '"' then at + 1
                else if c == '\\' then
                    if at + 1 < text.length && isQuotable(text.charAt(at + 1)) then loop(at + 2) else -1
                else if isQtext(c) then loop(at + 1)
                else -1
                end if
        loop(1)
    end quotedEnd

    // RFC 6532's UTF8-non-ascii starts at U+0080, but U+0080 to U+009F are the C1 control characters, which no address accepts.
    private inline val NonAsciiTextStart = 0xa0

    private def isAtext(c: Char): Boolean = HeaderCodec.isAtext(c) || c >= NonAsciiTextStart

    private def isQtext(c: Char): Boolean =
        (c >= ' ' && c <= '~' && c != '"' && c != '\\') || c == '\t' || c >= NonAsciiTextStart

    private def isQuotable(c: Char): Boolean = (c >= ' ' && c <= '~') || c == '\t' || c >= NonAsciiTextStart

    private def isDtext(c: Char): Boolean = (c >= '!' && c <= 'Z') || (c >= '^' && c <= '~') || c >= NonAsciiTextStart

end AddressCodec
