package kyo.ai

import kyo.*
import kyo.ai.Context.*
import kyo.ai.Tokenizer.Encoding
import kyo.ai.Tokenizer.internal.Tiktoken

class TokenizerTest extends kyo.test.Test[Any]:

    private def o200kTokens(text: String): Int  = Tiktoken(Encoding.O200kBase).countText(text)
    private def cl100kTokens(text: String): Int = Tiktoken(Encoding.Cl100kBase).countText(text)

    /** A pure config value (no System read) for `LLM.run`: `countMessages` calls the tokenizer through
      * the trait's abstract signature, so its declared row is always the wide `LLM & Async &
      * Abort[HttpException | AIGenException]`, discharged here even though the offline tokenizers under
      * test never actually suspend an `LLM` op.
      */
    private def testConfig: Config = Config.Anthropic.default

    "o200k parity vs the reference corpus, token-for-token (ASCII prose + Scala code + JSON args)" in {
        assert(o200kTokens("The quick brown fox.") == 5)
        assert(o200kTokens("def add(x: Int, y: Int): Int = x + y") == 15)
        assert(o200kTokens("""{"location":"Paris","unit":"celsius"}""") == 10)
    }

    "o200k parity on CJK text (the cross-platform-skew-prone case)" in {
        assert(o200kTokens("東京都に住んでいます") == 6)
        assert(o200kTokens("Tokyo東京123") == 3)
    }

    "o200k parity on NFD / combining-mark text" in {
        val nfd = "é" // 'e' followed by a standalone combining acute accent (two code points)
        val nfc = "é"  // the precomposed 'e' with acute accent (one code point)
        assert(o200kTokens(nfd) == 2)
        assert(o200kTokens(nfc) == 1)
    }

    "cl100k parity vs the reference corpus (alternate encoding)" in {
        assert(cl100kTokens("1234567") == 3, "digit runs cap at three on both encodings, so '1234567' groups '123'/'456'/'7'")
        assert(cl100kTokens("camelCase") == 2, "cl100k's flat letter run still reaches the same reference count for this row")
    }

    "digit grouping: o200k groups runs of up to 3 digits" in {
        assert(o200kTokens("1234567") == 3)
    }

    "contraction suffix is its own piece" in {
        assert(cl100kTokens("don't") == 2, "cl100k splits the contraction into 'don' + \"'t\"")
        assert(o200kTokens("don't") == 1, "o200k folds the contraction onto the letter run as one piece")
        assert(o200kTokens("we'll") == 2)
    }

    "case-transition split inside a letter run" in {
        assert(o200kTokens("camelCase") == 2)
        assert(o200kTokens("HTTPServer") == 2)
    }

    "trailing-whitespace lookahead keeps the final space with the next piece" in {
        assert(o200kTokens("a  b") == 3)
        assert(o200kTokens("a \n b") == 3)
    }

    "empty-string and single-char inputs" in {
        assert(o200kTokens("") == 0)
        assert(o200kTokens("a") == 1)
    }

    "batched count returns one count per input, in order" in {
        val tiktoken = Tiktoken(Encoding.O200kBase)
        val result   = tiktoken.count(Chunk("alpha", "", "gamma")).eval
        assert(result == Chunk(tiktoken.countText("alpha"), tiktoken.countText(""), tiktoken.countText("gamma")))
        assert(result == Chunk(1, 0, 1))
    }

    "byte-level BPE falls back to byte tokens for a rare code point" in {
        val rare = new String(Character.toChars(0x10fffd)) // a supplementary private-use code point
        assert(o200kTokens(rare) == 4, "no vocabulary token spans the whole code point, so each UTF-8 byte is its own token")
    }

    "countMessages adds the per-message envelope to a text message" in {
        val tokenizer = Tokenizer.tiktoken(Encoding.O200kBase)
        LLM.run(testConfig)(Tokenizer.internal.countMessages(tokenizer, Chunk(SystemMessage("hello")))).map { result =>
            assert(result == Chunk(o200kTokens("hello") + 4))
        }
    }

    "image message count includes the image surcharge, never zero" in {
        val tokenizer = Tokenizer.tiktoken(Encoding.O200kBase)
        val image     = Image.fromBase64("aGVsbG8=")
        val withImage = UserMessage("", Present(image))
        val textOnly  = UserMessage("", Absent)
        for
            stampedImg <- LLM.run(testConfig)(Tokenizer.internal.countMessages(tokenizer, Chunk(withImage)))
            stampedTxt <- LLM.run(testConfig)(Tokenizer.internal.countMessages(tokenizer, Chunk(textOnly)))
        yield
            assert(stampedImg == Chunk(0 + 4 + 2000))
            assert(stampedImg.head >= 2000)
            assert(stampedTxt == Chunk(0 + 4))
        end for
    }

    "envelope is offline-only: added for a raw tokenizer, NOT for an envelope-inclusive one" in {
        val inclusiveTotal = 42

        val offline: Tokenizer = new Tokenizer:
            def count(texts: Chunk[String])(using Frame): Chunk[Int] < Any =
                texts.map(o200kTokens)

        val inclusive: Tokenizer = new Tokenizer:
            def count(texts: Chunk[String])(using Frame): Chunk[Int] < Any =
                texts.map(_ => inclusiveTotal)
            override private[kyo] def includesMessageEnvelope: Boolean = true

        val text  = SystemMessage("hello")
        val image = UserMessage("", Present(Image.fromBase64("aGVsbG8=")))

        for
            offlineText    <- LLM.run(testConfig)(Tokenizer.internal.countMessages(offline, Chunk(text)))
            inclusiveText  <- LLM.run(testConfig)(Tokenizer.internal.countMessages(inclusive, Chunk(text)))
            offlineImage   <- LLM.run(testConfig)(Tokenizer.internal.countMessages(offline, Chunk(image)))
            inclusiveImage <- LLM.run(testConfig)(Tokenizer.internal.countMessages(inclusive, Chunk(image)))
        yield
            assert(offlineText == Chunk(o200kTokens("hello") + 4))
            assert(inclusiveText == Chunk(inclusiveTotal))
            assert(offlineImage == Chunk(0 + 4 + 2000))
            assert(inclusiveImage == Chunk(inclusiveTotal + 2000))
        end for
    }

    "countMessages counts assistant tool-call arguments as billed bytes" in {
        val tokenizer = Tokenizer.tiktoken(Encoding.O200kBase)
        val call      = Call(CallId("r1"), "f", """{"x":1}""")
        val message   = AssistantMessage("ok", Chunk(call))
        LLM.run(testConfig)(Tokenizer.internal.countMessages(tokenizer, Chunk(message))).map { result =>
            assert(result == Chunk(o200kTokens("""ok {"x":1}""") + 4))
        }
    }

    "offline count is pure: no Async/Abort capability is exercised" in {
        // The concrete Tiktoken.count returns `Chunk[Int] < Any` (kyo.kernel.<'s S parameter is
        // contravariant, so this is a valid override of the trait's wider declared row): `.eval`
        // needs no LLM/Async/Abort handler at all, which is itself the proof no such capability
        // is ever exercised on the offline path, not merely a convention to remember.
        val tiktoken = Tiktoken(Encoding.O200kBase)
        val result   = tiktoken.count(Chunk("hello", "world")).eval
        assert(result == Chunk(o200kTokens("hello"), o200kTokens("world")))
    }

    "Encoding is CanEqual and selects distinct tables" in {
        assert(Encoding.O200kBase == Encoding.O200kBase)
        assert(Encoding.O200kBase != Encoding.Cl100kBase)
        assert(o200kTokens("camelCase") == 2)
        assert(cl100kTokens("camelCase") == 2)
        assert(o200kTokens("東京都に住んでいます") != cl100kTokens("東京都に住んでいます"))
    }

    "no regex is constructed anywhere in the tiktoken source".onlyJvm in {
        val pattern = "(\\.r\\b)|(Regex\\()|(\\.matches\\()".r
        tiktokenSources.foreach { (name, text) =>
            assert(pattern.findFirstIn(text).isEmpty, s"$name unexpectedly constructs a regex: ${pattern.findFirstIn(text)}")
        }
    }

    "the rank table is an immutable embedded value, no AllowUnsafe".onlyJvm in {
        val varPattern = "(?m)^\\s*var\\s+(o200k|cl100k|chunks)\\b".r
        tiktokenSources.foreach { (name, text) =>
            assert(!text.contains("AllowUnsafe"), s"$name unexpectedly references AllowUnsafe")
            assert(varPattern.findFirstIn(text).isEmpty, s"$name declares a mutable process-global var")
        }
    }

    "O200kRanks.load decodes the expected entry count and spot ranks" in {
        val table = O200kRanks.load()
        assert(table.size == 199998, s"expected the full o200k_base vocabulary, got ${table.size}")
        assert(table(Tokenizer.internal.Ranks.Key(Array('!'.toByte), 0, 1)) == 0)
        assert(table(Tokenizer.internal.Ranks.Key(Array('"'.toByte), 0, 1)) == 1)
        val lastEntry = Array(' ', 'c', 'o', 'c', 'o', 's').map(_.toByte)
        assert(table(Tokenizer.internal.Ranks.Key(lastEntry, 0, lastEntry.length)) == 199997)
    }

    /** The three tiktoken source files, read once for the source-hygiene leaves above (JVM-only:
      * reading the module's own source tree is build-mechanics, not cross-platform behavior).
      */
    private def tiktokenSources: Chunk[(String, String)] =
        val names = Chunk("Tokenizer.scala", "O200kRanks.scala", "Cl100kRanks.scala")
        names.map(name => (name, readModuleSource(name)))

    private def readModuleSource(fileName: String): String =
        val relative = s"shared/src/main/scala/kyo/ai/$fileName"
        val candidates = Chunk(
            new java.io.File(relative),
            new java.io.File("kyo-ai", relative),
            new java.io.File(s"../$relative")
        )
        candidates.find(_.exists()) match
            case Some(file) => scala.io.Source.fromFile(file, "UTF-8").mkString
            case None       => throw new java.io.FileNotFoundException(s"could not locate $fileName from ${sys.props("user.dir")}")
    end readModuleSource

end TokenizerTest
