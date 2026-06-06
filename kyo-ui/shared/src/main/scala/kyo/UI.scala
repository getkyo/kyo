package kyo

import kyo.internal.HtmlRenderer
import kyo.internal.ReactiveUI
import kyo.internal.UIExchange
import kyo.internal.UIServer
import scala.language.implicitConversions

/** An immutable description of a DOM subtree.
  *
  * A `UI` is a pure value, not a live widget. Building one allocates plain case classes and runs no effects; the same value can be rendered
  * repeatedly and shared freely. There are three kinds of node:
  *
  *   - **Elements** (`Div`, `Button`, `Input`, ...): the HTML tags, each an [[kyo.UI.Ast.Element]] case class carrying its attributes and
  *     children.
  *   - **Text** (`Text`): a literal text node; a bare `String` child auto-lifts to one.
  *   - **Reactive nodes** (`Reactive`, `Foreach`): subtrees driven by a [[kyo.Signal]]; they re-render when the signal emits.
  *
  * Elements are built with a fluent style. Each attribute/event setter returns the element's own concrete type (`Self`), so chains stay
  * typed and container factories can restrict their children at the type level. Reactivity enters through setters and child slots that
  * accept a `Signal[A]` (or `SignalRef[A]` for two-way binding) in place of a plain `A`; those overloads return `UI` because they cross
  * the reactive boundary.
  *
  * The same `UI` value runs three ways, changing only the runner:
  *
  *   - `runMount` (Scala.js): mounts to the live DOM as a single-page app.
  *   - `runHandlers`: exposes it as a server-push HTTP triple (page + events + SSE diff stream).
  *   - `runRender`: emits a `Stream[String, Async]` of full-page HTML for SSR, tests, or custom transports.
  *
  * @see
  *   [[kyo.UI.div]], [[kyo.UI.button]], [[kyo.UI.input]] and the other factory constructors for building elements
  * @see
  *   [[kyo.UI.when]], [[kyo.UI.render]] and [[kyo.Signal]] for reactive nodes
  * @see
  *   [[kyo.UI.runHandlers]], [[kyo.UI.runRender]] for running a UI value
  * @see
  *   [[kyo.UI.Ast]] for the case-class AST that every factory returns
  */
sealed abstract class UI:
    private[kyo] def frame: Frame

/** Companion of [[kyo.UI]]: factory constructors, the [[kyo.UI.Ast]] node types, and the runners.
  *
  * Import `kyo.UI.*` to bring the element factories (`div`, `button`, `input`, ...), the reactive combinators (`when`, the `Signal`
  * extensions), and the implicit `String`/`Signal` lifts into scope.
  *
  * @see
  *   [[kyo.UI]] for the conceptual overview of the UI tree
  */
