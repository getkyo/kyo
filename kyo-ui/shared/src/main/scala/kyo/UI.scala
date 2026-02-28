package kyo

import scala.language.implicitConversions

sealed abstract class UI

object UI:

    // Needed for Signal[UI] — Signal requires CanEqual to detect changes.
    given CanEqual[UI, UI] = CanEqual.derived

    // Conversions

    given stringToUI: Conversion[String, UI]               = AST.Text(_)
    given signalStringToUI: Conversion[Signal[String], UI] = AST.ReactiveText(_)
    given signalUIToUI: Conversion[Signal[UI], UI]         = AST.ReactiveNode(_)

    // Collection extensions

    extension [A](signal: Signal[Chunk[A]])
        def foreach(render: A => UI): AST.ForeachIndexed[A]               = AST.ForeachIndexed(signal, (_, a) => render(a))
        def foreachIndexed(render: (Int, A) => UI): AST.ForeachIndexed[A] = AST.ForeachIndexed(signal, render)
        def foreachKeyed(key: A => String)(render: A => UI): AST.ForeachKeyed[A] =
            AST.ForeachKeyed(signal, key, (_, a) => render(a))
        def foreachKeyedIndexed(key: A => String)(render: (Int, A) => UI): AST.ForeachKeyed[A] =
            AST.ForeachKeyed(signal, key, render)
    end extension

    // Tag constructors

    import AST.*

    val empty: UI          = Fragment(Chunk.empty)
    val div: Div           = Div()
    val p: P               = P()
    val span: Span         = Span()
    val ul: Ul             = Ul()
    val ol: Ol             = Ol()
    val li: Li             = Li()
    val nav: Nav           = Nav()
    val header: Header     = Header()
    val footer: Footer     = Footer()
    val section: Section   = Section()
    val main: Main         = Main()
    val label: Label       = Label()
    val pre: Pre           = Pre()
    val code: Code         = Code()
    val table: Table       = Table()
    val tr: Tr             = Tr()
    val td: Td             = Td()
    val th: Th             = Th()
    val h1: H1             = H1()
    val h2: H2             = H2()
    val h3: H3             = H3()
    val h4: H4             = H4()
    val h5: H5             = H5()
    val h6: H6             = H6()
    val hr: Hr             = Hr()
    val br: Br             = Br()
    val button: Button     = Button()
    val a: Anchor          = Anchor()
    val form: Form         = Form()
    val select: Select     = Select()
    val option: Option     = Option()
    val input: Input       = Input()
    val textarea: Textarea = Textarea()

    def img(src: String, alt: String): Img = Img(src = src, alt = alt)

    def fragment(cs: UI*): UI = AST.Fragment(Chunk.from(cs))

    inline def when(condition: Signal[Boolean])(ui: => UI)(using Frame): UI =
        AST.ReactiveNode(condition.map(v => if v then ui else UI.empty))

    // AST types — use `import UI.*` for tag constructors,
    // `import UI.AST.*` when pattern matching.

    object AST:

        // Base node types

        case class Text(value: String) extends UI

        case class ReactiveText(signal: Signal[String]) extends UI

        case class ReactiveNode(signal: Signal[UI]) extends UI

        case class ForeachIndexed[A](signal: Signal[Chunk[A]], render: (Int, A) => UI) extends UI

        case class ForeachKeyed[A](signal: Signal[Chunk[A]], key: A => String, render: (Int, A) => UI) extends UI

        case class Fragment(children: Chunk[UI]) extends UI

        // Key event data — universal across web and terminal backends.

        case class KeyEvent(
            key: String,
            ctrl: Boolean = false,
            alt: Boolean = false,
            shift: Boolean = false,
            meta: Boolean = false
        ) derives CanEqual

        final private[kyo] case class CommonAttrs(
            classes: Chunk[(String, Maybe[Signal[Boolean]])] = Chunk.empty,
            dynamicClassName: Maybe[Signal[String]] = Absent,
            identifier: Maybe[String] = Absent,
            style: Maybe[String | Signal[String]] = Absent,
            uiStyle: Style = Style.empty,
            hidden: Maybe[Boolean | Signal[Boolean]] = Absent,
            onClick: Maybe[Unit < Async] = Absent,
            onKeyDown: Maybe[KeyEvent => Unit < Async] = Absent,
            onKeyUp: Maybe[KeyEvent => Unit < Async] = Absent,
            onFocus: Maybe[Unit < Async] = Absent,
            onBlur: Maybe[Unit < Async] = Absent,
            attrs: Map[String, String | Signal[String]] = Map.empty,
            handlers: Map[String, Unit < Async] = Map.empty
        )

        sealed abstract class Element extends UI:
            type Self <: Element
            private[kyo] def common: CommonAttrs
            private[kyo] def withCommon(c: CommonAttrs): Self
            def children: Chunk[UI] = Chunk.empty

            def cls(v: String): Self         = withCommon(common.copy(classes = common.classes :+ (v, Absent)))
            def cls(v: Signal[String]): Self = withCommon(common.copy(dynamicClassName = Present(v)))
            def clsWhen(name: String, condition: Signal[Boolean]): Self =
                withCommon(common.copy(classes = common.classes :+ (name, Present(condition))))
            def id(v: String): Self                          = withCommon(common.copy(identifier = Present(v)))
            def style(v: String | Signal[String]): Self      = withCommon(common.copy(style = Present(v)))
            def style(v: Style): Self                        = withCommon(common.copy(uiStyle = common.uiStyle ++ v))
            def hidden(v: Boolean | Signal[Boolean]): Self   = withCommon(common.copy(hidden = Present(v)))
            def onClick(action: Unit < Async): Self          = withCommon(common.copy(onClick = Present(action)))
            def onKeyDown(f: KeyEvent => Unit < Async): Self = withCommon(common.copy(onKeyDown = Present(f)))
            def onKeyUp(f: KeyEvent => Unit < Async): Self   = withCommon(common.copy(onKeyUp = Present(f)))
            def onFocus(action: Unit < Async): Self          = withCommon(common.copy(onFocus = Present(action)))
            def onBlur(action: Unit < Async): Self           = withCommon(common.copy(onBlur = Present(action)))

            def attr(name: String, value: String | Signal[String]): Self =
                withCommon(common.copy(attrs = common.attrs + (name -> value)))

            def on(name: String, handler: Unit < Async): Self =
                withCommon(common.copy(handlers = common.handlers + (name -> handler)))
        end Element

        // Container elements (have children)

        final case class Div(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Div
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Div                     = copy(children = children ++ Chunk.from(cs))
        end Div

        final case class P(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = P
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): P                       = copy(children = children ++ Chunk.from(cs))
        end P

        final case class Span(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Span
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Span                    = copy(children = children ++ Chunk.from(cs))
        end Span

        final case class Ul(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Ul
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Ul                      = copy(children = children ++ Chunk.from(cs))
        end Ul

        final case class Li(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Li
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Li                      = copy(children = children ++ Chunk.from(cs))
        end Li

        final case class Nav(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Nav
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Nav                     = copy(children = children ++ Chunk.from(cs))
        end Nav

        final case class Header(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Header
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Header                  = copy(children = children ++ Chunk.from(cs))
        end Header

        final case class Footer(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Footer
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Footer                  = copy(children = children ++ Chunk.from(cs))
        end Footer

        final case class Section(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Section
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Section                 = copy(children = children ++ Chunk.from(cs))
        end Section

        final case class Main(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Main
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Main                    = copy(children = children ++ Chunk.from(cs))
        end Main

        final case class Label(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            forId: Maybe[String] = Absent
        ) extends Element:
            type Self = Label
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Label                   = copy(children = children ++ Chunk.from(cs))
            def forId(v: String): Label                 = copy(forId = Present(v))
            def `for`(v: String): Label                 = forId(v)
        end Label

        final case class Table(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Table
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Table                   = copy(children = children ++ Chunk.from(cs))
        end Table

        final case class Tr(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty
        ) extends Element:
            type Self = Tr
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Tr                      = copy(children = children ++ Chunk.from(cs))
        end Tr

        final case class Td(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        ) extends Element:
            type Self = Td
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Td                      = copy(children = children ++ Chunk.from(cs))
            def colspan(v: Int): Td                     = copy(colspan = Present(v))
            def rowspan(v: Int): Td                     = copy(rowspan = Present(v))
        end Td

        final case class Th(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        ) extends Element:
            type Self = Th
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Th                      = copy(children = children ++ Chunk.from(cs))
            def colspan(v: Int): Th                     = copy(colspan = Present(v))
            def rowspan(v: Int): Th                     = copy(rowspan = Present(v))
        end Th

        // Heading elements

        final case class H1(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H1
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H1                      = copy(children = children ++ Chunk.from(cs))
        end H1

        final case class H2(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H2
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H2                      = copy(children = children ++ Chunk.from(cs))
        end H2

        final case class H3(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H3
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H3                      = copy(children = children ++ Chunk.from(cs))
        end H3

        final case class H4(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H4
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H4                      = copy(children = children ++ Chunk.from(cs))
        end H4

        final case class H5(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H5
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H5                      = copy(children = children ++ Chunk.from(cs))
        end H5

        final case class H6(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = H6
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): H6                      = copy(children = children ++ Chunk.from(cs))
        end H6

        // Additional container elements

        final case class Pre(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = Pre
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Pre                     = copy(children = children ++ Chunk.from(cs))
        end Pre

        final case class Code(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = Code
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Code                    = copy(children = children ++ Chunk.from(cs))
        end Code

        final case class Ol(common: CommonAttrs = CommonAttrs(), override val children: Chunk[UI] = Chunk.empty) extends Element:
            type Self = Ol
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Ol                      = copy(children = children ++ Chunk.from(cs))
        end Ol

        // Void elements without attributes

        final case class Hr(common: CommonAttrs = CommonAttrs()) extends Element:
            type Self = Hr
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
        end Hr

        final case class Br(common: CommonAttrs = CommonAttrs()) extends Element:
            type Self = Br
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
        end Br

        // Interactive elements

        final case class Button(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent
        ) extends Element:
            type Self = Button
            private[kyo] def withCommon(c: CommonAttrs)        = copy(common = c)
            def apply(cs: UI*): Button                         = copy(children = children ++ Chunk.from(cs))
            def disabled(v: Boolean | Signal[Boolean]): Button = copy(disabled = Present(v))
        end Button

        final case class Anchor(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            href: Maybe[String | Signal[String]] = Absent,
            target: Maybe[String] = Absent
        ) extends Element:
            type Self = Anchor
            private[kyo] def withCommon(c: CommonAttrs)  = copy(common = c)
            def apply(cs: UI*): Anchor                   = copy(children = children ++ Chunk.from(cs))
            def href(v: String | Signal[String]): Anchor = copy(href = Present(v))
            def target(v: String): Anchor                = copy(target = Present(v))
        end Anchor

        final case class Form(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            onSubmit: Maybe[Unit < Async] = Absent
        ) extends Element:
            type Self = Form
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
            def apply(cs: UI*): Form                    = copy(children = children ++ Chunk.from(cs))
            def onSubmit(action: Unit < Async): Form    = copy(onSubmit = Present(action))
        end Form

        final case class Select(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            value: Maybe[String | SignalRef[String]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[(String => Unit < Async)] = Absent
        ) extends Element:
            type Self = Select
            private[kyo] def withCommon(c: CommonAttrs)        = copy(common = c)
            def apply(cs: UI*): Select                         = copy(children = children ++ Chunk.from(cs))
            def value(v: String | SignalRef[String]): Select   = copy(value = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Select = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): Select    = copy(onChange = Present(f))
        end Select

        final case class Option(
            common: CommonAttrs = CommonAttrs(),
            override val children: Chunk[UI] = Chunk.empty,
            value: Maybe[String] = Absent,
            selected: Maybe[Boolean | Signal[Boolean]] = Absent
        ) extends Element:
            type Self = Option
            private[kyo] def withCommon(c: CommonAttrs)        = copy(common = c)
            def apply(cs: UI*): Option                         = copy(children = children ++ Chunk.from(cs))
            def value(v: String): Option                       = copy(value = Present(v))
            def selected(v: Boolean | Signal[Boolean]): Option = copy(selected = Present(v))
        end Option

        // Void elements (no children)

        final case class Input(
            common: CommonAttrs = CommonAttrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            typ: Maybe[String] = Absent,
            checked: Maybe[Boolean | Signal[Boolean]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[(String => Unit < Async)] = Absent
        ) extends Element:
            type Self = Input
            private[kyo] def withCommon(c: CommonAttrs)       = copy(common = c)
            def value(v: String | SignalRef[String]): Input   = copy(value = Present(v))
            def placeholder(v: String): Input                 = copy(placeholder = Present(v))
            def typ(v: String): Input                         = copy(typ = Present(v))
            def checked(v: Boolean | Signal[Boolean]): Input  = copy(checked = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Input = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Input     = copy(onInput = Present(f))
        end Input

        final case class Textarea(
            common: CommonAttrs = CommonAttrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[(String => Unit < Async)] = Absent
        ) extends Element:
            type Self = Textarea
            private[kyo] def withCommon(c: CommonAttrs)          = copy(common = c)
            def value(v: String | SignalRef[String]): Textarea   = copy(value = Present(v))
            def placeholder(v: String): Textarea                 = copy(placeholder = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Textarea = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Textarea     = copy(onInput = Present(f))
        end Textarea

        final case class Img(
            common: CommonAttrs = CommonAttrs(),
            src: String = "",
            alt: String = ""
        ) extends Element:
            type Self = Img
            private[kyo] def withCommon(c: CommonAttrs) = copy(common = c)
        end Img

    end AST

end UI
