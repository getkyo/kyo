package kyo.ai

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import kyo.*
import kyo.ai.Tokenizer.Encoding
import kyo.ai.Tokenizer.internal.Tiktoken

/** The jtokkit JVM-oracle parity build gate. The bundled pure-Scala tiktoken is held
  * token-for-token to the reference jtokkit encoder across a multi-sample corpus for both encodings. JVM-only
  * because the ORACLE (com.knuddels:jtokkit) is a JVM library; the cross-platform exactness pin stays in the
  * shared TokenizerTest. Pure count comparison: no LLM, no network, no fiber.
  */
class TokenizerJtokkitParityTest extends kyo.test.Test[Any]:

    // A multi-sample corpus spanning ASCII prose, code, JSON, CJK, mixed scripts, combining marks, emoji, a
    // long paragraph, digit runs, whitespace, a contraction, camelCase, an acronym-cased run, and the empty
    // string: the surfaces where a BPE encoder's grouping is most likely to skew.
    private val corpus: List[String] = List(
        "The quick brown fox jumps over the lazy dog.",
        "def add(x: Int, y: Int): Int = x + y",
        """{"location":"Paris","unit":"celsius"}""",
        "東京都に住んでいます",
        "Tokyo東京123",
        "é", // NFD: 'e' followed by a standalone combining acute accent (two code points)
        "😀🎉🚀",
        "The connection pool was sized to a safe ceiling for stability across the whole deployment fleet.",
        "1234567",
        "  \n \t \n ",
        "don't",
        "camelCase",
        "HTTPServer",
        ""
    )

    private val registry = Encodings.newDefaultEncodingRegistry()

    "the o200k tiktoken matches the jtokkit JVM oracle token count on every corpus sample" in {
        val tiktoken = Tiktoken(Encoding.O200kBase)
        val oracle   = registry.getEncoding(EncodingType.O200K_BASE)
        corpus.foreach { s =>
            assert(
                tiktoken.countText(s) == oracle.countTokens(s),
                s"o200k parity mismatch on sample [$s]: tiktoken ${tiktoken.countText(s)} vs jtokkit ${oracle.countTokens(s)}"
            )
        }
    }

    "the cl100k tiktoken matches the jtokkit JVM oracle token count on every corpus sample" in {
        val tiktoken = Tiktoken(Encoding.Cl100kBase)
        val oracle   = registry.getEncoding(EncodingType.CL100K_BASE)
        corpus.foreach { s =>
            assert(
                tiktoken.countText(s) == oracle.countTokens(s),
                s"cl100k parity mismatch on sample [$s]: tiktoken ${tiktoken.countText(s)} vs jtokkit ${oracle.countTokens(s)}"
            )
        }
        // each encoding table is a distinct oracle-checked table: the contraction splits differently.
        assert(
            Tiktoken(Encoding.O200kBase).countText("don't") != Tiktoken(Encoding.Cl100kBase).countText("don't"),
            "o200k folds the contraction to one piece while cl100k splits it, so the two tables are genuinely distinct"
        )
    }

end TokenizerJtokkitParityTest
