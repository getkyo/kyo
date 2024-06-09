package org.typelevel.paiges

import org.scalatest.freespec.AnyFreeSpec
import ExtendedSyntax.*

class DocxTest extends AnyFreeSpec {

    "Docx" - {
        "ungrouped" - {
            "should collapse none when fits" in {
                val doc = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
                val actual = doc.render(5)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assert(actual == expected)
            }
            "should collapse none when too long" in {
                val doc = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
                val actual = doc.render(3)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assert(actual == expected)
            }
        }
        "regrouped" - {
            "should collapse all when fits" in {
                val doc = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = doc.render(5)
                val expected =
                    """|a b c""".stripMargin
                assert(actual == expected)
            }
            "should collapse none when too long" in {
                val doc = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = doc.render(3)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assert(actual == expected)
            }
        }
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
                val sig      = Doc.text("def foo = ")
                val body     = Doc.text("a") + Doc.hardLine + Doc.text("b")
                val doc      = sig + Docx.bracketIfMultiline(Doc.char('{'), body, Doc.char('}'))
                val actual   = doc.render(25)
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
