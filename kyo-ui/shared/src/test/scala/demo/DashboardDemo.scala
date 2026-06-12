package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.Ast.HtmlContent

/** Live metrics dashboard driven entirely by the server.
  *
  * A single background fiber ticks once a second and writes new values into shared `SignalRef`s. Every connected browser subscribes to
  * those same signals, so the server pushes fine-grained DOM diffs over SSE and the numbers update live with no client-side code and no
  * polling. This is the `UI.runHandlers` server-driven model end to end: the state lives on the server, the browser is a thin presenter.
  *
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.Dashboard'` (optional port as the first argument), then open the URL and watch the cards and the
  * activity log update on their own.
  *
  * Demonstrates: shared server-side state, a `Fiber` background updater, fine-grained reactive text via `signal.render`, a reactive list
  * via `signal.foreach`, and equal-width cards via `flexGrow(1).flexBasis(0.px)`.
  */
object DashboardDemo extends KyoApp:

    private val pageStyle     = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(16.px)
    private val subtitleStyle = Style.color(Color.gray).fontSize(14.px)
    private val cardsRow      = Style.row.gap(16.px)
    private val cardStyle =
        Style.column.gap(6.px).padding(16.px).bg(Color.slate).rounded(10.px).flexGrow(1).flexBasis(0.px)
    private val cardLabel           = Style.color(Color.white).fontSize(13.px)
    private def cardValue(c: Color) = Style.color(c).fontSize(34.px).bold
    private val logStyle            = Style.column.gap(6.px).padding(12.px).bg(Color.slate).rounded(10.px)
    private val logRow              = Style.color(Color.white).fontFamily(FontFamily.Monospace).fontSize(13.px)

    private def metric(label: String, sig: Signal[Int], color: Color): HtmlContent =
        div.style(cardStyle)(
            span(label).style(cardLabel),
            sig.render(v => span(v.toString).style(cardValue(color)))
        )

    private def dashboard(
        requests: Signal[Int],
        users: Signal[Int],
        cpu: Signal[Int],
        latency: Signal[Int],
        events: Signal[Chunk[String]]
    ): UI =
        UI.main.style(pageStyle)(
            h1("Live Dashboard"),
            p("Server-pushed metrics. The values below update every second over SSE, with no client-side code.").style(subtitleStyle),
            div.style(cardsRow)(
                metric("Requests / sec", requests, Color.blue),
                metric("Active users", users, Color.green),
                metric("CPU %", cpu, Color.orange),
                metric("Latency ms", latency, Color.purple)
            ),
            h2("Recent activity"),
            ul.style(logStyle)(events.foreach(e => li(e).style(logRow)))
        )

    /** Background fiber: derive fresh, ever-changing metric values from a tick counter (no RNG, so it stays deterministic). */
    private def updater(
        tick: AtomicInt,
        requests: SignalRef[Int],
        users: SignalRef[Int],
        cpu: SignalRef[Int],
        latency: SignalRef[Int],
        events: SignalRef[Chunk[String]]
    ): Unit < Async =
        Loop.foreach {
            for
                t <- tick.incrementAndGet
                _ <- requests.set(120 + (t * 7) % 80)
                _ <- users.set(40 + (t * 3) % 30)
                _ <- cpu.set(25 + (t * 13) % 60)
                _ <- latency.set(15 + (t * 5) % 45)
                _ <- events.updateAndGet(es => (s"batch #$t processed (${(t * 7) % 80} req)" +: es).take(6)).unit
                _ <- Async.sleep(1.second)
            yield Loop.continue
        }

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            requests <- Signal.initRef(0)
            users    <- Signal.initRef(0)
            cpu      <- Signal.initRef(0)
            latency  <- Signal.initRef(0)
            events   <- Signal.initRef(Chunk.empty[String])
            tick     <- AtomicInt.init(0)
            handlers <- UI.runHandlers("/")(dashboard(requests, users, cpu, latency, events))
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Dashboard running on http://localhost:${server.port}/")
            _        <- Fiber.init(updater(tick, requests, users, cpu, latency, events))
            _        <- server.await
        yield ()
        end for
    }
end DashboardDemo
