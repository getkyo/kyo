package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Tests form reset/clear — programmatic clearing of all fields, signal→input→signal round-trips. */
object FormResetUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm      = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)
    private val fieldStyle = Style.margin(0, 0, 8, 0)

    def build: UI < Async =
        for
            // Form 1: Simple contact
            name    <- Signal.initRef("")
            email   <- Signal.initRef("")
            message <- Signal.initRef("")
            // Form 2: Settings with various input types
            username <- Signal.initRef("")
            theme    <- Signal.initRef("light")
            notify   <- Signal.initRef(false)
            priority <- Signal.initRef("medium")
            // Submission results
            submissions <- Signal.initRef(Chunk.empty[String])
        yield div.style(app)(
            header.style(Style.bg("#2563eb").color(Color.white).padding(16, 32))(
                h1("Form Reset & Round-Trip Tests")
            ),
            main.style(content)(
                // Form 1: Contact form with clear
                section.cls("contact-form").style(card)(
                    h3("Contact Form"),
                    p.style(Style.fontSize(13).color("#64748b"))("Fill, submit, clear, re-fill — tests signal round-trips:"),
                    form.cls("form1").onSubmit {
                        for
                            n <- name.get
                            e <- email.get
                            m <- message.get
                            _ <- submissions.getAndUpdate(_.append(s"Contact: name=$n, email=$e, msg=$m"))
                        yield ()
                    }(
                        div.style(fieldStyle)(
                            label.style(Style.bold.fontSize(13))("Name:"),
                            input.cls("name-input").value(name).onInput(name.set(_)).placeholder("Your name")
                        ),
                        div.style(fieldStyle)(
                            label.style(Style.bold.fontSize(13))("Email:"),
                            input.cls("email-input").value(email).onInput(email.set(_)).placeholder("you@example.com")
                        ),
                        div.style(fieldStyle)(
                            label.style(Style.bold.fontSize(13))("Message:"),
                            textarea.cls("message-input").value(message).onInput(message.set(_)).placeholder("Your message...")
                        ),
                        div.style(Style.row.gap(8))(
                            button.cls("submit1-btn").style(btn)("Submit"),
                            button.cls("clear1-btn").style(btnSm).onClick {
                                for
                                    _ <- name.set("")
                                    _ <- email.set("")
                                    _ <- message.set("")
                                yield ()
                            }("Clear All")
                        )
                    ),
                    // Live preview
                    div.cls("form1-preview").style(Style.padding(12).bg("#f0f9ff").rounded(8).margin(8, 0, 0, 0).fontSize(13))(
                        p(name.map(n => s"Name: ${if n.isEmpty then "(empty)" else n}")),
                        p(email.map(e => s"Email: ${if e.isEmpty then "(empty)" else e}")),
                        p(message.map(m => s"Message: ${if m.isEmpty then "(empty)" else m}"))
                    )
                ),
                // Form 2: Settings with mixed input types
                section.cls("settings-form").style(card)(
                    h3("Settings Form"),
                    p.style(Style.fontSize(13).color("#64748b"))("Mixed input types — text, select, checkbox:"),
                    div.style(fieldStyle)(
                        label.style(Style.bold.fontSize(13))("Username:"),
                        input.cls("username-input").value(username).onInput(username.set(_)).placeholder("username")
                    ),
                    div.style(fieldStyle)(
                        label.style(Style.bold.fontSize(13))("Theme:"),
                        select.cls("theme-select").value(theme).onChange(theme.set(_))(
                            option.value("light")("Light"),
                            option.value("dark")("Dark"),
                            option.value("auto")("Auto")
                        )
                    ),
                    div.style(fieldStyle.row.gap(8).align(_.center))(
                        input.cls("notify-check").typ("checkbox").checked(notify).onInput(_ => notify.getAndUpdate(!_).unit),
                        label.style(Style.bold.fontSize(13))("Enable notifications")
                    ),
                    div.style(fieldStyle)(
                        label.style(Style.bold.fontSize(13))("Priority:"),
                        select.cls("priority-select").value(priority).onChange(priority.set(_))(
                            option.value("low")("Low"),
                            option.value("medium")("Medium"),
                            option.value("high")("High")
                        )
                    ),
                    div.style(Style.row.gap(8))(
                        button.cls("save-settings-btn").style(btn).onClick {
                            for
                                u <- username.get
                                t <- theme.get
                                n <- notify.get
                                p <- priority.get
                                _ <- submissions.getAndUpdate(_.append(
                                    s"Settings: user=$u, theme=$t, notify=$n, priority=$p"
                                ))
                            yield ()
                        }("Save Settings"),
                        button.cls("reset-settings-btn").style(btnSm).onClick {
                            for
                                _ <- username.set("")
                                _ <- theme.set("light")
                                _ <- notify.set(false)
                                _ <- priority.set("medium")
                            yield ()
                        }("Reset to Defaults")
                    ),
                    // Settings preview
                    div.cls("settings-preview").style(Style.padding(12).bg("#fef3c7").rounded(8).margin(8, 0, 0, 0).fontSize(13))(
                        p(username.map(u => s"Username: ${if u.isEmpty then "(empty)" else u}")),
                        p(theme.map(t => s"Theme: $t")),
                        p(notify.map(n => s"Notifications: $n")),
                        p(priority.map(p => s"Priority: $p"))
                    )
                ),
                // Submission history
                section.cls("submission-log").style(card)(
                    h3("Submission History"),
                    UI.when(submissions.map(_.isEmpty))(
                        p.style(Style.color("#94a3b8"))("No submissions yet.")
                    ),
                    div.cls("submissions-list").style(Style.gap(4))(
                        submissions.foreachIndexed { (idx, entry) =>
                            div.style(Style.padding(8).bg(if idx % 2 == 0 then "#f8fafc" else "#ffffff").rounded(4).fontSize(13))(
                                span.style(Style.color("#64748b"))(s"#${idx + 1}: "),
                                span(entry)
                            )
                        }
                    ),
                    button.cls("clear-history-btn").style(btnSm.margin(8, 0, 0, 0)).onClick(
                        submissions.set(Chunk.empty).unit
                    )("Clear History")
                )
            )
        )

end FormResetUI
