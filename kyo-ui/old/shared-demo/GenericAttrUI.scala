package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Tests the generic .attr() and .on() APIs which are never used in any other demo. */
object GenericAttrUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#0d9488").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)

    def build: UI < Async =
        for
            clickLog     <- Signal.initRef(Chunk.empty[String])
            dynamicTitle <- Signal.initRef("Initial Title")
            attrValue    <- Signal.initRef("data-initial")
        yield div.style(app)(
            header.style(Style.bg("#0d9488").color(Color.white).padding(16, 32))(
                h1("Generic Attr & Event Tests")
            ),
            main.style(content)(
                // Static .attr() usage
                section.cls("static-attrs").style(card)(
                    h3("Static Attributes"),
                    p.style(Style.fontSize(13).color("#64748b"))("Elements with custom data-* attributes:"),
                    div.cls("attr-el-1").attr("data-testid", "first-element").attr("data-role", "primary")
                        .style(Style.padding(12).bg("#f0fdfa").rounded(8))(
                            "Element with data-testid='first-element' and data-role='primary'"
                        ),
                    div.cls("attr-el-2").attr("data-testid", "second-element").attr("data-count", "42")
                        .style(Style.padding(12).bg("#ecfdf5").rounded(8).margin(8, 0, 0, 0))(
                            "Element with data-testid='second-element' and data-count='42'"
                        ),
                    // title attribute (shows tooltip on hover)
                    div.cls("tooltip-el").attr("title", "This is a tooltip!").style(
                        Style.padding(12).bg("#fef3c7").rounded(8).margin(8, 0, 0, 0).cursor(_.pointer)
                    )(
                        "Hover me for a tooltip (title attribute)"
                    )
                ),
                // Reactive .attr() with Signal[String]
                section.cls("reactive-attrs").style(card)(
                    h3("Reactive Attributes"),
                    p.style(Style.fontSize(13).color("#64748b"))("Attribute value changes via signal:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("cycle-attr-btn").style(btn).onClick {
                            attrValue.getAndUpdate {
                                case "data-initial" => "data-updated"
                                case "data-updated" => "data-final"
                                case _              => "data-initial"
                            }.unit
                        }("Cycle Attribute Value"),
                        span.cls("attr-display").style(Style.bold)(attrValue.map(v => s"Current: $v"))
                    ),
                    div.cls("reactive-attr-el").attr("data-state", attrValue).style(
                        Style.padding(12).bg("#f0f9ff").rounded(8).margin(8, 0, 0, 0)
                    )(
                        "This element's data-state attribute changes reactively"
                    ),
                    // Dynamic title
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        input.cls("title-input").value(dynamicTitle).onInput(dynamicTitle.set(_)).placeholder("Set title..."),
                        div.cls("dynamic-title-el").attr("title", dynamicTitle).style(
                            Style.padding(8).bg("#fef3c7").rounded(4).cursor(_.pointer)
                        )("Hover — title updates from input")
                    )
                ),
                // Generic .on() event handler
                section.cls("generic-events").style(card)(
                    h3("Generic Event Handlers (.on)"),
                    p.style(Style.fontSize(13).color("#64748b"))("Using .on() for custom event binding:"),
                    button.cls("on-click-btn").style(btn).on(
                        "click",
                        clickLog.getAndUpdate(_.append("on('click') fired")).unit
                    )("Click via .on('click')"),
                    div.cls("on-dblclick-el").style(
                        Style.padding(16).bg("#fdf2f8").rounded(8).margin(8, 0, 0, 0).cursor(_.pointer)
                    ).on(
                        "dblclick",
                        clickLog.getAndUpdate(_.append("on('dblclick') fired")).unit
                    )(
                        "Double-click me (using .on('dblclick'))"
                    ),
                    // Event log
                    div.cls("event-log").style(Style.maxHeight(150).overflow(_.scroll).padding(8).bg("#f9fafb")
                        .rounded(8).margin(8, 0, 0, 0).fontSize(12).gap(2))(
                        clickLog.foreachIndexed { (idx, entry) =>
                            div.style(Style.color("#64748b"))(s"${idx + 1}. $entry")
                        }
                    ),
                    button.cls("clear-log-btn").style(Style.padding(4, 12).rounded(4).border(1, "#ccc")
                        .cursor(_.pointer).fontSize(12).margin(8, 0, 0, 0)).onClick(
                        clickLog.set(Chunk.empty).unit
                    )("Clear Log")
                )
            )
        )

end GenericAttrUI
