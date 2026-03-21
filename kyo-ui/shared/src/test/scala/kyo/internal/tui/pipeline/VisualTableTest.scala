package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class VisualTableTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    def assertRender(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Plain)(expected: String) =
        RenderToString.render(ui, cols, rows, theme).map { actual =>
            val lines   = expected.stripMargin.stripPrefix("\n").linesIterator.toVector
            val trimmed = if lines.nonEmpty && lines.last.trim.isEmpty then lines.dropRight(1) else lines
            val exp     = trimmed.map(_.padTo(cols, ' ')).mkString("\n")
            if actual != exp then
                val msg = s"\nExpected:\n${exp.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}" +
                    s"\nActual:\n${actual.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}"
                fail(msg)
            else succeed
            end if
        }

    // ==== 11.1 Basic table ====

    "11.1 basic table" - {
        "2x2 table with A B C D — grid layout" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("A"), UI.td("B")),
                    UI.tr(UI.td("C"), UI.td("D"))
                ),
                10,
                2
            )(
                """
                |A    B
                |C    D
                """
            )
        }

        "columns aligned vertically" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Alpha"), UI.td("X")),
                    UI.tr(UI.td("B"), UI.td("Y"))
                ),
                12,
                2
            )(
                """
                |Alpha   X
                |B       Y
                """
            )
        }

        "row 1 above row 2" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Top"), UI.td("Row")),
                    UI.tr(UI.td("Bot"), UI.td("Row"))
                ),
                10,
                2
            )(
                """
                |Top  Row
                |Bot  Row
                """
            )
        }
    }

    // ==== 11.2 Column width ====

    "11.2 column width" - {
        "column widths from widest cell content" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Wide"), UI.td("X")),
                    UI.tr(UI.td("W"), UI.td("Narrow"))
                ),
                14,
                2
            )(
                """
                |Wide  X
                |W     Narrow
                """
            )
        }

        "short cell in wide column — padded" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Long"), UI.td("A")),
                    UI.tr(UI.td("X"), UI.td("B"))
                ),
                12,
                2
            )(
                """
                |Long   A
                |X      B
                """
            )
        }

        "all cells same width — equal columns" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("AA"), UI.td("BB")),
                    UI.tr(UI.td("CC"), UI.td("DD"))
                ),
                10,
                2
            )(
                """
                |AA   BB
                |CC   DD
                """
            )
        }
    }

    // ==== 11.3 Colspan ====

    "11.3 colspan" - {
        "td.colspan(2) spans two columns" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td.colspan(2)("Header")),
                    UI.tr(UI.td("A"), UI.td("B"))
                ),
                10,
                2
            )(
                """
                |Header
                |A    B
                """
            )
        }

        "colspan cell width equals sum of spanned column widths" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("AA"), UI.td("BB"), UI.td("CC")),
                    UI.tr(UI.td.colspan(2)("Spanned"), UI.td("CC"))
                ),
                12,
                2
            )(
                """
                |AA  BB  CC
                |Spanned CC
                """
            )
        }
    }

    // ==== 11.4 Table header ====

    "11.4 table header" - {
        "th cells rendered same as td" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.th("Name"), UI.th("Age")),
                    UI.tr(UI.td("Jo"), UI.td("25"))
                ),
                12,
                2
            )(
                """
                |Name  Age
                |Jo    25
                """
            )
        }

        "header row plus data rows — aligned" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.th("Col1"), UI.th("Col2")),
                    UI.tr(UI.td("A"), UI.td("B")),
                    UI.tr(UI.td("C"), UI.td("D"))
                ),
                12,
                3
            )(
                """
                |Col1  Col2
                |A     B
                |C     D
                """
            )
        }
    }

    // ==== 11.5 Table fills width ====

    "11.5 table fills width" - {
        "extra width distributed to columns" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("A"), UI.td("B"))
                ),
                10,
                1
            )(
                """
                |A    B
                """
            )
        }

        "table in 30-col viewport with 2 narrow columns — columns expanded" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("X"), UI.td("Y")),
                    UI.tr(UI.td("A"), UI.td("B"))
                ),
                30,
                2
            )(
                """
                |X              Y
                |A              B
                """
            )
        }
    }

    // ==== 11.6 Edge cases ====

    "11.6 edge cases" - {
        "empty table — no crash" in run {
            assertRender(
                UI.table(),
                10,
                1
            )(
                """
                |
                """
            )
        }

        "single cell table — renders" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Only"))
                ),
                10,
                1
            )(
                """
                |Only
                """
            )
        }

        "table with one column — column fills width" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("A")),
                    UI.tr(UI.td("B"))
                ),
                10,
                2
            )(
                """
                |A
                |B
                """
            )
        }

        "table with many columns in narrow viewport — squished" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("A"), UI.td("B"), UI.td("C"), UI.td("D"), UI.td("E"))
                ),
                5,
                1
            )(
                """
                |ABCDE
                """
            )
        }
    }

    // ==== 11.7 Containment ====

    "11.7 containment" - {
        "table in bounded container — cells within bounds" in run {
            assertRender(
                UI.div.style(Style.width(10.px).height(2.px))(
                    UI.table(
                        UI.tr(UI.td("A"), UI.td("B")),
                        UI.tr(UI.td("C"), UI.td("D"))
                    )
                ),
                12,
                2
            )(
                """
                |A    B
                |C    D
                """
            )
        }

        "table content doesn't overflow container" in run {
            assertRender(
                UI.div.style(Style.width(6.px).height(1.px))(
                    UI.table(
                        UI.tr(UI.td("Hello"), UI.td("World")),
                        UI.tr(UI.td("A"), UI.td("B"))
                    )
                ),
                10,
                1
            )(
                """
                |HelloWorld
                """
            )
        }
    }

    // ==== 11.8 Table cell padding in Default theme ====

    "11.8 table cell padding" - {
        "header cells have horizontal padding in Default theme" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.th("Name"), UI.th("Age"))
                ),
                20,
                1,
                Theme.Default
            )(
                """
                | Name      Age
                """
            )
        }

        "data cells have horizontal padding in Default theme" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("Alice"), UI.td("30"))
                ),
                20,
                1,
                Theme.Default
            )(
                """
                | Alice      30
                """
            )
        }
    }

    // ==== 11.9 Multiple rows align with headers ====

    "11.9 multiple rows" - {
        "two data rows below header — columns aligned" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.th("Name"), UI.th("Role")),
                    UI.tr(UI.td("Alice"), UI.td("Dev")),
                    UI.tr(UI.td("Bob"), UI.td("Mgr"))
                ),
                20,
                3
            )(
                """
                |Name      Role
                |Alice     Dev
                |Bob       Mgr
                """
            )
        }

        "second row is below first, not beside it" in run {
            assertRender(
                UI.table(
                    UI.tr(UI.td("A"), UI.td("B")),
                    UI.tr(UI.td("C"), UI.td("D"))
                ),
                10,
                2
            )(
                """
                |A    B
                |C    D
                """
            )
        }
    }

    // ==== 11.10 Demo pattern: table with foreachKeyed rows added dynamically ====

    "11.10 dynamic table rows via foreachKeyed" - {
        "add two entries — each on its own row, columns aligned with header" in run {
            for
                entriesRef <- Signal.initRef(Chunk.empty[(String, String)])
            yield
                import kyo.UI.foreachKeyed
                val s = screen(
                    UI.table(
                        UI.tr(UI.th("Name"), UI.th("Role")),
                        entriesRef.foreachKeyed(e => e._1) { entry =>
                            UI.tr(UI.td(entry._1), UI.td(entry._2))
                        }
                    ),
                    20,
                    4
                )
                for
                    _ <- s.render
                    _ = s.assertFrame(
                        """
                        |Name      Role
                        |
                        |
                        |
                        """
                    )
                    // Add first entry
                    _ <- Sync.defer(entriesRef.set(Chunk(("Alice", "Dev"))))
                    _ <- Async.sleep(10.millis)
                    _ <- s.render
                    _ = s.assertFrame(
                        """
                        |Name      Role
                        |Alice     Dev
                        |
                        |
                        """
                    )
                    // Add second entry
                    _ <- Sync.defer(entriesRef.set(Chunk(("Alice", "Dev"), ("Bob", "Mgr"))))
                    _ <- Async.sleep(10.millis)
                    _ <- s.render
                yield
                    // Both entries should be on separate rows, aligned with headers
                    s.assertFrame(
                        """
                        |Name      Role
                        |Alice     Dev
                        |Bob       Mgr
                        |
                        """
                    )
                end for
        }
    }

end VisualTableTest
