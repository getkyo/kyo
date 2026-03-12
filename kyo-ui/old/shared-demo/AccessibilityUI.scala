package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** .attr() for role/aria-*, label.forId, onKeyDown for keyboard activation, accessible patterns. */
object AccessibilityUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val panelHeader = Style.row.justify(_.spaceBetween).align(_.center).padding(12, 16)
        .bg("#f8fafc").cursor(_.pointer).border(1, "#e2e8f0").rounded(4)
    private val panelBody   = Style.padding(12, 16).border(1, "#e2e8f0").borderTop(0, "#e2e8f0")
    private val toggleTrack = Style.width(48).height(24).rounded(12).cursor(_.pointer).padding(2)
    private val toggleThumb = Style.width(20).height(20).rounded(10).bg(Color.white)
    private val formField   = Style.margin(0, 0, 12, 0)
    private val inputStyle  = Style.padding(8, 12).border(1, "#ccc").rounded(4)

    def build: UI < Async =
        for
            panel1Open <- Signal.initRef(false)
            panel2Open <- Signal.initRef(false)
            panel3Open <- Signal.initRef(false)
            toggleOn   <- Signal.initRef(false)
            formName   <- Signal.initRef("")
            formEmail  <- Signal.initRef("")
            statusMsg  <- Signal.initRef("Ready")
        yield
            val nameValid  = formName.map(_.length >= 2)
            val emailValid = formEmail.map(e => e.isEmpty || (e.contains("@") && e.contains(".")))

            div.style(app)(
                header.style(Style.bg("#0891b2").color(Color.white).padding(16, 32))(
                    h1("Accessibility Patterns")
                ),
                main.style(content)(
                    // Section 1: Accordion with ARIA attributes
                    section.cls("accordion").style(card)(
                        h3("Accordion"),
                        p.style(Style.fontSize(13).color("#64748b"))("Collapsible panels with role, aria-expanded, keyboard activation:"),
                        div.style(Style.gap(4))(
                            // Panel 1
                            button.cls("panel1-header").style(panelHeader)
                                .attr("aria-expanded", panel1Open.map(_.toString))
                                .onClick(panel1Open.getAndUpdate(!_).unit)(
                                    span.style(Style.bold)("Getting Started"),
                                    span(panel1Open.map(o => if o then "▼" else "▶"))
                                ),
                            UI.when(panel1Open)(
                                div.cls("panel1-body").style(panelBody)
                                    .attr("role", "region")(
                                        p("Welcome to the application. This section covers basic setup and configuration.")
                                    )
                            ),
                            // Panel 2
                            button.cls("panel2-header").style(panelHeader)
                                .attr("aria-expanded", panel2Open.map(_.toString))
                                .onClick(panel2Open.getAndUpdate(!_).unit)(
                                    span.style(Style.bold)("Advanced Features"),
                                    span(panel2Open.map(o => if o then "▼" else "▶"))
                                ),
                            UI.when(panel2Open)(
                                div.cls("panel2-body").style(panelBody)
                                    .attr("role", "region")(
                                        p("Learn about advanced features including signals, effects, and reactive rendering.")
                                    )
                            ),
                            // Panel 3
                            button.cls("panel3-header").style(panelHeader)
                                .attr("aria-expanded", panel3Open.map(_.toString))
                                .onClick(panel3Open.getAndUpdate(!_).unit)(
                                    span.style(Style.bold)("FAQ"),
                                    span(panel3Open.map(o => if o then "▼" else "▶"))
                                ),
                            UI.when(panel3Open)(
                                div.cls("panel3-body").style(panelBody)
                                    .attr("role", "region")(
                                        p("Frequently asked questions and troubleshooting tips.")
                                    )
                            )
                        )
                    ),

                    // Section 2: Toggle switch with ARIA
                    section.cls("toggle-section").style(card)(
                        h3("Toggle Switch"),
                        p.style(Style.fontSize(13).color("#64748b"))("Custom toggle with role=\"switch\" and aria-checked:"),
                        div.style(Style.row.gap(12).align(_.center))(
                            div.cls("toggle-switch").style(toggleTrack)
                                .style(toggleOn.map(on => s"background-color: ${if on then "#22c55e" else "#cbd5e1"}"))
                                .attr("role", "switch")
                                .attr("aria-checked", toggleOn.map(_.toString))
                                .attr("tabindex", "0")
                                .onClick(toggleOn.getAndUpdate(!_).unit)
                                .onKeyDown(e => if e.key == "Enter" || e.key == " " then toggleOn.getAndUpdate(!_).unit else ())(
                                    div.style(toggleThumb)
                                        .style(toggleOn.map(on => s"margin-left: ${if on then "24" else "0"}px"))
                                ),
                            span.cls("toggle-label")(toggleOn.map(on => if on then "Enabled" else "Disabled"))
                        )
                    ),

                    // Section 3: Accessible form with label association
                    section.cls("accessible-form").style(card)(
                        h3("Accessible Form"),
                        p.style(Style.fontSize(13).color("#64748b"))("Proper label/input association with aria-required, aria-invalid:"),
                        div.style(formField)(
                            label.style(Style.bold.fontSize(13)).forId("a11y-name")("Full Name *"),
                            input.cls("a11y-name-input").id("a11y-name")
                                .value(formName).onInput(v => formName.set(v).andThen(statusMsg.set("Editing...")))
                                .placeholder("Enter your name")
                                .attr("aria-required", "true")
                                .attr("aria-invalid", nameValid.map(v => (!v).toString)),
                            div.cls("a11y-name-hint").style(Style.fontSize(12).color("#94a3b8"))("Min 2 characters")
                        ),
                        div.style(formField)(
                            label.style(Style.bold.fontSize(13)).forId("a11y-email")("Email"),
                            input.cls("a11y-email-input").id("a11y-email")
                                .value(formEmail).onInput(v => formEmail.set(v).andThen(statusMsg.set("Editing...")))
                                .placeholder("you@example.com")
                                .attr("aria-invalid", emailValid.map(v => (!v).toString))
                        ),
                        button.cls("a11y-submit").style(Style.bg("#0891b2").color(Color.white).padding(8, 20).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer))
                            .onClick(statusMsg.set("Form submitted!"))(
                                "Submit"
                            )
                    ),

                    // Section 4: Live region for status messages
                    section.cls("live-region").style(card)(
                        h3("Live Region"),
                        p.style(Style.fontSize(13).color("#64748b"))("Dynamic status updates with aria-live=\"polite\":"),
                        div.cls("status-area").style(Style.padding(12).bg("#f0f9ff").rounded(8))
                            .attr("aria-live", "polite")
                            .attr("role", "status")(
                                span.cls("status-text")(statusMsg.map(identity))
                            ),
                        div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                            button.cls("status-ready").style(Style.padding(6, 12).rounded(4).border(1, "#ccc").cursor(_.pointer))
                                .onClick(statusMsg.set("Ready"))("Set Ready"),
                            button.cls("status-loading").style(Style.padding(6, 12).rounded(4).border(1, "#ccc").cursor(_.pointer))
                                .onClick(statusMsg.set("Loading..."))("Set Loading"),
                            button.cls("status-done").style(Style.padding(6, 12).rounded(4).border(1, "#ccc").cursor(_.pointer))
                                .onClick(statusMsg.set("Done!"))("Set Done")
                        )
                    )
                )
            )

end AccessibilityUI
