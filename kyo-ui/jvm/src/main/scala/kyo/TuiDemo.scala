package demo

import kyo.*
import kyo.Length.*
import kyo.UI.foreachKeyed
import kyo.internal.tui.JvmTerminalIO
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

object TuiDemo extends KyoApp:

    case class Entry(name: String, email: String, role: String, agreed: Boolean) derives CanEqual

    run {
        for
            nameRef    <- Signal.initRef("")
            emailRef   <- Signal.initRef("")
            passRef    <- Signal.initRef("")
            roleRef    <- Signal.initRef("developer")
            entriesRef <- Signal.initRef(Chunk.empty[Entry])

            terminal = new JvmTerminalIO

            session <- TuiBackend.render(
                terminal,
                UI.div(
                    UI.h1("Kyo UI — Form Demo"),
                    UI.hr,
                    UI.div.style(Style.row.gap(2.px))(
                        // Left: form
                        UI.div.style(Style.width(40.px))(
                            UI.h2("New Entry"),
                            UI.form.onSubmit {
                                for
                                    name  <- nameRef.get
                                    email <- emailRef.get
                                    role  <- roleRef.get
                                    prev  <- entriesRef.get
                                    _     <- entriesRef.set(prev.append(Entry(name, email, role, true)))
                                    _     <- nameRef.set("")
                                    _     <- emailRef.set("")
                                    _     <- passRef.set("")
                                yield ()
                            }(
                                UI.div(
                                    UI.label("Name:"),
                                    UI.input.value(nameRef).placeholder("John Doe")
                                ),
                                UI.div(
                                    UI.label("Email:"),
                                    UI.email.value(emailRef).placeholder("john@example.com")
                                ),
                                UI.div(
                                    UI.label("Password:"),
                                    UI.password.value(passRef).placeholder("••••••")
                                ),
                                UI.div(
                                    UI.label("Role:"),
                                    UI.select.value(roleRef)(
                                        UI.option.value("developer")("Developer"),
                                        UI.option.value("designer")("Designer"),
                                        UI.option.value("manager")("Manager")
                                    )
                                ),
                                UI.div.style(Style.row)(
                                    UI.checkbox.checked(true),
                                    UI.span(" I agree to the terms")
                                ),
                                UI.button("Submit")
                            )
                        ),
                        // Right: submissions table
                        UI.div.style(Style.width(50.px))(
                            UI.h2("Submissions"),
                            UI.table(
                                UI.tr(
                                    UI.th("Name"),
                                    UI.th("Email"),
                                    UI.th("Role")
                                ),
                                entriesRef.foreachKeyed(e => e.name + e.email) { entry =>
                                    UI.tr(
                                        UI.td(entry.name),
                                        UI.td(entry.email),
                                        UI.td(entry.role)
                                    )
                                }
                            )
                        )
                    )
                )
            )
            _ <- session.await
        yield ()
    }
end TuiDemo
