package org.typelevel.paiges.internal

import org.scalatest.freespec.AnyFreeSpec
import org.typelevel.paiges.*
import org.typelevel.paiges.internal.ExtendedSyntax.*

class ExtendedSyntaxTest extends AnyFreeSpec {

    private def normalize(str: String): String =
        str.replace("\r\n", "\n").replace("\r", "\n")

    private def render(doc: Doc, width: Int): String =
        normalize(doc.render(width))

    private def assertSame(actual: String, expected: String): Unit =
        assert(normalize(actual) == normalize(expected))

    "ExtendedSyntax" - {
        "ungrouped" - {
            "should collapse none when fits" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
                val actual = render(doc, 5)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assertSame(actual, expected)
            }
            "should collapse none when too long" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
                val actual = render(doc, 3)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assertSame(actual, expected)
            }
        }
        "regrouped" - {
            "should collapse all when fits" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = render(doc, 5)
                val expected =
                    """|a b c""".stripMargin
                assertSame(actual, expected)
            }
            "should collapse none when too long" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = render(doc, 3)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assertSame(actual, expected)
            }
        }
        "hangingUnsafe" - {
            "should not indent when short" in {
                val prefix = Doc.text("foo")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = render(doc.grouped, 10)
                val expected =
                    """foo bar""".stripMargin
                assertSame(actual, expected)
            }
            "should indent when long" in {
                val prefix = Doc.text("foo")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = render(doc.grouped, 5)
                val expected =
                    """foo
                      |  bar""".stripMargin
                assertSame(actual, expected)
            }
            "should indent when long from multiline" in {
                val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = render(doc.grouped, 5)
                val expected =
                    """foo
                      |baz
                      |  bar""".stripMargin
                assertSame(actual, expected)
            }
            // These tests convince us why we can't use hang.
            "hang" - {
                "should not indent when short" in {
                    val prefix = Doc.text("foo") + Doc.line
                    val doc    = (prefix + Doc.text("bar")).hang(2)
                    val actual = render(doc.grouped, 10)
                    val expected =
                        """foo bar""".stripMargin
                    assertSame(actual, expected)
                }
                "should indent when long" in {
                    val prefix = Doc.text("foo") + Doc.line
                    val doc    = (prefix + Doc.text("bar")).hang(2)
                    val actual = render(doc.grouped, 5)
                    val expected =
                        """foo
                          |  bar""".stripMargin
                    assertSame(actual, expected)
                }
                "should indent when long from multiline" in {
                    // This is why hang doesn't work. It requires that you include the previous new line.
                    val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                    val doc    = (prefix + Doc.line + Doc.text("bar")).hang(2)
                    val actual = render(doc.grouped, 5)
                    val expected =
                        """foo
                          |  baz
                          |  bar""".stripMargin
                    assertSame(actual, expected)
                }
                "should indent when long from multiline 2" in {
                    // If you only include the previous line it doesn't work either.
                    // It hangs relative to the current position which is whatever was before the hanged document.
                    // hanging has to include it's own new line in order to ensure that the position is reset.
                    val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                    val doc    = prefix + (Doc.line + Doc.text("bar")).hang(2)
                    val actual = render(doc.grouped, 5)
                    val expected =
                        """foo
                          |baz
                          |     bar""".stripMargin
                    assertSame(actual, expected)
                }
            }
        }
    }
}
