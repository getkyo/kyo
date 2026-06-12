package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.Ast.HtmlContent

/** Multi-view single-page app routed by a route `SignalRef`, served over server-push.
  *
  * A single `route` signal holds the current path; the nav bar writes it and `route.render` swaps the view, including a parameterized
  * `"/users/:id"` route. This is the routing pattern expressed with plain signals.
  *
  * Note: for true URL + browser-history routing in a Scala.js `runMount` app, kyo-ui provides `UILocation` (`UILocation.current` is a
  * `Signal[String]` of the path, with `push`/`replace`/`back`); plain anchor clicks are intercepted into history navigation. That path is
  * JS-only, so this server-push demo uses a route signal to show the same view-switching pattern in a form you can run with `runMain`.
  *
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.Router'` (optional port as the first argument).
  *
  * Demonstrates: `route.render` view switching, a parameterized route, nav driven by writing a `SignalRef`, and active-link styling derived
  * from the route signal.
  */
object RouterDemo extends KyoApp:

    private val users = Seq("1" -> "Ada Lovelace", "2" -> "Alan Turing", "3" -> "Grace Hopper")

    private val pageStyle = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(16.px)
    private val navStyle  = Style.row.gap(8.px).align(Alignment.center).padding(0.px, 0.px, 12.px, 0.px)
    private val viewStyle = Style.column.gap(8.px).padding(16.px).bg(Color.slate).rounded(10.px).minHeight(220.px)
    private val linkBase  = Style.padding(6.px, 12.px).rounded(8.px).cursor(Cursor.pointer)
    private def navLink(active: Boolean): Style =
        if active then linkBase.bg(Color.blue).color(Color.white)
        else linkBase.bg(Color.slate).color(Color.white)
    private val userLink = Style.color(Color.blue).cursor(Cursor.pointer).padding(4.px, 0.px)

    private def view(title: String, body: HtmlContent*): HtmlContent =
        div.style(viewStyle)((h2(title).style(Style.color(Color.white)) +: body).map(UI.Ast.HtmlChildVal.lift(_))*)

    private def render(path: String, go: String => Any < Async): HtmlContent =
        path match
            case "/" =>
                view("Home", p("Welcome. Pick a section above.").style(Style.color(Color.white)))
            case "/about" =>
                view("About", p("A tiny single-page app routed by one signal.").style(Style.color(Color.white)))
            case "/users" =>
                view(
                    "Users",
                    ul.style(Style.column.gap(4.px))(
                        users.map((id, name) => li(span(name).id(s"user-$id").style(userLink).onClick(go(s"/users/$id"))))*
                    )
                )
            case s"/users/$id" =>
                val name = users.toMap.getOrElse(id, "Unknown")
                view(
                    s"User #$id",
                    p(name).style(Style.color(Color.white).bold.fontSize(18.px)),
                    span("Back to users").style(userLink).onClick(go("/users"))
                )
            case _ =>
                view("Not found", p(s"No view for $path").style(Style.color(Color.white)))

    private def app: UI < Async =
        for route <- Signal.initRef("/")
        yield
            val go = (path: String) => route.set(path)
            def active(cur: String, path: String): Boolean =
                cur == path || (path != "/" && cur.startsWith(path + "/"))
            def link(path: String, label: String) =
                route.render(cur => button(label).id(s"nav-$label").onClick(go(path)).style(navLink(active(cur, path))))
            UI.main.style(pageStyle)(
                h1("Router"),
                nav.style(navStyle)(
                    link("/", "Home"),
                    link("/about", "About"),
                    link("/users", "Users")
                ),
                route.render(p => render(p, go))
            )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Router running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end RouterDemo
