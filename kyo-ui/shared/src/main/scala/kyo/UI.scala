package kyo

import kyo.internal.HtmlRenderer
import kyo.internal.ReactiveUI
import kyo.internal.Reducible
import kyo.internal.UICommands
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
  *   - `runHandlers`: exposes it as a server-push pair (SSR page GET and a WebSocket diff channel).
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
    def blockquote(using Frame): Blockquote       = Blockquote()
    def code(using Frame): Code                   = Code()
    def table(using Frame): Table                 = Table()
    def tbody(using Frame): Tbody                 = Tbody()
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

    /** Inline HTML passthrough: emits `html` verbatim into the rendered output without any escaping.
      *
      * This is the framework's single deliberate escape hatch for trusted HTML content that cannot be expressed through the normal `UI`
      * element API. Use it only for content you control and trust completely: strings from external sources, user input, or any untrusted
      * origin must NOT be passed here as doing so creates an XSS vulnerability. There is no sanitization.
      *
      * Intended for the bounded case of inline HTML snippets (such as `<img>` or `<a><img></a>` nodes found in module README files) that
      * the Markdown transpiler encounters and must pass through verbatim. The article body itself is always a real `UI` subtree; this node
      * appears only at the leaf positions where raw HTML snippets exist in the source.
      *
      * The rendered bytes are byte-identical to `html`. Contrast with `UI.text`, which HTML-escapes its argument:
      * `UI.text("<b>x</b>")` renders `&lt;b&gt;x&lt;/b&gt;`; `UI.rawHtml("<b>x</b>")` renders `<b>x</b>`.
      *
      * @param html
      *   Trusted HTML string to emit verbatim. Must come from a controlled, trusted source.
      * @see
      *   [[kyo.UI.Ast.RawHtml]] for the AST node this factory returns
      * @see
      *   [[kyo.UI.text]] for the safe alternative that HTML-escapes its argument
      */
    def rawHtml(html: String)(using Frame): UI = Ast.RawHtml(html)

    /** Build a `<script type="...">` data island carrying a verbatim JSON body and an optional `id`.
      *
      * A data island is an inert structured-data block read back by a script (the JSON-LD
      * SEO block, the SSG-seeded boot payload), NOT a reactive element. It returns a
      * [[kyo.UI.DataIsland]], which does NOT extend [[kyo.UI]], so the type system rejects
      * placing it as a live tree child: it can be placed ONLY through the [[kyo.UI.PageHead]]
      * `jsonLd` (head) / `dataIslands` (body-end) fields. `scriptType` is
      * `application/ld+json` for the JSON-LD block and `application/json` for a data island;
      * `id` is `Present` for an addressable island (read back by `querySelector("#id")`) and
      * `Absent` for the JSON-LD block.
      *
      * The `json` body is stored verbatim at construction and escaped only at render time
      * (`<` becomes `<`, `>` becomes `>`), so a literal `</script>` substring in
      * any field renders as inert text that cannot close the element. This factory owns the
      * one escape; callers pass trusted JSON and never pre-escape it.
      *
      * @param scriptType
      *   the `type` attribute (`application/ld+json` or `application/json`)
      * @param id
      *   the element `id` (`Present` for an addressable island, `Absent` for the JSON-LD block)
      * @param json
      *   the verbatim JSON body (trusted; escaped at render time)
      * @see
      *   [[kyo.UI.PageHead.jsonLd]], [[kyo.UI.PageHead.dataIslands]] for the fields that place it
      * @see
      *   [[kyo.UI.rawHtml]] for the verbatim-HTML leaf
      */
    def dataIsland(scriptType: String, id: Maybe[String], json: String)(using Frame): UI.DataIsland =
        UI.DataIsland(scriptType, id, json)

    /** A `<script type="..." id="...?">json</script>` document-level payload, built by
      * [[kyo.UI.dataIsland]] and carried on [[kyo.UI.PageHead]] (the `jsonLd` head field and the
      * `dataIslands` body-end field).
      *
      * It does NOT extend [[kyo.UI]]: a data island is a `PageHead` payload, not a renderable UI
      * node. Because it is not a `UI`, the type system rejects placing it as a live tree child,
      * and the renderer never sees it as a tree node; it is rendered only from the `PageHead`
      * fields that carry it, as an inert, position-stable block with no `data-kyo-path`, no
      * attributes, and no event handler. The `json` body is verbatim at construction and escaped
      * at render time. `derives CanEqual` because [[kyo.UI.PageHead]] carries these values and
      * itself `derives CanEqual`.
      */
    final case class DataIsland(scriptType: String, id: Maybe[String], json: String)
        derives CanEqual

    /** Conditional rendering: shows `body` while `condition` is true and an empty node otherwise, re-evaluating when the signal emits. */
    def when[C <: UI](condition: Signal[Boolean])(body: => C)(using Frame): Reactive[C] =
        Reactive[C](condition.map(v => if v then body else UI.empty))

    /** What an already-rendered mounted node shows while it re-mounts (key change): keep the last rendered content visible as a
      * frozen snapshot until the new effect publishes (`KeepLatest`, the default. Note the snapshot is inert: its nested regions
      * and handlers are torn down with the old Scope), or drop back to the placeholder (`Placeholder`: the honest loading window,
      * right for interactive content where a frozen-but-visible form would swallow input).
      */
    enum PendingPolicy derives CanEqual:
        case KeepLatest, Placeholder

    /** How a mounted node's failure reached it, the one thing an [[kyo.UI.MountedOps.onError]] render cannot recover from the
      * `Throwable` alone: `Effect` and `Panic` mean the mount never produced content (a retry, via a key swap, is the sensible
      * offer), `Fork` means the node HAD published content and a background fiber it forked with [[kyo.UI.fork]] died afterwards
      * (what is on screen came from a feed that no longer runs).
      */
    enum MountErrorSource derives CanEqual:
        case Effect, Panic, Fork

    /** A mounted node's failure, with the context the node itself cannot see from the `Throwable`.
      *
      * @param error
      *   the failure. An `Abort` failure that is not a `Throwable` arrives wrapped.
      * @param source
      *   which of the node's failure paths produced it: see [[kyo.UI.MountErrorSource]].
      * @param message
      *   a rendering-ready one-liner (the `Throwable`'s message, else its `toString`), so a custom render does not repeat the
      *   null-check the default rendering does.
      * @param frame
      *   the frame of the [[kyo.UI.mounted]] call, not of the failure: it points at the node that went dark.
      * @param path
      *   the node's render path, the same identity the wire protocol addresses it by.
      * @param key
      *   the node's [[kyo.UI.MountedOps.keyed]] key, if it has one.
      */
    final case class MountError(
        error: Throwable,
        source: MountErrorSource,
        message: String,
        frame: Frame,
        path: Seq[String],
        key: Maybe[Any]
    )

    /** Embeds an effectful component mount in a pure render tree: `effect` runs when the node attaches, inside a node-lifetime
      * `Scope` (torn down on detach), with `Abort[Throwable]` caught into the node's error state. See [[kyo.UI.Ast.Mounted]] for
      * the lifecycle/identity contract and the [[kyo.UI.MountedOps]] extensions (`keyed`, `placeholder`, `onError`,
      * `whilePending`).
      *
      * '''Node-scope supervision.''' The node's error state is not limited to the mount phase: a fiber the effect forks with
      * [[kyo.UI.fork]] (directly, or from inside a fiber it already forked that way) is SUPERVISED. A non-interrupt terminal
      * failure of such a fiber retroactively flips the ALREADY-PUBLISHED node into the same error routing a failed mount takes:
      * node-local `.onError` render, else the default inline error.
      * First failure wins (at most one flip per instance, shared with the effect-failure path); teardown interrupts are exempt;
      * the node `Scope` stays open on a flip (sibling fibers keep running, exactly like a pending-phase failure). Participation
      * is opt-in: a plain `Fiber.init` is scoped to the node like always but its failure stays invisible to the node, which is
      * also the escape hatch (`Fiber.initUnscoped` for fire-and-forget work, `Fiber.init`/`Fiber.use` for a fiber whose failure
      * the effect observes itself). See [[kyo.UI.fork]] for the full contract.
      *
      * Effect typing: `S` must be dischargeable across a fork boundary (the same `Isolate[S, Sync, S]` gate `Fiber.init` uses).
      * Context effects (`Env`, `Local`) derive silently and resolve at run time from the renderer fiber's inherited context
      * (all renderer fibers descend from the `runMount`/`serveSession` caller, so an `Env.runAll` at the root reaches every
      * mount); stateful effects (`Var`, `Emit`) do not derive and must be handled before the node is constructed. The node
      * stores the effect with `S` erased: the `Isolate` evidence is the compile-time gate, not a runtime strategy. An
      * explicitly provided stateful isolate is NOT applied, and such an effect fails loudly at run time (missing handler).
      */
    def mounted[S](effect: UI < (Abort[Throwable] & Async & Scope & S))(using
        frame: Frame,
        isolate: Isolate[S, Sync, S]
    ): Mounted =
        discard(isolate)
        Mounted(
            effect.asInstanceOf[UI < (Abort[Throwable] & Async & Scope)],
            key = Absent,
            placeholderUI = Absent,
            errorRender = Absent,
            pendingPolicy = PendingPolicy.KeepLatest
        )
    end mounted

    /** Forks a supervised background fiber: the `Fiber.init` to use inside a [[kyo.UI.mounted]] effect.
      *
      * A mount effect allocates and returns; anything long-lived it opens (a data feed, a subscription drain, a poll loop) keeps
      * running on the node's `Scope` after the node published its UI. Forked with `Fiber.init`, such a fiber dying is invisible to
      * the node: the effect already succeeded, so the node keeps showing content produced from a feed that no longer runs. Forked
      * with `UI.fork`, a non-interrupt terminal failure (an `Abort` failure or a panic) flips the already-published node into its
      * error rendering, taking the same route a failed mount takes.
      *
      * Supervision rides an inheritable `Local` that the engine installs around the mount effect, so it also reaches `UI.fork`
      * calls made from inside a fiber the effect already forked that way. Engine fibers run outside it, a plain `Fiber.init` does
      * not participate, and outside any mount effect `UI.fork` IS `Fiber.init`.
      *
      * The flip REPLACES the node's content. An app that wants "keep the content, surface the error elsewhere" routes that failure
      * to a value channel (a toast, say) rather than failing the fiber; an `.onError` render may offer retry by swapping the
      * node's key, since a key change mounts a fresh instance.
      */
    def fork[E, A, S, S2](
        using isolate: Isolate[S, Sync, S2]
    )(
        v: => A < (Abort[E] & Async & S)
    )(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[A, reduce.SReduced & S2] < (Sync & S & Scope) =
        ReactiveUI.MountSupervision.local.use {
            case Absent => Fiber.init[E, A, S, S2](v)
            case Present(supervision) =>
                Fiber.init[E, A, S, S2](v).map(fiber => fiber.onComplete(r => supervision.report(r)).andThen(fiber))
        }

    /** Builder-style modifiers for [[kyo.UI.Ast.Mounted]], following the attrs-API idiom. */
    object MountedOps:
        extension (m: Mounted)
            /** Continuity anchor: a mounted node with a key keeps its live instance (Scope, resources, published content)
              * across re-renders of its immediately enclosing reactive region while an equal key is re-emitted; the instance
              * is torn down (closed and awaited) when the key changes or disappears. Any `CanEqual`-comparable value works:
              * the idiomatic key is the component's singleton object (`.keyed(ScoreboardView)`), tupled with value inputs the
              * effect closed over when identity depends on them (`.keyed(EraPanel -> era)`): changing inputs that should
              * UPDATE the live instance belong in `Signal` parameters, not the key; non-Signal inputs the effect captures
              * belong IN the key, or they are silently frozen at mount time.
              */
            def keyed(key: Any): Mounted =
                given Frame = m.frame
                m.copy(key = Present(key))

            /** Content rendered while the effect is pending on FIRST mount (and on re-mount under `PendingPolicy.Placeholder`).
              * Defaults to an empty node.
              */
            def placeholder(ui: UI): Mounted =
                given Frame = m.frame
                m.copy(placeholderUI = Present(ui))

            /** Renders a failure of this node: the effect aborting or panicking, and a [[kyo.UI.fork]] fiber dying after the
              * node already published (see [[kyo.UI.MountError.source]] to tell those apart). Defaults to a minimal inline
              * error node; the failure is additionally logged. The enclosing region stays alive; a subsequent key change
              * re-mounts cleanly.
              */
            def onError(f: MountError => UI): Mounted =
                given Frame = m.frame
                m.copy(errorRender = Present(f))

            /** What a keyed node shows while it re-mounts: see [[kyo.UI.PendingPolicy]]. Default: `KeepLatest`. On FIRST
              * mount the placeholder always wins, so this only takes effect once the node has content to keep.
              */
            def whilePending(p: PendingPolicy): Mounted =
                given Frame = m.frame
                m.copy(pendingPolicy = p)
        end extension
    end MountedOps
    export MountedOps.*

    /** Server-push: returns HTTP handlers (SSR page GET and a WebSocket route) for this UI at the given path. Compose with other handlers
      * via HttpServer.init.
      *
      * The page GET is pure SSR: it evaluates the UI, renders the initial HTML, and returns. It creates no session, forks no fibers, and
      * sets no cookie. Each WebSocket connection owns its own subscription tree for the duration of the connection; per-connection state
      * resets when the client disconnects.
      */
    def runHandlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
        UIServer.handlers(basePath)(ui)

    /** Scrolls the element with `id` into the client viewport, from inside an event handler.
      *
      * The command rides the session the handler runs in: under [[runHandlers]] it travels the same
      * WebSocket as the reactive updates and the connected browser scrolls the element; under a
      * browser mount (`UI.runMount`) it scrolls the local document directly. Outside any session
      * (plain SSR, [[runRender]], render-only tests) there is no client to scroll and the call is a
      * no-op.
      *
      * Fire-and-forget: an `id` with no matching element on the client is silently ignored, with no
      * round trip or retry. The target must therefore already exist on the client when the command
      * arrives; a scroll aimed at an element that only appears in a later re-render is dropped, since
      * the command does not wait for pending updates beyond the frame it lands in.
      */
    def scrollIntoView(id: String)(using Frame): Unit < Async =
        UICommands.scrollIntoView(id)

    /** Read-only stream of the full rendered HTML. Emits whenever any signal changes. First emission is the initial render. Useful for
      * testing, SSR, export, or custom transports.
      */
    def runRender(ui: UI)(using Frame): Stream[String, Async] =
        Stream[String, Async] {
            for initialHtml <- HtmlRenderer.render(ui, Seq.empty)
            yield Emit.valueWith(Chunk(initialHtml)) {
                Channel.use[Unit](256) { channel =>
                    val exchange =
                        new UIExchange:
                            def onChange(path: Seq[String], changedUI: UI)(using Frame): Unit < Async =
                                // runPartial drops only a Closed (the consumer stopped draining); a Panic propagates.
                                Abort.runPartial[Closed](channel.put(())).unit
                    // Scope.run owns the root region Fiber.init: when the stream consumer stops draining,
                    // the consume loop ends, the Scope closes, and the subscription tree cascade-tears-down.
                    Scope.run {
                        for
                            root <- ReactiveUI.normalize(ui, Seq.empty)
                            _    <- ReactiveUI.subscribe(root, exchange)
                        yield Loop.foreach {
                            // runPartial captures only the Closed failure (the channel closed when the consumer stopped
                            // draining / on teardown -> end the stream); a Panic propagates rather than being silently
                            // turned into a clean stream end.
                            Abort.runPartial[Closed](channel.take).map {
                                case Result.Success(_) =>
                                    HtmlRenderer.render(ui, Seq.empty).map { html =>
                                        Emit.valueWith(Chunk(html))(Loop.continue)
                                    }
                                case Result.Failure(_) => Loop.done
                            }
                        }
                        end for
                    }
                }
            }
        }

    /** The base CSS reset kyo-ui relies on for correct layout: `box-sizing: border-box`, the
      * flex-column / flex-row element defaults, `[data-kyo-reactive] { display: contents }`, the
      * list/heading/anchor/table normalizations, and `[hidden] { display: none }`.
      *
      * Every kyo-ui runner injects this automatically (`runMount` via the DOM stylesheet,
      * `runHandlers` and the page runner via the document `<head>`), so a normal app never names
      * it. It is public for one case: a static site that hand-builds its own HTML document and
      * wants the framework reset in its `<head>`. Emit it BEFORE any custom stylesheet so custom
      * rules win at equal specificity (CSS is last-declaration-wins). `UI.runRenderPage` already
      * does this ordering for you; reach for `UI.baseCss` directly only when you assemble the
      * document yourself.
      */
    val baseCss: String = HtmlRenderer.baseCss

    /** Configuration for the `<head>` of a full HTML document produced by [[kyo.UI.runRenderPage]].
      *
      * `title` becomes the `<title>` (attribute-escaped). `meta` is a sequence of
      * `(name, content)` pairs each emitted as `<meta name="..." content="...">` (both escaped),
      * for SEO/OpenGraph/viewport tags; the renderer always prepends `charset=utf-8` and a
      * responsive `viewport` so a minimal `PageHead` is still a valid document. `links` is a
      * sequence of `(rel, href)` pairs each emitted as `<link rel="..." href="...">` (escaped),
      * for canonical, icons, preconnect, and stylesheet links such as web fonts. `css` is extra
      * stylesheet text emitted in a `<style>` block AFTER [[kyo.UI.baseCss]] so site rules win at
      * equal specificity. `moduleScript` is an optional `<script type="module" src="...">` ESModule
      * reference (a custom Scala.js bundle); `Absent` emits no script. `jsonLd` is an optional
      * head-level data island (a `<script type="application/ld+json">` structured-data
      * block) rendered immediately before `</head>`; `Absent` (the default) emits none.
      * `dataIslands` is an ordered list of body-end data islands (`<script
      * type="application/json" id="...">` blocks) rendered immediately before `</body>`;
      * empty (the default) emits none. Both carry [[kyo.UI.DataIsland]] values built by
      * [[kyo.UI.dataIsland]]; the renderer escapes their JSON bodies so a `</script>`
      * substring cannot close the element early.
      */
    final case class PageHead(
        title: String,
        meta: Seq[(String, String)] = Seq.empty,
        links: Seq[(String, String)] = Seq.empty,
        css: String = "",
        moduleScript: Maybe[String] = Absent,
        jsonLd: Maybe[UI.DataIsland] = Absent,
        dataIslands: Seq[UI.DataIsland] = Seq.empty
    ) derives CanEqual

    /** Read-only stream of a COMPLETE HTML document (`<!DOCTYPE html>` ... `</html>`) for static-site
      * generation and SSR. Like [[kyo.UI.runRender]], the first emission is the initial render and each
      * later emission is a full re-render whenever any signal changes; unlike `runRender` (which emits
      * a body FRAGMENT), this wraps the fragment in a document with a configurable head and an
      * optional module `<script>`.
      *
      * The head is built from `head`: charset + viewport are always present, then the `meta`/`links`
      * pairs, then a single `<style>` block containing [[kyo.UI.baseCss]] strictly before `head.css` so
      * custom rules override the framework reset. The body holds the rendered UI fragment; the
      * optional module script is emitted last, before `</html>`, so the page paints before the bundle
      * loads. Take the first emission for a one-shot SSG snapshot.
      *
      * This is the SSG counterpart to [[kyo.UI.runHandlers]] (which serves a server-push page with the
      * SSE client baked in): `runRenderPage` produces a static document that links YOUR bundle, with
      * no kyo-ui server-push client injected.
      */
    def runRenderPage(head: PageHead)(ui: UI)(using Frame): Stream[String, Async] =
        runRender(ui).map(fragment => HtmlRenderer.page(head, fragment))

    /** The base CSS plus a stylesheet, ready to drop into [[kyo.UI.PageHead.css]]: returns
      * `sheet.render` (the stylesheet alone). [[kyo.UI.runRenderPage]] already prepends [[kyo.UI.baseCss]],
      * so pass `sheet.render` as `PageHead.css` directly; this helper is the named, discoverable
      * bridge from a `Stylesheet` value to the page head, parallel to how `baseCss` is the bridge for
      * the reset.
      */
    def stylesheetCss(sheet: Stylesheet)(using Frame): String = sheet.render

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

    /** A typed set of characters a text input accepts, replacing a stringly-typed pattern.
      *
      * `Digits` and `Decimal` name the two numeric constraints, and `Allowed` is the explicit escape for an arbitrary
      * character set. A stringly-typed pattern could not express both: a set spelled `"int"` was indistinguishable
      * from the digits-only keyword, and a typo like `"integer"` silently degraded to "allow i, n, t, e, g and r".
      *
      * The filter is applied in the browser as the reader types, so it is a typing convenience rather than a
      * validation boundary: any client can submit any value, and server-side checks still apply.
      *
      * @see
      *   [[kyo.UI.ConstrainedInput.inputFilter]] for the setter
      */
    enum InputFilter derives CanEqual:
        case Digits                 // "digits"
        case Decimal                // "decimal", digits plus at most one `.` or `,`
        case Allowed(chars: String) // "chars:" ++ chars, explicit escape for an arbitrary set
    end InputFilter

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

            /** Adds a CSS class to this element (emitted as `class="..."`); repeated calls space-join.
              * The class is the stable hook a [[kyo.Stylesheet]] class rule ([[kyo.Selector.cls]])
              * targets, so document-level rules (`@media`, shared component styles, `:hover` against a
              * named class) apply to it. For per-element one-off styling use [[style]] instead; the two
              * coexist on one element.
              */
            def cssClass(name: String): Self = withAttrs(attrs.copy(cssClasses = attrs.cssClasses :+ name))

            /** Declarative ENTER transition (client-local): when a patch inserts this element newly into the DOM (its
              * `data-kyo-path` was not present before, or at initial mount), the client adds the space-separated `classes`
              * after insertion, forces a reflow, then removes them next frame, so a CSS transition runs from the enter-from
              * state the classes describe. An echo re-render of an already-present element does not replay. Emits
              * `data-kyo-enter="..."`.
              */
            def enterTransition(classes: String): Self = withAttrs(attrs.copy(enterTransition = Present(classes)))

            /** Declarative LEAVE transition (client-local): when a Replace/Remove removes this element, the client captures
              * its `getBoundingClientRect` before applying the patch (nothing crosses the wire), then appends a visual GHOST
              * (deep clone, `position:fixed` at the captured rect, `pointer-events:none`) to `document.body` with these
              * classes added next frame; the ghost is removed on `transitionend`/`animationend` or a 1s safety timeout. Only
              * the outermost removed leave-element per patch spawns a ghost. The ghost is visual-only: its identifying
              * `data-kyo-*`/`id` attributes are stripped from the clone. Emits `data-kyo-leave="..."`.
              */
            def leaveTransition(classes: String): Self = withAttrs(attrs.copy(leaveTransition = Present(classes)))

            // Internal JS property setter (used by Checkbox.indeterminate, etc.)
            private[kyo] def jsProp(name: String, value: String): Self =
                withAttrs(attrs.copy(jsProps = attrs.jsProps.updated(name, value)))
        end Element

        // ---- Interactive trait (event handlers + tabIndex) ----

        /** Handler storage erasure: the setter's `Isolate[S, Sync, S]` gate proved `S` fork-safe (context effects
          * only, in practice); storage drops `S` because the Attrs slots are `Any < Async` and the run-time context
          * comes from the event-drain fiber's inherited Context, not from the type. Mirrors [[kyo.UI.mounted]]'s
          * erased storage.
          */
        private def eraseHandler[S](v: Any < (Abort[Throwable] & Async & S)): Any < Async =
            v.asInstanceOf[Any < Async]

        private def eraseHandlerFn[A, S](f: A => Any < (Abort[Throwable] & Async & S)): A => Any < Async =
            f.asInstanceOf[A => Any < Async]

        /** Capability trait for elements that accept event handlers (`onClick`, `onKeyDown`, ...) and `tabIndex`/focus-group settings.
          *
          * Handler effect typing: every setter is effect-polymorphic in `S` behind the same `Isolate[S, Sync, S]` gate as
          * [[kyo.UI.mounted]]. Context effects (`Env`, `Local`) derive silently, stateful effects get the curated compile
          * error. Handlers are stored with `S` erased and resolve their context at run time from the fiber that drains
          * events (the browser backend's event-drain fiber descends from the `runMount` caller, so a root `Env.runAll`
          * reaches every handler). Pure `Any < Async` handlers infer `S = Any` (fully source-compatible). NOTE: in the
          * server-push transport (`runHandlers`), event dispatch runs on kyo-http session fibers, which do not currently
          * inherit the `runHandlers` caller's context (the same known gap as mounted effects there).
          */
        sealed trait Interactive extends Element:
            def tabIndex(v: Int): Self       = withAttrs(attrs.copy(tabIndex = Present(v)))
            def focusTrap(v: Boolean): Self  = withAttrs(attrs.copy(focusTrap = Present(v)))
            def focusGroup(id: String): Self = withAttrs(attrs.copy(focusGroup = Present(id)))

            /** Declarative focus seeding: when a re-render inserts this element newly into the DOM (its `data-kyo-path` was
              * not present before), the client calls `.focus()` on it once; re-rendering an already-visible element (same
              * path) does not re-seed. Seeding runs after focus/caret restore, so opening a panel wins over
              * restore-to-trigger; on initial load any focus-auto element is seeded (like native autofocus). Does not make
              * the element focusable, pair with `tabIndex(-1)` for non-natively-focusable elements. The previously focused
              * element is recorded for [[focusRestore]].
              */
            def focusAuto(v: Boolean): Self = withAttrs(attrs.copy(focusAuto = Present(v)))

            /** Declarative focus return: when this focus-seeded element (see [[focusAuto]]) leaves the document, the client
              * focuses whatever was focused just before seeding (located by `data-kyo-path`, skipped silently if gone). Only
              * meaningful with `focusAuto(true)`; typical use is a modal that returns focus to its trigger on close.
              */
            def focusRestore(v: Boolean): Self = withAttrs(attrs.copy(focusRestore = Present(v)))

            /** Opt-in per-level event consumption: when the dispatch walk (innermost target first, then ancestors) reaches
              * this element AND it declared a handler for the event's type, the event stops here so handlers on elements
              * above do not fire. Only consumes event types this element actually handles; others pass through, and the
              * default (unset/`false`) keeps bubble-through. Applies to bubbling events (click, keydown, keyup, hover,
              * unhover, scroll). Typical use: nested overlays where Escape closes only the innermost layer.
              */
            def stopPropagation(v: Boolean): Self = withAttrs(attrs.copy(stopPropagation = Present(v)))

            /** Runs `action` on click, ignoring the event payload. */
            def onClick[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onClick = Present(eraseHandler(Sync.defer(action)(using frame)))))

            /** Runs `f` on click, receiving the [[kyo.UI.MouseEvent]] payload (target id, modifier chord). */
            def onClick[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onClickEvt = Present(eraseHandlerFn(f))))

            /** Runs `action` only when the click lands on this element itself, not on a descendant; ignores the event payload. */
            def onClickSelf[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onClickSelf = Present(eraseHandler(Sync.defer(action)(using frame)))))

            /** Runs `f` only when the click lands on this element itself, not on a descendant; receives the [[kyo.UI.MouseEvent]] payload. */
            def onClickSelf[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onClickSelfEvt = Present(eraseHandlerFn(f))))

            /** Runs `f` on key-down, receiving the [[kyo.UI.KeyboardEvent]] payload (typed key, modifier chord, target id). */
            def onKeyDown[S](f: KeyboardEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onKeyDown = Present(eraseHandlerFn(f))))
            def onKeyUp[S](f: KeyboardEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onKeyUp = Present(eraseHandlerFn(f))))
            def onFocus[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onFocus = Present(eraseHandler(Sync.defer(action)(using frame)))))
            def onFocus[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onFocusEvt = Present(eraseHandlerFn(f))))
            def onBlur[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onBlur = Present(eraseHandler(Sync.defer(action)(using frame)))))
            def onBlur[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onBlurEvt = Present(eraseHandlerFn(f))))

            /** Runs `action` when the pointer enters this element. */
            def onHover[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onHover = Present(eraseHandler(Sync.defer(action)(using frame)))))

            /** Runs `f` when the pointer enters this element, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onHover[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onHoverEvt = Present(eraseHandlerFn(f))))

            /** Runs `action` when the pointer leaves this element. */
            def onUnhover[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onUnhover = Present(eraseHandler(Sync.defer(action)(using frame)))))

            /** Runs `f` when the pointer leaves this element, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onUnhover[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onUnhoverEvt = Present(eraseHandlerFn(f))))

            /** Runs `action` when the mouse wheel is used over this element. */
            def onScroll[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onScroll = Present(eraseHandler(Sync.defer(action)(using frame)))))

            /** Runs `f` when the mouse wheel is used over this element, receiving the [[kyo.UI.WheelEvent]] payload. */
            def onScroll[S](f: WheelEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Self =
                withAttrs(attrs.copy(onScrollEvt = Present(eraseHandlerFn(f))))

            /** Marks this element (and its subtree) as a keyboard-navigation region whose page-scrolling keys are
              * suppressed. While focus is on this element or a descendant, the client calls `preventDefault()`
              * synchronously for the navigation keys so the browser does not ALSO scroll the page; the keydown is
              * still forwarded, so this element's own `onKeyDown` (e.g. moving an option highlight) runs unchanged.
              *
              * Vertical keys (`ArrowUp`/`ArrowDown`/`PageUp`/`PageDown`) are suppressed unless the focused target
              * consumes them itself (`textarea`, `select`, contenteditable — there the browser default is caret line
              * movement or an option change, not a page scroll). A single-line input stays suppressed for vertical keys:
              * that is the combobox case where `ArrowDown` drives the listbox highlight. Horizontal/edge keys
              * (`ArrowLeft`/`ArrowRight`/`Home`/`End`) are suppressed only when the focused target is NOT a text-editable
              * field, so caret movement inside a filter input keeps working.
              *
              * Declarative by necessity: a kyo-ui handler runs asynchronously (and remotely on the server-push
              * transport), so it cannot decline the browser default in time. Both transports therefore read the emitted
              * `data-kyo-scroll-keys` attribute in the browser before posting the event — the framework's equivalent of
              * the imperative `event.preventDefault()` a component library calls in every arrow-key handler.
              */
            def preventScrollKeys: Self = withAttrs(attrs.copy(dataAttrs = attrs.dataAttrs.updated("kyo-scroll-keys", "1")))
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
          * accepted), and union types `A | B` where both sides have witnesses. A lowest-priority
          * `AsHtmlChild[UI]` given (in `AsHtmlChildLowestPriority`) accepts bare `UI` values as children; it resolves only
          * when no higher-priority given matches, so named `HtmlContent` subtypes, wrappers, and fragments all resolve first.
          * `AsHtmlChild[Svg.Circle]` has no given (SVG primitives do not extend `HtmlContent`), so SVG nodes are rejected.
          * Each child is checked individually at the call site via the `HtmlChildVal`
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

        /** Lowest-priority `AsHtmlChild` given: allows bare `UI` values as children. This is the fallback
          * for callers whose helpers return `UI` (rather than a specific `HtmlContent` subtype); the
          * typed givens in `AsHtmlChild` and `AsHtmlChildLowPriority` take precedence for all named types.
          * Correctness is maintained by `HtmlRenderer`, which handles every `UI` variant at runtime.
          */
        sealed trait AsHtmlChildLowestPriority:
            given AsHtmlChild_UI: AsHtmlChild[UI] = new AsHtmlChild[UI] {}

        /** Lower-priority `AsHtmlChild` givens. Holds the `emptyOr` given for the `A | Fragment[Nothing]` branch union so it does
          * not create an ambiguity with the direct `Fragment` given when the static type is a bare `Fragment[Nothing]`.
          */
        sealed trait AsHtmlChildLowPriority extends AsHtmlChildLowestPriority:
            given emptyOr[A <: UI](using AsHtmlChild[A]): AsHtmlChild[A | Fragment[Nothing]] = new AsHtmlChild[A | Fragment[Nothing]] {}
            // RawHtml extends UI (not HtmlContent); given here at low priority so the
            // HtmlContent-based given in AsHtmlChild takes precedence for HtmlContent subtypes.
            given AsHtmlChild_RawHtml: AsHtmlChild[RawHtml] = new AsHtmlChild[RawHtml] {}
        end AsHtmlChildLowPriority

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

        /** Capability trait for text inputs that accept declarative client-local typing constraints.
          *
          * Every free-text input mixes this in except `NumberInput`. A `number` field constrains its content through
          * `type`, `min`, `max` and `step` instead, its `value` reads as the empty string while the content is not a
          * valid number, and a mask's own literals (`(`, `)`, `-`, space) are never valid there. Leaving the trait off
          * that one element keeps the combination from compiling rather than failing at runtime.
          *
          * Both constraints are enforced in the browser as the reader types, so they are typing conveniences rather
          * than validation boundaries: any client can submit any value, and server-side checks still apply.
          *
          * A composition (an IME, a dead key, mobile autocorrect) is let through and its finished text corrected on
          * `compositionend`, rather than being cancelled mid-composition, which is what cancelling the composition
          * event would do.
          */
        sealed trait ConstrainedInput extends TextInput:
            def inputFilter: Maybe[InputFilter]
            def inputMask: Maybe[String]

            /** Restricts the characters the field accepts, rejecting the rest as they are typed, pasted or dropped.
              *
              * The rejected character never enters the field, so the reader sees no flicker and the caret does not
              * jump, and the server only observes filtered text through the normal input and change events. Emits
              * `data-kyo-filter`, read by a document-level `beforeinput` capture listener.
              *
              * @see
              *   [[kyo.UI.InputFilter]] for the available character sets
              */
            def inputFilter(v: InputFilter): Self

            /** Formats the field progressively against a fixed pattern as the reader types.
              *
              * Tokens are `9` for a digit, `a` for an ASCII letter and `*` for either; every other character is a
              * literal the mask inserts on its own, as in `"(999) 999-9999"`. A backslash escapes the next character,
              * so `"+4\\9 999"` keeps a literal `9` in the country code rather than opening an input position there.
              * Each position accepts only its own character class, backspace steps back over literals, and the caret
              * follows the insertion. Formatting stops at the first position the value cannot fill, so a partial entry
              * renders without trailing literals.
              *
              * The classes are ASCII only: `a` and `*` reject accented and non-Latin letters. The server receives the
              * formatted value through the normal input and change events. Emits `data-kyo-mask`.
              *
              * The mask governs display, not only typing: the initial value and a value bound to a [[kyo.SignalRef]]
              * are formatted as they render, so a masked field never shows an unformatted value whoever set it. A
              * value the mask cannot hold is truncated at its capacity.
              */
            def inputMask(v: String): Self
        end ConstrainedInput

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
            focusAuto: Maybe[Boolean] = Absent,
            focusRestore: Maybe[Boolean] = Absent,
            stopPropagation: Maybe[Boolean] = Absent,
            enterTransition: Maybe[String] = Absent,
            leaveTransition: Maybe[String] = Absent,
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
            cssClasses: Chunk[String] = Chunk.empty,
            role: Maybe[String] = Absent
        )

        // ---- Non-element AST cases ----

        /** A literal text node; a bare `String` child auto-lifts to one. */
        case class Text(value: String)(using val frame: Frame) extends UI with HtmlContent

        /** Verbatim inline HTML passthrough node. The `value` string is emitted byte-for-byte into the rendered output with no HTML
          * escaping. This is the AST counterpart to [[kyo.UI.rawHtml]].
          *
          * Security contract: `value` must come from a trusted, controlled source. There is no sanitization; passing user-supplied or
          * externally-sourced strings here creates an XSS vulnerability. Use [[kyo.UI.Ast.Text]] (which escapes its argument) for any
          * content that is not fully trusted.
          *
          * @param value
          *   Trusted HTML string; rendered verbatim, no escaping applied.
          */
        case class RawHtml(value: String)(using val frame: Frame) extends UI

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

        /** An effectful component mount: a first-class AST node whose content is produced by an effect.
          *
          * The renderer runs `effect` when the node attaches, inside a node-lifetime `Scope` that is torn down when the node
          * detaches; `placeholderUI` renders while the effect is pending (first mount), and a `Failure`/`Panic` of the effect
          * renders through `errorRender` while the enclosing region stays alive. The effect must follow the same prompt-return
          * contract as reactive regions: allocate and fork, never park (the node's Scope owns anything long-lived it forks).
          *
          * Identity is explicit: a node without a key is remounted (Scope closed and awaited, effect re-run) whenever its
          * enclosing reactive region re-renders; a node with [[kyo.UI.MountedOps.keyed]] keeps its live instance (Scope,
          * resources, published content) across re-renders of the immediately enclosing region for as long as a re-render
          * emits a mounted node with an equal key, and is torn down when the key disappears or changes. Extends `HtmlContent`
          * so it is accepted anywhere an HTML child is (the content-model of the effect's RESULT is the caller's contract).
          *
          * Stored effect: `S` from [[kyo.UI.mounted]] is erased here after the `Isolate` gate (see the constructor's note).
          */
        case class Mounted(
            effect: UI < (Abort[Throwable] & Async & Scope),
            key: Maybe[Any],
            placeholderUI: Maybe[UI],
            errorRender: Maybe[UI.MountError => UI],
            pendingPolicy: UI.PendingPolicy
        )(using val frame: Frame) extends UI with HtmlContent

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

        final case class Blockquote(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Blockquote
            def withAttrs(a: Attrs): Blockquote      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Blockquote = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Blockquote

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

        /** Explicit table row group. The HTML parser only synthesizes an implicit `<tbody>` while PARSING row
          * content; rows patched into a live table programmatically (a reactive region between comment markers)
          * become direct `<table>` children, which renders fine but breaks `tbody`-scoped selectors and
          * semantics. Wrap a row region in `UI.tbody` to get a real row group.
          */
        final case class Tbody(attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using val frame: Frame) extends Block
            with Interactive:
            type Self = Tbody
            def withAttrs(a: Attrs): Tbody      = copy(attrs = a)
            def apply(cs: HtmlChildVal*): Tbody = copy(children = children ++ Chunk.from(cs.map(_.value)))
        end Tbody

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
            def onSubmit[S](action: => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Form =
                copy(onSubmit = Present(eraseHandler(Sync.defer(action)(using frame))))

            /** Runs `f` on form submit, receiving the [[kyo.UI.MouseEvent]] payload. */
            def onSubmit[S](f: MouseEvent => Any < (Abort[Throwable] & Async & S))(using Isolate[S, Sync, S]): Form =
                copy(onSubmitEvt = Present(eraseHandlerFn(f)))
        end Form

        final case class Textarea(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Block with Interactive with ConstrainedInput with Void:
            type Self = Textarea
            def withAttrs(a: Attrs): Textarea = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): Textarea                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): Textarea        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): Textarea             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): Textarea               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): Textarea               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): Textarea  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): Textarea = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): Textarea        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): Textarea               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
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
            onChange: Maybe[String => Any < Async] = Absent,
            inputFilter: Maybe[InputFilter] = Absent,
            inputMask: Maybe[String] = Absent
        )

        final case class Input(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = Input
            def withAttrs(a: Attrs): Input = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): Input                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): Input        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): Input             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): Input               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): Input               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): Input  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): Input = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): Input        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): Input               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
        end Input

        final case class PasswordInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = PasswordInput
            def withAttrs(a: Attrs): PasswordInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): PasswordInput            = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): PasswordInput = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): PasswordInput      = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): PasswordInput        = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): PasswordInput        = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): PasswordInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): PasswordInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): PasswordInput        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): PasswordInput               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
        end PasswordInput

        final case class EmailInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = EmailInput
            def withAttrs(a: Attrs): EmailInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): EmailInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): EmailInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): EmailInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): EmailInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): EmailInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): EmailInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): EmailInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): EmailInput        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): EmailInput               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
        end EmailInput

        final case class TelInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = TelInput
            def withAttrs(a: Attrs): TelInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): TelInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): TelInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): TelInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): TelInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): TelInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): TelInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): TelInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): TelInput        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): TelInput               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
        end TelInput

        final case class UrlInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = UrlInput
            def withAttrs(a: Attrs): UrlInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): UrlInput                   = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): UrlInput        = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): UrlInput             = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): UrlInput               = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): UrlInput               = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): UrlInput  = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): UrlInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): UrlInput        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): UrlInput               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
        end UrlInput

        final case class SearchInput(
            attrs: Attrs = Attrs(),
            textInputAttrs: TextInputAttrs = TextInputAttrs()
        )(using val frame: Frame) extends Inline with Interactive with ConstrainedInput with Void:
            type Self = SearchInput
            def withAttrs(a: Attrs): SearchInput = copy(attrs = a)

            def value: Maybe[Bound[String]]            = textInputAttrs.value
            def placeholder: Maybe[String]             = textInputAttrs.placeholder
            def readOnly: Maybe[Boolean]               = textInputAttrs.readOnly
            def disabled: Maybe[Boolean]               = textInputAttrs.disabled
            def onInput: Maybe[String => Any < Async]  = textInputAttrs.onInput
            def onChange: Maybe[String => Any < Async] = textInputAttrs.onChange
            def inputFilter: Maybe[InputFilter]        = textInputAttrs.inputFilter
            def inputMask: Maybe[String]               = textInputAttrs.inputMask

            def value(v: String): SearchInput                  = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Const(v))))
            def value(v: SignalRef[String]): SearchInput       = copy(textInputAttrs = textInputAttrs.copy(value = Present(Bound.Ref(v))))
            def placeholder(v: String): SearchInput            = copy(textInputAttrs = textInputAttrs.copy(placeholder = Present(v)))
            def readOnly(v: Boolean): SearchInput              = copy(textInputAttrs = textInputAttrs.copy(readOnly = Present(v)))
            def disabled(v: Boolean): SearchInput              = copy(textInputAttrs = textInputAttrs.copy(disabled = Present(v)))
            def onInput(f: String => Any < Async): SearchInput = copy(textInputAttrs = textInputAttrs.copy(onInput = Present(f)))
            def onChange(f: String => Any < Async): SearchInput = copy(textInputAttrs = textInputAttrs.copy(onChange = Present(f)))
            def inputFilter(v: InputFilter): SearchInput        = copy(textInputAttrs = textInputAttrs.copy(inputFilter = Present(v)))
            def inputMask(v: String): SearchInput               = copy(textInputAttrs = textInputAttrs.copy(inputMask = Present(v)))
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

    end Ast

end UI
