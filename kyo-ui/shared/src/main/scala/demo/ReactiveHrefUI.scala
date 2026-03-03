package demo

import kyo.*
import scala.language.implicitConversions

/** Tests reactive href on anchors and fragment as a multi-root pattern in reactive contexts. */
object ReactiveHrefUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#ea580c").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)

    def build: UI < Async =
        for
            linkTarget <- Signal.initRef("https://example.com")
            linkText   <- Signal.initRef("Example.com")
            fragMode   <- Signal.initRef("simple") // "simple", "nested", "reactive"
            items      <- Signal.initRef(Chunk("One", "Two", "Three"))
        yield div.style(app)(
            header.style(Style.bg("#ea580c").color(Color.white).padding(16, 32))(
                h1("Reactive Href & Fragment Tests")
            ),
            main.style(content)(
                // Reactive href
                section.cls("reactive-href").style(card)(
                    h3("Reactive Anchor href"),
                    p.style(Style.fontSize(13).color("#64748b"))("Anchor href changes via signal:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("href-example-btn").style(btnSm).onClick {
                            for
                                _ <- linkTarget.set("https://example.com")
                                _ <- linkText.set("Example.com")
                            yield ()
                        }("Example.com"),
                        button.cls("href-github-btn").style(btnSm).onClick {
                            for
                                _ <- linkTarget.set("https://github.com")
                                _ <- linkText.set("GitHub")
                            yield ()
                        }("GitHub"),
                        button.cls("href-scala-btn").style(btnSm).onClick {
                            for
                                _ <- linkTarget.set("https://scala-lang.org")
                                _ <- linkText.set("Scala")
                            yield ()
                        }("Scala")
                    ),
                    div.style(Style.padding(12).bg("#fff7ed").rounded(8).margin(8, 0, 0, 0))(
                        a.cls("reactive-link").href(linkTarget).target("_blank").style(Style.color("#ea580c").bold)(
                            linkText.map(t => s"Visit: $t")
                        )
                    ),
                    div.cls("current-href").style(Style.fontSize(12).color("#94a3b8").margin(4, 0, 0, 0))(
                        linkTarget.map(h => s"Current href: $h")
                    )
                ),
                // Fragment in various contexts
                section.cls("fragment-tests").style(card)(
                    h3("Fragment as Multi-Root Pattern"),
                    p.style(Style.fontSize(13).color("#64748b"))("Fragments used in different contexts:"),
                    // Static fragment
                    div.cls("static-fragment").style(Style.padding(12).bg("#f0fdf4").rounded(8).margin(8, 0, 0, 0))(
                        h3.style(Style.fontSize(14))("Static Fragment"),
                        UI.fragment(
                            span.style(Style.color("#059669").bold)("First "),
                            span.style(Style.color("#0891b2").bold)("Second "),
                            span.style(Style.color("#7c3aed").bold)("Third")
                        )
                    ),
                    // Fragment inside foreach
                    div.cls("foreach-fragment").style(Style.padding(12).bg("#fef3c7").rounded(8).margin(8, 0, 0, 0))(
                        h3.style(Style.fontSize(14))("Fragment Inside foreach"),
                        div.style(Style.gap(4))(
                            items.foreach { item =>
                                UI.fragment(
                                    span.style(Style.bold)(s"$item: "),
                                    span.style(Style.color("#92400e"))("detail"),
                                    hr
                                )
                            }
                        )
                    ),
                    // Fragment inside Signal[UI]
                    div.cls("reactive-fragment").style(Style.padding(12).bg("#faf5ff").rounded(8).margin(8, 0, 0, 0))(
                        h3.style(Style.fontSize(14))("Fragment Inside Signal[UI]"),
                        div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                            button.cls("frag-simple-btn").style(btnSm).onClick(fragMode.set("simple").unit)("Simple"),
                            button.cls("frag-nested-btn").style(btnSm).onClick(fragMode.set("nested").unit)("Nested"),
                            button.cls("frag-reactive-btn").style(btnSm).onClick(fragMode.set("reactive").unit)("Reactive")
                        ),
                        div.cls("frag-content")(
                            fragMode.map {
                                case "simple" =>
                                    UI.fragment(
                                        p.style(Style.bold)("Simple mode"),
                                        p("Just two paragraphs in a fragment")
                                    ): UI
                                case "nested" =>
                                    UI.fragment(
                                        p.style(Style.bold)("Nested mode"),
                                        div.style(Style.bg("#ede9fe").padding(8).rounded(4))(
                                            UI.fragment(
                                                span("Inner "),
                                                span.style(Style.bold)("fragment "),
                                                span("content")
                                            )
                                        )
                                    ): UI
                                case "reactive" =>
                                    UI.fragment(
                                        p.style(Style.bold)("Reactive mode"),
                                        div.style(Style.gap(4))(
                                            items.foreach { item =>
                                                span.style(Style.bg("#e0e7ff").padding(4, 8).rounded(4))(item)
                                            }
                                        )
                                    ): UI
                                case _ =>
                                    p("Unknown mode"): UI
                            }
                        )
                    )
                ),
                // Mutate items that are shown via fragment
                section.cls("fragment-mutation").style(card)(
                    h3("Fragment + Mutation"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("frag-add-btn").style(btn).onClick(
                            items.getAndUpdate(c => c.append(s"Item-${c.size + 1}")).unit
                        )("Add Item"),
                        button.cls("frag-remove-btn").style(btnSm).onClick(
                            items.getAndUpdate(c => if c.nonEmpty then c.dropRight(1) else c).unit
                        )("Remove Last"),
                        button.cls("frag-clear-btn").style(btnSm).onClick(
                            items.set(Chunk.empty).unit
                        )("Clear")
                    ),
                    div.cls("frag-items").style(Style.row.gap(4).margin(8, 0, 0, 0))(
                        items.foreach { item =>
                            span.style(Style.bg("#fed7aa").padding(4, 12).rounded(12))(item)
                        }
                    ),
                    div.cls("frag-count").style(Style.fontSize(12).color("#64748b").margin(4, 0, 0, 0))(
                        items.map(c => s"${c.size} items")
                    )
                )
            )
        )

end ReactiveHrefUI