object UI:

    given CanEqual[UI, UI] = CanEqual.derived

    import Ast.*

    /** Auto-lifts a bare `String` into a [[kyo.UI.Ast.Text]] node, so string literals can be passed directly wherever a `UI` child is
      * expected. Returns `Text` (not the wider `UI`) so that the static type satisfies `HtmlContent` in container `apply` overloads.
      */
    implicit def stringToUI(v: String)(using Frame): Text = Text(v)

    /** Auto-lifts a `Signal[String]` into a reactive text node that re-renders whenever the signal emits. */
    implicit def signalStringToUI(v: Signal[String])(using Frame): Reactive[Text] = Reactive[Text](v.map(s => Text(s)))

    /** Auto-lifts a `Signal[A]` of UI values into a reactive node that swaps in the latest emitted subtree on every emission. */
    implicit def signalUIToUI[A <: UI](v: Signal[A])(using Frame, CanEqual[A, A]): Reactive[A] = Reactive[A](v.map(x => x: UI))

    // ---- Signal extensions ----

    extension [A](signal: Signal[A])
        /** Projects a signal into a reactive subtree: `f` maps each emitted value to a `C`, and the region re-renders on every emission. */
        def render[C <: UI](f: A => C)(using Frame): Reactive[C] = Reactive[C](signal.map(a => f(a): UI))

    extension [A](signal: Signal[Chunk[A]])
        /** Renders one child per element of the collection signal, re-rendering when the chunk changes. Elements are reconciled by
          * position; for stable identity across reorders and insertions use [[foreachKeyed]].
          */
        def foreach[C <: UI](render: A => C)(using Frame): Foreach[A, C] = Foreach[A, C](signal, Absent, (_, a) => render(a))

        /** Like [[foreach]] but `render` also receives each element's index. */
        def foreachIndexed[C <: UI](render: (Int, A) => C)(using Frame): Foreach[A, C] = Foreach[A, C](signal, Absent, render)

        /** Renders one child per element, identifying elements by `key` so reorders and insertions reuse the matching existing nodes
          * instead of re-rendering positionally.
          */
        def foreachKeyed[C <: UI](key: A => String)(render: A => C)(using Frame): Foreach[A, C] =
            Foreach[A, C](signal, Present(key), (_, a) => render(a))

        /** Keyed [[foreachKeyed]] whose `render` also receives each element's index. */
        def foreachKeyedIndexed[C <: UI](key: A => String)(render: (Int, A) => C)(using Frame): Foreach[A, C] =
            Foreach[A, C](signal, Present(key), render)
    end extension

    // ---- Factory constructors ----

    /** The empty UI: an [[kyo.UI.Ast.Fragment]] with no children, rendering nothing. Useful as a neutral element or a conditional
      * "render nothing" branch. Returns `Fragment[Nothing]`, which an HTML container accepts because `AsHtmlChild[Fragment[Nothing]]`
      * resolves (the `Fragment` given needs `AsHtmlChild[Nothing]`, which the base `[T <: HtmlContent]` given supplies since
      * `Nothing <: HtmlContent`).
      */
    def empty(using Frame): Fragment[Nothing] = Fragment[Nothing](Chunk.empty[UI])

    def div(using Frame): Div                     = Div()
    def p(using Frame): P                         = P()
    def span(using Frame): SpanElement            = SpanElement()
    def ul(using Frame): Ul                       = Ul()
    def ol(using Frame): Ol                       = Ol()
    def li(using Frame): Li                       = Li()
    def nav(using Frame): Nav                     = Nav()
    def header(using Frame): Header               = Header()
    def footer(using Frame): Footer               = Footer()
    def section(using Frame): Section             = Section()
    def main(using Frame): Main                   = Main()
    def label(using Frame): Label                 = Label()
    def pre(using Frame): Pre                     = Pre()
    def code(using Frame): Code                   = Code()
    def table(using Frame): Table                 = Table()
    def tr(using Frame): Tr                       = Tr()
    def td(using Frame): Td                       = Td()
    def th(using Frame): Th                       = Th()
    def h1(using Frame): H1                       = H1()
    def h2(using Frame): H2                       = H2()
    def h3(using Frame): H3                       = H3()
    def h4(using Frame): H4                       = H4()
    def h5(using Frame): H5                       = H5()
    def h6(using Frame): H6                       = H6()
    def hr(using Frame): Hr                       = Hr()
    def br(using Frame): Br                       = Br()
    def button(using Frame): Button               = Button()
    def checkbox(using Frame): Checkbox           = Checkbox()
    def radio(using Frame): Radio                 = Radio()
    def a(using Frame): Anchor                    = Anchor()
    def form(using Frame): Form                   = Form()
    def select(using Frame): Select               = Select()
    def option(using Frame): Opt                  = Opt()
    def input(using Frame): Input                 = Input()
    def passwordInput(using Frame): PasswordInput = PasswordInput()
    def emailInput(using Frame): EmailInput       = EmailInput()
    def telInput(using Frame): TelInput           = TelInput()
    def urlInput(using Frame): UrlInput           = UrlInput()
    def searchInput(using Frame): SearchInput     = SearchInput()
    def numberInput(using Frame): NumberInput     = NumberInput()
    def dateInput(using Frame): DateInput         = DateInput()
    def timeInput(using Frame): TimeInput         = TimeInput()
    def colorInput(using Frame): ColorInput       = ColorInput()
    def rangeInput(using Frame): RangeInput       = RangeInput()
    def fileInput(using Frame): FileInput         = FileInput()
    def hiddenInput(using Frame): HiddenInput     = HiddenInput()
    def textarea(using Frame): Textarea           = Textarea()

    /** A custom dropdown rendered as a `div`-based overlay (not a native `<select>`), styleable like any other element. Each option is a
      * `(value, label)` pair.
      */
    def dropdown(options: (String, String)*)(using Frame): Dropdown = Dropdown(options = Chunk.from(options))

    /** An image element. `src` selects the source kind (URL, path, or inline data) and `alt` is the accessibility text. */
    def img(src: ImgSrc, alt: String)(using Frame): Img = Img(src = Present(src), alt = Present(alt))

    /** An inline frame embedding the document at `src` (a URL or `data:` URI). Give it a size with `.style` and a `.title` for accessibility. */
    def iframe(src: String)(using Frame): Iframe = Iframe(src = Present(src))

    /** Groups children into a single UI without introducing a wrapper element; the children render as siblings in the parent.
      * The type parameter `C` is inferred from the children, so `fragment(div, span)` produces `Fragment[Div | SpanElement]`,
      * which an HTML container accepts because `AsHtmlChild[Fragment[Div | SpanElement]]` resolves (the `Fragment` given on top
      * of the union given). `fragment(circle, rect)` produces a `Fragment` that fits SVG containers. A mixed `fragment(div, circle)`
      * fits neither (correct: no `AsHtmlChild` witness for the SVG side and no SVG witness for the HTML side).
      */
    def fragment[C <: UI](cs: C*)(using Frame): Fragment[C] = Fragment[C](Chunk.from(cs))

    /** Conditional rendering: shows `body` while `condition` is true and an empty node otherwise, re-evaluating when the signal emits. */
    def when[C <: UI](condition: Signal[Boolean])(body: => C)(using Frame): Reactive[C] =
        Reactive[C](condition.map(v => if v then body else UI.empty))

    /** Server-push: returns HTTP handlers (GET page + POST events + SSE stream) for this UI at the given path. Compose with other handlers
      * via HttpServer.init.
      */
    def runHandlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
        UIServer.handlers(basePath)(ui)

    /** Read-only stream of the full rendered HTML. Emits whenever any signal changes. First emission is the initial render. Useful for
      * testing, SSR, export, or custom transports.
      */
    def runRender(ui: UI)(using Frame): Stream[String, Async] =
        Stream[String, Async] {
            for initialHtml <- HtmlRenderer.render(ui, Seq.empty)
            yield Emit.valueWith(Chunk(initialHtml)) {
                kyo.Channel.use[Unit](256) { channel =>
                    val exchange =
                        new UIExchange:
                            def onChange(path: Seq[String], changedUI: UI)(using Frame): Unit < Async =
                                Abort.run[Closed](channel.put(())).unit
                    for
                        root <- ReactiveUI.normalize(ui, Seq.empty)
                        _    <- ReactiveUI.subscribe(root, exchange)
                    yield Loop.foreach {
                        Abort.run[Closed](channel.take).map {
                            case Result.Success(_) =>
                                HtmlRenderer.render(ui, Seq.empty).map { html =>
                                    Emit.valueWith(Chunk(html))(Loop.continue)
                                }
                            case _ => Loop.done
                        }
                    }
                    end for
                }
            }
        }

    /** A property value that is either a constant or a reactive [[kyo.SignalRef]].
      *
      * Setters that accept both a plain value and a writable signal (the `value`/`checked` family) store their argument as a `Bound`:
      * `Bound.Const` for a one-shot value, `Bound.Ref` for a `SignalRef` that establishes two-way binding. Renderers and backends match on
      * the two cases to decide whether to wire input/change listeners back to the signal.
      *
      * @tparam A
      *   the type of the underlying property value
      */
    sealed trait Bound[+A]

    object Bound:
        final case class Const[A](value: A)        extends Bound[A]
        final case class Ref[A](ref: SignalRef[A]) extends Bound[A]
    end Bound

    /** The typed identity of a key in a [[kyo.UI.KeyboardEvent]].
      *
      * Named cases cover the non-printable keys (`Enter`, `Escape`, the arrows, function keys, ...). Printable keys arrive as `Char`
      * carrying the character, and anything the mapping does not recognize falls back to `Unknown` carrying the raw DOM `key` string, so no
      * keystroke is ever dropped. Use [[kyo.UI.Keyboard.fromString]] to map a browser `key` value to a `Keyboard`.
      *
      * @see
      *   [[kyo.UI.Keyboard.fromString]] for parsing a DOM `key` string
      * @see
      *   [[kyo.UI.KeyboardEvent]] for the event payload that carries it
      */
    enum Keyboard derives CanEqual:
        case Enter, Tab, Escape, Backspace, Delete, Space
        case Home, End, Insert, PageUp, PageDown
        case ArrowUp, ArrowDown, ArrowLeft, ArrowRight
        case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
        case Char(c: scala.Char)
        case Unknown(raw: String)

        /** The character produced by this key, present for `Char` and `Space`, `Absent` for every other case. */
        def charValue: Maybe[String] = this match
            case Char(c) => Present(c.toString)
            case Space   => Present(" ")
            case _       => Absent
    end Keyboard

    object Keyboard:
        /** Maps a DOM `KeyboardEvent.key` string to a [[kyo.UI.Keyboard]], falling back to `Char` for single characters and `Unknown` otherwise. */
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

    /** Target attribute for anchor elements.
      *
      * Each case maps to the corresponding HTML `target` attribute string value:
      *   - `Self` → `"_self"` (default browser behaviour: current frame)
      *   - `Blank` → `"_blank"` (new tab / window)
      *   - `Parent` → `"_parent"` (parent frame)
      *   - `Top` → `"_top"` (full window, escaping all frames)
      *
      * The mapping is performed by `HtmlRenderer` and is not visible to callers. PascalCase names are used deliberately to follow Scala
      * enum conventions; the HTML wire-format strings are an encoding detail.
      */
    enum Target derives CanEqual:
        case Self, Blank, Parent, Top
    end Target

    /** A typed anchor destination, replacing a stringly-typed `href` attribute.
      *
      * `Absolute` carries a parsed [[kyo.HttpUrl]], `Path` a same-origin path, `Fragment` an in-page `#id`, and `External` an arbitrary
      * scheme (`mailto`, `tel`, ...). Each case renders to the corresponding `href` string.
      */
    enum Href derives CanEqual:
        case Absolute(url: HttpUrl)
        case Path(value: String)
        case Fragment(id: String)
        case External(scheme: String, value: String)
    end Href

    /** A typed image source for an `img` `src` attribute.
      *
      * `Absolute` carries a parsed [[kyo.HttpUrl]], `Path` a same-origin path, and `Data` an inline `data:` URI (MIME type plus payload).
      */
    enum ImgSrc derives CanEqual:
        case Absolute(url: HttpUrl)
        case Path(value: String)
        case Data(mime: String, payload: String)
    end ImgSrc

    /** A supported raster/vector image format, used by [[kyo.UI.FileAccept.Image]] to build an image-extension accept filter. */
    enum ImageExt derives CanEqual:
        case Png, Jpeg, Webp, Gif, Svg, Avif
    end ImageExt

    /** A typed entry for a file input's `accept` filter, replacing the stringly-typed list of MIME types and extensions.
      *
      * The wildcard cases (`AnyImage`, `AnyVideo`, `AnyAudio`, `Pdf`) cover the common groups, `Image` builds an extension filter from a
      * known [[kyo.UI.ImageExt]], and `Extension`/`MediaType` are the explicit escapes for arbitrary extensions or MIME types. Each case
      * renders to one `accept` token.
      */
    enum FileAccept derives CanEqual:
        case AnyImage                // "image/*"
        case AnyVideo                // "video/*"
        case AnyAudio                // "audio/*"
        case Pdf                     // "application/pdf"
        case Image(ext: ImageExt)    // ".png", ".jpg", etc.
        case Extension(ext: String)  // ".csv", ".docx", explicit string escape for arbitrary extensions
        case MediaType(mime: String) // "application/foo", explicit escape for arbitrary MIME types
    end FileAccept

    /** The ctrl/alt/shift/meta chord held down when a mouse or keyboard event fired.
      *
      * Carried by [[kyo.UI.MouseEvent]] and [[kyo.UI.KeyboardEvent]] so handlers can branch on modified clicks/keystrokes (for example
      * shift-click range selection). [[kyo.UI.Modifiers.none]] is the no-key-held value.
      */
    final case class Modifiers(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false
    ) derives CanEqual, Schema

    object Modifiers:
        /** The chord with no modifier keys held. */
        val none: Modifiers = Modifiers()

    /** The typed payload delivered to a typed `onClick`/`onClickSelf`/`onFocus`/`onBlur` handler.
      *
      * `targetId` is the `id` of the element the event fired on (`Absent` when that element has no id), and `modifiers` is the
      * [[kyo.UI.Modifiers]] chord at the time of the event.
      */
    final case class MouseEvent(
        targetId: Maybe[String],
        modifiers: Modifiers
    ) derives CanEqual

    /** The typed payload delivered to an `onKeyDown`/`onKeyUp` handler.
      *
      * `key` is the typed [[kyo.UI.Keyboard]] identity, `modifiers` the [[kyo.UI.Modifiers]] chord, and `targetId` the `id` of the element
      * the event fired on (`Absent` when that element has no id).
      */
    final case class KeyboardEvent(
        key: Keyboard,
        modifiers: Modifiers,
        targetId: Maybe[String]
    ) derives CanEqual

    /** The typed payload delivered to an `onScroll` handler.
      *
      * `deltaX` and `deltaY` are the wheel deltas in pixels (positive = scroll right/down). `targetId` is the `id` of the element the event
      * fired on (`Absent` when that element has no id), and `modifiers` records any held keys (ctrl-wheel zoom is the common case).
      *
      * @see
      *   [[kyo.UI.Ast.Interactive.onScroll]] for the setter that registers a handler receiving this payload.
      */
    final case class WheelEvent(
        deltaX: Double,
        deltaY: Double,
        targetId: Maybe[String],
        modifiers: Modifiers
    ) derives CanEqual

    // ---- Charts: sentinel ----

    /** A unique sentinel object shared by every optional accessor parameter.
      *
      * The sentinel is compared by reference identity inside each factory. A parameter
      * that was not supplied by the caller is equal to `Unset.accessor` (same object);
      * a supplied parameter is any other function value. The factory body calls
      * `Unset.supplied` to distinguish the two cases and builds `Present` or `Absent`
      * accordingly.
      *
      * This is the one `asInstanceOf` idiom in the channel layer; it is safe because
      * the sentinel is never invoked, only compared by reference.
      */
    private[kyo] object Unset:
        val accessor: Any => Nothing           = _ => throw new NoSuchElementException("Unset sentinel invoked")
        inline def of[A, C]: A => C            = accessor.asInstanceOf[A => C]
        def supplied[A, C](f: A => C): Boolean = !(f.asInstanceOf[AnyRef] eq accessor)
    end Unset

    // ---- Charts: grouping factory ----

    /** Builds a `Grouping[A]` that groups by `group` and optionally normalizes to 100%.
      *
      * This is the only public constructor for `Grouping`. Pass the result to a mark's
      * `stack` parameter:
      *
      * {{{
      * UI.mark.bar(x = _.month, y = _.revenue, stack = UI.by(_.region))
      * UI.mark.bar(x = _.month, y = _.revenue, stack = UI.by(_.region, normalize = true))
      * }}}
      *
      * `normalize = true` produces a 100%-stacked bar; `false` (the default) stacks
      * absolute values.
      */
    def by[A](group: A => Any, normalize: Boolean = false): Grouping[A] =
        Grouping(Present(group), normalize)

    // ---- Charts: enums ----

    /** Selects which y-axis a mark binds to.
      *
      * Marks that carry no explicit `axis` parameter default to `Axis.Left`. A mark
      * with `axis = Axis.Right` binds to an independent right y-scale, which is
      * resolved separately from the left y-scale and rendered on the right margin.
      *
      * Use `Axis.Right` together with `.yAxisRight(...)` to configure the right axis.
      */
    enum Axis derives CanEqual:
        case Left, Right

    /** Interpolation strategy between line or area mark vertices.
      *
      * `linear` draws straight segments; `monotone` uses a Hermite spline that
      * preserves monotonicity; `stepBefore` and `stepAfter` produce staircase lines;
      * `basis` uses a B-spline; `catmullRom` uses a Catmull-Rom spline. The two
      * cases explicitly named in the public-API contract are `stepAfter` and
      * `monotone`; the rest are additive.
      */
    enum Curve derives CanEqual:
        case linear, monotone, stepBefore, stepAfter, basis, catmullRom

    /** Point glyph shape.
      *
      * Selects the shape rendered at each point in a `point` mark. `circle` is the
      * default and renders as an `Svg.circle`; the others render as `Svg.path`
      * glyphs of equal visual weight. All five cases are additive beyond the two the
      * contract names.
      */
    enum Symbol derives CanEqual:
        case circle, square, triangle, diamond, cross

    /** Text alignment for `text` marks and rotated axis tick labels. */
    enum TextAnchor derives CanEqual:
        case Start, Middle, End

    /** Position of a legend relative to the plot area. */
    enum LegendPosition derives CanEqual:
        case Top, Bottom, Left, Right

    /** The scale kind selected by a `UI.Ast.ScaleOverride`. */
    enum ScaleKind derives CanEqual:
        case Band
        case Log
        case Linear(lo: Double, hi: Double)
        case Time    // D11
        case Ordinal // D11
        case Point   // D11
        case Symlog  // D11
    end ScaleKind

    /** The constant-or-signal carrier for `UI.mark.rule(x = ...)` / `rule(y = ...)`.
      *
      * A rule position is either a constant value (`Const`) or a `Signal`-tracked
      * value (`Reactive`). Neither form carries a row accessor; a rule reads no row.
      * The lowering phase resolves `Const` once and `Reactive` through
      * `signal.render`.
      */
    enum RuleValue[C]:
        case Const(value: C, plottable: Plottable[C])
        case Reactive(signal: Signal[C], plottable: Plottable[C])
        case Unset extends RuleValue[Nothing] // D33: total absence sentinel replacing null
    end RuleValue

    /** Implicit conversions from plain values and signals to `RuleValue`.
      *
      * These allow the inline `rule(y = Usd(1000))` and `rule(y = threshold)` forms
      * to compile without an explicit `RuleValue.Const(...)` wrapper.
      */
    object RuleValue:
        // Unsafe: widening Nothing to C is safe because Unset is a total sentinel that is never invoked;
        // the cast is the canonical idiom for a covariant singleton sentinel in a non-covariant enum.
        private[kyo] def unset[C]: RuleValue[C] = RuleValue.Unset.asInstanceOf[RuleValue[C]]
        given constConversion[C: Plottable]: Conversion[C, RuleValue[C]] =
            c => RuleValue.Const(c, summon[Plottable[C]])
        given signalConversion[C: Plottable](using CanEqual[C, C]): Conversion[Signal[C], RuleValue[C]] =
            s => RuleValue.Reactive(s, summon[Plottable[C]])
    end RuleValue

    // ---- Charts: mark hierarchy ----

    /** A single visual layer over the chart's row type `A`.
      *
      * Sealed: the seven cases (`Bar`, `Line`, `Area`, `Point`, `Rule`, `Text`, `ErrorBar`)
      * are the complete mark vocabulary. All lowering in `internal/ChartLower.scala` is an
      * exhaustive match on this sealed trait; adding a new case is a compile error until
      * lowering is extended. Marks are pure immutable values; they carry no rendering logic.
      *
      * Marks are produced by the `UI.mark.*` factories `bar`/`line`/`area`/`point`/`rule`/`text`
      * and consumed by `UI.chart(data)(marks*)`. Users never name the concrete cases
      * directly; the factories are the public API.
      */
    sealed trait Mark[A]

    object Mark:

        /** A bar or column mark.
          *
          * Carries required positional channels `x` and `y`, and optional grouping
          * channels `color` and `stack`. `axis` selects the y-axis. The additive
          * fields `opacity`, `label`, and `tooltip` are optional per-datum accessors
          * that default to `Absent` when not supplied by the factory.
          */
        final case class Bar[A, X, Y](
            x: Encoding[A, X],
            y: Encoding[A, Y],
            color: Maybe[Encoding[A, ?]],
            stack: Grouping[A],
            opacity: Maybe[A => Double] = Absent, // D4
            label: Maybe[A => String] = Absent,   // D5
            tooltip: Maybe[A => String] = Absent, // D6
            axis: Axis
        ) extends Mark[A]

        /** A line mark with optional gap support.
          *
          * `y` is a `EncodingMaybe` to support `Maybe[Y]` accessors (gaps). `color`
          * splits into one line per series. `defined` overrides gap detection. The
          * additive fields `opacity`, `label`, and `tooltip` are optional per-datum
          * accessors that default to `Absent` when not supplied by the factory.
          */
        final case class Line[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            color: Maybe[Encoding[A, ?]],
            curve: Curve,
            defined: Maybe[A => Boolean],
            opacity: Maybe[A => Double] = Absent, // D4
            label: Maybe[A => String] = Absent,   // D5
            tooltip: Maybe[A => String] = Absent, // D6
            axis: Axis
        ) extends Mark[A]

        /** An area mark.
          *
          * Either `y` (fill to baseline) or the `y0`/`y1` band form must be supplied.
          * Exactly one of the two forms is valid; a lowering-time check selects it. The
          * additive fields `opacity`, `label`, and `tooltip` are optional per-datum
          * accessors that default to `Absent` when not supplied by the factory.
          */
        final case class Area[A, X, Y](
            x: Encoding[A, X],
            y: Maybe[EncodingMaybe[A, Y]],
            y0: Maybe[Encoding[A, Y]],
            y1: Maybe[Encoding[A, Y]],
            color: Maybe[Encoding[A, ?]],
            stack: Grouping[A],
            curve: Curve,
            opacity: Maybe[A => Double] = Absent, // D4
            label: Maybe[A => String] = Absent,   // D5
            tooltip: Maybe[A => String] = Absent, // D6
            axis: Axis
        ) extends Mark[A]

        /** A point (scatter/bubble) mark.
          *
          * `y` is a `EncodingMaybe` so `Maybe[Y]` accessors render gaps as absent dots.
          * `size` controls the dot radius as a sqrt-area magnitude; `sizePx` is the
          * raw-pixel-radius escape hatch; `symbol` selects the glyph. The additive
          * fields `opacity`, `label`, and `tooltip` are optional per-datum accessors
          * that default to `Absent` when not supplied by the factory.
          */
        final case class Point[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            color: Maybe[Encoding[A, ?]],
            size: Maybe[A => Double],
            sizePx: Maybe[A => Double] = Absent, // D7 escape hatch: raw pixel radius
            symbol: Maybe[A => Symbol],
            opacity: Maybe[A => Double] = Absent, // D4
            label: Maybe[A => String] = Absent,   // D5
            tooltip: Maybe[A => String] = Absent, // D6
            axis: Axis
        ) extends Mark[A]

        /** A reference line mark.
          *
          * `x` or `y` carries a `RuleValue`: a constant (`Const`) or a reactive
          * signal (`Reactive`). At least one of `x`/`y` should be `Present`. `rule`
          * has no `color` or `size` channel.
          */
        final case class Rule[A](
            x: Maybe[RuleValue[?]],
            y: Maybe[RuleValue[?]],
            axis: Axis
        ) extends Mark[A]

        /** A text annotation mark.
          *
          * Renders one `Svg.text` per row at `(x, y)` with the string produced by
          * `label`. `y` is a `EncodingMaybe` so gap rows produce no text. `color`
          * optionally groups by category; `anchor` controls horizontal alignment;
          * `opacity` controls per-datum transparency.
          */
        final case class Text[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            label: A => String,
            color: Maybe[Encoding[A, ?]],
            anchor: TextAnchor,
            opacity: Maybe[A => Double],
            axis: Axis
        ) extends Mark[A]

        /** An error bar mark.
          *
          * Renders a vertical line from `low` to `high` at `x`, with horizontal
          * caps of `capWidth` pixels, and a center marker at `y`. All three y-channels
          * fold into the y-extent. `color` optionally groups by category.
          */
        final case class ErrorBar[A, X, Y](
            x: Encoding[A, X],
            y: Encoding[A, Y],
            low: Encoding[A, Y],
            high: Encoding[A, Y],
            color: Maybe[Encoding[A, ?]],
            capWidth: Double,
            axis: Axis
        ) extends Mark[A]

    end Mark

    // ---- Charts: mark factories ----

    /** Named-parameter factories for chart marks: `UI.mark.bar`, `UI.mark.line`, and so on.
      *
      * Each factory returns a [[kyo.UI.Mark]] value. Channels are named parameters,
      * never chained setters: supplying `color` is what turns a plain chart into a grouped
      * one. Omitted optionals are `Absent` inside the resulting mark; supplied ones are
      * `Present`. The lowercase `mark` term coexists with the uppercase `Mark` type.
      *
      * `@targetName` gives the term object a distinct bytecode module-class name so that
      * `UI$mark$` does not clash with the `Mark` companion's `UI$Mark$` on case-insensitive
      * filesystems (macOS, Windows). The Scala-level name stays `UI.mark`.
      */
    @scala.annotation.targetName("markFactory")
    object mark:

        /** Tag for a positional (x/y/low/high) channel value.
          *
          * Positional channel values feed numeric or ordinal scales and are never
          * category-keyed, so their `ConcreteTag` is not used for identity. A positional
          * `Y` may also be a `Maybe[..]` gap type, which `ConcreteTag` cannot derive.
          * This returns the widest tag so the field is populated without constraining the
          * value type. Category keying (`CatKey`) only ever reads the color/group channel
          * tag, which is always derived from a concrete type.
          */
        private[kyo] def positionalTag[C]: ConcreteTag[C] =
            summon[ConcreteTag[Any]].asInstanceOf[ConcreteTag[C]]

        /** Creates a bar/column mark.
          *
          * `x` and `y` are required positional channels; `color` groups the bars and
          * derives a legend; `stack` stacks or normalizes the bars (carrying its own
          * grouping via `UI.by(...)`); `axis` selects the y-axis (default `Axis.Left`).
          *
          * `bar` has no `size` parameter. `rule` has no `color` parameter. These
          * omissions are intentional capability gates: the compiler enforces them.
          */
        def bar[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y,
            color: A => Any = Unset.of[A, Any],
            stack: Grouping[A] = Grouping.none[A],
            opacity: A => Double = Unset.of[A, Double],
            label: A => String = Unset.of[A, String],
            tooltip: A => String = Unset.of[A, String],
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val xCh = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
            val yCh = Encoding[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            val opacityMaybe: Maybe[A => Double] = if Unset.supplied(opacity) then Present(opacity) else Absent
            val labelMaybe: Maybe[A => String]   = if Unset.supplied(label) then Present(label) else Absent
            val tooltipMaybe: Maybe[A => String] = if Unset.supplied(tooltip) then Present(tooltip) else Absent
            Mark.Bar(xCh, yCh, colorMaybe, stack, opacityMaybe, labelMaybe, tooltipMaybe, axis)
        end bar

        /** Creates a line mark.
          *
          * `x` and `y` are required; `color` splits into one line per series; `curve`
          * selects the interpolation strategy (default `Curve.linear`); `defined`
          * overrides gap detection (a row where `defined` returns `false` breaks the
          * line); `axis` selects the y-axis. A `y` accessor that returns `Maybe[Y]`
          * type-checks because `Plottable[Maybe[Y]]` is derived from `Plottable[Y]`.
          */
        def line[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y,
            color: A => Any = Unset.of[A, Any],
            curve: Curve = Curve.linear,
            defined: A => Boolean = Unset.of[A, Boolean],
            opacity: A => Double = Unset.of[A, Double],
            label: A => String = Unset.of[A, String],
            tooltip: A => String = Unset.of[A, String],
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val xCh = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
            val yCh = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            val definedMaybe: Maybe[A => Boolean] =
                if Unset.supplied(defined) then Present(defined) else Absent
            val opacityMaybe: Maybe[A => Double] = if Unset.supplied(opacity) then Present(opacity) else Absent
            val labelMaybe: Maybe[A => String]   = if Unset.supplied(label) then Present(label) else Absent
            val tooltipMaybe: Maybe[A => String] = if Unset.supplied(tooltip) then Present(tooltip) else Absent
            Mark.Line(xCh, yCh, colorMaybe, curve, definedMaybe, opacityMaybe, labelMaybe, tooltipMaybe, axis)
        end line

        /** Creates an area mark.
          *
          * Two forms are accepted: (1) a single `y` accessor fills the area between the
          * y value and the baseline; (2) `y0` and `y1` fill the band between two values.
          * At lowering time exactly one of {`y`} or {`y0`,`y1`} must be present; a
          * misconfiguration renders an empty frame (never a crash, per contract section
          * 10). `color`, `stack`, `curve`, and `axis` follow the same rules as `bar` and
          * `line`.
          */
        def area[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y = Unset.of[A, Y],
            y0: A => Y = Unset.of[A, Y],
            y1: A => Y = Unset.of[A, Y],
            color: A => Any = Unset.of[A, Any],
            stack: Grouping[A] = Grouping.none[A],
            curve: Curve = Curve.linear,
            opacity: A => Double = Unset.of[A, Double],
            label: A => String = Unset.of[A, String],
            tooltip: A => String = Unset.of[A, String],
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val xCh                            = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
            val plY                            = summon[Plottable[Y]]
            val tagY                           = positionalTag[Y]
            val yMaybe                         = if Unset.supplied(y) then Present(EncodingMaybe.fromTotal[A, Y](y, plY, tagY)) else Absent
            val y0Maybe: Maybe[Encoding[A, Y]] = if Unset.supplied(y0) then Present(Encoding[A, Y](y0, plY, tagY)) else Absent
            val y1Maybe: Maybe[Encoding[A, Y]] = if Unset.supplied(y1) then Present(Encoding[A, Y](y1, plY, tagY)) else Absent
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            val opacityMaybe: Maybe[A => Double] = if Unset.supplied(opacity) then Present(opacity) else Absent
            val labelMaybe: Maybe[A => String]   = if Unset.supplied(label) then Present(label) else Absent
            val tooltipMaybe: Maybe[A => String] = if Unset.supplied(tooltip) then Present(tooltip) else Absent
            Mark.Area(xCh, yMaybe, y0Maybe, y1Maybe, colorMaybe, stack, curve, opacityMaybe, labelMaybe, tooltipMaybe, axis)
        end area

        /** Creates a point (scatter/bubble) mark.
          *
          * `x` and `y` are required; `color`, `size`, and `symbol` are optional
          * non-positional channels. `bar` has no `size` parameter; `point` does. This
          * asymmetry is the capability gate enforced by the named-parameter design.
          * A `y` accessor returning `Maybe[Y]` renders a gap (no dot) at `Absent` rows.
          */
        def point[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y,
            color: A => Any = Unset.of[A, Any],
            size: A => Double = Unset.of[A, Double],   // D7: sqrt-area scaled magnitude
            sizePx: A => Double = Unset.of[A, Double], // D7 escape hatch: raw pixel radius
            symbol: A => Symbol = Unset.of[A, Symbol],
            opacity: A => Double = Unset.of[A, Double], // D4
            label: A => String = Unset.of[A, String],   // D5
            tooltip: A => String = Unset.of[A, String], // D6
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val xCh = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
            val yCh = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            val sizeSup   = Unset.supplied(size)
            val sizePxSup = Unset.supplied(sizePx)
            // Q-005: when both size and sizePx are supplied, size wins and sizePx is dropped.
            val sizeMaybe: Maybe[A => Double]    = if sizeSup then Present(size) else Absent
            val sizePxMaybe: Maybe[A => Double]  = if sizePxSup && !sizeSup then Present(sizePx) else Absent
            val symbolMaybe: Maybe[A => Symbol]  = if Unset.supplied(symbol) then Present(symbol) else Absent
            val opacityMaybe: Maybe[A => Double] = if Unset.supplied(opacity) then Present(opacity) else Absent
            val labelMaybe: Maybe[A => String]   = if Unset.supplied(label) then Present(label) else Absent
            val tooltipMaybe: Maybe[A => String] = if Unset.supplied(tooltip) then Present(tooltip) else Absent
            Mark.Point(xCh, yCh, colorMaybe, sizeMaybe, sizePxMaybe, symbolMaybe, opacityMaybe, labelMaybe, tooltipMaybe, axis)
        end point

        /** Creates a reference line mark.
          *
          * A `rule` draws a horizontal (`y`) or vertical (`x`) line spanning the plot at
          * a constant value or at a `Signal`-tracked value. At least one of `x` or `y`
          * must be supplied. `rule` has no `color` or `size` parameter; these are
          * intentional omissions gating capability at the call site.
          *
          * Constant form: `rule(y = Usd(1000))`. Signal form: `rule(y = threshold)`
          * where `threshold: Signal[Usd]`.
          */
        def rule[A, C: Plottable](
            x: RuleValue[C] = RuleValue.unset[C],
            y: RuleValue[C] = RuleValue.unset[C],
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            // Pattern matching on RuleValue.Unset across type parameters requires an erased check.
            // isInstanceOf[RuleValue.Unset.type] detects the singleton; the value arm carries the concrete case.
            val xMaybe: Maybe[RuleValue[?]] = if x.isInstanceOf[RuleValue.Unset.type] then Absent else Present(x)
            val yMaybe: Maybe[RuleValue[?]] = if y.isInstanceOf[RuleValue.Unset.type] then Absent else Present(y)
            Mark.Rule(xMaybe, yMaybe, axis)
        end rule

        /** Creates a text annotation mark.
          *
          * Renders one `Svg.text` per row at `(x, y)` with the string from `label`. `y` is treated
          * as a `EncodingMaybe` internally so gap rows (where the accessor would yield no value) skip
          * the text element. `anchor` controls horizontal alignment. `color` optionally groups rows
          * by category so each group can carry a distinct fill color.
          */
        def text[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y,
            label: A => String,
            color: A => Any = Unset.of[A, Any],
            anchor: TextAnchor = TextAnchor.Middle,
            opacity: A => Double = Unset.of[A, Double],
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val xCh = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
            val yCh = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            val opacityMaybe: Maybe[A => Double] = if Unset.supplied(opacity) then Present(opacity) else Absent
            Mark.Text(xCh, yCh, label, colorMaybe, anchor, opacityMaybe, axis)
        end text

        /** Creates an error bar mark.
          *
          * Renders a vertical line from `low` to `high` at `x`, two horizontal caps of `capWidth` pixels,
          * and a center marker at `y`. All three y-channels (`y`, `low`, `high`) contribute to the y-axis
          * extent. `color` optionally groups by category. `capWidth` defaults to 6 pixels.
          *
          * The rendered elements are plain `Svg.line` and `Svg.circle` with no `url(#id)` or `<marker>`
          * references (INV-022).
          */
        def errorBar[A, X: Plottable, Y: Plottable](
            x: A => X,
            y: A => Y,
            low: A => Y,
            high: A => Y,
            color: A => Any = Unset.of[A, Any],
            capWidth: Double = 6.0,
            axis: Axis = Axis.Left
        )(using Frame): Mark[A] =
            val plY  = summon[Plottable[Y]]
            val tagY = positionalTag[Y]
            val colorMaybe: Maybe[Encoding[A, ?]] =
                if Unset.supplied(color) then
                    Present(Encoding[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]], summon[ConcreteTag[Any]]))
                else Absent
            Mark.ErrorBar(
                Encoding(x, summon[Plottable[X]], positionalTag[X]),
                Encoding(y, plY, tagY),
                Encoding(low, plY, tagY),
                Encoding(high, plY, tagY),
                colorMaybe,
                capWidth,
                axis
            )
        end errorBar

    end mark

    // ---- Charts: entry point ----

    /** Opens a chart over a static `Chunk[A]`.
      *
      * `UI.chart(data)` fixes the row type `A`; the second application `(marks*)` supplies
      * the marks and returns a `UI.Ast.ChartSpec[A]`. The two-stage application is what
      * makes row-type inference work: `A` is bound before the mark channel lambdas are read,
      * so `UI.mark.bar(x = _.month, y = _.revenue)` needs no annotations. A `ChartSpec[A]`
      * converts to `Svg.Root` automatically wherever one is expected (including `UI.div`
      * children), via the `given Conversion`.
      */
    def chart[A](data: Chunk[A])(using Frame): Builder[A] =
        new Builder[A](DataSource.Static(data))

    /** Opens a chart over a live `Signal[Chunk[A]]`; the marks region redraws on each emission. */
    def chart[A](data: Signal[Chunk[A]])(using CanEqual[Chunk[A], Chunk[A]], Frame): Builder[A] =
        new Builder[A](DataSource.Live(data))

    /** Holds the data source and accepts the mark list.
      *
      * The second application `(marks*)` is where the `ChartSpec[A]` is built.
      * Keeping this as a two-step application ensures that `A` is bound to the data
      * element type before the mark channel lambdas are read, which is the
      * inference boundary the named-parameter design relies on.
      */
    final class Builder[A] private[kyo] (data: DataSource[A]):
        def apply(marks: Mark[A]*)(using Frame): ChartSpec[A] =
            ChartSpec[A](
                data = data,
                marks = Chunk.from(marks),
                chartSize = (640, 480),
                xAxisCfg = AxisConfig.default,
                yAxisCfg = AxisConfig.default,
                yAxisRightCfg = Absent,
                legendCfg = LegendConfig.default,
                theme = Theme.default,
                xScaleOverride = Absent,
                yScaleOverride = Absent,
                yScaleRightOverride = Absent,
                animateCfg = AnimateConfig.default,
                key = Absent,
                onHover = Absent,
                onSelect = Absent,
                tooltip = Absent
            )
    end Builder

    // ---- Charts: Plottable typeclass ----

    /** Maps a static value type to the scale that plots it.
      *
      * Open: the library ships built-in instances for `Int`, `Long`, `Double`, `String`, and `Instant`; enum types
      * derive instances automatically; opaque numeric quantities use `Plottable.numeric`. A channel over a type with
      * no instance is a compile error, so you cannot accidentally plot a `Boolean` or an arbitrary class.
      *
      * `kind` selects the scale family (`Scale.Kind`). `toDomain` projects a value into the scale's native domain
      * coordinate: `Present(d)` for a valid domain point, `Absent` for a value that must be SKIPPED and contribute
      * nothing to the extent (used by `Plottable[Maybe[A]]` for `Absent` inputs). `label` returns the tick or
      * legend text for a value.
      */
    trait Plottable[A]:
        def kind: kyo.internal.Scale.Kind
        def toDomain(a: A): Maybe[kyo.internal.Domain]
        def label(a: A): String
    end Plottable

    /** Companion object with cached built-in `Plottable` instances and derivation utilities.
      *
      * All built-in instances are `val`s (never `inline`), so each is a single shared object; there is no per-call
      * duplication. Enum instances are produced by `derived`, which uses a thread-safe cache (keyed on the
      * comma-joined label string) so that any two summons for the same enum type return the same object regardless
      * of call site, compilation unit, or JVM class loader.
      *
      * The `enumCache` field is a `ConcurrentHashMap`, which is thread-safe by contract. Values are computed once
      * and never mutated after insertion, and `computeIfAbsent` provides the atomic read-or-create semantic without
      * requiring explicit locks at the call site.
      */
    object Plottable:

        import kyo.internal.Domain
        import kyo.internal.NumberFormat
        import kyo.internal.Scale

        given int: Plottable[Int] = continuous(_.toDouble, _.toString)

        given long: Plottable[Long] = continuous(_.toDouble, _.toString)

        given double: Plottable[Double] = continuous(identity, NumberFormat.double)

        given string: Plottable[String] = categorical(identity)

        given instant: Plottable[Instant] = temporal(i => i.toJava.toEpochMilli, i => i.toJava.toString)

        /** `Plottable[Maybe[A]]` projects only `Present` values; `Absent` returns `Absent` so the extent-folding
          * layer skips it entirely.
          *
          * The `kind` is identical to the inner `Plottable[A]` kind: a `Maybe[Int]` channel is still a Linear
          * channel; a `Maybe[String]` channel is still a Band channel. An all-`Absent` column produces an empty
          * extent (no domain contributions at all).
          */
        given maybe[A](using inner: Plottable[A]): Plottable[Maybe[A]] =
            new Plottable[Maybe[A]]:
                def kind: Scale.Kind = inner.kind
                def toDomain(a: Maybe[A]): Maybe[Domain] = a match
                    case Present(v) => inner.toDomain(v)
                    case Absent     => Absent
                def label(a: Maybe[A]): String = a match
                    case Present(v) => inner.label(v)
                    case Absent     => ""

        /** Derives a linear `Plottable` for an opaque numeric quantity with an upper `<: Double` bound.
          *
          * The underlying `double` instance is reused directly; the cast is sound because the `<: Double` bound
          * guarantees that `A` is a `Double` at runtime (the opaque alias is erased).
          *
          * This is the one `asInstanceOf` in the charting layer.
          * // Unsafe: sound only because `A <: Double` guarantees the erased runtime type is Double.
          */
        def numeric[A <: Double]: Plottable[A] =
            // Unsafe: sound only because A <: Double guarantees the erased runtime type is Double.
            double.asInstanceOf[Plottable[A]]

        /** Derives a band/ordinal `Plottable` for an enum from its `Mirror.SumOf`.
          *
          * The `inline given` surface reifies the literal label tuple (which must be done inline) and then looks
          * up or inserts an entry in `enumCache` keyed on the comma-joined label string. The heavy allocation
          * (`new Plottable[A]`) is performed at most once per label set, regardless of how many call sites summon
          * the instance and regardless of compilation unit or class loader ordering.
          *
          * Two `summon[Plottable[E]]` calls for the same enum `E` from ANY call site return the same cached
          * object (reference equal), because the cache lookup uses `computeIfAbsent` which is atomic.
          */
        inline given derived[A](using m: scala.deriving.Mirror.SumOf[A]): Plottable[A] =
            val labelTuple = scala.compiletime.constValueTuple[m.MirroredElemLabels]
            cachedDeriveEnum[A](labelTuple)

        // Unsafe: ConcurrentHashMap is shared mutable state accessed from multiple threads.
        // Safe because ConcurrentHashMap is thread-safe by contract, values are immutable after
        // insertion, and computeIfAbsent provides atomic read-or-create semantics.
        private val enumCache: java.util.concurrent.ConcurrentHashMap[String, Plottable[?]] =
            new java.util.concurrent.ConcurrentHashMap[String, Plottable[?]]()

        private def cachedDeriveEnum[A](labelTuple: Tuple): Plottable[A] =
            val labels: Chunk[String] = tupleToChunk(labelTuple)
            val cacheKey              = labels.toSeq.mkString(",")
            // Unsafe: the cast from Plottable[?] to Plottable[A] is safe because the cache is keyed
            // on the unique label string which uniquely identifies the enum type A.
            enumCache
                .computeIfAbsent(cacheKey, _ => deriveEnum[A](labels))
                .asInstanceOf[Plottable[A]]
        end cachedDeriveEnum

        private def deriveEnum[A](labels: Chunk[String]): Plottable[A] =
            new Plottable[A]:
                def kind: Scale.Kind = Scale.Kind.Band
                def toDomain(a: A): Maybe[Domain] =
                    // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                    // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                    val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                    Present(Domain.Category(if idx >= 0 && idx < labels.size then labels(idx) else idx.toString))
                end toDomain
                def label(a: A): String =
                    // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                    // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                    val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                    if idx >= 0 && idx < labels.size then labels(idx) else idx.toString
                end label
            end new
        end deriveEnum

        private def tupleToChunk(t: Tuple): Chunk[String] =
            @scala.annotation.tailrec
            def loop(remaining: Tuple, acc: Chunk[String]): Chunk[String] =
                remaining match
                    case _: EmptyTuple => acc
                    case h *: tail =>
                        loop(tail, acc.append(h.asInstanceOf[String]))
            loop(t, Chunk.empty)
        end tupleToChunk

        private def continuous[A](toD: A => Double, lbl: A => String): Plottable[A] =
            new Plottable[A]:
                def kind: Scale.Kind              = Scale.Kind.Linear
                def toDomain(a: A): Maybe[Domain] = Present(Domain.Continuous(toD(a)))
                def label(a: A): String           = lbl(a)

        private def categorical[A](lbl: A => String): Plottable[A] =
            new Plottable[A]:
                def kind: Scale.Kind              = Scale.Kind.Band
                def toDomain(a: A): Maybe[Domain] = Present(Domain.Category(lbl(a)))
                def label(a: A): String           = lbl(a)

        private def temporal[A](toMillis: A => Long, lbl: A => String): Plottable[A] =
            new Plottable[A]:
                def kind: Scale.Kind              = Scale.Kind.Time
                def toDomain(a: A): Maybe[Domain] = Present(Domain.Temporal(toMillis(a)))
                def label(a: A): String           = lbl(a)

    end Plottable

    /** The case-class abstract syntax tree that every [[kyo.UI]] factory returns.
      *
      * Every node a factory builds is a value under `Ast`: the element case classes (`Div`, `Button`, `Input`, ...), the text node
      * `Text`, the reactive nodes `Reactive`/`Foreach`, and `Fragment`. Because they are plain case classes, you can pattern-match on a
      * `UI` for tests, transforms, or to write a custom rendering backend.
      *
      * @see
      *   [[kyo.UI.Ast.Element]] for the element base trait and its capability taxonomy
      */
    object Ast:

        // ---- Element base trait ----

        /** The base trait for every HTML element node, and the root of kyo-ui's capability taxonomy.
          *
          * An `Element` carries its `attrs`, its `children`, and a `Self` type fixed to its own concrete class so that every setter
          * returns the same element type and chains stay precisely typed. Setters that take a `Signal` instead return `UI`, since they
          * cross the reactive boundary.
          *
          * Capabilities an element supports are expressed as mixed-in marker/setter traits, which is how invalid HTML is made to not
          * compile (`.checked` exists only on a [[kyo.UI.Ast.BooleanInput]], `.disabled` only on a [[kyo.UI.Ast.HasDisabled]], and so
          * on):
          *
          *   - [[kyo.UI.Ast.Interactive]]: event handlers (`onClick`, `onKeyDown`, ...) and `tabIndex`/focus grouping.
          *   - [[kyo.UI.Ast.Block]] / [[kyo.UI.Ast.Inline]]: the element's layout flow category.
          *   - [[kyo.UI.Ast.Void]]: elements that cannot have children (`hr`, `input`, ...).
          *   - [[kyo.UI.Ast.Focusable]]: can receive keyboard focus.
          *   - [[kyo.UI.Ast.HasDisabled]]: exposes a `disabled` setter.
          *   - [[kyo.UI.Ast.TextInput]] / [[kyo.UI.Ast.PickerInput]] / [[kyo.UI.Ast.BooleanInput]]: the three form-input shapes (free text,
          *     picker with a `String` value, boolean `checked`).
          *   - [[kyo.UI.Ast.Activatable]] / [[kyo.UI.Ast.Clickable]]: semantic activation/click affordances (button, anchor).
          *
          * @see
          *   [[kyo.UI.Ast]] for the surrounding AST and the non-element node types
          */
        sealed trait Element extends UI:
            type Self <: Element
            private[kyo] def frame: Frame
            def attrs: Attrs
            def children: Chunk[UI]
            private[kyo] def withAttrs(a: Attrs): Self

            // Identity & visibility
            def id(v: String): Self      = withAttrs(attrs.copy(identifier = Present(v)))
            def hidden(v: Boolean): Self = withAttrs(attrs.copy(hidden = Present(v)))

            /** Reactive `hidden`: re-renders when the signal emits. */
            def hidden(v: Signal[Boolean]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(b => this.hidden(b): UI))

            // Visual
            def style(v: Style): Self               = withAttrs(attrs.copy(uiStyle = attrs.uiStyle ++ v))
            def style(f: Style.type => Style): Self = style(f(Style))

            /** Reactive `style`: re-renders when the signal emits. */
            def style(v: Signal[Style]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(s => this.style(s): UI))

            // ARIA attributes
            /** Sets a single `aria-*` attribute (the `name` is the suffix after `aria-`). */
            def aria(name: String, value: String): Self =
                withAttrs(attrs.copy(ariaAttrs = attrs.ariaAttrs.updated(name, value)))

            /** Sets several `aria-*` attributes at once from name/value pairs. */
            def aria(pairs: (String, String)*): Self =
                withAttrs(attrs.copy(ariaAttrs = attrs.ariaAttrs ++ pairs))

            /** Sets the WAI-ARIA `role` attribute (emitted as the bare `role="..."` HTML attribute). */
            def role(v: String): Self =
                withAttrs(attrs.copy(role = Present(v)))

            // Data attributes (kyo- prefix is reserved)
            /** Sets a single `data-*` attribute (the `name` is the suffix after `data-`).
              *
              * @throws java.lang.IllegalArgumentException
              *   if `name` starts with `kyo-`; that prefix is reserved by the framework
              */
            def data(name: String, value: String): Self =
                if name.startsWith("kyo-") then
                    throw new IllegalArgumentException(
                        s"data-* names must not start with 'kyo-'; the kyo- prefix is reserved by the framework. Got: kyo-${name.stripPrefix("kyo-")}"
                    )
                end if
                withAttrs(attrs.copy(dataAttrs = attrs.dataAttrs.updated(name, value)))
            end data

            /** Sets several `data-*` attributes at once from name/value pairs.
              *
              * @throws java.lang.IllegalArgumentException
              *   if any name starts with `kyo-`; that prefix is reserved by the framework
              */
            def data(pairs: (String, String)*): Self =
                pairs.foreach { case (n, _) =>
                    if n.startsWith("kyo-") then
                        throw new IllegalArgumentException(
                            s"data-* names must not start with 'kyo-'; the kyo- prefix is reserved by the framework. Got: kyo-${n.stripPrefix("kyo-")}"
                        )
                }
                withAttrs(attrs.copy(dataAttrs = attrs.dataAttrs ++ pairs))
            end data

            // Internal JS property setter (used by Checkbox.indeterminate, etc.)
            private[kyo] def jsProp(name: String, value: String): Self =
                withAttrs(attrs.copy(jsProps = attrs.jsProps.updated(name, value)))
        end Element

        // ---- Interactive trait (event handlers + tabIndex) ----

        /** Capability trait for elements that accept event handlers (`onClick`, `onKeyDown`, ...) and `tabIndex`/focus-group settings. */
        sealed trait Interactive extends Element:
            def tabIndex(v: Int): Self       = withAttrs(attrs.copy(tabIndex = Present(v)))
            def focusTrap(v: Boolean): Self  = withAttrs(attrs.copy(focusTrap = Present(v)))
            def focusGroup(id: String): Self = withAttrs(attrs.copy(focusGroup = Present(id)))

            /** Runs `action` on click, ignoring the event payload. */
            def onClick(action: => Any < Async): Self = withAttrs(attrs.copy(onClick = Present(Sync.defer(action)(using frame))))

            /** Runs `f` on click, receiving the [[kyo.UI.MouseEvent]] payload (target id, modifier chord). */
            def onClick(f: MouseEvent => Any < Async): Self = withAttrs(attrs.copy(onClickEvt = Present(f)))

            /** Runs `action` only when the click lands on this element itself, not on a descendant; ignores the event payload. */
            def onClickSelf(action: => Any < Async): Self = withAttrs(attrs.copy(onClickSelf = Present(Sync.defer(action)(using frame))))

            /** Runs `f` only when the click lands on this element itself, not on a descendant; receives the [[kyo.UI.MouseEvent]] payload. */
            def onClickSelf(f: MouseEvent => Any < Async): Self = withAttrs(attrs.copy(onClickSelfEvt = Present(f)))

            /** Runs `f` on key-down, receiving the [[kyo.UI.KeyboardEvent]] payload (typed key, modifier chord, target id). */
            def onKeyDown(f: KeyboardEvent => Any < Async): Self = withAttrs(attrs.copy(onKeyDown = Present(f)))
            def onKeyUp(f: KeyboardEvent => Any < Async): Self   = withAttrs(attrs.copy(onKeyUp = Present(f)))
            def onFocus(action: => Any < Async): Self            = withAttrs(attrs.copy(onFocus = Present(Sync.defer(action)(using frame))))
            def onFocus(f: MouseEvent => Any < Async): Self      = withAttrs(attrs.copy(onFocusEvt = Present(f)))
            def onBlur(action: => Any < Async): Self             = withAttrs(attrs.copy(onBlur = Present(Sync.defer(action)(using frame))))
            def onBlur(f: MouseEvent => Any < Async): Self       = withAttrs(attrs.copy(onBlurEvt = Present(f)))

            /** Runs `action` when the pointer enters this element. */
            def onHover(action: => Any < Async): Self = withAttrs(attrs.copy(onHover = Present(Sync.defer(action)(using frame))))

            /** Runs `f` when the pointer enters this element, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onHover(f: MouseEvent => Any < Async): Self = withAttrs(attrs.copy(onHoverEvt = Present(f)))

            /** Runs `action` when the pointer leaves this element. */
            def onUnhover(action: => Any < Async): Self = withAttrs(attrs.copy(onUnhover = Present(Sync.defer(action)(using frame))))

            /** Runs `f` when the pointer leaves this element, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onUnhover(f: MouseEvent => Any < Async): Self = withAttrs(attrs.copy(onUnhoverEvt = Present(f)))

            /** Runs `action` when the mouse wheel is used over this element. */
            def onScroll(action: => Any < Async): Self = withAttrs(attrs.copy(onScroll = Present(Sync.defer(action)(using frame))))

            /** Runs `f` when the mouse wheel is used over this element, receiving the [[kyo.UI.WheelEvent]] payload. */
            def onScroll(f: WheelEvent => Any < Async): Self = withAttrs(attrs.copy(onScrollEvt = Present(f)))
        end Interactive

        // ---- Layout traits ----

        /** Content-model marker for elements that are valid HTML children: all `Block` and `Inline` layout elements, `Text` nodes,
          * and the SVG root (`Svg.Root`). HTML containers restrict their `apply` overloads to this bound so that bare SVG primitives
          * (which extend `SvgElement` but NOT `HtmlContent`) are rejected at compile time.
          */
        sealed trait HtmlContent extends UI

        /** Typeclass witnessing that `T` is a valid child of an HTML container (content-model safety).
          *
          * Recursive givens cover: direct `HtmlContent` values, `Reactive[C]`/`Foreach[A,C]`/`Fragment[C]` where `C` has an
          * `AsHtmlChild` witness (so a nested `Fragment[Fragment[...]]` and a `foreach`/`when` returning a `Fragment` are
          * accepted), and union types `A | B` where both sides have witnesses. `AsHtmlChild[UI]` has no given, so a value
          * statically typed as bare `UI` is still rejected; `AsHtmlChild[Svg.Circle]` has no given (SVG primitives do not extend
          * `HtmlContent`), so SVG nodes are rejected. Each child is checked individually at the call site via the `HtmlChildVal`
          * implicit conversion, which avoids the LUB-widening that varargs would otherwise cause for heterogeneous arg lists.
          *
          * The `if cond then elem else UI.empty` idiom infers the branch union `A | Fragment[Nothing]`. The general `A | B`
          * given does not decompose a union (implicit search binds both `A` and `B` to the whole union, which then fails), so a
          * dedicated `emptyOr` given resolves the "render this element or render nothing" shape directly when it is passed as a
          * direct container child, e.g. `div(if cond then span(...) else UI.empty)`. It is placed in the lower-priority
          * [[kyo.UI.Ast.AsHtmlChildLowPriority]] base so it does not compete with the direct `Fragment` given when the type is a
          * bare `Fragment[Nothing]`. `A` still needs its own `AsHtmlChild` witness, so `Svg.Circle | Fragment[Nothing]` is rejected.
          *
          * For a reactive body the union must be pinned explicitly: `signal.render[Elem | Fragment[Nothing]](v => if v then elem
          * else UI.empty)`, because `render`'s content type `C` is inferred independently of the container and would otherwise
          * widen to `UI` (which has no witness). The simpler "show element while true, else nothing" case needs no union at all:
          * `UI.when(signal)(elem)` renders `elem` when true and `UI.empty` otherwise.
          *
          * Known limitation: the general `A | B` union given does NOT recurse through the structural givens for a
          * disjunct, so a single `fragment(...)` whose children mix a nested structural child with a plain sibling (element type
          * e.g. `Fragment[SpanElement] | SpanElement`) is rejected. The fix is to convert per-element at the container instead,
          * e.g. `div(fragment(fragment(a, b)), c)`; all realistic patterns compile this way.
          */
        trait AsHtmlChild[T]

        /** Lower-priority `AsHtmlChild` givens. Holds the `emptyOr` given for the `A | Fragment[Nothing]` branch union so it does
          * not create an ambiguity with the direct `Fragment` given when the static type is a bare `Fragment[Nothing]`.
          */
        sealed trait AsHtmlChildLowPriority:
            given emptyOr[A <: UI](using AsHtmlChild[A]): AsHtmlChild[A | Fragment[Nothing]] = new AsHtmlChild[A | Fragment[Nothing]] {}

        object AsHtmlChild extends AsHtmlChildLowPriority:
            given [T <: HtmlContent]: AsHtmlChild[T]                               = new AsHtmlChild[T] {}
            given [C <: UI](using AsHtmlChild[C]): AsHtmlChild[Reactive[C]]        = new AsHtmlChild[Reactive[C]] {}
            given [A, C <: UI](using AsHtmlChild[C]): AsHtmlChild[Foreach[A, C]]   = new AsHtmlChild[Foreach[A, C]] {}
            given [C <: UI](using AsHtmlChild[C]): AsHtmlChild[Fragment[C]]        = new AsHtmlChild[Fragment[C]] {}
            given [A, B](using AsHtmlChild[A], AsHtmlChild[B]): AsHtmlChild[A | B] = new AsHtmlChild[A | B] {}
        end AsHtmlChild

        /** Witness that a value has passed the `AsHtmlChild` check, wrapping the underlying `UI` value. Because the wrapper is
          * stored in the `HtmlChildVal*` vararg array (a generic position), the `AnyVal` is boxed there, so each argument
          * allocates one short-lived wrapper at the call site. The wrappers are unwrapped immediately into the container's
          * `Chunk[UI]` children and not retained, so this is a per-argument allocation on the cold construction path, not on
          * the hot render path. HTML containers accept `HtmlChildVal*` so that each argument is checked individually at the
          * call site rather than via a single inferred `T`, which avoids LUB-widening issues for heterogeneous lists like
          * `(Button, Reactive[Span])`.
          */
        final class HtmlChildVal private[kyo] (private[kyo] val value: UI) extends AnyVal

        object HtmlChildVal:
            /** Implicit conversion: any value `T` with a `AsHtmlChild[T]` witness becomes a `HtmlChildVal`. */
            implicit def lift[T <: UI](t: T)(using AsHtmlChild[T]): HtmlChildVal = new HtmlChildVal(t)

            /** Direct implicit conversion from `String`: bridges the `stringToUI` auto-lift so that bare string literals
              * can be passed directly to HTML container `apply` overloads without a double-implicit chain.
              */
            implicit def liftString(s: String)(using Frame): HtmlChildVal = new HtmlChildVal(Text(s))

            /** Direct implicit conversion from a `Signal[A]` where A is an HTML-valid UI type: bridges the
              * `signalUIToUI`/`signalStringToUI` auto-lifts without requiring a double-implicit chain.
              * Checks that `AsHtmlChild[A]` exists so bare `Signal[SvgElement]` is still rejected.
              */
            implicit def liftSignal[A <: UI](sig: Signal[A])(using Frame, AsHtmlChild[A]): HtmlChildVal =
                new HtmlChildVal(Reactive[A](sig.map(x => x: UI)))

            /** Direct implicit conversion from a `Signal[String]` (including `SignalRef[String]`): produces a reactive
              * text node so string signals can be passed directly to HTML container `apply` overloads.
              */
            implicit def liftSignalString(sig: Signal[String])(using Frame): HtmlChildVal =
                new HtmlChildVal(Reactive[Text](sig.map(s => Text(s))))
        end HtmlChildVal

        /** Layout marker for block-flow elements (`div`, `p`, headings, ...). */
        sealed trait Block extends Element with HtmlContent

        /** Layout marker for inline-flow elements (`span`, `a`, inputs, ...). */
        sealed trait Inline extends Element with HtmlContent

        // ---- SVG cross-file extension bridge ----

        /** Sanctioned cross-file extension point for the SVG AST (defined in `Svg.scala`).
          *
          * `Element`, `Interactive`, `Block`, `Inline`, and `HtmlContent` are `sealed`, so the HTML
          * AST stays closed and exhaustive over `UI`. SVG nodes live in a separate file, so they
          * cannot extend a sealed trait directly; `SvgNode`, `SvgInteractiveNode`, and `SvgRootNode`
          * are the only non-sealed descendants and form the bridge. `Svg.SvgElement` extends `SvgNode`, which makes every SVG
          * element an `Element` (so it reuses the path/event/reactive engine) without making it
          * `HtmlContent`: a bare SVG primitive is therefore not a valid HTML child (`div(Svg.circle)`
          * does not compile).
          */
        trait SvgNode extends Element

        /** Bridge for SVG nodes that also accept event handlers (`SvgNode` plus `Interactive`).
          * SVG elements that carry events mix this in instead of `Interactive` directly, since
          * `Interactive` is sealed.
          */
        trait SvgInteractiveNode extends SvgNode with Interactive

        /** Bridge for the `<svg>` root only: an `SvgInteractiveNode` that is also `Inline`
          * `HtmlContent`, so `Svg.Root` (and only the root) embeds in an HTML container
          * (`div(Svg.svg(...))` compiles; `div(Svg.circle(...))` does not).
          */
        trait SvgRootNode extends SvgInteractiveNode with Inline with HtmlContent

        // ---- Void trait (elements that cannot have children) ----

        /** Capability trait for void elements that cannot have children (`hr`, `br`, `input`, ...); fixes `children` to empty. */
        sealed trait Void extends Element:
            final def children: Chunk[UI] = Chunk.empty

        // ---- Capability traits ----

        /** Capability marker for elements that can receive keyboard focus. */
        sealed trait Focusable extends Element

        /** Capability trait for elements exposing a `disabled` setter (plain `Boolean` and reactive `Signal[Boolean]` overloads). */
        sealed trait HasDisabled extends Element:
            def disabled: Maybe[Boolean]
            def disabled(v: Boolean): Self

            /** Reactive `disabled`: re-renders when the signal emits. */
            def disabled(v: Signal[Boolean]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(b => this.disabled(b): UI))
        end HasDisabled

        /** Capability trait for free-text form inputs: a `String` `value` (constant or `SignalRef`), placeholder, read-only, and `onInput`/`onChange`. */
        sealed trait TextInput extends Focusable with HasDisabled:
            def value: Maybe[Bound[String]]
            def placeholder: Maybe[String]
            def readOnly: Maybe[Boolean]
            def onInput: Maybe[String => Any < Async]
            def onChange: Maybe[String => Any < Async]
            def value(v: String): Self

            /** Binds the input to a `SignalRef` two-way: edits write the new text back into the ref, and ref changes update the input. */
            def value(v: SignalRef[String]): Self
        end TextInput

        /** Capability trait for picker inputs that carry a `String` `value` and an `onChange` but no free-text typing (`select`, date/time/color, ...). */
        sealed trait PickerInput extends Focusable with HasDisabled:
            def value: Maybe[Bound[String]]
            def onChange: Maybe[String => Any < Async]
            def value(v: String): Self

            /** Binds the picker to a `SignalRef` two-way: a selection writes back into the ref, and ref changes update the selection. */
            def value(v: SignalRef[String]): Self
        end PickerInput

        /** Capability trait for boolean inputs that carry a `checked` flag (constant or `SignalRef`) and a `Boolean` `onChange` (`checkbox`, `radio`). */
        sealed trait BooleanInput extends Focusable with HasDisabled:
            def checked: Maybe[Bound[Boolean]]
            def onChange: Maybe[Boolean => Any < Async]
            def checked(v: Boolean): Self

            /** Binds the checked state to a `SignalRef` two-way: toggling writes back into the ref, and ref changes update the control. */
            def checked(v: SignalRef[Boolean]): Self

            /** Reactive (one-way) `checked` from a read-only `Signal`; use the `SignalRef` overload for two-way binding. */
            def checked(v: Signal[Boolean]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(b => this.checked(b): UI))
        end BooleanInput

        /** Capability marker for elements with semantic activation (button, anchor): can be triggered by keyboard as well as click. */
        sealed trait Activatable extends Element

        /** Capability marker for elements whose primary semantic is being clicked (button, anchor). */
        sealed trait Clickable extends Element

        final private[kyo] case class Attrs(
            identifier: Maybe[String] = Absent,
            hidden: Maybe[Boolean] = Absent,
            tabIndex: Maybe[Int] = Absent,
            focusTrap: Maybe[Boolean] = Absent,
            focusGroup: Maybe[String] = Absent,
            uiStyle: Style = Style.empty,
            onClick: Maybe[Any < Async] = Absent,
            onClickEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onClickSelf: Maybe[Any < Async] = Absent,
            onClickSelfEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onKeyDown: Maybe[KeyboardEvent => Any < Async] = Absent,
            onKeyUp: Maybe[KeyboardEvent => Any < Async] = Absent,
            onFocus: Maybe[Any < Async] = Absent,
            onFocusEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onBlur: Maybe[Any < Async] = Absent,
            onBlurEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onHover: Maybe[Any < Async] = Absent,
            onHoverEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onUnhover: Maybe[Any < Async] = Absent,
            onUnhoverEvt: Maybe[MouseEvent => Any < Async] = Absent,
            onScroll: Maybe[Any < Async] = Absent,
            onScrollEvt: Maybe[WheelEvent => Any < Async] = Absent,
            ariaAttrs: Map[String, String] = Map.empty,
            dataAttrs: Map[String, String] = Map.empty,
            jsProps: Map[String, String] = Map.empty,
            role: Maybe[String] = Absent
        )

        // ---- Non-element AST cases ----

        /** A literal text node; a bare `String` child auto-lifts to one. */
        case class Text(value: String)(using val frame: Frame) extends UI with HtmlContent

        /** A subtree driven by a `Signal[UI]`: re-renders the bound region whenever the signal emits a new value. The type parameter `C`
          * is a phantom bound that records the content type at the construction site; it erases to `UI` at runtime.
          */
        case class Reactive[C <: UI](signal: Signal[UI])(using val frame: Frame) extends UI

        /** A keyed list driven by a `Signal[Chunk[A]]`: renders one child per element, reconciling by `key` when present. The type
          * parameter `C` is a phantom bound that records the content type at the construction site; it erases to `UI` at runtime.
          */
        case class Foreach[A, C <: UI](signal: Signal[Chunk[A]], key: Maybe[A => String], render: (Int, A) => UI)(using val frame: Frame)
            extends UI:
            /** Apply a polymorphic continuation with the typed members of this Foreach.
              *
              * Callers that match on `Foreach[?, ?]` (existential) lose the type parameters. This method re-introduces A in a single
              * cast that is sound because the Foreach instance carries its own signal/render/key all constructed with the same A.
              */
            private[kyo] def applyTyped[B](k: [T] => (Signal[Chunk[T]], Maybe[T => String], (Int, T) => UI) => B): B =
                k[A](signal, key, render)
        end Foreach

        /** A transparent grouping of children with no wrapping element; renders its children inline into the parent. The type parameter
          * `C` is a phantom bound that records the content type at the construction site; it erases to `UI` at runtime.
          */
        case class Fragment[C <: UI](children: Chunk[UI])(using val frame: Frame) extends UI

        private[kyo] case class KeyedChild[C <: UI](key: String, child: UI)(using val frame: Frame) extends UI

        // ====== Block containers ======

        final case class Div(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Div
            def withAttrs(a: Attrs): Div      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Div = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Div

        final case class P(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = P
            def withAttrs(a: Attrs): P      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): P = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end P

        final case class Section(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Section
            def withAttrs(a: Attrs): Section      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Section = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Section

        final case class Main(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Main
            def withAttrs(a: Attrs): Main      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Main = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Main

        final case class Header(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Header
            def withAttrs(a: Attrs): Header      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Header = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Header

        final case class Footer(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Footer
            def withAttrs(a: Attrs): Footer      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Footer = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Footer

        final case class Pre(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Pre
            def withAttrs(a: Attrs): Pre      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Pre = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Pre

        final case class Code(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Code
            def withAttrs(a: Attrs): Code      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Code = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Code

        final case class Ul(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Ul
            def withAttrs(a: Attrs): Ul      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Ul = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Ul

        final case class Ol(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Ol
            def withAttrs(a: Attrs): Ol      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Ol = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Ol

        final case class Table(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Table
            def withAttrs(a: Attrs): Table      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Table = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Table

        // ====== Headings (Block) ======

        final case class H1(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H1
            def withAttrs(a: Attrs): H1      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H1 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H1

        final case class H2(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H2
            def withAttrs(a: Attrs): H2      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H2 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H2

        final case class H3(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H3
            def withAttrs(a: Attrs): H3      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H3 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H3

        final case class H4(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H4
            def withAttrs(a: Attrs): H4      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H4 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H4

        final case class H5(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H5
            def withAttrs(a: Attrs): H5      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H5 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H5

        final case class H6(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = H6
            def withAttrs(a: Attrs): H6      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): H6 = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end H6

        // ====== Inline containers ======

        final case class SpanElement(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Inline
            with Interactive:
            type Self = SpanElement
            def withAttrs(a: Attrs): SpanElement      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): SpanElement = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end SpanElement

        final case class Nav(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Inline
            with Interactive:
            type Self = Nav
            def withAttrs(a: Attrs): Nav      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Nav = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Nav

        final case class Li(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Inline
            with Interactive:
            type Self = Li
            def withAttrs(a: Attrs): Li      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Li = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Li

        final case class Tr(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Inline
            with Interactive:
            type Self = Tr
            def withAttrs(a: Attrs): Tr      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Tr = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Tr

        // ====== Specialized Block elements ======

        final case class Form(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            onSubmit: Maybe[Any < Async] = Absent,
            onSubmitEvt: Maybe[MouseEvent => Any < Async] = Absent
        )(using val frame: Frame) extends Block with Interactive:
            type Self = Form
            def withAttrs(a: Attrs): Form      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Form = copy(children = children ++ Chunk.from(cs.map(_.value)))

            /** Runs `action` on form submit, ignoring the event payload. */
            def onSubmit(action: => Any < Async): Form = copy(onSubmit = Present(Sync.defer(action)(using frame)))

            /** Runs `f` on form submit, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onSubmit(f: MouseEvent => Any < Async): Form = copy(onSubmitEvt = Present(f))
        end Form

        final case class Textarea(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Block with Interactive with TextInput with Void:
            type Self = Textarea
            def withAttrs(a: Attrs): Textarea = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): Textarea                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): Textarea        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): Textarea             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): Textarea               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): Textarea               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): Textarea  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): Textarea = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end Textarea

        final case class Select(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            value: Maybe[Bound[String]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Block with Interactive with PickerInput:
            type Self = Select
            def withAttrs(a: Attrs): Select = copy(attrs = a)

            def apply(cs: HtmlChildVal*): Select           = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def value(v: String): Select                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): Select        = copy(value = Present(Bound.Ref(v)))
            def disabled(v: Boolean): Select               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): Select = copy(onChange = Present(f))
        end Select

        final case class Hr(attrs: Attrs = Attrs())(using val frame: Frame) extends Block with Void:
            type Self = Hr
            def withAttrs(a: Attrs): Hr = copy(attrs = a)

        final case class Br(attrs: Attrs = Attrs())(using val frame: Frame) extends Block with Void:
            type Self = Br
            def withAttrs(a: Attrs): Br = copy(attrs = a)

        final case class Td(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        )(using val frame: Frame) extends Block with Interactive:
            type Self = Td
            def withAttrs(a: Attrs): Td      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Td = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def colspan(v: Int): Td          = copy(colspan = Present(math.max(1, v)))
            def rowspan(v: Int): Td          = copy(rowspan = Present(math.max(1, v)))
        end Td

        final case class Th(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            colspan: Maybe[Int] = Absent,
            rowspan: Maybe[Int] = Absent
        )(using val frame: Frame) extends Block with Interactive:
            type Self = Th
            def withAttrs(a: Attrs): Th      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Th = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def colspan(v: Int): Th          = copy(colspan = Present(math.max(1, v)))
            def rowspan(v: Int): Th          = copy(rowspan = Present(math.max(1, v)))
        end Th

        final case class Label(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            forId: Maybe[String] = Absent
        )(using val frame: Frame) extends Block with Interactive:
            type Self = Label
            def withAttrs(a: Attrs): Label      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Label = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def forId(v: String): Label         = copy(forId = Present(v))
            def `for`(v: String): Label         = forId(v)
        end Label

        final case class Opt(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            value: Maybe[String] = Absent,
            selected: Maybe[Boolean] = Absent
        )(using val frame: Frame) extends Block:
            type Self = Opt
            def withAttrs(a: Attrs): Opt      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Opt = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def value(v: String): Opt         = copy(value = Present(v))
            def selected(v: Boolean): Opt     = copy(selected = Present(v))
            def selected(v: Signal[Boolean]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(b => this.selected(b): UI))
        end Opt

        // ====== Specialized Inline elements ======

        final case class Button(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            disabled: Maybe[Boolean] = Absent
        )(using val frame: Frame) extends Inline with Interactive with Focusable with HasDisabled with Activatable with Clickable:
            type Self = Button
            def withAttrs(a: Attrs): Button = copy(attrs = a)

            def apply(cs: HtmlChildVal*): Button = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def disabled(v: Boolean): Button     = copy(disabled = Present(v))
        end Button

        // ---- Boolean inputs (checked + onChange: Boolean) ----

        final case class Checkbox(
            attrs: Attrs = Attrs(),
            checked: Maybe[Bound[Boolean]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[Boolean => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with BooleanInput with Void:
            type Self = Checkbox
            def withAttrs(a: Attrs): Checkbox = copy(attrs = a)

            def checked(v: Boolean): Checkbox                 = copy(checked = Present(Bound.Const(v)))
            def checked(v: SignalRef[Boolean]): Checkbox      = copy(checked = Present(Bound.Ref(v)))
            def disabled(v: Boolean): Checkbox                = copy(disabled = Present(v))
            def onChange(f: Boolean => Any < Async): Checkbox = copy(onChange = Present(f))

            /** Sets the DOM `indeterminate` property, a visual tri-state distinct from `checked` (the checkbox shows a dash, not a tick). */
            def indeterminate(v: Boolean): Checkbox =
                if v then jsProp("indeterminate", "true")
                else withAttrs(attrs.copy(jsProps = attrs.jsProps - "indeterminate"))

            /** Reactive `indeterminate`: re-renders when the signal emits. */
            def indeterminate(v: Signal[Boolean])(using Frame, CanEqual[Boolean, Boolean]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(b => this.indeterminate(b): UI))
        end Checkbox

        final case class Radio(
            attrs: Attrs = Attrs(),
            checked: Maybe[Bound[Boolean]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[Boolean => Any < Async] = Absent,
            name: Maybe[String] = Absent
        )(using val frame: Frame) extends Inline with Interactive with BooleanInput with Void:
            type Self = Radio
            def withAttrs(a: Attrs): Radio = copy(attrs = a)

            def checked(v: Boolean): Radio                 = copy(checked = Present(Bound.Const(v)))
            def checked(v: SignalRef[Boolean]): Radio      = copy(checked = Present(Bound.Ref(v)))
            def disabled(v: Boolean): Radio                = copy(disabled = Present(v))
            def onChange(f: Boolean => Any < Async): Radio = copy(onChange = Present(f))
            def name(v: String): Radio                     = copy(name = Present(v))
        end Radio

        // ---- Text inputs (value + placeholder + onInput + onChange: String) ----

        final private[kyo] case class TextInputAttrs(
            value: Maybe[Bound[String]] = Absent,
            placeholder: Maybe[String] = Absent,
            readOnly: Maybe[Boolean] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onInput: Maybe[String => Any < Async] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )

        final case class Input(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = Input
            def withAttrs(a: Attrs): Input = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): Input                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): Input        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): Input             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): Input               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): Input               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): Input  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): Input = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end Input

        final case class PasswordInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = PasswordInput
            def withAttrs(a: Attrs): PasswordInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): PasswordInput            = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): PasswordInput = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): PasswordInput      = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): PasswordInput        = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): PasswordInput        = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): PasswordInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): PasswordInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end PasswordInput

        final case class EmailInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = EmailInput
            def withAttrs(a: Attrs): EmailInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): EmailInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): EmailInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): EmailInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): EmailInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): EmailInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): EmailInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): EmailInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end EmailInput

        final case class TelInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = TelInput
            def withAttrs(a: Attrs): TelInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): TelInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): TelInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): TelInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): TelInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): TelInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): TelInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): TelInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end TelInput

        final case class UrlInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = UrlInput
            def withAttrs(a: Attrs): UrlInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): UrlInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): UrlInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): UrlInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): UrlInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): UrlInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): UrlInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): UrlInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end UrlInput

        final case class SearchInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = SearchInput
            def withAttrs(a: Attrs): SearchInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): SearchInput                  = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): SearchInput       = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): SearchInput            = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): SearchInput              = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): SearchInput              = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): SearchInput = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): SearchInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
        end SearchInput

        final case class NumberInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs(),
            min: Maybe[Double] = Absent,
            max: Maybe[Double] = Absent,
            step: Maybe[Double] = Absent,
            onChangeNumeric: Maybe[Double => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with TextInput with Void:
            type Self = NumberInput
            def withAttrs(a: Attrs): NumberInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange

            def value(v: String): NumberInput                  = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): NumberInput       = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): NumberInput            = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): NumberInput              = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): NumberInput              = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): NumberInput = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): NumberInput        = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def min(v: Double): NumberInput                            = copy(min = Present(v))
            def max(v: Double): NumberInput                            = copy(max = Present(v))
            def step(v: Double): NumberInput                           = copy(step = Present(if v <= 0 then 1.0 else v))
            def onChangeNumeric(f: Double => Any < Async): NumberInput = copy(onChangeNumeric = Present(f))
        end NumberInput

        // ---- Picker inputs (value + onChange: String, no placeholder/onInput) ----

        final case class DateInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Bound[String]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with PickerInput with Void:
            type Self = DateInput
            def withAttrs(a: Attrs): DateInput                = copy(attrs = a)
            def value(v: String): DateInput                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): DateInput        = copy(value = Present(Bound.Ref(v)))
            def disabled(v: Boolean): DateInput               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): DateInput = copy(onChange = Present(f))
        end DateInput

        final case class TimeInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Bound[String]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with PickerInput with Void:
            type Self = TimeInput
            def withAttrs(a: Attrs): TimeInput                = copy(attrs = a)
            def value(v: String): TimeInput                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): TimeInput        = copy(value = Present(Bound.Ref(v)))
            def disabled(v: Boolean): TimeInput               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): TimeInput = copy(onChange = Present(f))
        end TimeInput

        final case class ColorInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Bound[String]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with PickerInput with Void:
            type Self = ColorInput
            def withAttrs(a: Attrs): ColorInput                = copy(attrs = a)
            def value(v: String): ColorInput                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): ColorInput        = copy(value = Present(Bound.Ref(v)))
            def disabled(v: Boolean): ColorInput               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): ColorInput = copy(onChange = Present(f))
        end ColorInput

        // ---- Special inputs ----

        final case class RangeInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Bound[Double]] = Absent,
            min: Maybe[Double] = Absent,
            max: Maybe[Double] = Absent,
            step: Maybe[Double] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[Double => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with Focusable with HasDisabled with Void:
            type Self = RangeInput
            def withAttrs(a: Attrs): RangeInput                = copy(attrs = a)
            def value(v: Double): RangeInput                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[Double]): RangeInput        = copy(value = Present(Bound.Ref(v)))
            def min(v: Double): RangeInput                     = copy(min = Present(v))
            def max(v: Double): RangeInput                     = copy(max = Present(v))
            def step(v: Double): RangeInput                    = copy(step = Present(if v <= 0 then 1.0 else v))
            def disabled(v: Boolean): RangeInput               = copy(disabled = Present(v))
            def onChange(f: Double => Any < Async): RangeInput = copy(onChange = Present(f))
        end RangeInput

        final case class FileInput(
            attrs: Attrs = Attrs(),
            accept: Maybe[Chunk[FileAccept]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Inline with Interactive with Focusable with HasDisabled with Void:
            type Self = FileInput
            def withAttrs(a: Attrs): FileInput                = copy(attrs = a)
            def accept(vs: FileAccept*): FileInput            = copy(accept = Present(Chunk.from(vs)))
            def disabled(v: Boolean): FileInput               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): FileInput = copy(onChange = Present(f))
        end FileInput

        final case class HiddenInput(
            attrs: Attrs = Attrs(),
            value: Maybe[Bound[String]] = Absent
        )(using val frame: Frame) extends Inline with Void:
            type Self = HiddenInput
            def withAttrs(a: Attrs): HiddenInput         = copy(attrs = a)
            def value(v: String): HiddenInput            = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): HiddenInput = copy(value = Present(Bound.Ref(v)))
        end HiddenInput

        final case class Anchor(
            attrs: Attrs = Attrs(),
            children: Chunk[UI] = Chunk.empty,
            href: Maybe[Href] = Absent,
            target: Maybe[Target] = Absent
        )(using val frame: Frame) extends Inline with Interactive with Focusable with Activatable with Clickable:
            type Self = Anchor
            def withAttrs(a: Attrs): Anchor      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Anchor = copy(children = children ++ Chunk.from(cs.map(_.value)))
            def href(v: Href): Anchor            = copy(href = Present(v))
            def href(v: Signal[Href]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(h => this.href(h): UI))
            def href(v: Href, target: Target): Anchor = copy(href = Present(v), target = Present(target))
            def target(v: Target): Anchor             = copy(target = Present(v))
        end Anchor

        final case class Img(
            attrs: Attrs = Attrs(),
            src: Maybe[ImgSrc] = Absent,
            alt: Maybe[String] = Absent
        )(using val frame: Frame) extends Inline with Void:
            type Self = Img
            def withAttrs(a: Attrs): Img = copy(attrs = a)
            def src(v: ImgSrc): Img      = copy(src = Present(v))
            def src(v: Signal[ImgSrc]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(s => this.src(s): UI))
            def alt(v: String): Img = copy(alt = Present(v))
        end Img

        /** An inline frame embedding another document at `src`. `title` names the frame for assistive technology. Size it with `.style`. */
        final case class Iframe(
            attrs: Attrs = Attrs(),
            src: Maybe[String] = Absent,
            frameTitle: Maybe[String] = Absent
        )(using val frame: Frame) extends Block with Void:
            type Self = Iframe
            def withAttrs(a: Attrs): Iframe = copy(attrs = a)
            def src(v: String): Iframe      = copy(src = Present(v))
            def src(v: Signal[String]): Reactive[Self] =
                given Frame = frame
                Reactive[Self](v.map(s => this.src(s): UI))
            def title(v: String): Iframe = copy(frameTitle = Present(v))
        end Iframe

        // ---- Custom dropdown (div-based overlay, NOT native <select>) ----

        final case class Dropdown(
            attrs: Attrs = Attrs(),
            options: Chunk[(String, String)] = Chunk.empty,
            value: Maybe[Bound[String]] = Absent,
            disabled: Maybe[Boolean] = Absent,
            onChange: Maybe[String => Any < Async] = Absent
        )(using val frame: Frame) extends Block with Interactive with PickerInput:
            type Self = Dropdown
            def children: Chunk[UI]                          = Chunk.empty
            def withAttrs(a: Attrs): Dropdown                = copy(attrs = a)
            def value(v: String): Dropdown                   = copy(value = Present(Bound.Const(v)))
            def value(v: SignalRef[String]): Dropdown        = copy(value = Present(Bound.Ref(v)))
            def disabled(v: Boolean): Dropdown               = copy(disabled = Present(v))
            def onChange(f: String => Any < Async): Dropdown = copy(onChange = Present(f))
        end Dropdown

        // ---- Charts: grouping carrier ----

        /** Carries the grouping accessor and normalization flag for a stacked mark.
          *
          * A `Grouping[A]` is produced exclusively by `UI.by(...)`, so there is no way to
          * express a stack without a grouping accessor. The two `by` forms are:
          *
          * {{{
          * stack = UI.by(_.region)                        // stack by region
          * stack = UI.by(_.region, normalize = true)      // 100% stacked
          * }}}
          *
          * The `normalize` flag is a named parameter, not a chained method, which
          * preserves row-type inference (the appendix-validated design constraint).
          *
          * `Grouping.none[A]` is the library-internal default for the `stack` parameter;
          * callers never construct it directly.
          */
        final case class Grouping[A](group: Maybe[A => Any], normalize: Boolean)

        object Grouping:
            private[kyo] def none[A]: Grouping[A] = Grouping(Absent, false)

        // ---- Charts: channel carriers ----

        /** Bundles a row accessor with the `Plottable` evidence for its value type.
          *
          * Captures the scale evidence and the `ConcreteTag` of the channel value type
          * at the factory call site so the lowering phase does not need to re-derive
          * either. The `tag` is a stable, platform-independent type identity captured
          * from the static type `C`, used to key category values so the keying matches
          * across the JVM, Scala.js, and Native (a boxed runtime class would diverge).
          * `A` is the row type; `C` is the channel value type. Both positional and
          * non-positional channels use this carrier.
          */
        final case class Encoding[A, C](accessor: A => C, plottable: Plottable[C], tag: ConcreteTag[C])

        /** A channel whose accessor may return `Maybe[C]`, supporting gap semantics.
          *
          * A `EncodingMaybe[A, C]` is built from an `A => C` total accessor (via
          * `EncodingMaybe.fromTotal`) or an `A => Maybe[C]` gap accessor (via
          * `EncodingMaybe.fromMaybe`). The lowering phase calls `accessor` and treats
          * `Absent` as a gap (breaks a line, drops a bar or point).
          *
          * `plottable` is the evidence for `C`, not for `Maybe[C]`, so the scale
          * resolution in lowering uses the inner type directly. `tag` is the stable
          * `ConcreteTag` of the inner type `C`, captured from the static type at
          * construction for cross-platform category keying.
          */
        final case class EncodingMaybe[A, C](accessor: A => Maybe[C], plottable: Plottable[C], tag: ConcreteTag[C])

        object EncodingMaybe:
            def fromTotal[A, C](f: A => C, pl: Plottable[C], tag: ConcreteTag[C]): EncodingMaybe[A, C] =
                EncodingMaybe(a => Present(f(a)), pl, tag)
            def fromMaybe[A, C](f: A => Maybe[C], pl: Plottable[C], tag: ConcreteTag[C]): EncodingMaybe[A, C] =
                EncodingMaybe(f, pl, tag)
        end EncodingMaybe

        // ---- Charts: data source carrier ----

        /** The data backing a chart: static (a `Chunk`) or live (a `Signal`).
          *
          * `Static` data lowers the whole tree once. `Live` data lowers the static frame
          * once and wraps the marks region in `signal.render` so marks redraw on each
          * emission while the frame, axes, and legend stay put.
          */
        private[kyo] enum DataSource[A]:
            case Static(rows: Chunk[A])
            case Live(rows: Signal[Chunk[A]])

        // ---- Charts: ChartSpec ----

        /** The fully-configured, not-yet-lowered chart intermediate representation.
          *
          * Built by `UI.chart(data)(marks*)` and then refined by the configuration methods.
          * Every config method returns a copy with one field changed; the whole chain is
          * pure and allocation-light (case class copy).
          *
          * `ChartSpec[A]` converts to `Svg.Root` automatically via the `given Conversion`
          * below; that conversion is the only path to a rendered chart. Until it is
          * invoked the spec is just a value you can inspect and modify.
          *
          * Fields: `data` is either `DataSource.Static` (a `Chunk`) or `DataSource.Live`
          * (a `Signal`). `marks` is the ordered list of mark layers. The `*Cfg` fields
          * hold the configuration built by the builder lambdas. `key` / `onHover` /
          * `onSelect` / `tooltip` drive reactivity and interaction.
          */
        final case class ChartSpec[A](
            data: DataSource[A],
            marks: Chunk[Mark[A]],
            chartSize: (Int, Int),
            xAxisCfg: AxisConfig,
            yAxisCfg: AxisConfig,
            yAxisRightCfg: Maybe[AxisConfig],
            legendCfg: LegendConfig,
            theme: Theme,
            xScaleOverride: Maybe[ScaleOverride],
            yScaleOverride: Maybe[ScaleOverride],
            yScaleRightOverride: Maybe[ScaleOverride],
            animateCfg: AnimateConfig,
            key: Maybe[A => String],
            onHover: Maybe[Signal.SignalRef[Maybe[A]]],
            onSelect: Maybe[Signal.SignalRef[Maybe[A]]],
            tooltip: Maybe[A => String],
            interactionCfg: ChartSpec.InteractionConfig = ChartSpec.InteractionConfig.default,
            a11y: ChartSpec.A11y = ChartSpec.A11y.default,
            isResponsive: Boolean = false,
            aspectRatio: Maybe[Double] = Absent,
            marginsCfg: ChartSpec.Margins = ChartSpec.Margins.default
        )

        /** Companion for `ChartSpec`. Holds nested configuration types. */
        object ChartSpec:

            /** Accessibility metadata emitted into the chart's root `<svg>` (D28, Q-008).
              *
              * `title` becomes a `<title>` child and implies `role="img"` on the root so assistive
              * technology announces the chart as a single image with that accessible name. `desc`
              * becomes a `<desc>` child for a longer description. `ariaLabel` sets the `aria-label`
              * attribute directly. All three default to `Absent`, in which case no a11y markup is
              * emitted. Set them with `.title(s)`, `.desc(s)`, and `.ariaLabel(s)`.
              */
            final case class A11y(
                title: Maybe[String],
                desc: Maybe[String],
                ariaLabel: Maybe[String]
            )

            object A11y:
                /** The default: no accessibility metadata. */
                val default: A11y = A11y(Absent, Absent, Absent)
            end A11y

            /** Plot margins in pixels around the inner plot rectangle (D30).
              *
              * `top`, `right`, `bottom`, and `left` reserve space for axis chrome and labels.
              * The defaults (`20/20/40/60`) match the historical built-in constants; the larger
              * bottom and left make room for the x-axis tick labels and the y-axis numbers. Set
              * them with `.margins(t, r, b, l)` or `.margins(_.left(80))`.
              */
            final case class Margins(
                top: Double,
                right: Double,
                bottom: Double,
                left: Double
            ):
                def top(v: Double): Margins    = copy(top = v)
                def right(v: Double): Margins  = copy(right = v)
                def bottom(v: Double): Margins = copy(bottom = v)
                def left(v: Double): Margins   = copy(left = v)
            end Margins

            object Margins:
                /** The default margins matching the historical built-in layout constants. */
                val default: Margins = Margins(20.0, 20.0, 40.0, 60.0)
            end Margins

            /** Controls built-in visual highlight behavior when a hover or select ref is set.
              *
              * All fields default `false`/`Absent`. Set `hoverHighlight` or `selectHighlight` to opt into the
              * built-in opacity boost on the active mark. `hoverStyle` and `selectStyle` let you supply a custom
              * `Style` instead of the default boost. If the relevant ref (`onHover`/`onSelect`) is not configured
              * on the chart, all highlight settings are no-ops (INV-024).
              *
              * Build via the chaining methods: `_.highlightHover`, `_.highlightSelect`, `_.hoverStyle(s)`,
              * `_.selectStyle(s)`.
              */
            final case class InteractionConfig(
                hoverHighlight: Boolean = false,
                selectHighlight: Boolean = false,
                hoverStyle: Maybe[Style] = Absent,
                selectStyle: Maybe[Style] = Absent
            ):
                /** Enable the built-in hover highlight (opacity boost on the hovered mark). */
                def highlightHover: InteractionConfig = copy(hoverHighlight = true)

                /** Enable the built-in select highlight (opacity boost on the selected mark). */
                def highlightSelect: InteractionConfig = copy(selectHighlight = true)

                /** Apply a custom style on hover (also enables hover highlight). */
                def hoverStyle(s: Style): InteractionConfig = copy(hoverHighlight = true, hoverStyle = Present(s))

                /** Apply a custom style on select (also enables select highlight). */
                def selectStyle(s: Style): InteractionConfig = copy(selectHighlight = true, selectStyle = Present(s))
            end InteractionConfig

            object InteractionConfig:
                /** The default config: all highlight features disabled. */
                val default: InteractionConfig = InteractionConfig()
            end InteractionConfig

        end ChartSpec

        extension [A](spec: ChartSpec[A])

            /** Configures the x-axis using a builder lambda. */
            def xAxis(f: AxisConfig => AxisConfig): ChartSpec[A] =
                spec.copy(xAxisCfg = f(spec.xAxisCfg))

            /** Configures the left y-axis using a builder lambda. */
            def yAxis(f: AxisConfig => AxisConfig): ChartSpec[A] =
                spec.copy(yAxisCfg = f(spec.yAxisCfg))

            /** Configures the right y-axis using a builder lambda. Creates it if absent. */
            def yAxisRight(f: AxisConfig => AxisConfig): ChartSpec[A] =
                val base = spec.yAxisRightCfg.getOrElse(AxisConfig.default)
                spec.copy(yAxisRightCfg = Present(f(base)))

            /** Configures the legend using a builder lambda. */
            def legend(f: LegendConfig => LegendConfig): ChartSpec[A] =
                spec.copy(legendCfg = f(spec.legendCfg))

            /** Configures the theme using a builder lambda. */
            def theme(f: Theme => Theme): ChartSpec[A] =
                spec.copy(theme = f(spec.theme))

            /** Sets the chart width and height in user units. Disables responsive sizing (last-set wins). */
            def size(w: Int, h: Int): ChartSpec[A] =
                spec.copy(chartSize = (w, h), isResponsive = false)

            /** Overrides the x-axis scale using a builder lambda. */
            def xScale(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
                spec.copy(xScaleOverride = Present(f(ScaleOverride.default)))

            /** Overrides the y-axis scale using a builder lambda. */
            def yScale(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
                spec.copy(yScaleOverride = Present(f(ScaleOverride.default)))

            /** Overrides the right y-axis scale using a builder lambda.
              *
              * Applies only to marks bound to the right axis (`axis = Axis.Right`). The left axis is
              * unaffected; configure it with `.yScale(...)`. Use this when the two axes carry
              * unrelated domains that need independent scale kinds, ranges, or fitting knobs (the
              * canonical dual-axis case, e.g. an absolute count on the left and a log-scaled ratio on
              * the right).
              *
              * The builder receives a fresh `ScaleOverride.default` and returns the configured
              * override: `.yScaleRight(_.log)`, `.yScaleRight(_.linear(0, 1).withClamp(true))`. An
              * unset right override (the default) leaves the right scale inferred from the right-bound
              * marks' data extent, exactly as today.
              *
              * Note: a right axis only exists when the chart has right-bound marks or `.yAxisRight(f)`
              * was called; on a single-axis chart this override is a no-op.
              */
            def yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
                spec.copy(yScaleRightOverride = Present(f(ScaleOverride.default)))

            /** Configures animation using a builder lambda. */
            def animate(f: AnimateConfig => AnimateConfig): ChartSpec[A] =
                spec.copy(animateCfg = f(spec.animateCfg))

            /** Sets the key extractor used for keyed transitions. */
            def key(f: A => String): ChartSpec[A] =
                spec.copy(key = Present(f))

            /** Publishes the hovered datum to `ref` on pointer enter/leave. */
            def onHover(ref: Signal.SignalRef[Maybe[A]]): ChartSpec[A] =
                spec.copy(onHover = Present(ref))

            /** Publishes the selected datum to `ref` on click. */
            def onSelect(ref: Signal.SignalRef[Maybe[A]]): ChartSpec[A] =
                spec.copy(onSelect = Present(ref))

            /** Attaches a default tooltip using `f` to format the hovered datum. */
            def tooltip(f: A => String): ChartSpec[A] =
                spec.copy(tooltip = Present(f))

            /** Configures built-in visual highlight behavior using a builder lambda.
              *
              * Example: `.interaction(_.highlightSelect)` enables the default opacity boost on the selected mark.
              * If no `onHover`/`onSelect` ref is configured, all highlight settings are no-ops (INV-024).
              */
            def interaction(f: ChartSpec.InteractionConfig => ChartSpec.InteractionConfig): ChartSpec[A] =
                spec.copy(interactionCfg = f(spec.interactionCfg))

            /** Sets the accessible title (a `<title>` child); also implies `role="img"` on the root. */
            def title(s: String): ChartSpec[A] =
                spec.copy(a11y = spec.a11y.copy(title = Present(s)))

            /** Sets the accessible long description (a `<desc>` child). */
            def desc(s: String): ChartSpec[A] =
                spec.copy(a11y = spec.a11y.copy(desc = Present(s)))

            /** Sets the `aria-label` on the chart's root `<svg>`. */
            def ariaLabel(s: String): ChartSpec[A] =
                spec.copy(a11y = spec.a11y.copy(ariaLabel = Present(s)))

            /** Makes the chart responsive: the root uses `width="100%"` and a `viewBox`, no fixed height. */
            def responsive: ChartSpec[A] =
                spec.copy(isResponsive = true)

            /** Makes the chart responsive with the given aspect ratio (width / height). */
            def responsive(aspectRatio: Double): ChartSpec[A] =
                spec.copy(isResponsive = true, aspectRatio = Present(aspectRatio))

            /** Sets all four plot margins (top, right, bottom, left) in pixels. */
            def margins(top: Double, right: Double, bottom: Double, left: Double): ChartSpec[A] =
                spec.copy(marginsCfg = ChartSpec.Margins(top, right, bottom, left))

            /** Configures plot margins using a builder lambda: `.margins(_.left(80))`. */
            def margins(f: ChartSpec.Margins => ChartSpec.Margins): ChartSpec[A] =
                spec.copy(marginsCfg = f(spec.marginsCfg))

            /** Lowers this chart spec to an `Svg.Root`.
              *
              * Equivalent to `summon[Conversion[ChartSpec[A], Svg.Root]](spec)`. Provided as an explicit
              * method for callers that do not have `scala.language.implicitConversions` in scope.
              */
            def toSvg: Svg.Root = kyo.internal.ChartLower.lower(spec)

            /** Lowers this chart spec to an `Svg.Root` together with its resolved [[ChartScales]].
              *
              * The returned `ChartScales` exposes the data-to-pixel projection for both axes and the
              * inner plot rectangle, so callers can build overlays, brush outlines, or annotations at
              * exact chart pixel coordinates without re-deriving the scale math. For live charts the
              * scales are computed from the current signal value at call time; they do not update until
              * `toSvgWithScales` is called again.
              */
            def toSvgWithScales: (Svg.Root, ChartScales) =
                kyo.internal.ChartLower.lowerWithScales(spec)

        end extension

        /** Converts a `ChartSpec[A]` to an `Svg.Root` wherever one is expected.
          *
          * Delegates to `ChartLower.lower`. The lowering uses `Frame.internal` for SVG node construction,
          * so the conversion itself requires no frame synthesis at the call site.
          */
        given [A]: Conversion[ChartSpec[A], Svg.Root] = spec => kyo.internal.ChartLower.lower(spec)

        // ---- Charts: config types ----

        /** Configures axis appearance for one axis.
          *
          * Builder methods return a copy with one field changed, so chains compose
          * without mutation. Used as the argument to `.xAxis(f)` / `.yAxis(f)` /
          * `.yAxisRight(f)`: write `_.grid.ticks(5).format(...)`.
          *
          * `axisLabel` is an optional axis label string. `showGrid` enables gridlines
          * across the plot. `tickCount` is the desired number of ticks (a hint, not a
          * hard limit). `tickFormat` overrides the default tick label formatter.
          */
        final case class AxisConfig(
            axisLabel: Maybe[String],
            showGrid: Boolean,
            tickCount: Int,
            tickFormat: Maybe[Double => String],
            tickRotation: Double = 0.0,                 // D17
            tickAnchor: TextAnchor = TextAnchor.Middle, // D17
            reversed: Boolean = false,                  // D20
            padding: Double = 0.0                       // D21
        ):
            def label(s: String): AxisConfig             = copy(axisLabel = Present(s))
            def grid: AxisConfig                         = copy(showGrid = true)
            def ticks(n: Int): AxisConfig                = copy(tickCount = n)
            def format(f: Double => String): AxisConfig  = copy(tickFormat = Present(f))
            def reverse: AxisConfig                      = copy(reversed = true)
            def pad(fraction: Double): AxisConfig        = copy(padding = fraction)
            def rotateTicks(degrees: Double): AxisConfig = copy(tickRotation = degrees)
            def anchor(a: TextAnchor): AxisConfig        = copy(tickAnchor = a)

        end AxisConfig

        object AxisConfig:
            val default: AxisConfig = AxisConfig(Absent, false, 5, Absent)

        /** Configures legend appearance, position, color scale, and interactivity.
          *
          * Builder methods return a copy with one field changed. Used as the argument to
          * `.legend(f)`: write `_.top` or `_.hidden`. The `colorScale` methods attach an
          * explicit color mapping for the mark's color channel.
          *
          * `position` selects where the legend box is placed relative to the plot area.
          * `isHidden` hides the legend entirely. `colorScale` is an optional
          * [[kyo.UI.Ast.LegendConfig.ColorScale]]: either a `Categorical` mapping (built from
          * value-equality pairs or a label function) or a `Sequential` low-to-high gradient over a
          * numeric domain; if `Absent` the default palette is used.
          *
          * `isInteractive` and `hiddenSeries` enable click-to-toggle series visibility: when a
          * `hiddenSeries` ref is attached via `interactive`, clicking a legend swatch toggles that
          * series label in the ref, and the marks lowering filters out the hidden series.
          */
        final case class LegendConfig(
            position: Maybe[LegendPosition],
            isHidden: Boolean,
            colorScale: Maybe[LegendConfig.ColorScale],
            isInteractive: Boolean = false,
            hiddenSeries: Maybe[Signal.SignalRef[Set[String]]] = Absent
        ):
            def top: LegendConfig    = copy(position = Present(LegendPosition.Top))
            def bottom: LegendConfig = copy(position = Present(LegendPosition.Bottom))
            def left: LegendConfig   = copy(position = Present(LegendPosition.Left))
            def right: LegendConfig  = copy(position = Present(LegendPosition.Right))
            def hidden: LegendConfig = copy(isHidden = true)

            /** Enables click-to-toggle series visibility, driven by the supplied `ref`.
              *
              * Clicking a legend swatch toggles that series' label in `ref`: a label not in the set is added
              * (the series is hidden), a label already present is removed (the series is shown again). The marks
              * lowering reads `ref` and drops rows whose color-channel label is in the hidden set, applying the
              * filter before color-splitting so the visible categories keep their stable palette order.
              */
            def interactive(ref: Signal.SignalRef[Set[String]]): LegendConfig =
                copy(isInteractive = true, hiddenSeries = Present(ref))

            /** Attaches a color scale built from value-equality pairs over a typed key `K`.
              *
              * Each pair maps a key value to a color; categories are matched by `==` (value equality), so two
              * enum cases that share a `toString` stay distinct. A category with no matching pair falls back to
              * `Style.Color.blue` (the first default-palette color).
              *
              * The Scala call name is `colorScale[K]`. The JVM bytecode symbol is `colorScaleTyped` (set via
              * `@targetName`) to avoid an erasure conflict with the `String => Style.Color` overload.
              *
              * Example:
              * {{{
              * .legend(_.colorScale[Region](
              *   Region.NA -> Style.Color.blue,
              *   Region.EU -> Style.Color.green
              * ))
              * }}}
              */
            @scala.annotation.targetName("colorScaleTyped")
            def colorScale[K](pairs: (K, Style.Color)*)(using CanEqual[K, K]): LegendConfig =
                val chunk = Chunk.from(pairs)
                copy(colorScale =
                    Present(LegendConfig.ColorScale.Categorical(v =>
                        // Match the raw category value against each pair by value equality. The closure scans a small
                        // pairs collection (typically 2-10 entries) once per category, not a hot membership scan.
                        // Style.Color.blue is the unmatched fallback (the first default-palette color); never null.
                        // Maybe.fromOption at the stdlib collectFirst boundary; getOrElse on Kyo Maybe.
                        Maybe.fromOption(chunk.collectFirst { case (k, c) if k.equals(v) => c }).getOrElse(Style.Color.blue)
                    ))
                )
            end colorScale

            /** Attaches a total color-scale function keyed on the category label string.
              *
              * `f` must be exhaustive over the category labels that the color channel produces. For a `String`-keyed
              * color channel the labels are the raw string values. For an enum color channel the labels are the enum
              * case names (e.g. `"NA"`, `"EU"`, `"APAC"`).
              */
            def colorScale(f: String => Style.Color): LegendConfig =
                copy(colorScale = Present(LegendConfig.ColorScale.Categorical(v => f(v.toString))))

            /** Attaches a sequential low-to-high color gradient over the numeric color-channel domain.
              *
              * Each row's numeric color value is normalized into `[0, 1]` over the data extent and interpolated
              * between `low` (domain minimum) and `high` (domain maximum). The legend shows a continuous gradient
              * swatch; the mark fills are concrete interpolated colors, never `url(#...)` references.
              */
            def colorScaleSequential(low: Style.Color, high: Style.Color): LegendConfig =
                copy(colorScale = Present(LegendConfig.ColorScale.Sequential(low, high, Absent)))

            /** Attaches an explicit sequential color scale, allowing a fixed domain override. */
            def colorScaleSequential(scale: LegendConfig.ColorScale.Sequential): LegendConfig =
                copy(colorScale = Present(scale))

        end LegendConfig

        object LegendConfig:
            val default: LegendConfig = LegendConfig(Absent, false, Absent)

            /** A legend color scale: how raw color-channel values map to swatch and mark colors.
              *
              * `Categorical` carries a total function from a raw value to a color (built from value-equality pairs
              * or a label function); each distinct category gets its own swatch. `Sequential` carries a low-to-high
              * gradient over a numeric domain: values are normalized into `[0, 1]` over the data extent (or the
              * `domain` override when present) and interpolated between `low` and `high`.
              */
            enum ColorScale:
                case Categorical(fn: Any => Style.Color)
                case Sequential(low: Style.Color, high: Style.Color, domain: Maybe[(Double, Double)])
            end ColorScale
        end LegendConfig

        /** Configures chart theme.
          *
          * Selects the overall color scheme (light or dark) and optionally overrides the
          * palette. Builder methods return a copy. Used as the argument to `.theme(f)`:
          * write `_.light` or `_.dark`.
          */
        final case class Theme(
            isDark: Boolean,
            palette: Maybe[Chunk[Style.Color]],
            background: Maybe[Style.Color] = Absent,
            axisColor: Maybe[Style.Color] = Absent,
            gridColor: Maybe[Style.Color] = Absent,
            fontFamily: Maybe[String] = Absent,
            fontSize: Maybe[Double] = Absent
        ):
            def light: Theme = copy(isDark = false)
            def dark: Theme  = copy(isDark = true)

            /** Overrides the plot background fill color. */
            def background(c: Style.Color): Theme = copy(background = Present(c))

            /** Overrides the axis line, tick mark, and tick label color. */
            def axisColor(c: Style.Color): Theme = copy(axisColor = Present(c))

            /** Overrides the gridline color. */
            def gridColor(c: Style.Color): Theme = copy(gridColor = Present(c))

            /** Sets the font family for axis and tick labels. */
            def font(family: String): Theme = copy(fontFamily = Present(family))

            /** Sets the font size (in pixels) for axis and tick labels. */
            def fontSize(px: Double): Theme = copy(fontSize = Present(px))

            /** Sets the categorical palette from a named [[Palette]]. */
            def palette(p: Palette): Theme = copy(palette = Present(Palette.colors(p)))

            /** Sets the categorical palette from an explicit color list. */
            def palette(colors: Chunk[Style.Color]): Theme = copy(palette = Present(colors))

        end Theme

        object Theme:
            val default: Theme = Theme(false, Absent)

        /** Configures animation for live charts.
          *
          * `ease(d)` enables one-shot SMIL transitions with duration `d`. `none` disables all transitions. The easing
          * function is fixed to ease-in-out-cubic (the demo's pattern); named easing variants are additive extensions.
          * `morphSteps` is RESERVED and not consulted by the current declarative-SMIL lowering. The current lowering
          * animates same-structure path morphs (equal command count) with a declarative SMIL `animate` on `d` and
          * snaps structural changes (different command count, i.e. a category added or removed); snapping is the
          * documented v1 limitation. `morphSteps` is the hook for a future effectful chart-mount API.
          * Used as the argument to `.animate(f)`: write `_.ease(300.millis)` or `_.none`.
          */
        final case class AnimateConfig(
            enabled: Boolean,
            duration: Duration,
            morphSteps: Int
        ):
            def ease(d: Duration): AnimateConfig = copy(enabled = true, duration = d)
            def none: AnimateConfig              = copy(enabled = false)

        end AnimateConfig

        object AnimateConfig:
            val default: AnimateConfig =
                AnimateConfig(true, Duration.fromJava(java.time.Duration.ofMillis(300)), morphSteps = 24)

        /** Overrides the automatically-inferred scale for an axis.
          *
          * Builder methods return a copy with the override set. Used as the argument to `.xScale(f)` or `.yScale(f)`:
          * write `_.band`, `_.linear(0, 5000)`, or `_.log`. An unset override (the default) leaves the scale inferred
          * from the accessor's `Plottable` kind and the data extent.
          *
          * `nice`, `clamp`, and `pad` are additive knobs that refine the fitted domain. `nice=true` (default) snaps
          * domain bounds to round values; `clamp=false` (default) allows extrapolation beyond the domain. `pad` widens
          * the domain symmetrically by the given fraction before fitting (INV-007, Q-003).
          */
        final case class ScaleOverride(
            kind: Maybe[ScaleKind],
            nice: Boolean = true,
            clamp: Boolean = false,
            pad: Double = 0.0
        ):
            def band: ScaleOverride                           = copy(kind = Present(ScaleKind.Band))
            def log: ScaleOverride                            = copy(kind = Present(ScaleKind.Log))
            def linear(lo: Double, hi: Double): ScaleOverride = copy(kind = Present(ScaleKind.Linear(lo, hi)))
            def time: ScaleOverride                           = copy(kind = Present(ScaleKind.Time))
            def ordinal: ScaleOverride                        = copy(kind = Present(ScaleKind.Ordinal))
            def point: ScaleOverride                          = copy(kind = Present(ScaleKind.Point))
            def symlog: ScaleOverride                         = copy(kind = Present(ScaleKind.Symlog))
            def withNice(on: Boolean): ScaleOverride          = copy(nice = on)
            def noNice: ScaleOverride                         = copy(nice = false)
            def withClamp(on: Boolean): ScaleOverride         = copy(clamp = on)
            def withPad(fraction: Double): ScaleOverride      = copy(pad = fraction)

        end ScaleOverride

        object ScaleOverride:
            val default: ScaleOverride = ScaleOverride(Absent)

        /** Named color palettes for categorical charts (D26).
          *
          * Pass to `_.theme(_.palette(Palette.Okabe))` to select a categorical color set.
          * `Default` is the built-in palette (unchanged for backward compatibility; not optimized
          * for color-vision deficiency). `Okabe` is the Okabe-Ito 8-color set, the recommended
          * accessible choice for categorical data. `Viridis` is an 8-category perceptually-derived
          * set. `Tableau10` is the Tableau 10 categorical palette. Resolve a palette to its colors
          * with `Palette.colors(p)`.
          */
        enum Palette derives CanEqual:
            case Default
            case Okabe
            case Viridis
            case Tableau10
        end Palette

        object Palette:
            /** Resolve a named palette to its `Chunk[Style.Color]`. */
            def colors(p: Palette): Chunk[Style.Color] = p match
                case Palette.Default => kyo.internal.ChartLower.DefaultPalette
                case Palette.Okabe =>
                    Chunk(
                        Style.Color.rgb(0, 0, 0),
                        Style.Color.rgb(230, 159, 0),
                        Style.Color.rgb(86, 180, 233),
                        Style.Color.rgb(0, 158, 115),
                        Style.Color.rgb(240, 228, 66),
                        Style.Color.rgb(0, 114, 178),
                        Style.Color.rgb(213, 94, 0),
                        Style.Color.rgb(204, 121, 167)
                    )
                case Palette.Viridis =>
                    Chunk(
                        Style.Color.rgb(68, 1, 84),
                        Style.Color.rgb(72, 40, 120),
                        Style.Color.rgb(62, 74, 137),
                        Style.Color.rgb(49, 104, 142),
                        Style.Color.rgb(38, 130, 142),
                        Style.Color.rgb(31, 158, 137),
                        Style.Color.rgb(53, 183, 121),
                        Style.Color.rgb(253, 231, 37)
                    )
                case Palette.Tableau10 =>
                    Chunk(
                        Style.Color.rgb(31, 119, 180),
                        Style.Color.rgb(255, 127, 14),
                        Style.Color.rgb(44, 160, 44),
                        Style.Color.rgb(214, 39, 40),
                        Style.Color.rgb(148, 103, 189),
                        Style.Color.rgb(140, 86, 75),
                        Style.Color.rgb(227, 119, 194),
                        Style.Color.rgb(127, 127, 127),
                        Style.Color.rgb(188, 189, 34),
                        Style.Color.rgb(23, 190, 207)
                    )
        end Palette

        /** Read-only projection of the resolved scale state after lowering a `ChartSpec` (D32).
          *
          * Obtain one via `spec.toSvgWithScales`. The accessors expose the data-to-pixel projection
          * for both axes and the inner plot rectangle, while the internal `Scale`/`Layout` types stay
          * private. Use it as an escape hatch to build overlays, brush outlines, or custom annotations
          * at exact chart pixel coordinates. `x` and `y` are always present; `yRight` is `Present` only
          * for dual-axis charts; `plot` is the inner plot rectangle in pixel coordinates.
          */
        sealed trait ChartScales:
            def x: ChartScales.Axis
            def y: ChartScales.Axis
            def yRight: Maybe[ChartScales.Axis]
            def plot: ChartScales.Rect
        end ChartScales

        object ChartScales:

            /** The inner plot rectangle in pixel coordinates. */
            final case class Rect(x: Double, y: Double, width: Double, height: Double)

            /** A single axis projection: maps domain values to pixels and back. */
            sealed trait Axis:
                /** Map a continuous domain value to its pixel coordinate on this axis. */
                def toPixel(value: Double): Double

                /** Map a category key to its band pixel coordinate, or `Absent` for non-categorical axes / unknown keys. */
                def toPixelCategory(key: String): Maybe[Double]

                /** Invert a pixel coordinate back to a resolved domain value. */
                def invert(pixel: Double): ChartScales.Resolved

                /** The scale family this axis was fitted with. */
                def kind: ScaleKind
            end Axis

            /** The inverse of an axis projection: a continuous value or a category key. */
            enum Resolved derives CanEqual:
                case Continuous(value: Double)
                case Category(key: String)
            end Resolved

            final private case class AxisImpl(scale: kyo.internal.Scale, kind: ScaleKind) extends Axis:
                def toPixel(value: Double): Double =
                    scale.apply(kyo.internal.Domain.Continuous(value))
                def toPixelCategory(key: String): Maybe[Double] =
                    // Only categorical scales project a category key, and only when the key is actually present.
                    scale match
                        case b: kyo.internal.Scale.Band =>
                            if b.keys.contains(key) then Present(scale.apply(kyo.internal.Domain.Category(key))) else Absent
                        case o: kyo.internal.Scale.Ordinal =>
                            if o.keys.contains(key) then Present(scale.apply(kyo.internal.Domain.Category(key))) else Absent
                        case _ => Absent
                    end match
                end toPixelCategory
                def invert(pixel: Double): ChartScales.Resolved =
                    scale.invert(pixel) match
                        case kyo.internal.Domain.Continuous(v) => Resolved.Continuous(v)
                        case kyo.internal.Domain.Category(k)   => Resolved.Category(k)
                        case kyo.internal.Domain.Temporal(ms)  => Resolved.Continuous(ms.toDouble)
            end AxisImpl

            final private case class Impl(x: Axis, y: Axis, yRight: Maybe[Axis], plot: Rect) extends ChartScales

            private def kindOf(scale: kyo.internal.Scale): ScaleKind = scale match
                case _: kyo.internal.Scale.Band    => ScaleKind.Band
                case _: kyo.internal.Scale.Log     => ScaleKind.Log
                case s: kyo.internal.Scale.Linear  => ScaleKind.Linear(s.domainMin, s.domainMax)
                case _: kyo.internal.Scale.Time    => ScaleKind.Time
                case _: kyo.internal.Scale.Ordinal => ScaleKind.Ordinal
                case _: kyo.internal.Scale.Symlog  => ScaleKind.Symlog

            /** Internal factory used by the lowering to project resolved scales into the public surface. */
            private[kyo] def from(
                xs: kyo.internal.Scale,
                ysL: kyo.internal.Scale,
                ysR: Maybe[kyo.internal.Scale],
                plot: Rect
            ): ChartScales =
                Impl(
                    AxisImpl(xs, kindOf(xs)),
                    AxisImpl(ysL, kindOf(ysL)),
                    ysR.map(s => AxisImpl(s, kindOf(s))),
                    plot
                )
        end ChartScales

    end Ast

end UI
