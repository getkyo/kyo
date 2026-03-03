package demo

import kyo.*
import scala.language.implicitConversions

/** Computed signal chains, reactive disabled, .clsWhen(), label.forId association. */
object FormValidationUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val fieldStyle  = Style.margin(0, 0, 12, 0)
    private val labelStyle  = Style.bold.fontSize(13).margin(0, 0, 4, 0)
    private val inputStyle  = Style.padding(8, 12).border(1, "#ccc").rounded(4)
    private val errorStyle  = Style.fontSize(12).color("#ef4444").margin(2, 0, 0, 0)
    private val validStyle  = Style.fontSize(12).color("#22c55e").margin(2, 0, 0, 0)
    private val btn         = Style.bg("#2563eb").color(Color.white).padding(10, 24).rounded(4).borderStyle(_.none).cursor(_.pointer)
    private val btnDisabled = Style.bg("#94a3b8").color(Color.white).padding(10, 24).rounded(4).borderStyle(_.none)
    private val statusDot   = Style.width(12).height(12).rounded(6)

    def build: UI < Async =
        for
            username  <- Signal.initRef("")
            email     <- Signal.initRef("")
            password  <- Signal.initRef("")
            confirm   <- Signal.initRef("")
            submitted <- Signal.initRef(false)
        yield
            // Derived validation signals (computed chains: field → isValid → errorMsg)
            val usernameValid = username.map(_.length >= 3)
            val usernameError = username.map(u => if u.isEmpty then "" else if u.length < 3 then "Min 3 characters" else "")

            val emailValid = email.map(e => e.contains("@") && e.contains("."))
            val emailError =
                email.map(e => if e.isEmpty then "" else if !e.contains("@") || !e.contains(".") then "Must be a valid email" else "")

            val passwordValid = password.map(_.length >= 8)
            val passwordError = password.map(p => if p.isEmpty then "" else if p.length < 8 then "Min 8 characters" else "")

            // Multi-level derivation: confirm depends on password AND confirm values
            val confirmValid = password.zip(confirm).map((p, c) => c.nonEmpty && c == p)
            val confirmError = password.zip(confirm).map((p, c) =>
                if c.isEmpty then "" else if c != p then "Passwords don't match" else ""
            )

            // Top-level computed: all fields valid (chained zip)
            val allValid = usernameValid.zip(emailValid).flatMap { case (u, e) =>
                passwordValid.zip(confirmValid).map((p, c) => u && e && p && c)
            }

            div.style(app)(
                header.style(Style.bg("#059669").color(Color.white).padding(16, 32))(
                    h1("Form Validation")
                ),
                main.style(content)(
                    section.cls("registration").style(card)(
                        h3("Registration"),
                        p.style(Style.fontSize(13).color("#64748b"))("Live validation with computed signal chains:"),

                        // Username field with label association
                        div.cls("username-field").style(fieldStyle)(
                            label.style(labelStyle).forId("username")("Username:"),
                            input.cls("username-input").id("username")
                                .value(username).onInput(username.set(_))
                                .placeholder("Choose a username")
                                .clsWhen("field-error", usernameValid.map(!_)),
                            div.cls("username-error").style(errorStyle)(usernameError),
                            div.cls("username-ok").style(validStyle)(
                                usernameValid.map(v => if v then "Valid" else "")
                            )
                        ),

                        // Email field
                        div.cls("email-field").style(fieldStyle)(
                            label.style(labelStyle).forId("email")("Email:"),
                            input.cls("email-input").id("email")
                                .value(email).onInput(email.set(_))
                                .placeholder("you@example.com")
                                .clsWhen("field-error", emailValid.map(!_)),
                            div.cls("email-error").style(errorStyle)(emailError)
                        ),

                        // Password field
                        div.cls("password-field").style(fieldStyle)(
                            label.style(labelStyle).forId("password")("Password:"),
                            input.cls("password-input").id("password")
                                .value(password).onInput(password.set(_))
                                .placeholder("Min 8 characters")
                                .clsWhen("field-error", passwordValid.map(!_)),
                            div.cls("password-error").style(errorStyle)(passwordError)
                        ),

                        // Confirm password
                        div.cls("confirm-field").style(fieldStyle)(
                            label.style(labelStyle).forId("confirm")("Confirm Password:"),
                            input.cls("confirm-input").id("confirm")
                                .value(confirm).onInput(confirm.set(_))
                                .placeholder("Re-enter password")
                                .clsWhen("field-error", confirmValid.map(!_)),
                            div.cls("confirm-error").style(errorStyle)(confirmError)
                        ),

                        // Submit with reactive disabled
                        button.cls("submit-btn").style(btn)
                            .disabled(allValid.map(!_))
                            .onClick(submitted.set(true))(
                                "Register"
                            ),
                        div.cls("submit-status").style(Style.margin(8, 0, 0, 0).fontSize(13))(
                            submitted.map(s => if s then "Submitted!" else "")
                        )
                    ),

                    // Validation summary — shows computed chain results
                    section.cls("validation-summary").style(card)(
                        h3("Validation Summary"),
                        p.style(Style.fontSize(13).color("#64748b"))("Computed signal chain: field → isValid → allValid:"),
                        div.style(Style.gap(8))(
                            div.cls("status-username").style(Style.row.gap(8).align(_.center))(
                                div.style(statusDot).style(usernameValid.map(v =>
                                    s"background-color: ${if v then "#22c55e" else "#ef4444"}"
                                )),
                                span("Username")
                            ),
                            div.cls("status-email").style(Style.row.gap(8).align(_.center))(
                                div.style(statusDot).style(emailValid.map(v => s"background-color: ${if v then "#22c55e" else "#ef4444"}")),
                                span("Email")
                            ),
                            div.cls("status-password").style(Style.row.gap(8).align(_.center))(
                                div.style(statusDot).style(passwordValid.map(v =>
                                    s"background-color: ${if v then "#22c55e" else "#ef4444"}"
                                )),
                                span("Password")
                            ),
                            div.cls("status-confirm").style(Style.row.gap(8).align(_.center))(
                                div.style(statusDot).style(confirmValid.map(v =>
                                    s"background-color: ${if v then "#22c55e" else "#ef4444"}"
                                )),
                                span("Confirm")
                            ),
                            hr,
                            div.cls("status-all").style(Style.row.gap(8).align(_.center).bold)(
                                div.style(statusDot).style(allValid.map(v => s"background-color: ${if v then "#22c55e" else "#ef4444"}")),
                                span("All Valid"),
                                span(allValid.map(v => if v then "(ready to submit)" else "(incomplete)"))
                            )
                        )
                    )
                )
            )

end FormValidationUI
