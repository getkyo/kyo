package demo

import kyo.*
import scala.language.implicitConversions

object FormUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val labelStyle  = Style.bold.margin(8, 0, 4, 0)
    private val inputStyle  = Style.padding(8).border(1, "#ccc").rounded(4)
    private val submitStyle = DemoStyles.submitBtn

    def build: UI < Async =
        for
            nameVal     <- Signal.initRef("")
            emailVal    <- Signal.initRef("")
            textareaVal <- Signal.initRef("")
            selectVal   <- Signal.initRef("option1")
            checkVal    <- Signal.initRef(false)
            inputsOn    <- Signal.initRef(true)
            submitted   <- Signal.initRef("")
        yield div.style(app)(
            header.style(Style.bg("#dc2626").color(Color.white).padding(16, 32))(
                h1("Form Showcase")
            ),
            main.style(content)(
                // Basic form
                section.style(card)(
                    h3("Form with Submit"),
                    form.onSubmit {
                        for
                            n <- nameVal.get
                            e <- emailVal.get
                            t <- textareaVal.get
                            s <- selectVal.get
                            c <- checkVal.get
                            _ <- submitted.set(s"Name=$n, Email=$e, Text=$t, Select=$s, Check=$c")
                        yield ()
                    }(
                        label.forId("name")("Name:"),
                        input.id("name").typ("text").value(nameVal).onInput(nameVal.set(_))
                            .placeholder("Enter your name"),
                        label.forId("email")("Email:"),
                        input.id("email").typ("email").value(emailVal).onInput(emailVal.set(_))
                            .placeholder("you@example.com"),
                        label("Message:"),
                        textarea.value(textareaVal).onInput(textareaVal.set(_))
                            .placeholder("Type a message..."),
                        label("Category:"),
                        select.value(selectVal).onChange(selectVal.set(_))(
                            option.value("option1")("Option 1"),
                            option.value("option2")("Option 2"),
                            option.value("option3")("Option 3")
                        ),
                        div.style(Style.row.gap(8).align(_.center).margin(8, 0))(
                            input.typ("checkbox").checked(checkVal).onInput(_ =>
                                checkVal.getAndUpdate(!_).unit
                            ),
                            label("I agree to the terms")
                        ),
                        button.style(submitStyle)("Submit")
                    ),
                    p.style(Style.margin(12, 0, 0, 0))(
                        submitted.map(s => if s.isEmpty then "Not submitted yet" else s"Submitted: $s")
                    )
                ),
                // Disabled controls
                section.style(card)(
                    h3("Disabled Controls"),
                    div.style(Style.row.gap(12).align(_.center).margin(0, 0, 12, 0))(
                        button.style(submitStyle).onClick(inputsOn.getAndUpdate(!_).unit)(
                            inputsOn.map(on => if on then "Disable All" else "Enable All")
                        )
                    ),
                    label("Disabled Input:"),
                    input.placeholder("I can be disabled").disabled(inputsOn.map(!_)),
                    label("Disabled Textarea:"),
                    textarea.placeholder("I can be disabled too").disabled(inputsOn.map(!_)),
                    label("Disabled Select:"),
                    select.disabled(inputsOn.map(!_))(
                        option.value("a")("Alpha"),
                        option.value("b")("Beta")
                    )
                )
            )
        )

end FormUI
