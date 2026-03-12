package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Sortable/filterable table, th.colspan, td.colspan, select with option, computed sort/filter chains. */
object DataGridUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val headerBtn = Style.cursor(_.pointer).bold.padding(8).bg(Color.transparent).borderStyle(_.none).textAlign(_.left)
    private val cellStyle = Style.padding(8).border(1, "#e2e8f0")
    private val filterRow = Style.row.gap(12).margin(0, 0, 12, 0).align(_.center)

    case class Person(name: String, age: Int, score: Int, category: String) derives CanEqual

    private val data = Chunk(
        Person("Alice", 28, 92, "Engineering"),
        Person("Bob", 35, 78, "Marketing"),
        Person("Carol", 24, 95, "Engineering"),
        Person("Dave", 42, 65, "Sales"),
        Person("Eve", 31, 88, "Engineering"),
        Person("Frank", 29, 71, "Marketing"),
        Person("Grace", 38, 84, "Sales"),
        Person("Hank", 26, 90, "Engineering")
    )

    private val categories = Chunk("All", "Engineering", "Marketing", "Sales")

    def build: UI < Async =
        for
            sortCol    <- Signal.initRef("name")
            ascending  <- Signal.initRef(true)
            filterCat  <- Signal.initRef("All")
            searchText <- Signal.initRef("")
        yield
            // Computed signal chain: data → filter by category → filter by text → sort
            val filtered = filterCat.map { cat =>
                if cat == "All" then data else data.filter(_.category == cat)
            }
            val searched = filtered.flatMap { items =>
                searchText.map { text =>
                    if text.isEmpty then items
                    else items.filter(_.name.toLowerCase.contains(text.toLowerCase))
                }
            }
            val sorted = searched.flatMap { items =>
                sortCol.flatMap { col =>
                    ascending.map { asc =>
                        val ordered = col match
                            case "name"  => items.toSeq.sortBy(_.name)
                            case "age"   => items.toSeq.sortBy(_.age)
                            case "score" => items.toSeq.sortBy(_.score)
                            case _       => items.toSeq.sortBy(_.name)
                        Chunk.from(if asc then ordered else ordered.reverse)
                    }
                }
            }
            val resultCount = sorted.map(_.size)
            val sortIndicator = sortCol.flatMap { col =>
                ascending.map(asc => s"$col ${if asc then "▲" else "▼"}")
            }

            div.style(app)(
                header.style(Style.bg("#7c3aed").color(Color.white).padding(16, 32))(
                    h1("Data Grid")
                ),
                main.style(content)(
                    section.cls("data-grid").style(card)(
                        h3("Sortable & Filterable Table"),
                        p.style(
                            Style.fontSize(13).color("#64748b")
                        )("Computed signal chain: raw data → category filter → text search → sort:"),

                        // Filters
                        div.cls("filters").style(filterRow)(
                            label.style(Style.fontSize(13).bold)("Category:"),
                            select.cls("category-filter").value(filterCat).onChange(filterCat.set(_))(
                                option.value("All")("All"),
                                option.value("Engineering")("Engineering"),
                                option.value("Marketing")("Marketing"),
                                option.value("Sales")("Sales")
                            ),
                            label.style(Style.fontSize(13).bold)("Search:"),
                            input.cls("search-input").value(searchText).onInput(searchText.set(_)).placeholder("Filter by name...")
                        ),

                        // Sort indicator
                        div.cls("sort-info").style(Style.fontSize(12).color("#64748b").margin(0, 0, 8, 0))(
                            sortIndicator.map(s => s"Sorting by: $s")
                        ),

                        // Table with colspan headers
                        table.style(Style.width(Size.pct(100)).border(1, "#e2e8f0"))(
                            // Grouped header row with colspan
                            tr(
                                th.cls("header-identity").style(cellStyle.bg("#f8fafc")).colspan(2)("Identity"),
                                th.cls("header-metrics").style(cellStyle.bg("#f8fafc")).colspan(2)("Metrics")
                            ),
                            // Sortable column headers
                            tr(
                                th.style(cellStyle)(
                                    button.cls("sort-name").style(headerBtn).onClick {
                                        for
                                            cur <- sortCol.get
                                            _   <- if cur == "name" then ascending.getAndUpdate(!_).unit else ascending.set(true)
                                            _   <- sortCol.set("name")
                                        yield ()
                                    }("Name")
                                ),
                                th.style(cellStyle)("Category"),
                                th.style(cellStyle)(
                                    button.cls("sort-age").style(headerBtn).onClick {
                                        for
                                            cur <- sortCol.get
                                            _   <- if cur == "age" then ascending.getAndUpdate(!_).unit else ascending.set(true)
                                            _   <- sortCol.set("age")
                                        yield ()
                                    }("Age")
                                ),
                                th.style(cellStyle)(
                                    button.cls("sort-score").style(headerBtn).onClick {
                                        for
                                            cur <- sortCol.get
                                            _   <- if cur == "score" then ascending.getAndUpdate(!_).unit else ascending.set(true)
                                            _   <- sortCol.set("score")
                                        yield ()
                                    }("Score")
                                )
                            ),
                            // Data rows
                            sorted.foreachIndexed { (idx, person) =>
                                tr.style(Style.bg(if idx % 2 == 0 then "#ffffff" else "#f8fafc"))(
                                    td.cls("cell-name").style(cellStyle)(person.name),
                                    td.style(cellStyle)(person.category),
                                    td.style(cellStyle)(person.age.toString),
                                    td.cls("cell-score").style(cellStyle.bold)(person.score.toString)
                                )
                            },
                            // Summary row with colspan
                            tr(
                                td.cls("summary-row").style(cellStyle.bg("#f0f9ff").bold).colspan(4)(
                                    resultCount.map(c => s"Showing $c of ${data.size} people")
                                )
                            )
                        )
                    )
                )
            )

end DataGridUI
