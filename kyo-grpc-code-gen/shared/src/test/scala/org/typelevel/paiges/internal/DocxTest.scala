package org.typelevel.paiges.internal

import org.scalatest.freespec.AnyFreeSpec
import org.typelevel.paiges.*

class DocxTest extends AnyFreeSpec {

    "Docx" - {
        "bracketIfMultiline" - {
            "should add separators when is too long" in {
                val sig    = Doc.text("def foo = ")
                val body   = Doc.text("howlongcanyougo")
                val doc    = sig + Docx.bracketIfMultiline(Doc.char('{'), body, Doc.char('}'))
                val actual = doc.render(24)
                val expected =
                    """def foo = {
                      |  howlongcanyougo
                      |}""".stripMargin
                assert(actual == expected)
            }
            "should not add separators when the document fits" in {
                val sig      = Doc.text("def foo = ")
                val body     = Doc.text("howlongcanyougo")
                val doc      = sig + Docx.bracketIfMultiline(Doc.char('{'), body, Doc.char('}'))
                val actual   = doc.render(25)
                val expected = """def foo = howlongcanyougo""".stripMargin
                assert(actual == expected)
            }
            "should add separators when the document is multiline" in {
                val sig    = Doc.text("def foo = ")
                val body   = Doc.text("a") + Doc.hardLine + Doc.text("b")
                val doc    = sig + Docx.bracketIfMultiline(Doc.char('{'), body, Doc.char('}'))
                val actual = doc.render(25)
                val expected =
                    """def foo = {
                      |  a
                      |  b
                      |}""".stripMargin
                assert(actual == expected)
            }
        }
    }
}
