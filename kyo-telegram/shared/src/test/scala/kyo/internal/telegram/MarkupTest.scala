package kyo.internal.telegram

import kyo.*
import kyo.TelegramMarkup.*

class MarkupTest extends kyo.test.Test[Any]:

    private val specials = "_*[]()~`>#+-=|{}.!\\"

    // --- MarkdownV2 ---

    "every character MarkdownV2 reserves is escaped in text, and nothing else is" in {
        assert(Markup.markdownV2(Text(specials + "abc é")) == specials.flatMap(c => s"\\$c") + "abc é")
    }

    "each style writes its marker around its content" in {
        assert(Chunk(Bold(Text("b")), Italic(Text("i")), Underline(Text("u")), Strikethrough(Text("s")), Spoiler(Text("p"))).map(
            Markup.markdownV2
        ) == Chunk("*b*", "_i_", "__u__", "~s~", "||p||"))
    }

    "an underscore marker written directly after another is separated by an empty bold entity" in {
        assert(Markup.markdownV2(Italic(Underline(Text("x")))) == "_**__x__**_")
        assert(Markup.markdownV2(of(Italic(Text("a")), Italic(Text("b")))) == "_a_**_b_")
        assert(Markup.markdownV2(of(Italic(Text("a")), Text("-"), Italic(Text("b")))) == "_a_\\-_b_")
    }

    "code escapes only the backquote and the backslash" in {
        assert(Markup.markdownV2(Code("a`b\\c*d")) == "`a\\`b\\\\c*d`")
    }

    "a code block writes its language up to the first white space" in {
        assert(Markup.markdownV2(Pre("x = 1", Present("scala 3"))) == "```scala\nx = 1\n```")
        assert(Markup.markdownV2(Pre("`", Absent)) == "```\n\\`\n```")
    }

    "a link escapes its target's closing parenthesis and backslash, and a mention links to the user" in {
        assert(Markup.markdownV2(Link(Text("docs."), TelegramUrl("https://e.com/a_(b)\\"))) == "[docs\\.](https://e.com/a_(b\\)\\\\)")
        assert(Markup.markdownV2(Mention(Text("Ann"), TelegramId.UserId(5L))) == "[Ann](tg://user?id=5)")
    }

    "a blockquote quotes each of its lines" in {
        assert(Markup.markdownV2(Blockquote(Text("one\ntwo."))) == ">one\n>two\\.")
    }

    // --- HTML ---

    "HTML escapes <, > and & in text and nothing else" in {
        assert(Markup.html(Text("<a> & b_*")) == "&lt;a&gt; &amp; b_*")
    }

    "each style writes its tag" in {
        assert(Chunk(
            Bold(Text("b")),
            Italic(Text("i")),
            Underline(Text("u")),
            Strikethrough(Text("s")),
            Spoiler(Text("p")),
            Blockquote(Text("q"))
        )
            .map(Markup.html) ==
            Chunk("<b>b</b>", "<i>i</i>", "<u>u</u>", "<s>s</s>", "<tg-spoiler>p</tg-spoiler>", "<blockquote>q</blockquote>"))
    }

    "code, code blocks and links escape their text and their attributes" in {
        assert(Markup.html(Code("<x>")) == "<code>&lt;x&gt;</code>")
        assert(Markup.html(Pre("a<b", Present("c\"s"))) == "<pre><code class=\"language-c&quot;s\">a&lt;b</code></pre>")
        assert(Markup.html(Pre("a<b", Absent)) == "<pre>a&lt;b</pre>")
        assert(Markup.html(Link(Text("x"), TelegramUrl("https://e.com/?a=1&b=\"2\""))) ==
            "<a href=\"https://e.com/?a=1&amp;b=&quot;2&quot;\">x</a>")
        assert(Markup.html(Mention(Bold(Text("Ann")), TelegramId.UserId(5L))) == "<a href=\"tg://user?id=5\"><b>Ann</b></a>")
    }

end MarkupTest
