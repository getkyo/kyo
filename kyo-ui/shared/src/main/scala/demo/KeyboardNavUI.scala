package demo

import kyo.*
import scala.language.implicitConversions

object KeyboardNavUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val key = Style.bg("#f1f5f9").padding(4, 10).rounded(4).fontFamily("monospace")
        .fontSize(13).border(1, "#d1d5db")

    def build: UI < Async =
        for
            lastKeyDown <- Signal.initRef("(none)")
            lastKeyUp   <- Signal.initRef("(none)")
            modifiers   <- Signal.initRef("")
            keyLog      <- Signal.initRef(Chunk.empty[String])
            inputText   <- Signal.initRef("")
        yield div.style(app)(
            header.style(Style.bg("#475569").color(Color.white).padding(16, 32))(
                h1("Keyboard Navigation")
            ),
            main.style(content)(
                // onKeyDown vs onKeyUp
                section.style(card)(
                    h3("onKeyDown vs onKeyUp"),
                    p.style(Style.fontSize(13).color("#64748b"))("Type in the input to see both events fire:"),
                    input.placeholder("Type here...").style(Style.padding(8, 12).rounded(4).border(1, "#d1d5db"))
                        .onKeyDown(ev => lastKeyDown.set(ev.key))
                        .onKeyUp(ev => lastKeyUp.set(ev.key)),
                    div.style(Style.row.gap(16).margin(12, 0, 0, 0))(
                        div(
                            label.style(Style.fontSize(12).color("#64748b"))("Last keyDown: "),
                            span.style(key)(lastKeyDown)
                        ),
                        div(
                            label.style(Style.fontSize(12).color("#64748b"))("Last keyUp: "),
                            span.style(key)(lastKeyUp)
                        )
                    )
                ),
                // Modifier keys
                section.style(card)(
                    h3("Modifier Keys"),
                    p.style(Style.fontSize(13).color("#64748b"))("Press any key combo (Ctrl/Alt/Shift/Meta + key):"),
                    input.placeholder("Try Ctrl+A, Shift+B...").style(Style.padding(8, 12).rounded(4).border(1, "#d1d5db"))
                        .onKeyDown { ev =>
                            val mods = Chunk(
                                if ev.ctrl then "Ctrl" else "",
                                if ev.alt then "Alt" else "",
                                if ev.shift then "Shift" else "",
                                if ev.meta then "Meta" else ""
                            ).filter(_.nonEmpty).mkString(" + ")
                            val display = if mods.nonEmpty then s"$mods + ${ev.key}" else ev.key
                            modifiers.set(display)
                        },
                    div.style(Style.margin(12, 0, 0, 0).padding(12).bg("#f8fafc").rounded(4))(
                        p("Combo: "),
                        span.style(Style.bold.fontFamily("monospace"))(modifiers)
                    )
                ),
                // Key log
                section.style(card)(
                    h3("Key Event Log"),
                    p.style(Style.fontSize(13).color("#64748b"))("Keys are logged as you type:"),
                    div.style(Style.row.gap(8).margin(0, 0, 8, 0))(
                        input.placeholder("Type to log keys...").style(Style.padding(8, 12).rounded(4).border(1, "#d1d5db"))
                            .onKeyDown { ev =>
                                keyLog.getAndUpdate { log =>
                                    val entry   = s"↓ ${ev.key}"
                                    val updated = log.append(entry)
                                    if updated.size > 10 then updated.drop(updated.size - 10) else updated
                                }.unit
                            }
                            .onKeyUp { ev =>
                                keyLog.getAndUpdate { log =>
                                    val entry   = s"↑ ${ev.key}"
                                    val updated = log.append(entry)
                                    if updated.size > 10 then updated.drop(updated.size - 10) else updated
                                }.unit
                            },
                        button.style(Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer))
                            .onClick(keyLog.set(Chunk.empty).unit)("Clear")
                    ),
                    div.style(Style.bg("#1e293b").color("#e2e8f0").padding(12).rounded(6)
                        .fontFamily("monospace").fontSize(12).minHeight(80))(
                        keyLog.foreach(entry => p(entry))
                    )
                ),
                // Focus/blur with keyboard
                section.style(card)(
                    h3("Focus Management"),
                    p.style(Style.fontSize(13).color("#64748b"))("Tab between inputs to see focus tracking:"),
                    div.style(Style.gap(8))(
                        input.placeholder("Field 1").style(
                            Style.padding(8, 12).rounded(4).border(1, "#d1d5db")
                                .focus(Style.border(2, "#3b82f6"))
                        ).onInput(inputText.set(_)),
                        input.placeholder("Field 2").style(
                            Style.padding(8, 12).rounded(4).border(1, "#d1d5db")
                                .focus(Style.border(2, "#10b981"))
                        ),
                        input.placeholder("Field 3").style(
                            Style.padding(8, 12).rounded(4).border(1, "#d1d5db")
                                .focus(Style.border(2, "#f59e0b"))
                        )
                    )
                )
            )
        )

end KeyboardNavUI
