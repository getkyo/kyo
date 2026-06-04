package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.mark.*
import scala.language.implicitConversions

/** Linked views: clicking a bar in one chart drives a second chart, with no glue beyond a shared `SignalRef`.
  *
  * The left chart is a bar of category totals; the right chart is a detail line of the selected category's
  * monthly series. The only wiring between them is one `Signal.initRef`: the bar chart's `.onSelect` writes
  * the clicked datum into it, and the detail title and line read it back. There is no event bus and no
  * callback plumbing; "interaction" is just another app signal.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.LinkedSelection'` (optional port as the first argument).
  */
object LinkedSelection extends KyoApp:

    // ---- domain ----

    /** A category and its headline total (one bar on the left chart). */
    case class Cat(name: String, total: Double) derives CanEqual

    /** One month of a category's series (one point on the right chart). */
    case class Pt(month: Int, value: Double) derives CanEqual

    private val cats: Chunk[Cat] = Chunk(
        Cat("Widgets", 820.0),
        Cat("Gadgets", 540.0),
        Cat("Gizmos", 1180.0),
        Cat("Doohickeys", 360.0),
        Cat("Sprockets", 690.0)
    )

    /** A 12-point monthly series per category. Each series is a simple pure walk off the category total. */
    private val seriesByCat: Map[String, Chunk[Pt]] =
        cats.map { c =>
            val series = Chunk.from((1 to 12).map(m => Pt(m, c.total / 12.0 * (1.0 + 0.4 * math.sin(m.toDouble)))))
            c.name -> series
        }.toMap

    private def seriesFor(name: String): Chunk[Pt] = seriesByCat.getOrElse(name, Chunk.empty)

    // ---- app ----

    private[demo] def app: UI < Async =
        for
            // The single point of coupling between the two charts: the current selection as an app signal.
            selected <- Signal.initRef(Maybe.empty[Cat])
        yield
            // LEFT (write side): bar of totals. `.onSelect(selected)` publishes the clicked `Cat` into the ref.
            val totalsChart: Svg.Root =
                UI.chart(cats)(bar(x = _.name, y = _.total))
                    .onSelect(selected)
                    .yScale(_.withNice(true))
                    .yAxis(_.left.grid.ticks(4))
                    .theme(_.dark)
                    .size(560, 300)
                    .toSvg

            val totalsTitle = UI.h3("Total by product")

            // INTERACTION IS AN ORDINARY APP SIGNAL.
            // `.onSelect(selected)` above is the only write; the title and detail line below are the only
            // reads. Linking the two charts is nothing more than sharing this one `SignalRef`: no event bus,
            // no callbacks, no chart-to-chart reference. The detail chart re-renders because it reads a signal
            // that changed, exactly like any other reactive `UI`.

            // RIGHT (read side): a title and a detail line, both derived from the same `selected` ref.
            val detailTitle =
                selected.render(sel => UI.h3(sel.fold("Click a bar to drill in")(c => s"${c.name} over time")))

            // The detail data is derived from the same ref: empty until a bar is clicked, then that category's
            // series. Because it is a `Signal`, the chart built over it updates itself; the marks region is
            // reactive by construction, so no `.render` wrapper is needed here.
            val detailData = selected.map(sel => sel.fold(Chunk.empty[Pt])(c => seriesFor(c.name)))

            val detailChart: Svg.Root =
                UI.chart(detailData)(line(x = _.month, y = _.value))
                    .xScale(_.linear(1.0, 12.0))
                    .yScale(_.linear(0.0, 130.0))
                    .xAxis(_.bottom)
                    .yAxis(_.left.grid.ticks(4))
                    .theme(_.dark)
                    .size(560, 300)
                    .toSvg

            UI.div.style(Style.column.gap(16.px).padding(20.px).bg(Color.rgb(15, 18, 28)).color(Color.rgb(226, 232, 240)))(
                UI.h2("Linked selection"),
                UI.div.style(Style.row.gap(24.px).align(_.start))(
                    UI.div.style(Style.column.gap(8.px))(totalsTitle, UI.div(totalsChart)),
                    UI.div.style(Style.column.gap(8.px))(detailTitle, detailChart)
                )
            )
    end app

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"LinkedSelection running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end LinkedSelection
