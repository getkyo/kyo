package demo

import kyo.*
import scala.language.implicitConversions

object InteractiveUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    def build: UI < Async =
        for
            lastKey     <- Signal.initRef("")
            focusStatus <- Signal.initRef("Not focused")
            isDisabled  <- Signal.initRef(false)
        yield div.style(app)(
            header.style(Style.bg("#7c3aed").color(Color.white).padding(16, 32))(
                h1("Interactive Showcase")
            ),
            main.style(content)(
                // Hover / Active pseudo-states
                section.style(card)(
                    h3("Hover & Active States"),
                    div.style(Style.row.gap(12))(
                        button.style(
                            Style.bg("#2563eb").color(Color.white).padding(10, 24).rounded(6)
                                .borderStyle(_.none).cursor(_.pointer)
                                .hover(Style.bg("#1d4ed8"))
                                .active(Style.bg("#1e40af"))
                        )("Hover & Click Me"),
                        button.style(
                            Style.bg("#16a34a").color(Color.white).padding(10, 24).rounded(6)
                                .borderStyle(_.none).cursor(_.pointer)
                                .hover(Style.bg("#15803d"))
                        )("Green Hover")
                    )
                ),
                // Keyboard events
                section.style(card)(
                    h3("Keyboard Events"),
                    p("Type in the input below:"),
                    input.placeholder("Press any key...")
                        .onKeyDown(ev => lastKey.set(s"KeyDown: ${ev.key}")),
                    p(lastKey.map(k => if k.isEmpty then "No key pressed yet" else k))
                ),
                // Focus / Blur
                section.style(card)(
                    h3("Focus & Blur"),
                    input.placeholder("Click to focus...")
                        .onFocus(focusStatus.set("Focused"))
                        .onBlur(focusStatus.set("Blurred")),
                    p(focusStatus)
                ),
                // Disabled state
                section.style(card)(
                    h3("Disabled State"),
                    div.style(Style.row.gap(12).align(_.center))(
                        button.style(
                            Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
                                .borderStyle(_.none).cursor(_.pointer)
                        ).onClick(isDisabled.getAndUpdate(!_).unit)(
                            isDisabled.map(d => if d then "Enable" else "Disable")
                        ),
                        button.style(
                            Style.bg("#6b7280").color(Color.white).padding(8, 20).rounded(4)
                                .borderStyle(_.none)
                        ).disabled(isDisabled)("Target Button"),
                        input.placeholder("Target Input").disabled(isDisabled)
                    )
                ),
                // Cursor variants
                section.style(card)(
                    h3("Cursor Variants"),
                    div.style(Style.row.gap(8))(
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.pointer))("pointer"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.text))("text"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.move))("move"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.notAllowed))("not-allowed"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.crosshair))("crosshair"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.help))("help"),
                        span.style(Style.padding(8, 12).bg("#f3f4f6").rounded(4).cursor(_.grab))("grab")
                    )
                )
            )
        )

end InteractiveUI
