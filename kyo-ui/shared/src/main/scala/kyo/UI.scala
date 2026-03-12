package kyo

import scala.language.implicitConversions

sealed abstract class UI

object UI:

    given CanEqual[UI, UI] = CanEqual.derived

    // ---- Keyboard enum ----

    enum Keyboard derives CanEqual:
        case Enter, Tab, Escape, Backspace, Delete, Space
        case Home, End, Insert, PageUp, PageDown
        case ArrowUp, ArrowDown, ArrowLeft, ArrowRight
        case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
        case Char(c: scala.Char)
        case Unknown(raw: String)

        /** Returns the character for Char and Space keys. */
        def charValue: Maybe[String] = this match
            case Char(c) => Present(c.toString)
            case Space   => Present(" ")
            case _       => Absent
    end Keyboard

    object Keyboard:
        def fromString(s: String): Keyboard = s match
            case "Enter"            => Enter
            case "Tab"              => Tab
            case "Escape"           => Escape
            case "Backspace"        => Backspace
            case "Delete"           => Delete
            case " "                => Space
            case "Home"             => Home
            case "End"              => End
            case "Insert"           => Insert
            case "PageUp"           => PageUp
            case "PageDown"         => PageDown
            case "ArrowUp"          => ArrowUp
            case "ArrowDown"        => ArrowDown
            case "ArrowLeft"        => ArrowLeft
            case "ArrowRight"       => ArrowRight
            case "F1"               => F1
            case "F2"               => F2
            case "F3"               => F3
            case "F4"               => F4
            case "F5"               => F5
            case "F6"               => F6
            case "F7"               => F7
            case "F8"               => F8
            case "F9"               => F9
            case "F10"              => F10
            case "F11"              => F11
            case "F12"              => F12
            case s if s.length == 1 => Char(s.charAt(0))
            case s                  => Unknown(s)
    end Keyboard

    // ---- Target enum ----

    enum Target derives CanEqual:
        case Self, Blank, Parent, Top
    end Target

    // ---- KeyEvent ----

    case class KeyEvent(
        key: Keyboard,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false
    ) derives CanEqual

    // ---- Element base trait ----

    sealed trait Element extends UI:
        type Self <: Element
        def attrs: Attrs
        def children: kyo.Span[UI]
        private[kyo] def withAttrs(a: Attrs): Self

        // Identity & visibility
        def id(v: String): Self                        = withAttrs(attrs.copy(identifier = Present(v)))
        def hidden(v: Boolean | Signal[Boolean]): Self = withAttrs(attrs.copy(hidden = Present(v)))

        // Visual
        def style(v: Style): Self =
            val newStyle: Style | Signal[Style] = attrs.uiStyle match
                case s: Style => s ++ v
                case _        => v
            withAttrs(attrs.copy(uiStyle = newStyle))
        end style
        def style(f: Style.type => Style): Self = style(f(Style))
        def style(v: Signal[Style])(using Frame): Self =
            val composed: Signal[Style] = attrs.uiStyle match
                case base: Style if base.nonEmpty => v.map(base ++ _)
                case _                            => v
            withAttrs(attrs.copy(uiStyle = composed))
        end style
    end Element

    // ---- Interactive trait (event handlers + tabIndex) ----

    sealed trait Interactive extends Element:
        def tabIndex(v: Int): Self                       = withAttrs(attrs.copy(tabIndex = Present(v)))
        def onClick(action: Unit < Async): Self          = withAttrs(attrs.copy(onClick = Present(action)))
        def onClickSelf(action: Unit < Async): Self      = withAttrs(attrs.copy(onClickSelf = Present(action)))
        def onKeyDown(f: KeyEvent => Unit < Async): Self = withAttrs(attrs.copy(onKeyDown = Present(f)))
        def onKeyUp(f: KeyEvent => Unit < Async): Self   = withAttrs(attrs.copy(onKeyUp = Present(f)))
        def onFocus(action: Unit < Async): Self          = withAttrs(attrs.copy(onFocus = Present(action)))
        def onBlur(action: Unit < Async): Self           = withAttrs(attrs.copy(onBlur = Present(action)))
    end Interactive

    // ---- Layout traits ----

    sealed trait Block  extends Element
    sealed trait Inline extends Element

    // ---- Void trait (elements that cannot have children) ----

    sealed trait Void extends Element:
        final def children: kyo.Span[UI] = kyo.Span.empty

    // ---- Capability traits ----

    sealed trait Focusable extends Element

    sealed trait HasDisabled extends Element:
        def disabled: Maybe[Boolean | Signal[Boolean]]

    sealed trait TextInput extends Focusable with HasDisabled:
        def value: Maybe[String | SignalRef[String]]
        def placeholder: Maybe[String]
        def readOnly: Maybe[Boolean]
        def onInput: Maybe[String => Unit < Async]
        def onChange: Maybe[String => Unit < Async]
    end TextInput

    sealed trait PickerInput extends Focusable with HasDisabled:
        def value: Maybe[String | SignalRef[String]]
        def onChange: Maybe[String => Unit < Async]
    end PickerInput

    sealed trait BooleanInput extends Focusable with HasDisabled:
        def checked: Maybe[Boolean | Signal[Boolean]]
        def onChange: Maybe[Boolean => Unit < Async]
    end BooleanInput

    sealed trait Activatable extends Element
    sealed trait Clickable   extends Element

    // ====== Private implementations ======

    private[kyo] object internal:

        final case class Attrs(
            identifier: Maybe[String] = Absent,
            hidden: Maybe[Boolean | Signal[Boolean]] = Absent,
            tabIndex: Maybe[Int] = Absent,
            uiStyle: Style | Signal[Style] = Style.empty,
            onClick: Maybe[Unit < Async] = Absent,
            onClickSelf: Maybe[Unit < Async] = Absent,
            onKeyDown: Maybe[KeyEvent => Unit < Async] = Absent,
            onKeyUp: Maybe[KeyEvent => Unit < Async] = Absent,
            onFocus: Maybe[Unit < Async] = Absent,
            onBlur: Maybe[Unit < Async] = Absent
        )

        // ---- Non-element AST cases ----

        case class Text(value: String) extends UI

        case class Reactive(signal: Signal[UI]) extends UI

        case class Foreach[A](signal: Signal[Chunk[A]], key: Maybe[A => String], render: (Int, A) => UI) extends UI

        case class Fragment(children: kyo.Span[UI]) extends UI

        // ====== Block containers ======

        final case class Div(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Div
            def withAttrs(a: Attrs): Div = copy(attrs = a)
            def apply(cs: UI*): Div      = copy(children = children ++ kyo.Span.from(cs))
        end Div

        final case class P(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = P
            def withAttrs(a: Attrs): P = copy(attrs = a)
            def apply(cs: UI*): P      = copy(children = children ++ kyo.Span.from(cs))
        end P

        final case class Section(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Section
            def withAttrs(a: Attrs): Section = copy(attrs = a)
            def apply(cs: UI*): Section      = copy(children = children ++ kyo.Span.from(cs))
        end Section

        final case class Main(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Main
            def withAttrs(a: Attrs): Main = copy(attrs = a)
            def apply(cs: UI*): Main      = copy(children = children ++ kyo.Span.from(cs))
        end Main

        final case class Header(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Header
            def withAttrs(a: Attrs): Header = copy(attrs = a)
            def apply(cs: UI*): Header      = copy(children = children ++ kyo.Span.from(cs))
        end Header

        final case class Footer(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Footer
            def withAttrs(a: Attrs): Footer = copy(attrs = a)
            def apply(cs: UI*): Footer      = copy(children = children ++ kyo.Span.from(cs))
        end Footer

        final case class Pre(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Pre
            def withAttrs(a: Attrs): Pre = copy(attrs = a)
            def apply(cs: UI*): Pre      = copy(children = children ++ kyo.Span.from(cs))
        end Pre

        final case class Code(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Code
            def withAttrs(a: Attrs): Code = copy(attrs = a)
            def apply(cs: UI*): Code      = copy(children = children ++ kyo.Span.from(cs))
        end Code

        final case class Ul(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Ul
            def withAttrs(a: Attrs): Ul                                 = copy(attrs = a)
            def apply(cs: (Li | Reactive | Foreach[?] | Fragment)*): Ul = copy(children = children ++ kyo.Span.from(cs: Seq[UI]))
        end Ul

        final case class Ol(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Ol
            def withAttrs(a: Attrs): Ol                                 = copy(attrs = a)
            def apply(cs: (Li | Reactive | Foreach[?] | Fragment)*): Ol = copy(children = children ++ kyo.Span.from(cs: Seq[UI]))
        end Ol

        final case class Table(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = Table
            def withAttrs(a: Attrs): Table                                 = copy(attrs = a)
            def apply(cs: (Tr | Reactive | Foreach[?] | Fragment)*): Table = copy(children = children ++ kyo.Span.from(cs: Seq[UI]))
        end Table

        // ====== Headings (Block) ======

        final case class H1(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H1
            def withAttrs(a: Attrs): H1 = copy(attrs = a)
            def apply(cs: UI*): H1      = copy(children = children ++ kyo.Span.from(cs))
        end H1

        final case class H2(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H2
            def withAttrs(a: Attrs): H2 = copy(attrs = a)
            def apply(cs: UI*): H2      = copy(children = children ++ kyo.Span.from(cs))
        end H2

        final case class H3(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H3
            def withAttrs(a: Attrs): H3 = copy(attrs = a)
            def apply(cs: UI*): H3      = copy(children = children ++ kyo.Span.from(cs))
        end H3

        final case class H4(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H4
            def withAttrs(a: Attrs): H4 = copy(attrs = a)
            def apply(cs: UI*): H4      = copy(children = children ++ kyo.Span.from(cs))
        end H4

        final case class H5(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H5
            def withAttrs(a: Attrs): H5 = copy(attrs = a)
            def apply(cs: UI*): H5      = copy(children = children ++ kyo.Span.from(cs))
        end H5

        final case class H6(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Block with Interactive:
            type Self = H6
            def withAttrs(a: Attrs): H6 = copy(attrs = a)
            def apply(cs: UI*): H6      = copy(children = children ++ kyo.Span.from(cs))
        end H6

        // ====== Inline containers ======

        final case class Span(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Inline with Interactive:
            type Self = Span
            def withAttrs(a: Attrs): Span = copy(attrs = a)
            def apply(cs: UI*): Span      = copy(children = children ++ kyo.Span.from(cs))
        end Span

        final case class Nav(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Inline with Interactive:
            type Self = Nav
            def withAttrs(a: Attrs): Nav = copy(attrs = a)
            def apply(cs: UI*): Nav      = copy(children = children ++ kyo.Span.from(cs))
        end Nav

        final case class Li(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Inline with Interactive:
            type Self = Li
            def withAttrs(a: Attrs): Li = copy(attrs = a)
            def apply(cs: UI*): Li      = copy(children = children ++ kyo.Span.from(cs))
        end Li

        final case class Tr(attrs: Attrs = Attrs(), children: kyo.Span[UI] = kyo.Span.empty) extends Inline with Interactive:
            type Self = Tr
            def withAttrs(a: Attrs): Tr                                      = copy(attrs = a)
            def apply(cs: (Td | Th | Reactive | Foreach[?] | Fragment)*): Tr = copy(children = children ++ kyo.Span.from(cs: Seq[UI]))
        end Tr

        // ====== Specialized Block elements ======

        final case class Form(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            onSubmit: Maybe[Unit < Async] = Absent
        ) extends Block with Interactive:
            type Self = Form
            def withAttrs(a: Attrs): Form            = copy(attrs = a)
            def apply(cs: UI*): Form                 = copy(children = children ++ kyo.Span.from(cs))
            def onSubmit(action: Unit < Async): Form = copy(onSubmit = Present(action))
        end Form

        final case class Textarea(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Block with Interactive with TextInput with Void:
            type Self = Textarea
            def withAttrs(a: Attrs): Textarea                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Textarea   = copy(value = Present(v))
            def placeholder(v: String): Textarea                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Textarea                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Textarea = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Textarea     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Textarea    = copy(onChange = Present(f))
        end Textarea

        final case class Select(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            value: Maybe[String | SignalRef[String]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Block with Interactive with PickerInput:
            type Self = Select
            def withAttrs(a: Attrs): Select                                  = copy(attrs = a)
            def apply(cs: (Opt | Reactive | Foreach[?] | Fragment)*): Select = copy(children = children ++ kyo.Span.from(cs: Seq[UI]))
            def value(v: String | SignalRef[String]): Select                 = copy(value = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Select               = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): Select                  = copy(onChange = Present(f))
        end Select

        final case class Hr(attrs: Attrs = Attrs()) extends Block with Void:
            type Self = Hr
            def withAttrs(a: Attrs): Hr = copy(attrs = a)

        final case class Br(attrs: Attrs = Attrs()) extends Block with Void:
            type Self = Br
            def withAttrs(a: Attrs): Br = copy(attrs = a)

        final case class Td(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        ) extends Block with Interactive:
            type Self = Td
            def withAttrs(a: Attrs): Td = copy(attrs = a)
            def apply(cs: UI*): Td      = copy(children = children ++ kyo.Span.from(cs))
            def colspan(v: Int): Td     = copy(colspan = Present(math.max(1, v)))
            def rowspan(v: Int): Td     = copy(rowspan = Present(math.max(1, v)))
        end Td

        final case class Th(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        ) extends Block with Interactive:
            type Self = Th
            def withAttrs(a: Attrs): Th = copy(attrs = a)
            def apply(cs: UI*): Th      = copy(children = children ++ kyo.Span.from(cs))
            def colspan(v: Int): Th     = copy(colspan = Present(math.max(1, v)))
            def rowspan(v: Int): Th     = copy(rowspan = Present(math.max(1, v)))
        end Th

        final case class Label(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            forId: Maybe[String] = Absent
        ) extends Block with Interactive:
            type Self = Label
            def withAttrs(a: Attrs): Label = copy(attrs = a)
            def apply(cs: UI*): Label      = copy(children = children ++ kyo.Span.from(cs))
            def forId(v: String): Label    = copy(forId = Present(v))
            def `for`(v: String): Label    = forId(v)
        end Label

        final case class Opt(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            value: Maybe[String] = Absent,
            selected: Maybe[Boolean | Signal[Boolean]] = Absent
        ) extends Block:
            type Self = Opt
            def withAttrs(a: Attrs): Opt                    = copy(attrs = a)
            def apply(cs: UI*): Opt                         = copy(children = children ++ kyo.Span.from(cs))
            def value(v: String): Opt                       = copy(value = Present(v))
            def selected(v: Boolean | Signal[Boolean]): Opt = copy(selected = Present(v))
        end Opt

        // ====== Specialized Inline elements ======

        final case class Button(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent
        ) extends Inline with Interactive with Focusable with HasDisabled with Activatable with Clickable:
            type Self = Button
            def withAttrs(a: Attrs): Button                    = copy(attrs = a)
            def apply(cs: UI*): Button                         = copy(children = children ++ kyo.Span.from(cs))
            def disabled(v: Boolean | Signal[Boolean]): Button = copy(disabled = Present(v))
        end Button

        // ---- Boolean inputs (checked + onChange: Boolean) ----

        final case class Checkbox(
            attrs: Attrs = Attrs(),
            checked: Maybe[Boolean | Signal[Boolean]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[Boolean => Unit < Async] = Absent
        ) extends Inline with Interactive with BooleanInput with Void:
            type Self = Checkbox
            def withAttrs(a: Attrs): Checkbox                    = copy(attrs = a)
            def checked(v: Boolean | Signal[Boolean]): Checkbox  = copy(checked = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Checkbox = copy(disabled = Present(v))
            def onChange(f: Boolean => Unit < Async): Checkbox   = copy(onChange = Present(f))
        end Checkbox

        final case class Radio(
            attrs: Attrs = Attrs(),
            checked: Maybe[Boolean | Signal[Boolean]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[Boolean => Unit < Async] = Absent,
            name: Maybe[String] = Absent
        ) extends Inline with Interactive with BooleanInput with Void:
            type Self = Radio
            def withAttrs(a: Attrs): Radio                    = copy(attrs = a)
            def checked(v: Boolean | Signal[Boolean]): Radio  = copy(checked = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Radio = copy(disabled = Present(v))
            def onChange(f: Boolean => Unit < Async): Radio   = copy(onChange = Present(f))
            def name(v: String): Radio                        = copy(name = Present(v))
        end Radio

        // ---- Text inputs (value + placeholder + onInput + onChange: String) ----

        final case class Input(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = Input
            def withAttrs(a: Attrs): Input                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Input   = copy(value = Present(v))
            def placeholder(v: String): Input                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Input                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Input = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Input     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Input    = copy(onChange = Present(f))
        end Input

        final case class Password(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = Password
            def withAttrs(a: Attrs): Password                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Password   = copy(value = Present(v))
            def placeholder(v: String): Password                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Password                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Password = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Password     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Password    = copy(onChange = Present(f))
        end Password

        final case class Email(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = Email
            def withAttrs(a: Attrs): Email                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Email   = copy(value = Present(v))
            def placeholder(v: String): Email                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Email                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Email = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Email     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Email    = copy(onChange = Present(f))
        end Email

        final case class Tel(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = Tel
            def withAttrs(a: Attrs): Tel                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Tel   = copy(value = Present(v))
            def placeholder(v: String): Tel                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Tel                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Tel = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Tel     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Tel    = copy(onChange = Present(f))
        end Tel

        final case class UrlInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = UrlInput
            def withAttrs(a: Attrs): UrlInput                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): UrlInput   = copy(value = Present(v))
            def placeholder(v: String): UrlInput                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): UrlInput                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): UrlInput = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): UrlInput     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): UrlInput    = copy(onChange = Present(f))
        end UrlInput

        final case class Search(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = Search
            def withAttrs(a: Attrs): Search                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): Search   = copy(value = Present(v))
            def placeholder(v: String): Search                 = copy(placeholder = Present(v))
            def readOnly(v: Boolean): Search                   = copy(readOnly = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): Search = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): Search     = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): Search    = copy(onChange = Present(f))
        end Search

        final case class NumberInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            min: Maybe[Double] = Absent,
            max: Maybe[Double] = Absent,
            step: Maybe[Double] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onInput: Maybe[String => Unit < Async] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent,
            onChangeNumeric: Maybe[Double => Unit < Async] = Absent
        ) extends Inline with Interactive with TextInput with Void:
            type Self = NumberInput
            def withAttrs(a: Attrs): NumberInput                        = copy(attrs = a)
            def value(v: String | SignalRef[String]): NumberInput       = copy(value = Present(v))
            def placeholder(v: String): NumberInput                     = copy(placeholder = Present(v))
            def readOnly(v: Boolean): NumberInput                       = copy(readOnly = Present(v))
            def min(v: Double): NumberInput                             = copy(min = Present(v))
            def max(v: Double): NumberInput                             = copy(max = Present(v))
            def step(v: Double): NumberInput                            = copy(step = Present(if v <= 0 then 1.0 else v))
            def disabled(v: Boolean | Signal[Boolean]): NumberInput     = copy(disabled = Present(v))
            def onInput(f: String => Unit < Async): NumberInput         = copy(onInput = Present(f))
            def onChange(f: String => Unit < Async): NumberInput        = copy(onChange = Present(f))
            def onChangeNumeric(f: Double => Unit < Async): NumberInput = copy(onChangeNumeric = Present(f))
        end NumberInput

        // ---- Picker inputs (value + onChange: String, no placeholder/onInput) ----

        final case class DateInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with PickerInput with Void:
            type Self = DateInput
            def withAttrs(a: Attrs): DateInput                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): DateInput   = copy(value = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): DateInput = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): DateInput    = copy(onChange = Present(f))
        end DateInput

        final case class TimeInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with PickerInput with Void:
            type Self = TimeInput
            def withAttrs(a: Attrs): TimeInput                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): TimeInput   = copy(value = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): TimeInput = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): TimeInput    = copy(onChange = Present(f))
        end TimeInput

        final case class ColorInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with PickerInput with Void:
            type Self = ColorInput
            def withAttrs(a: Attrs): ColorInput                    = copy(attrs = a)
            def value(v: String | SignalRef[String]): ColorInput   = copy(value = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): ColorInput = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): ColorInput    = copy(onChange = Present(f))
        end ColorInput

        // ---- Special inputs ----

        final case class RangeInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Double | SignalRef[Double]] = Absent,
            min: Maybe[Double] = Absent,
            max: Maybe[Double] = Absent,
            step: Maybe[Double] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[Double => Unit < Async] = Absent
        ) extends Inline with Interactive with Focusable with HasDisabled with Void:
            type Self = RangeInput
            def withAttrs(a: Attrs): RangeInput                    = copy(attrs = a)
            def value(v: Double | SignalRef[Double]): RangeInput   = copy(value = Present(v))
            def min(v: Double): RangeInput                         = copy(min = Present(v))
            def max(v: Double): RangeInput                         = copy(max = Present(v))
            def step(v: Double): RangeInput                        = copy(step = Present(if v <= 0 then 1.0 else v))
            def disabled(v: Boolean | Signal[Boolean]): RangeInput = copy(disabled = Present(v))
            def onChange(f: Double => Unit < Async): RangeInput    = copy(onChange = Present(f))
        end RangeInput

        final case class FileInput(
            attrs: Attrs = Attrs(),
            accept: Maybe[String] = Absent,
            disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
            onChange: Maybe[String => Unit < Async] = Absent
        ) extends Inline with Interactive with Focusable with HasDisabled with Void:
            type Self = FileInput
            def withAttrs(a: Attrs): FileInput                    = copy(attrs = a)
            def accept(v: String): FileInput                      = copy(accept = Present(v))
            def disabled(v: Boolean | Signal[Boolean]): FileInput = copy(disabled = Present(v))
            def onChange(f: String => Unit < Async): FileInput    = copy(onChange = Present(f))
        end FileInput

        final case class HiddenInput(
            attrs: Attrs = Attrs(),
            value: Maybe[String | SignalRef[String]] = Absent
        ) extends Inline with Void:
            type Self = HiddenInput
            def withAttrs(a: Attrs): HiddenInput                  = copy(attrs = a)
            def value(v: String | SignalRef[String]): HiddenInput = copy(value = Present(v))
        end HiddenInput

        final case class Anchor(
            attrs: Attrs = Attrs(),
            children: kyo.Span[UI] = kyo.Span.empty,
            href: Maybe[String | Signal[String]] = Absent,
            target: Maybe[Target] = Absent
        ) extends Inline with Interactive with Focusable with Activatable with Clickable:
            type Self = Anchor
            def withAttrs(a: Attrs): Anchor                              = copy(attrs = a)
            def apply(cs: UI*): Anchor                                   = copy(children = children ++ kyo.Span.from(cs))
            def href(v: String | Signal[String]): Anchor                 = copy(href = Present(v))
            def href(v: String | Signal[String], target: Target): Anchor = copy(href = Present(v), target = Present(target))
            def target(v: Target): Anchor                                = copy(target = Present(v))
        end Anchor

        final case class Img(
            attrs: Attrs = Attrs(),
            src: Maybe[String | Signal[String]] = Absent,
            alt: Maybe[String] = Absent
        ) extends Inline with Void:
            type Self = Img
            def withAttrs(a: Attrs): Img             = copy(attrs = a)
            def src(v: String | Signal[String]): Img = copy(src = Present(v))
            def alt(v: String): Img                  = copy(alt = Present(v))
        end Img

    end internal

    import internal.*

    // ---- Type aliases ----

    type Attrs       = internal.Attrs
    type Text        = internal.Text
    type Reactive    = internal.Reactive
    type Foreach[A]  = internal.Foreach[A]
    type Fragment    = internal.Fragment
    type Div         = internal.Div
    type P           = internal.P
    type Section     = internal.Section
    type Main        = internal.Main
    type Header      = internal.Header
    type Footer      = internal.Footer
    type Pre         = internal.Pre
    type Code        = internal.Code
    type Ul          = internal.Ul
    type Ol          = internal.Ol
    type Table       = internal.Table
    type H1          = internal.H1
    type H2          = internal.H2
    type H3          = internal.H3
    type H4          = internal.H4
    type H5          = internal.H5
    type H6          = internal.H6
    type Span        = internal.Span
    type Nav         = internal.Nav
    type Li          = internal.Li
    type Tr          = internal.Tr
    type Form        = internal.Form
    type Textarea    = internal.Textarea
    type Select      = internal.Select
    type Hr          = internal.Hr
    type Br          = internal.Br
    type Td          = internal.Td
    type Th          = internal.Th
    type Label       = internal.Label
    type Opt         = internal.Opt
    type Button      = internal.Button
    type Checkbox    = internal.Checkbox
    type Radio       = internal.Radio
    type Input       = internal.Input
    type Password    = internal.Password
    type Email       = internal.Email
    type Tel         = internal.Tel
    type UrlInput    = internal.UrlInput
    type Search      = internal.Search
    type NumberInput = internal.NumberInput
    type DateInput   = internal.DateInput
    type TimeInput   = internal.TimeInput
    type ColorInput  = internal.ColorInput
    type RangeInput  = internal.RangeInput
    type FileInput   = internal.FileInput
    type HiddenInput = internal.HiddenInput
    type Anchor      = internal.Anchor
    type Img         = internal.Img

    // ---- Conversions ----

    given stringToUI: Conversion[String, UI] = Text(_)
    given signalStringToUI(using Frame): Conversion[Signal[String], UI] = sig =>
        Reactive(sig.map(s => Text(s)))
    given signalUIToUI: Conversion[Signal[UI], UI] = Reactive(_)

    // ---- Signal extensions ----

    extension [A](signal: Signal[A])
        def render(f: A => UI)(using Frame): UI = Reactive(signal.map(f))

    extension [A](signal: Signal[Chunk[A]])
        def foreach(render: A => UI): Foreach[A]               = Foreach(signal, Absent, (_, a) => render(a))
        def foreachIndexed(render: (Int, A) => UI): Foreach[A] = Foreach(signal, Absent, render)
        def foreachKeyed(key: A => String)(render: A => UI): Foreach[A] =
            Foreach(signal, Present(key), (_, a) => render(a))
        def foreachKeyedIndexed(key: A => String)(render: (Int, A) => UI): Foreach[A] =
            Foreach(signal, Present(key), render)
    end extension

    // ---- Factory constructors ----

    val empty: UI                = Fragment(kyo.Span.empty[UI])
    val div: Div                 = Div()
    val p: P                     = P()
    val span: Span               = Span()
    val ul: Ul                   = Ul()
    val ol: Ol                   = Ol()
    val li: Li                   = Li()
    val nav: Nav                 = Nav()
    val header: Header           = Header()
    val footer: Footer           = Footer()
    val section: Section         = Section()
    val main: Main               = Main()
    val label: Label             = Label()
    val pre: Pre                 = Pre()
    val code: Code               = Code()
    val table: Table             = Table()
    val tr: Tr                   = Tr()
    val td: Td                   = Td()
    val th: Th                   = Th()
    val h1: H1                   = H1()
    val h2: H2                   = H2()
    val h3: H3                   = H3()
    val h4: H4                   = H4()
    val h5: H5                   = H5()
    val h6: H6                   = H6()
    val hr: Hr                   = Hr()
    val br: Br                   = Br()
    val button: Button           = Button()
    val checkbox: Checkbox       = Checkbox()
    val radio: Radio             = Radio()
    val a: Anchor                = Anchor()
    val form: Form               = Form()
    val select: Select           = Select()
    val option: Opt              = Opt()
    val input: Input             = Input()
    val password: Password       = Password()
    val email: Email             = Email()
    val tel: Tel                 = Tel()
    val url: UrlInput            = UrlInput()
    val search: Search           = Search()
    val number: NumberInput      = NumberInput()
    val dateInput: DateInput     = DateInput()
    val timeInput: TimeInput     = TimeInput()
    val colorInput: ColorInput   = ColorInput()
    val rangeInput: RangeInput   = RangeInput()
    val fileInput: FileInput     = FileInput()
    val hiddenInput: HiddenInput = HiddenInput()
    val textarea: Textarea       = Textarea()

    def img(src: String, alt: String): Img = Img(src = Present(src), alt = Present(alt))

    def fragment(cs: UI*): UI = Fragment(kyo.Span.from(cs))

    inline def when(condition: Signal[Boolean])(ui: => UI)(using Frame): UI =
        Reactive(condition.map(v => if v then ui else UI.empty))

end UI
