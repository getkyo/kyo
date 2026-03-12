package demo

import kyo.*
import kyo.UI.Keyboard
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Comprehensive TUI showcase — exercises every QA-tested element and edge case.
  *
  * Run: sbt 'kyo-ui/runMain demo.TuiShowcase'
  *
  * Navigate: Tab/Shift-Tab between widgets, Arrow keys within widgets, Ctrl-C to exit.
  */
object TuiShowcase extends KyoApp:

    private val PageCount = 7

    private def pageTitle(n: Int): String = n match
        case 0 => "Text & Containers"
        case 1 => "Inputs & Forms"
        case 2 => "Select, Checkbox & Radio"
        case 3 => "Tables & Lists"
        case 4 => "Layout & Flexbox"
        case 5 => "Styling & Borders"
        case 6 => "Overflow & Edge Cases"
        case _ => ""

    run {
        for
            // ---- Global state ----
            page <- Signal.initRef(0)
            log  <- Signal.initRef("")
            // ---- Page 1 state (text inputs need SignalRef to be editable) ----
            nameVal     <- Signal.initRef("")
            emailVal    <- Signal.initRef("")
            passwordVal <- Signal.initRef("hunter2")
            textareaVal <- Signal.initRef("")
            numberVal   <- Signal.initRef("50")
            // ---- Page 2 state ----
            checkVal   <- Signal.initRef(false)
            radioGroup <- Signal.initRef("opt1")
            selVal     <- Signal.initRef("r")
            // ---- Page 5 state ----
            highlight <- Signal.initRef(false)
            // ---- Page 6 state ----
            showVisible <- Signal.initRef(true)
            listItems   <- Signal.initRef(Chunk("Alpha", "Beta", "Gamma"))
            focusInput  <- Signal.initRef("")
            labelInput  <- Signal.initRef("")
            // ---- Build & run ----
            session <- Tui2Backend.render(
                buildUI(
                    page,
                    log,
                    nameVal,
                    emailVal,
                    passwordVal,
                    textareaVal,
                    numberVal,
                    checkVal,
                    radioGroup,
                    selVal,
                    highlight,
                    showVisible,
                    listItems,
                    focusInput,
                    labelInput
                )
            )
            _ <- session.await
        yield ()
    }

    private def buildUI(
        page: SignalRef[Int],
        log: SignalRef[String],
        nameVal: SignalRef[String],
        emailVal: SignalRef[String],
        passwordVal: SignalRef[String],
        textareaVal: SignalRef[String],
        numberVal: SignalRef[String],
        checkVal: SignalRef[Boolean],
        radioGroup: SignalRef[String],
        selVal: SignalRef[String],
        highlight: SignalRef[Boolean],
        showVisible: SignalRef[Boolean],
        listItems: SignalRef[Chunk[String]],
        focusInput: SignalRef[String],
        labelInput: SignalRef[String]
    )(using Frame): UI =
        div.style(Style.height(100.pct))(
            // ---- Header ----
            header.style(Style.row.gap(2.px).padding(0.px, 1.px).bg(Color.hex("#1a1a2e")).color(Color.hex("#e0e0ff")))(
                span.style(Style.bold)("kyo-ui Showcase"),
                span(page.map(p => s"[${p + 1}/$PageCount] ${pageTitle(p)}")),
                span.style(Style.color(Color.hex("#666")))("Tab/Arrows: navigate | PgDn/PgUp: page | Ctrl-C: exit")
            ),
            // ---- Status bar ----
            div.style(Style.padding(0.px, 1.px).bg(Color.hex("#0d0d1a")))(
                span.style(Style.color(Color.hex("#888")))(log.map(l => if l.isEmpty then " " else l))
            ),
            hr,
            // ---- Page content ----
            div.style(Style.padding(1.px, 2.px).flexGrow(1.0))
                .onKeyDown { e =>
                    e.key match
                        case Keyboard.PageDown => page.getAndUpdate(p => math.min(p + 1, PageCount - 1)).unit
                        case Keyboard.PageUp   => page.getAndUpdate(p => math.max(p - 1, 0)).unit
                        case _                 => ()
                }(
                    page.map[UI] {
                        case 0 => page0_textContainers(log, labelInput)
                        case 1 => page1_inputsForms(log, nameVal, emailVal, passwordVal, textareaVal, numberVal)
                        case 2 => page2_selectCheckboxRadio(log, checkVal, radioGroup, selVal)
                        case 3 => page3_tablesLists()
                        case 4 => page4_layoutFlexbox()
                        case 5 => page5_stylingBorders(log, highlight)
                        case 6 => page6_overflowEdge(log, showVisible, listItems, focusInput)
                        case _ => span("Unknown page")
                    }
                ),
            // ---- Footer nav ----
            footer.style(Style.row.justify(Justification.spaceBetween).padding(0.px, 1.px).bg(Color.hex("#1a1a2e")))(
                button("< Prev").onClick(page.getAndUpdate(p => math.max(p - 1, 0)).unit),
                button("Next >").onClick(page.getAndUpdate(p => math.min(p + 1, PageCount - 1)).unit)
            )
        )
    end buildUI

    // ========== PAGE 0: Text & Containers ==========
    private def page0_textContainers(log: SignalRef[String], labelInput: SignalRef[String])(using Frame): UI =
        fragment(
            h2("Headings"),
            div.style(Style.row.gap(4.px))(
                h1("H1"),
                h2("H2"),
                h3("H3"),
                h4("H4"),
                h5("H5"),
                h6("H6")
            ),
            h2("Text Rendering"),
            p(
                "This is a paragraph with word wrapping. It should break at word boundaries when the terminal is narrow enough. Resize your terminal to see it in action."
            ),
            div.style(Style.row.gap(2.px))(
                span("Plain span"),
                span.style(Style.bold)("Bold"),
                span.style(Style.italic)("Italic"),
                span.style(Style.underline)("Underline"),
                span.style(Style.strikethrough)("Strike")
            ),
            h2("Preformatted"),
            pre(
                """def hello() =
  println("  indented")
    println("    more")"""
            ),
            h2("Nested Containers"),
            div.style(Style.border(1.px, BorderStyle.solid, Color.hex("#444")).padding(1.px))(
                span("Outer"),
                div.style(Style.border(1.px, BorderStyle.dashed, Color.hex("#666")).padding(1.px))(
                    span("Middle"),
                    div.style(Style.border(1.px, BorderStyle.dotted, Color.hex("#888")).padding(1.px))(
                        span("Inner (3 levels deep)")
                    )
                )
            ),
            h2("Anchor & Label"),
            div.style(Style.row.gap(2.px))(
                label("Click label to focus input:").forId("labelTarget"),
                input.value(labelInput).id("labelTarget").placeholder("focused by label click")
            ),
            a("Kyo on GitHub").href("https://github.com/getkyo/kyo")
        )

    // ========== PAGE 1: Inputs & Forms ==========
    private def page1_inputsForms(
        log: SignalRef[String],
        nameVal: SignalRef[String],
        emailVal: SignalRef[String],
        passwordVal: SignalRef[String],
        textareaVal: SignalRef[String],
        numberVal: SignalRef[String]
    )(using Frame): UI =
        fragment(
            h2("Text Input"),
            div.style(Style.gap(1.px))(
                div.style(Style.row.gap(2.px))(
                    label("Name:"),
                    input.value(nameVal).placeholder("Type your name...").onInput(v => log.set(s"typing: $v"))
                ),
                div.style(Style.row.gap(2.px))(
                    label("Email:"),
                    email.value(emailVal).placeholder("user@example.com")
                ),
                div.style(Style.row.gap(2.px))(
                    label("Password:"),
                    password.value(passwordVal).placeholder("secret")
                ),
                div.style(Style.row.gap(2.px))(
                    label("Read-only:"),
                    input.value("Cannot edit this").readOnly(true)
                )
            ),
            h2("Textarea"),
            textarea.value(textareaVal).placeholder("Write multi-line text here...\nPress Enter for new lines.")
                .style(Style.width(40.em).height(4.em)),
            h2("Number Input"),
            div.style(Style.row.gap(2.px).align(Alignment.center))(
                label("Quantity (0-100):"),
                number.value(numberVal).min(0).max(100).step(5)
                    .onChangeNumeric(v => log.set(s"number: $v"))
            ),
            h2("Range Slider"),
            div.style(Style.row.gap(2.px).align(Alignment.center))(
                label("Volume:"),
                rangeInput.value(0.7).min(0).max(1).step(0.1)
                    .onChange(v => log.set(s"range: $v"))
            ),
            h2("Form Submit"),
            div.style(Style.row.gap(2.px))(
                input.value(nameVal).placeholder("Press Enter in input to see log"),
                button("Submit").onClick(log.set("Button clicked!")),
                span.style(Style.color(Color.hex("#888")))("(check status bar)")
            )
        )

    // ========== PAGE 2: Select, Checkbox & Radio ==========
    private def page2_selectCheckboxRadio(
        log: SignalRef[String],
        checkVal: SignalRef[Boolean],
        radioGroup: SignalRef[String],
        selVal: SignalRef[String]
    )(using Frame): UI =
        fragment(
            h2("Select (Dropdown)"),
            div.style(Style.gap(1.px))(
                div.style(Style.row.gap(2.px))(
                    label("Fruit (no signal):"),
                    // No signal binding — tests internal state (B20 fix)
                    select(
                        option("Apple").value("a"),
                        option("Banana").value("b"),
                        option("Cherry").value("c"),
                        option("Date").value("d"),
                        option("Elderberry").value("e")
                    )
                ),
                div.style(Style.row.gap(2.px))(
                    label("Color (signal-bound):"),
                    select(
                        option("Red").value("r"),
                        option("Green").value("g"),
                        option("Blue").value("b")
                    ).value(selVal).onChange(v => log.set(s"color: $v"))
                ),
                span(selVal.map(v => s"Selected color: $v")),
                div.style(Style.row.gap(2.px))(
                    label("Empty select:"),
                    select() // no options — edge case (test 15.07)
                )
            ),
            h2("Checkbox"),
            div.style(Style.gap(1.px))(
                div.style(Style.row.gap(2.px))(
                    checkbox.checked(checkVal).onChange(v => log.set(s"checked: $v")),
                    label("Accept terms")
                ),
                span(checkVal.map(v => s"Value: $v")),
                div.style(Style.row.gap(2.px))(
                    checkbox.disabled(true),
                    label("Disabled checkbox")
                )
            ),
            h2("Radio Buttons"),
            div.style(Style.gap(1.px))(
                div.style(Style.row.gap(2.px))(
                    radio.name("group1").checked(radioGroup.map(_ == "opt1"))
                        .onChange(v => if v then radioGroup.set("opt1").andThen(log.set("radio: opt1")) else ()),
                    label("Option 1")
                ),
                div.style(Style.row.gap(2.px))(
                    radio.name("group1").checked(radioGroup.map(_ == "opt2"))
                        .onChange(v => if v then radioGroup.set("opt2").andThen(log.set("radio: opt2")) else ()),
                    label("Option 2")
                ),
                div.style(Style.row.gap(2.px))(
                    radio.name("group1").checked(radioGroup.map(_ == "opt3"))
                        .onChange(v => if v then radioGroup.set("opt3").andThen(log.set("radio: opt3")) else ()),
                    label("Option 3")
                ),
                span(radioGroup.map(v => s"Selected: $v"))
            )
        )

    // ========== PAGE 3: Tables & Lists ==========
    private def page3_tablesLists()(using Frame): UI =
        fragment(
            h2("Table"),
            table(
                tr(th("Name"), th("Role"), th("Status")),
                tr(td("Alice"), td("Engineer"), td.style(Style.color(Color.green))("Active")),
                tr(td("Bob"), td("Designer"), td.style(Style.color(Color.yellow))("Away")),
                tr(td("Charlie"), td("Manager"), td.style(Style.color(Color.red))("Offline"))
            ),
            h2("Table with Colspan"),
            table(
                tr(th("Category").colspan(2), th("Count")),
                tr(td("Fruits"), td("Apple"), td("42")),
                tr(td("Vegs"), td("Carrot"), td("17"))
            ),
            h2("Unordered List"),
            ul(
                li("First item"),
                li("Second item with longer text"),
                li("Third item")
            ),
            h2("Ordered List"),
            ol(
                li("Step one: read the docs"),
                li("Step two: write some code"),
                li("Step three: profit")
            ),
            h2("Nested List"),
            ul(
                li("Parent A"),
                li(ul(
                    li("Child A.1"),
                    li("Child A.2")
                )),
                li("Parent B"),
                li(ul(
                    li("Child B.1")
                ))
            )
        )

    // ========== PAGE 4: Layout & Flexbox ==========
    private def page4_layoutFlexbox()(using Frame): UI =
        def box(text: String, c: String, extra: Style = Style.empty): UI =
            span.style(Style.bg(Color.hex(c)).padding(0.px, 1.px) ++ extra)(text)

        fragment(
            h2("Row Direction"),
            div.style(Style.row.gap(1.px))(
                box("AAA", "#633"),
                box("BBB", "#363"),
                box("CCC", "#336")
            ),
            h2("Column Direction (default)"),
            div.style(Style.gap(0.px).border(1.px, Color.hex("#444")))(
                box("Row 1", "#633"),
                box("Row 2", "#363"),
                box("Row 3", "#336")
            ),
            h2("Flex Grow"),
            div.style(Style.row.gap(1.px))(
                box("fixed", "#633"),
                box("grows to fill", "#336", Style.flexGrow(1.0)),
                box("fixed", "#633")
            ),
            h2("Flex Wrap"),
            div.style(Style.row.flexWrap(Style.FlexWrap.wrap).gap(1.px).width(30.em).border(1.px, Color.hex("#444")))(
                box("AAAA", "#633"),
                box("BBBB", "#363"),
                box("CCCC", "#336"),
                box("DDDD", "#633"),
                box("EEEE", "#363"),
                box("FFFF", "#336")
            ),
            h2("Justify Content"),
            div.style(Style.row.justify(Justification.spaceBetween).border(1.px, Color.hex("#444")).width(40.em))(
                box("L", "#633"),
                box("M", "#363"),
                box("R", "#336")
            ),
            div.style(Style.row.justify(Justification.center).border(1.px, Color.hex("#444")).width(40.em))(
                box("CENTER", "#336")
            ),
            h2("Align Items"),
            div.style(Style.row.align(Alignment.center).height(5.em).border(1.px, Color.hex("#444")))(
                box("top\ntext", "#633"),
                box("mid", "#363"),
                box("low\ntext\nhere", "#336")
            ),
            h2("Padding & Margin"),
            div.style(Style.border(1.px, Color.hex("#444")))(
                div.style(Style.bg(Color.hex("#336")).padding(1.px, 2.px).margin(1.px))(
                    span("1px margin + 1px/2px padding")
                )
            ),
            h2("Width & Height Constraints"),
            div.style(Style.row.gap(1.px))(
                div.style(Style.width(10.em).height(3.em).border(1.px, Color.hex("#633")))(span("10x3")),
                div.style(Style.width(15.em).height(3.em).border(1.px, Color.hex("#363")))(span("15x3")),
                div.style(Style.width(8.em).height(3.em).border(1.px, Color.hex("#336")))(span("8x3"))
            )
        )
    end page4_layoutFlexbox

    // ========== PAGE 5: Styling & Borders ==========
    private def page5_stylingBorders(log: SignalRef[String], highlight: SignalRef[Boolean])(using Frame): UI =
        fragment(
            h2("Text Transforms"),
            div.style(Style.row.gap(2.px))(
                span.style(Style.textTransform(TextTransform.uppercase))("upper"),
                span.style(Style.textTransform(TextTransform.lowercase))("LOWER"),
                span.style(Style.textTransform(TextTransform.capitalize))("capitalize me")
            ),
            h2("Text Alignment"),
            div.style(Style.width(30.em).border(1.px, Color.hex("#444")))(
                p.style(Style.textAlign(TextAlign.left))("Left aligned"),
                p.style(Style.textAlign(TextAlign.center))("Center aligned"),
                p.style(Style.textAlign(TextAlign.right))("Right aligned")
            ),
            h2("Text Overflow"),
            div.style(Style.width(20.em).border(1.px, Color.hex("#444")))(
                span.style(Style.textOverflow(TextOverflow.ellipsis))("This text is too long and will be truncated with ellipsis")
            ),
            h2("Colors"),
            div.style(Style.row.gap(1.px))(
                span.style(Style.bg(Color.red).padding(0.px, 1.px))("Red"),
                span.style(Style.bg(Color.green).padding(0.px, 1.px))("Green"),
                span.style(Style.bg(Color.blue).color(Color.white).padding(0.px, 1.px))("Blue"),
                span.style(Style.bg(Color.yellow).color(Color.black).padding(0.px, 1.px))("Yellow"),
                span.style(Style.bg(Color.purple).padding(0.px, 1.px))("Purple"),
                span.style(Style.bg(Color.hex("#ff6600")).padding(0.px, 1.px))("Orange")
            ),
            h2("Border Styles"),
            div.style(Style.row.gap(2.px))(
                div.style(Style.border(1.px, BorderStyle.solid, Color.hex("#888")).padding(1.px))(span("Solid")),
                div.style(Style.border(1.px, BorderStyle.dashed, Color.hex("#888")).padding(1.px))(span("Dashed")),
                div.style(Style.border(1.px, BorderStyle.dotted, Color.hex("#888")).padding(1.px))(span("Dotted")),
                div.style(Style.rounded(1.px).border(1.px, Color.hex("#888")).padding(1.px))(span("Rounded"))
            ),
            h2("Partial Border"),
            div.style(Style.borderTop(1.px, Color.hex("#4488ff")).padding(1.px))(
                span("Top border only")
            ),
            h2("Display None"),
            div.style(Style.row.gap(1.px))(
                span("A"),
                span.style(Style.displayNone)("HIDDEN"),
                span("B"),
                span("C")
            ),
            span("(B follows A directly — hidden element takes no space)"),
            h2("Reactive Styles"),
            div.style(Style.row.gap(2.px))(
                button("Toggle highlight").onClick(highlight.getAndUpdate(!_).unit),
                span.style(highlight.map(h =>
                    if h then Style.bg(Color.hex("#663333")).bold.padding(0.px, 1.px)
                    else Style.bg(Color.hex("#333366")).padding(0.px, 1.px)
                ))("Reactive style")
            ),
            h2("Disabled Button"),
            div.style(Style.row.gap(2.px))(
                button("Enabled").onClick(log.set("clicked enabled")),
                button("Disabled").disabled(true)
            )
        )

    // ========== PAGE 6: Overflow & Edge Cases ==========
    private def page6_overflowEdge(
        log: SignalRef[String],
        showVisible: SignalRef[Boolean],
        listItems: SignalRef[Chunk[String]],
        focusInput: SignalRef[String]
    )(using Frame): UI =
        fragment(
            h2("Overflow: Hidden (B31 fix)"),
            span("Container is 20 chars wide, text is longer:"),
            div.style(Style.width(20.em).overflow(Overflow.hidden).border(1.px, Color.hex("#633")))(
                span("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
            ),
            h2("Overflow: Scroll (B32 fix)"),
            span("Container is 4 lines tall, scroll with mouse wheel:"),
            div.style(Style.overflow(Overflow.scroll).height(4.em).width(30.em).border(1.px, Color.hex("#336")))(
                span("Line 1: The quick brown fox"),
                span("Line 2: jumps over the lazy dog"),
                span("Line 3: Pack my box with"),
                span("Line 4: five dozen liquor jugs"),
                span("Line 5: How vexingly quick"),
                span("Line 6: daft zebras jump"),
                span("Line 7: The five boxing wizards"),
                span("Line 8: jump quickly")
            ),
            h2("Conditional Rendering (UI.when)"),
            div.style(Style.row.gap(2.px))(
                button("Toggle").onClick(showVisible.getAndUpdate(!_).unit),
                UI.when(showVisible)(
                    span.style(Style.bg(Color.hex("#336")).padding(0.px, 1.px))("VISIBLE")
                ),
                span("always here")
            ),
            h2("Reactive List (foreach)"),
            div.style(Style.row.gap(2.px))(
                button("Add").onClick(
                    listItems.getAndUpdate(items =>
                        items.append(s"Item ${items.size + 1}")
                    ).unit
                ),
                button("Remove").onClick(
                    listItems.getAndUpdate(items =>
                        if items.size > 0 then items.dropRight(1) else items
                    ).unit
                )
            ),
            ul(
                listItems.foreachIndexed((i, item) => li(s"${i + 1}. $item"))
            ),
            h2("Empty Element (edge case)"),
            div.style(Style.border(1.px, Color.hex("#444")).width(20.em).height(2.em))(
                div() // empty div — should not crash
            ),
            h2("Deep Nesting with Borders"),
            div.style(Style.border(1.px, Color.hex("#633")).padding(0.px, 1.px))(
                div.style(Style.border(1.px, Color.hex("#363")).padding(0.px, 1.px))(
                    div.style(Style.border(1.px, Color.hex("#336")).padding(0.px, 1.px))(
                        div.style(Style.border(1.px, Color.hex("#663")).padding(0.px, 1.px))(
                            span("4 levels deep")
                        )
                    )
                )
            ),
            h2("Many Siblings (20 items, flex-wrap)"),
            div.style(Style.row.flexWrap(Style.FlexWrap.wrap).gap(1.px).width(50.em))(
                fragment(
                    (1 to 20).map(i =>
                        span.style(Style.bg(Color.hex(f"#${(i * 13) % 256}%02x${(i * 37) % 256}%02x${(i * 73) % 256}%02x"))
                            .padding(0.px, 1.px))(f"$i%02d")
                    )*
                )
            ),
            h2("Focus Events"),
            input.value(focusInput).placeholder("Focus me to see events in status bar")
                .onFocus(log.set("input focused"))
                .onBlur(log.set("input blurred")),
            h2("Tab Index Skip"),
            div.style(Style.row.gap(2.px))(
                button("A (tab=0)"),
                button("B (skipped)").tabIndex(-1),
                button("C (tab=0)")
            ),
            span("Tab should skip button B")
        )

end TuiShowcase
