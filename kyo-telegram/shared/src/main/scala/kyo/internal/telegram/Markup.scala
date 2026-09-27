package kyo.internal.telegram

import kyo.*

/** Renders a [[kyo.TelegramMarkup]] tree to Telegram's MarkdownV2 or HTML, escaping every piece of text
  * by the rules of Bot API "Formatting options".
  */
private[kyo] object Markup:

    // --- MarkdownV2 ---

    /** Outside code and link targets, each of these is a marker unless preceded by `\`. */
    private val MarkdownSpecial = "_*[]()~`>#+-=|{}.!\\"

    def markdownV2(markup: TelegramMarkup): String =
        val out = new StringBuilder
        // MarkdownV2 reads `__` greedily as an underline marker, so an italic or underline marker written
        // directly after another would merge with it. The documentation's remedy is an empty bold entity
        // between them, which `marker` inserts.
        var lastWasUnderscoreMarker                = false
        def put(s: String): Unit                   = discard(out.append(s))
        def text(s: String, special: String): Unit =
            s.foreach { c =>
                if special.indexOf(c) >= 0 then put("\\")
                discard(out.append(c))
            }
            if s.nonEmpty then lastWasUnderscoreMarker = false
        end text
        def marker(m: String): Unit =
            if m.startsWith("_") && lastWasUnderscoreMarker then put("**")
            put(m)
            lastWasUnderscoreMarker = m.endsWith("_")
        end marker
        def raw(s: String): Unit =
            put(s)
            lastWasUnderscoreMarker = false
        def wrap(m: String, content: TelegramMarkup): Unit =
            marker(m)
            loop(content)
            marker(m)
        end wrap
        def link(content: TelegramMarkup, url: String): Unit =
            raw("[")
            loop(content)
            raw("](")
            text(url, ")\\")
            raw(")")
        end link
        def loop(m: TelegramMarkup): Unit =
            m match
                case TelegramMarkup.Text(value)            => text(value, MarkdownSpecial)
                case TelegramMarkup.Bold(content)          => wrap("*", content)
                case TelegramMarkup.Italic(content)        => wrap("_", content)
                case TelegramMarkup.Underline(content)     => wrap("__", content)
                case TelegramMarkup.Strikethrough(content) => wrap("~", content)
                case TelegramMarkup.Spoiler(content)       => wrap("||", content)
                case TelegramMarkup.Code(value)            =>
                    raw("`")
                    text(value, "`\\")
                    raw("`")
                case TelegramMarkup.Pre(value, language) =>
                    raw("```")
                    language.foreach(l => text(languageOf(l), "`\\"))
                    raw("\n")
                    text(value, "`\\")
                    raw("\n```")
                case TelegramMarkup.Link(content, url)     => link(content, url.value)
                case TelegramMarkup.Mention(content, user) => link(content, s"tg://user?id=${user.value}")
                case TelegramMarkup.Blockquote(content)    =>
                    val inner = markdownV2(content)
                    raw(inner.split("\n", -1).map(">" + _).mkString("\n"))
                case TelegramMarkup.Concat(parts) => parts.foreach(loop)
        loop(markup)
        out.toString
    end markdownV2

    // --- HTML ---

    def html(markup: TelegramMarkup): String =
        val out                   = new StringBuilder
        def put(s: String): Unit  = discard(out.append(s))
        def text(s: String): Unit =
            s.foreach {
                case '<' => put("&lt;")
                case '>' => put("&gt;")
                case '&' => put("&amp;")
                case c   => discard(out.append(c))
            }
        def attribute(s: String): Unit =
            s.foreach {
                case '"' => put("&quot;")
                case c   => text(c.toString)
            }
        def wrap(tag: String, content: TelegramMarkup): Unit =
            put(s"<$tag>")
            loop(content)
            put(s"</$tag>")
        end wrap
        def link(content: TelegramMarkup, url: String): Unit =
            put("<a href=\"")
            attribute(url)
            put("\">")
            loop(content)
            put("</a>")
        end link
        def loop(m: TelegramMarkup): Unit =
            m match
                case TelegramMarkup.Text(value)            => text(value)
                case TelegramMarkup.Bold(content)          => wrap("b", content)
                case TelegramMarkup.Italic(content)        => wrap("i", content)
                case TelegramMarkup.Underline(content)     => wrap("u", content)
                case TelegramMarkup.Strikethrough(content) => wrap("s", content)
                case TelegramMarkup.Spoiler(content)       => wrap("tg-spoiler", content)
                case TelegramMarkup.Blockquote(content)    => wrap("blockquote", content)
                case TelegramMarkup.Code(value)            =>
                    put("<code>")
                    text(value)
                    put("</code>")
                case TelegramMarkup.Pre(value, language) =>
                    language match
                        case Present(l) =>
                            put("<pre><code class=\"language-")
                            attribute(languageOf(l))
                            put("\">")
                            text(value)
                            put("</code></pre>")
                        case Absent =>
                            put("<pre>")
                            text(value)
                            put("</pre>")
                case TelegramMarkup.Link(content, url)     => link(content, url.value)
                case TelegramMarkup.Mention(content, user) => link(content, s"tg://user?id=${user.value}")
                case TelegramMarkup.Concat(parts)          => parts.foreach(loop)
        loop(markup)
        out.toString
    end html

    /** A code block's language ends at its first white space, which would otherwise end the language line. */
    private def languageOf(language: String): String =
        language.takeWhile(c => c != ' ' && c != '\t' && c != '\n' && c != '\r')

end Markup
