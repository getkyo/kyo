package org.typelevel.paiges.internal

import org.scalatest.freespec.AnyFreeSpec
import org.typelevel.paiges.*
import org.typelevel.paiges.internal.ExtendedSyntax.*

class ExtendedSyntaxTest extends AnyFreeSpec {

    "ExtendedSyntax" - {
        "ungrouped" - {
            "should collapse none when fits" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
                val actual = doc.render(5)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assert(actual == expected)
            }
            "should collapse none when too long" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).ungrouped
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
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = doc.render(5)
                val expected =
                    """|a b c""".stripMargin
                assert(actual == expected)
            }
            "should collapse none when too long" in {
                val doc    = ((Doc.text("a") + Doc.line + Doc.text("b")).grouped + Doc.line + Doc.text("c")).regrouped
                val actual = doc.render(3)
                val expected =
                    """|a
                       |b
                       |c""".stripMargin
                assert(actual == expected)
            }
        }
        "hangingUnsafe" - {
            "should not indent when short" in {
                val prefix = Doc.text("foo")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = doc.grouped.render(10)
                val expected =
                    """foo bar""".stripMargin
                assert(actual == expected)
            }
            "should indent when long" in {
                val prefix = Doc.text("foo")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = doc.grouped.render(5)
                val expected =
                    """foo
                      |  bar""".stripMargin
                assert(actual == expected)
            }
            "should indent when long from multiline" in {
                val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                val doc    = prefix + Doc.text("bar").hangingUnsafe(2)
                val actual = doc.grouped.render(5)
                val expected =
                    """foo
                      |baz
                      |  bar""".stripMargin
                assert(actual == expected)
            }
            // These tests convince us why we can't use hang.
            "hang" - {
                "should not indent when short" in {
                    val prefix = Doc.text("foo") + Doc.line
                    val doc    = (prefix + Doc.text("bar")).hang(2)
                    val actual = doc.grouped.render(10)
                    val expected =
                        """foo bar""".stripMargin
                    assert(actual == expected)
                }
                "should indent when long" in {
                    val prefix = Doc.text("foo") + Doc.line
                    val doc    = (prefix + Doc.text("bar")).hang(2)
                    val actual = doc.grouped.render(5)
                    val expected =
                        """foo
                          |  bar""".stripMargin
                    assert(actual == expected)
                }
                "should indent when long from multiline" in {
                    // This is why hang doesn't work. It requires that you include the previous new line.
                    val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                    val doc    = (prefix + Doc.line + Doc.text("bar")).hang(2)
                    val actual = doc.grouped.render(5)
                    val expected =
                        """foo
                          |  baz
                          |  bar""".stripMargin
                    assert(actual == expected)
                }
                "should indent when long from multiline 2" in {
                    // If you only include the previous line it doesn't work either.
                    // It hangs relative to the current position which is whatever was before the hanged document.
                    // hanging has to include it's own new line in order to ensure that the position is reset.
                    val prefix = Doc.text("foo") + Doc.line + Doc.text("baz")
                    val doc    = prefix + (Doc.line + Doc.text("bar")).hang(2)
                    val actual = doc.grouped.render(5)
                    val expected =
                        """foo
                          |baz
                          |     bar""".stripMargin
                    assert(actual == expected)
                }
            }
        }
    }
}
