package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Registration form with live, reactive validation, served as a server-push app.
  *
  * Every keystroke is sent to the server, which re-derives the per-field validity signals and pushes the resulting DOM diffs back over SSE:
  * inline error messages appear and disappear, and the submit button enables only once every field is valid. On submit the server records a
  * confirmation message that replaces the form.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.Signup'` (optional port as the first argument).
  *
  * Demonstrates: the full input family (`input`, `emailInput`, `passwordInput`, `numberInput`, `dateInput`, `select`/`option`, `checkbox`),
  * two-way `value`/`checked` binding via `SignalRef`, per-field derived `Signal[Boolean]` validity, aggregate validity via
  * `Signal.combineLatestAll`, `.disabled(Signal[Boolean])` submit gating, `when` for conditional inline errors and the post-submit view, and
  * `Form.onSubmit`.
  */
object SignupDemo extends KyoApp:

    private val pageStyle  = Style.padding(24.px).fontFamily(FontFamily.SansSerif).maxWidth(420.px)
    private val fieldStyle = Style.column.gap(4.px).padding(8.px, 0.px)
    private val errStyle   = Style.color(Color.red).fontSize(13.px)
    private val okStyle    = Style.color(Color.green).padding(12.px).bg(Color.slate).rounded(8.px)

    private def error(show: Signal[Boolean], message: String) =
        when(show)(p(message).style(errStyle))

    private def formUI: UI < Async =
        for
            name      <- Signal.initRef("")
            email     <- Signal.initRef("")
            password  <- Signal.initRef("")
            age       <- Signal.initRef("")
            role      <- Signal.initRef("user")
            agreed    <- Signal.initRef(false)
            submitted <- Signal.initRef(Absent: Maybe[String])

            nameOk  = name.map(_.trim.nonEmpty)
            emailOk = email.map(e => e.contains("@") && e.contains("."))
            pwOk    = password.map(_.length >= 8)
            ageOk   = age.map(a => a.toIntOption.exists(n => n >= 18 && n <= 120))
            allOk   = Signal.combineLatestAll(Seq(nameOk, emailOk, pwOk, ageOk, agreed)).map(_.forall(identity))

            // Show an error only once the user has typed something invalid, not on the pristine empty field.
            nameErr  = name.map(v => v.nonEmpty && v.trim.isEmpty)
            emailErr = email.map(e => e.nonEmpty && !(e.contains("@") && e.contains(".")))
            pwErr    = password.map(p => p.nonEmpty && p.length < 8)
            ageErr   = age.map(a => a.nonEmpty && !a.toIntOption.exists(n => n >= 18 && n <= 120))

            submit =
                for
                    n <- name.get
                    e <- email.get
                    r <- role.get
                    _ <- submitted.set(Present(s"Welcome, $n. Confirmation sent to $e (role: $r)."))
                yield ()
        yield UI.main.style(pageStyle)(
            h1("Create your account"),
            submitted.render {
                case Present(message) =>
                    div.style(okStyle)(p(message).id("confirmation"))
                case Absent =>
                    UI.form.id("signup").onSubmit(submit)(
                        div.style(fieldStyle)(
                            label.forId("name")("Name"),
                            input.id("name").placeholder("Ada Lovelace").value(name),
                            error(nameErr, "Name cannot be blank.")
                        ),
                        div.style(fieldStyle)(
                            label.forId("email")("Email"),
                            emailInput.id("email").placeholder("you@example.com").value(email),
                            error(emailErr, "Enter a valid email address.")
                        ),
                        div.style(fieldStyle)(
                            label.forId("password")("Password"),
                            passwordInput.id("password").placeholder("at least 8 characters").value(password),
                            error(pwErr, "Password must be at least 8 characters.")
                        ),
                        div.style(fieldStyle)(
                            label.forId("age")("Age"),
                            numberInput.id("age").value(age).min(18).max(120).step(1),
                            error(ageErr, "Age must be between 18 and 120.")
                        ),
                        div.style(fieldStyle)(
                            label.forId("role")("Role"),
                            select.id("role").value(role)(
                                option.value("user")("User"),
                                option.value("admin")("Administrator"),
                                option.value("auditor")("Auditor")
                            )
                        ),
                        div.style(fieldStyle)(
                            label.style(Style.row.gap(8.px).align(Alignment.center))(
                                checkbox.id("terms").checked(agreed),
                                "I agree to the terms"
                            )
                        ),
                        button("Create account").id("submit").disabled(allOk.map(!_))
                    )
            }
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(formUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Signup running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end SignupDemo
