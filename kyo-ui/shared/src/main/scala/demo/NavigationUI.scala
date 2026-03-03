package demo

import kyo.*
import scala.language.implicitConversions

/** nav element, a.href, a.target, .clsWhen() active state, justify(spaceBetween), breadcrumbs. */
object NavigationUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val navBar     = Style.bg("#1e293b").padding(12, 24).row.justify(_.spaceBetween).align(_.center)
    private val navLink    = Style.color(Color.white).padding(6, 12).rounded(4).cursor(_.pointer).fontSize(14)
    private val activeLink = Style.bg(Color.rgba(255, 255, 255, 0.2))
    private val breadcrumb = Style.row.gap(4).fontSize(13).color("#64748b").padding(8, 0)
    private val extLink    = Style.color("#2563eb").underline

    private val pages = Chunk("Home", "Products", "About", "Contact")

    def build: UI < Async =
        for
            currentPage <- Signal.initRef("Home")
            visitCount  <- Signal.initRef(0)
        yield div.style(app)(
            // Section 1: Top navbar with justify(spaceBetween)
            section.cls("navbar-section").style(card.padding(0))(
                h3.style(Style.padding(16, 24).margin(0))("Navigation Bar"),
                nav.cls("top-nav").style(navBar)(
                    span.style(Style.color(Color.white).bold.fontSize(18))("MyApp"),
                    div.style(Style.row.gap(4))(
                        // Nav links with clsWhen for active state
                        button.cls("nav-home").style(navLink)
                            .clsWhen("active", currentPage.map(_ == "Home"))
                            .onClick(currentPage.set("Home").andThen(visitCount.getAndUpdate(_ + 1)).unit)("Home"),
                        button.cls("nav-products").style(navLink)
                            .clsWhen("active", currentPage.map(_ == "Products"))
                            .onClick(currentPage.set("Products").andThen(visitCount.getAndUpdate(_ + 1)).unit)("Products"),
                        button.cls("nav-about").style(navLink)
                            .clsWhen("active", currentPage.map(_ == "About"))
                            .onClick(currentPage.set("About").andThen(visitCount.getAndUpdate(_ + 1)).unit)("About"),
                        button.cls("nav-contact").style(navLink)
                            .clsWhen("active", currentPage.map(_ == "Contact"))
                            .onClick(currentPage.set("Contact").andThen(visitCount.getAndUpdate(_ + 1)).unit)("Contact")
                    )
                ),
                // Breadcrumb trail
                div.cls("breadcrumb").style(breadcrumb.padding(12, 24))(
                    span("MyApp"),
                    span(" / "),
                    span(currentPage.map(identity))
                ),
                // Page content area
                div.cls("page-content").style(Style.padding(16, 24).minHeight(80))(
                    currentPage.map {
                        case "Home"     => p("Welcome to MyApp. Select a page from the navigation above."): UI
                        case "Products" => p("Browse our product catalog with the latest items."): UI
                        case "About"    => p("Learn about our company and mission."): UI
                        case "Contact"  => p("Get in touch with us via email or phone."): UI
                        case _          => p("Page not found."): UI
                    }
                ),
                div.cls("visit-counter").style(Style.padding(8, 24).fontSize(12).color("#94a3b8"))(
                    visitCount.map(c => s"Navigation clicks: $c")
                )
            ),

            // Section 2: External links with a.target
            section.cls("external-links").style(card)(
                h3("External Links"),
                p.style(Style.fontSize(13).color("#64748b"))("Links with target=\"_blank\" open in new tab/window:"),
                div.style(Style.gap(8))(
                    div.style(Style.row.gap(8).align(_.center))(
                        a.cls("link-docs").style(extLink).href("https://kyo.dev").target("_blank")("Kyo Documentation"),
                        span.style(Style.fontSize(11).color("#94a3b8"))("(opens in new tab)")
                    ),
                    div.style(Style.row.gap(8).align(_.center))(
                        a.cls("link-github").style(extLink).href("https://github.com/getkyo/kyo").target("_blank")("GitHub Repository"),
                        span.style(Style.fontSize(11).color("#94a3b8"))("(opens in new tab)")
                    )
                )
            ),

            // Section 3: Reactive link
            section.cls("reactive-link").style(card)(
                h3("Reactive Link"),
                p.style(Style.fontSize(13).color("#64748b"))("Link href and text update from current page signal:"),
                div.style(Style.gap(8))(
                    a.cls("dynamic-link").style(extLink)
                        .href(currentPage.map(p => s"#${p.toLowerCase}"))(currentPage.map(p => s"Go to: $p")),
                    p.cls("link-display").style(Style.fontSize(12).color("#64748b"))(
                        currentPage.map(p => s"Current href: #${p.toLowerCase}")
                    )
                )
            )
        )

end NavigationUI
