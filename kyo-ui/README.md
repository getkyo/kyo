# kyo-ui

kyo-ui describes web UIs as pure values that run unchanged in three places: a Scala.js client mounting to the DOM, a server-driven HTML-over-SSE deployment, or an HTML stream for SSR and tests. Signals are first-class values throughout the API, and the API itself is typed tightly enough that broad categories of HTML and state mistakes do not compile.

**A `UI` is a pure value, runnable on any of three targets.** `UI.runMount(ui)` mounts to the DOM on Scala.js. `UI.runHandlers(basePath)(ui)` exposes the same value as an HTTP triple (GET page + POST events + SSE diff stream) for server-driven deployments. `UI.runRender(ui)` returns a `Stream[String, Async]` of full-page HTML for SSR, tests, or custom transports. The same source becomes a Scala.js single-page app or a server-push HTML-over-SSE app; only the runner changes. The same value tree includes a typed SVG layer (the `Svg.*` factories) rendered by the same engine across all three runners.

**A `Signal[A]` is a value of type `A` everywhere kyo-ui takes one.** Setters and child slots that accept `A` equally accept `Signal[A]` (read-only, re-renders on change) or `SignalRef[A]` (read-write, two-way binding). Conditional rendering is `when(cond: Signal[Boolean])(ui)`. There is no wrapper type, no binder operator, no hooks; reactivity is the API, not a layer on top of it.

**Invalid states do not compile.** Container factories restrict their children at the type level (`ul(div(...))` is a compile error). The same content-model typing rejects a bare SVG primitive as an HTML child (`div(Svg.circle(...))` does not compile; only the `<svg>` root, `Svg.svg(...)`, is HTML-embeddable). `Length` variants are method-typed (`.padding` rejects `Auto`, `.gap` rejects `Pct`). Domain enums (`Target`, `Href`, `Keyboard`, `FileAccept`, `Color`) replace stringly-typed attributes. Capability traits gate setters (`.disabled` only on `HasDisabled` elements, `.checked` only on `BooleanInput`). Wrong HTML stops compiling.

**Updates are fine-grained.** No virtual DOM, no component re-execution. The `UI` value is built once; the framework registers a subscription at every point where a signal appears. When a signal emits, only the subtree bound to that signal re-renders, and only the DOM nodes inside that subtree get patched. Granularity is determined at the call site: `div(name: Signal[String])` updates one text node; `when(loggedIn)(bigSubtree)` rebuilds a whole subtree. You pick the boundary.

Event handlers are typed `Any < Async` and can call anything in the kyo ecosystem. The element tree under `UI.Ast.*` is plain case classes; pattern-match on it for tests, transforms, or custom backends. Build a UI with `import kyo.UI.*` and factory functions like `div`, `button`, `input`; chain attribute and event setters; attach children via `.apply(...)`.

<!-- doctest:setup
```scala
import UI.*
import kyo.*
case class Todo(id: String, text: String, done: Boolean)
```
-->

```scala
import UI.*
import kyo.*

val hello: UI = div(h1("Hello"), button("Click").onClick(Console.printLine("clicked")))
```

## Build a UI tree

Every visible element is a factory call on `UI`: `UI.div`, `UI.button`, `UI.h1`, `UI.input`. Each factory returns a typed element value (`Div`, `Button`, `H1`, `Input`) that you chain attribute and event setters on, then call `.apply(children*)` to attach children. The element value is immutable; every setter returns a new instance of the same type.

```scala
import UI.*
import kyo.*

val card: UI =
    div(
        h2("Profile"),
        p("Welcome back."),
        button("Sign out").id("signout")
    ).id("card")
```

Bare strings are valid children: `div("hello")` works because a `String` becomes a text node. A `Signal[String]` becomes a reactive text node. A `Signal[A <: UI]` becomes a reactive subtree. You can mix all three with element values inside `.apply(...)`. `UI.fragment(cs*)` emits a flat sequence of children without a wrapping element, and `UI.empty` is a no-op placeholder.

```scala
import UI.*
import kyo.*

val greeting: UI = p("Hello, ", span("world").id("name"), "!")
```

### Identity, visibility, attributes

Every `Element` carries an `Attrs` record. The chainable setters are:

- `.id(v: String)`: DOM id (also used by `Browser`-style test helpers).
- `.hidden(v: Boolean)`: hide the element. The `Signal[Boolean]` overload exists but lives under "Reactivity" below because it changes the return type.
- `.style(v: Style)` / `.style(f: Style.type => Style)`: attach styling, see [Styles](#styles).
- `.tabIndex(v)`, `.focusTrap(v)`, `.focusGroup(id)`: focus order and grouping for keyboard navigation.

```scala
import UI.*
import kyo.*

val labeled: UI =
    div(
        label.forId("email")("Email"),
        emailInput.id("email").placeholder("you@example.com")
    )
```

### Event handlers

Every element that mixes in `Interactive` (which is every non-`Void` factory plus the input elements) exposes `.onClick`, `.onClickSelf`, `.onKeyDown`, `.onKeyUp`, `.onFocus`, `.onBlur`. Handler bodies are typed `Any < Async`: the framework discards the return value, so you can pass any effectful expression directly without an explicit `.unit` or `Unit` ascription. Handlers can suspend, perform `Sync`, raise via `Abort`, sleep, call kyo-http, anything. The return value is not a communication channel; reach the rest of the app via a `SignalRef` (a writable, observable cell, introduced in [Reactivity](#reactivity) below) or other shared state.

```scala
import UI.*
import kyo.*

val counter: UI < Async =
    for
        clicks <- Signal.initRef(0)
    yield div(
        button("+1").id("inc").onClick(clicks.getAndUpdate(_ + 1)),
        clicks.render(n => span(n.toString).id("count"))
    )
```

`onClickSelf` fires only when the element itself is the click target (a click bubbled up from a child does not trigger it). `onClick` fires on any click within the subtree.

```scala
import UI.*
import kyo.*

div.onClickSelf(Console.printLine("background"))(
    button("foreground")
)
```

> **Note:** `onClick`, `onClickSelf`, `onFocus`, `onBlur`, and `Form.onSubmit` each available as a by-name `=> Any < Async` action or as a typed handler receiving `MouseEvent` or `KeyboardEvent`. `onKeyDown` and `onKeyUp` take a `KeyboardEvent => Any < Async`; the function shape is required because the handler receives the key. Per-input change handlers take `String => Any < Async`, `Boolean => Any < Async`, or `Double => Any < Async`.

### Invalid states do not compile

The most common HTML and state-shape mistakes are caught by the compiler, not at runtime. The clearest case is container children: most containers accept `UI*`, but five narrow it to a typed union so the document structure stays valid. `ul`/`ol` accept only `(Li | Reactive | Foreach[?] | Fragment)*`, `table` only rows, `tr` only cells, `select` only options. Passing a `Div` to `ul` is a compile error.

```scala
import UI.*
import kyo.*

val list: UI = ul(li("Read"), li("Write"), li("Sleep"))
```

Finite value domains are typed the same way, each covered in its own section below: `Length` variants are method-typed per property so `.padding` rejects `Auto` and `.gap` rejects `Pct` ([Lengths and sizing](#lengths-and-sizing)); attribute domains are enums rather than strings ([Domain enums for attributes](#domain-enums-for-attributes)); `Color` is a sealed ADT, never a CSS string ([Styles](#styles)); and capability traits gate setters, so `.checked` exists only on `BooleanInput` and `.disabled` only on `HasDisabled`. The SVG-primitive content-model boundary (a bare `Svg.circle(...)` is not a valid HTML child) is covered in [SVG](#svg).

### Headings, lists, tables, structure

- Headings: `UI.h1`..`UI.h6`.
- Containers: `UI.div`, `UI.p`, `UI.section`, `UI.main`, `UI.header`, `UI.footer`, `UI.nav`, `UI.pre`, `UI.code`, `UI.span`.
- Lists: `UI.ul`, `UI.ol`, `UI.li`.
- Tables: `UI.table`, `UI.tr`, `UI.td`, `UI.th`. `Td` and `Th` carry `.colspan(v)` and `.rowspan(v)` setters (each is clamped to `>= 1`).
- Voids: `UI.hr`, `UI.br`.
- Links: `UI.a` with `.href(v: Href)`, `.href(v, target)`, `.target(v)`. See [Domain enums for attributes](#domain-enums-for-attributes).
- Labels: `UI.label.forId(v)` (alias `.\`for\`(v)` for HTML symmetry).
- Images: `UI.img(src, alt)` with `.src(v: ImgSrc)` and `.alt(v)`.

```scala
import UI.*
import kyo.*

val grid: UI = table(
    tr(th("Name"), th("Email")),
    tr(td("Ada"), td("ada@example.com"))
)
```

## Reactivity

A reactive UI needs values that change over time. kyo-core supplies two primitives for that:

- `Signal[A]` is a **read-only** value-over-time. You can observe its current value, await its next change, transform it with `.map`, or hand it to kyo-ui as a reactive subtree.
- `SignalRef[A]` is a **read-write** cell that IS a `Signal[A]`. You allocate one with `Signal.initRef(initial): SignalRef[A] < Sync`, read with `.get`, write with `.set(v)`, atomically update with `.updateAndGet(f)` / `.getAndUpdate(f)`. Every write notifies observers.

A `SignalRef[A]` widens to `Signal[A]` wherever a read-only signal is expected, so you can hand the same ref to inputs (which need write access) and to reactive subtrees (which only read) in the same UI.

```scala
import UI.*
import kyo.*

val example: UI < Async =
    for count <- Signal.initRef(0)
    // count: SignalRef[Int], both readable as Signal[Int] and writable
    yield div(
        button("+").onClick(count.updateAndGet(_ + 1)),
        count.render(n => span(s"Clicked $n times"))
    )
```

kyo-ui wires `Signal` into the UI tree in two ways: declarative re-render through `Reactive` boundaries (this section), and two-way binding on inputs (see [Inputs and forms](#inputs-and-forms)).

### Signals are first-class across the surface

A `Signal[A]` can drive almost any attribute, child slot, or layout decision. The same primitive that powers a reactive text node also powers conditional rendering, visibility, styling, list iteration, and form bindings. Every integration point reads a `Signal` (read-only) or a `SignalRef` (read-write, for two-way binding); the framework re-renders the affected subtree when the signal emits.

| Where                       | API                                                | Effect                                                |
|-----------------------------|----------------------------------------------------|-------------------------------------------------------|
| Reactive text               | Pass `Signal[String]` directly, or `signal.render(f)`  | Text node replaced on change                      |
| Reactive subtree            | Pass `Signal[A <: UI]` directly, or `signal.render(f)` | Subtree replaced on change                        |
| Conditional rendering       | `when(cond: Signal[Boolean])(ui)`                  | Renders `ui` when true, `empty` when false            |
| Lists                       | `signal: Signal[Chunk[A]]` + `.foreach*`           | Diffed render (positional or keyed)                   |
| Styling                     | `.style(s: Signal[Style])`                         | Re-applies style on change                            |
| Visibility                  | `.hidden(v: Signal[Boolean])`                      | Re-renders subtree with toggled visibility            |
| Disabled state              | `.disabled(v: Signal[Boolean])` on `HasDisabled`   | Disabled state tracks the signal                      |
| Anchor href                 | `.href(v: Signal[Href])`                           | Re-renders anchor when href changes                   |
| Image source                | `.src(v: Signal[ImgSrc])`                          | Re-renders image when source changes                  |
| Option selection            | `Opt.selected(v: Signal[Boolean])`                 | Re-renders option when selection changes              |
| Input value (two-way)       | `.value(ref: SignalRef[String])` etc.              | Framework writes ref BEFORE `onChange`/`onInput` fires|
| Boolean input (two-way)     | `.checked(ref: SignalRef[Boolean])`                | Framework writes ref BEFORE `onChange` fires          |

Note the consequence of the table: `when(loggedIn)(profilePanel)` is the conditional rendering primitive. The condition argument is a `Signal[Boolean]`, the framework subscribes to it, and `profilePanel` appears or disappears as the signal flips. There is no manual subscribe call and no top-level `if/else` wrapper to wire up. The same pattern applies to every entry in the table.

```scala
import UI.*
import kyo.*

val example: UI < Async =
    for
        loggedIn <- Signal.initRef(false)
        username <- Signal.initRef("")
        themed   <- Signal.initRef(Style.empty.color(Style.Color.indigo))
    yield div(
        button("Toggle").onClick(loggedIn.updateAndGet(!_)),
        when(loggedIn)(
            p("Welcome, ", username: Signal[String]).style(themed)
        ),
        input.placeholder("Your name").value(username).hidden(loggedIn)
    )
```

One value (`loggedIn`) drives four things at once: button click writes it, `when` shows or hides a subtree, `input.hidden(...)` flips the input visibility, and the input itself is bound to `username` (two-way) and styled by `themed`. Compose freely; the framework handles the rendering.

### Execution model: fine-grained, no virtual DOM

A reader coming from React or another VDOM library is likely to ask: when a signal updates, what re-runs? The answer is: only the closure attached to that signal's boundary.

kyo-ui builds the `UI` value once. The value is an AST of plain case classes. Wherever a signal appears (as a child, as a setter argument, as a `when` condition), the framework registers a subscription on that signal at construction time, anchored to a path in the AST. When the signal emits, the framework re-evaluates only that boundary's closure (producing a new subtree), then patches the DOM (or pushes a diff over SSE for `runHandlers`) at the corresponding path. Nothing above or beside the boundary is touched.

Contrast with React: a state setter call inside a component triggers re-execution of the component function and propagates down through its descendants, building a new virtual DOM, diffing against the previous one, and applying minimal DOM updates. Components are functions called over and over; expensive subtrees need `React.memo` or `useMemo` to opt out of re-running.

In kyo-ui:

- The function that constructs the `UI` value runs once. There is no component function that re-runs on every state change.
- A `signal.render(f)` boundary is a subscription to the signal at the granularity of `f`. Only `f` re-runs when the signal emits, and the framework patches the DOM only at the path where the boundary was anchored.
- There is no virtual DOM. The boundary holds the previous rendered AST for that subtree, generates a fresh one, and emits a `Replace` diff at its anchor path. The browser-side runtime applies the diff via `outerHTML` (or the server-push transport pushes it over SSE).
- Subscription granularity is determined at the call site. `div(name: Signal[String])` is a fine-grained subscription on one text node. `when(loggedIn)(bigSubtree)` is a coarse subscription that swaps a whole subtree. Both are explicit choices in the code, not framework defaults to argue with.
- There is no `useMemo`, `useCallback`, or `React.memo` equivalent because nothing gets re-executed that you did not opt into. The cost of "rendering" a subtree is the cost of the closure inside its boundary, and you wrote that closure.

### Scalar reactive boundary

`signal.render(f: A => UI)` is the basic boundary. The framework re-runs `f` and replaces the subtree whenever `signal` emits a new value. Same as `UI.Ast.Reactive(signal.map(f))`.

```scala
import UI.*
import kyo.*

val mirror: UI < Async =
    for ref <- Signal.initRef("")
    yield div(
        input.id("src").value(ref).onInput(v => ref.set(v)),
        ref.render(text => p(s"You typed: $text").id("mirror"))
    )
```

You can also skip the `.render` wrap for two common shapes: a `Signal[String]` becomes a reactive text node, and a `Signal[A <: UI]` becomes a reactive subtree. The two examples below produce the same `UI`:

```scala
import UI.*
import kyo.*

val explicit: UI < Async =
    for ref <- Signal.initRef("")
    yield ref.render(s => span(s))

val implicitForm: UI < Async =
    for ref <- Signal.initRef("")
    yield span(ref: Signal[String])
```

> **Note:** Only `Signal[A <: UI]` and `Signal[String]` auto-coerce. A `Signal[Style]` does NOT (it has its own `.style(Signal[Style])` overload). A `Signal[Boolean]` does NOT. Reading any other signal type in a `UI*` position produces a type error.

### Conditional rendering

`UI.when(condition: Signal[Boolean])(ui: => UI)` materializes `ui` only when `condition` emits `true`, and emits `UI.empty` (a `Fragment(Chunk.empty)`) when `false`.

```scala
import UI.*
import kyo.*

val agreedToTerms: UI < Async =
    for agreed <- Signal.initRef(false)
    yield div(
        checkbox.id("agree").checked(agreed),
        when(agreed)(button("Continue").id("go"))
    )
```

> **Caution:** The body of `UI.when` is by-name. Side effects inside it (anything other than building a pure `UI` value) re-run every time the condition transitions from `false` to `true`.

### Reactive collections

A `Signal[Chunk[A]]` is rendered by one of the four `foreach` variants:

- `signal.foreach(render: A => UI)`: render each item, no key.
- `signal.foreachIndexed(render: (Int, A) => UI)`: same, with the row index.
- `signal.foreachKeyed(key: A => String)(render: A => UI)`: keyed; preserves per-row state across reorders.
- `signal.foreachKeyedIndexed(key: A => String)(render: (Int, A) => UI)`: keyed with index.

```scala
import UI.*
import kyo.*

val todoList: UI < Async =
    for items <- Signal.initRef(Chunk(Todo("a", "Buy milk", false), Todo("b", "Walk dog", true)))
    yield ul(
        items.foreachKeyed(_.id)(t =>
            li(
                checkbox.checked(t.done),
                span(t.text)
            )
        )
    )
```

When you have both `foreach` and `foreachKeyed` available, the choice is: use `foreachKeyed` whenever the collection can reorder, can have items inserted in the middle, or contains rows with focus / cursor / scroll state. Plain `foreach` re-renders rows by positional index, so a reorder loses per-row state. The framework does NOT infer keys; you must pass the key function explicitly.

```scala
import UI.*
import kyo.*

val keyedReorderSafe: UI < Async =
    for items <- Signal.initRef(Chunk.empty[Todo])
    yield ul(items.foreachKeyed(_.id)(t => li(input.id(s"row-${t.id}"))))

val unkeyedLosesFocusOnReorder: UI < Async =
    for items <- Signal.initRef(Chunk.empty[Todo])
    yield ul(items.foreach(t => li(input.id(s"row-${t.id}"))))
```

### Signal-typed setters return `UI`, not `Self`

Several attribute setters have a signal overload:

- `.hidden(v: Signal[Boolean])` on every `Element`.
- `.style(v: Signal[Style])` on every `Element`.
- `.disabled(v: Signal[Boolean])` on every `HasDisabled` element.
- `.checked(v: Signal[Boolean])` on every `BooleanInput`.
- `.selected(v: Signal[Boolean])` on `Opt`.
- `.src(v: Signal[ImgSrc])` on `Img`.
- `.href(v: Signal[Href])` on `Anchor`.

Each of these wraps the entire element in a `Reactive` boundary and returns `UI`, NOT `Self`. Once you call `.style(signalStyle)` you lose the element-specific methods that follow: there is no more `.id`, `.onClick`, or `.value` available on the result.

```scala
import UI.*
import kyo.*
import kyo.Style.*

val rigidOrder: UI < Async =
    for theme <- Signal.initRef(Style.bg(Color.slate))
    yield div.id("panel").onClick(Console.printLine("clicked")).style(theme)
```

If you need both a signal-driven attribute AND a chainable element method, set the chainable methods first and the signal-typed setter last. The example above works because `.id` and `.onClick` come before `.style(theme)`.

> **Caution:** `.hidden(Signal[Boolean])` and `.disabled(Signal[Boolean])` re-render the ENTIRE subtree on every toggle, not just the attribute. For a leaf input you want toggled often, prefer two-way binding via a `SignalRef` (see Inputs and forms below), or use a constant `.hidden(true)` / `.disabled(true)` if the value never changes.

### `Bound[A]`: constant-or-signal carrier

When you pass either a constant `String` or a `SignalRef[String]` to `.value`, the framework wraps it in `UI.Bound[String]`: `Const(v)` or `Ref(ref)`. You normally never name this type; it surfaces in AST pattern matching (see [Pattern-matching on UI](#pattern-matching-on-ui-ast-access)).

## Inputs and forms

> **Note:** All input element factories (`UI.input`, `UI.textarea`, `UI.passwordInput`, `UI.emailInput`, `UI.telInput`, `UI.urlInput`, `UI.searchInput`, `UI.numberInput`, `UI.dateInput`, `UI.timeInput`, `UI.colorInput`, `UI.rangeInput`, `UI.fileInput`, `UI.hiddenInput`, `UI.checkbox`, `UI.radio`, `UI.dropdown`) are `Void` elements. They do not accept children; calling `.apply(children*)` on them is a compile error. Configure them through their setter methods only.

Input elements share three capability traits.

`TextInput`: text-shaped fields with a string value, placeholder, and Input + Change handlers. Setters common to all `TextInput` variants: `.value(v: String)`, `.value(ref: SignalRef[String])`, `.placeholder(v: String)`, `.readOnly(v: Boolean)`, `.disabled(v: Boolean)`, `.onInput(f: String => Unit < Async)`, `.onChange(f: String => Unit < Async)`. Implementations: `UI.input`, `UI.passwordInput`, `UI.emailInput`, `UI.telInput`, `UI.urlInput`, `UI.searchInput`, `UI.numberInput`, `UI.textarea`.

`PickerInput`: picker-shaped fields with a string value, disabled, and a Change handler (no placeholder, no onInput). Implementations: `UI.dateInput`, `UI.timeInput`, `UI.colorInput`, `UI.select`, `UI.dropdown(options*)`.

`BooleanInput`: boolean-shaped fields with `.checked`, disabled, and a `Boolean => Unit < Async` Change handler. Implementations: `UI.checkbox`, `UI.radio`.

### Two-way binding via `SignalRef`

The most useful API on every input is `.value(ref: SignalRef[String])` (text and picker) or `.checked(ref: SignalRef[Boolean])` (boolean). Passing a `SignalRef` activates two-way binding: the framework writes `ref.set(newValue)` BEFORE invoking any user-supplied `onInput` or `onChange`. By the time your handler runs, the ref already reflects the new value.

```scala
import UI.*
import kyo.*

val nameField: UI < Async =
    for name <- Signal.initRef("")
    yield div(
        input.id("name").placeholder("Your name").value(name),
        p("Hi, ", name: Signal[String])
    )
```

You did not write `.onInput(v => name.set(v))`. The framework did that for you when it saw a `SignalRef`. The same pattern applies to `.checked(ref)` on `Checkbox` and `Radio`, and to `.value(ref)` on `Select`, `Dropdown`, `DateInput`, `TimeInput`, `ColorInput`, `RangeInput`, and every `TextInput` variant.

When you want to react in addition to the binding, supply `onInput` or `onChange` and assume the ref already holds the new value:

```scala
import UI.*
import kyo.*

val withSideEffect: UI < Async =
    for query <- Signal.initRef("")
    yield input.id("q").value(query).onInput(v => Console.printLine(s"searched: $v"))
```

When to use which setter:

- `.value(v: String)` / `.checked(v: Boolean)`: a constant initial value, no binding. Use when the field is read-only or fully controlled by `onChange`.
- `.value(ref: SignalRef[A])` / `.checked(ref: SignalRef[Boolean])`: two-way binding. Use for any field whose value participates in app state.
- `.checked(sig: Signal[Boolean])` on `BooleanInput`: read-only reactive binding. The element re-renders when `sig` changes; user clicks do NOT mutate `sig` (you have to wire `onChange` yourself). This returns `UI`, not `Self`.

### Signup form: the running example

A signup form with two reactive text fields and a checkbox, validated reactively, with a disabled submit button until terms are accepted:

```scala
import UI.*
import kyo.*

case class SignupForm(name: SignalRef[String], email: SignalRef[String], agreed: SignalRef[Boolean])

val signup: UI < Async =
    for
        name   <- Signal.initRef("")
        email  <- Signal.initRef("")
        agreed <- Signal.initRef(false)
        form  = SignupForm(name, email, agreed)
        valid = form.name.combineLatest(form.email).map { case (n, e) => n.nonEmpty && e.contains("@") }
    yield UI.form.id("signup").onSubmit {
        for
            n <- form.name.get
            e <- form.email.get
            _ <- Console.printLine(s"signup: $n / $e")
        yield ()
    }(
        label.forId("name")("Name"),
        input.id("name").value(form.name),
        label.forId("email")("Email"),
        emailInput.id("email").value(form.email),
        label(
            checkbox.id("terms").checked(form.agreed),
            "I agree to the terms"
        ),
        button("Sign up").id("submit").disabled(valid.map(!_))
    )
```

A few things to notice in this block:

- `Signal.initRef` returns `< Sync`. Allocating three of them happens in a `for` comprehension.
- `valid` is a `Signal[Boolean]` derived from two refs with `combineLatest` and `map`. It is read-only.
- `.disabled(valid.map(!_))` uses the `Signal[Boolean]` overload, so the button is wrapped in a `Reactive`. That is the right behavior here: when validity flips, the button's disabled attribute flips.
- `Form.onSubmit(action)` fires on Enter inside a child input AND on a button click inside the form (no event-type discrimination needed). The action runs with `Async` available.

> **Note:** `Form.onSubmit` is a `Form`-specific extra, not an `Interactive` event. The handler is `=> Unit < Async`, same shape as `onClick`. Buttons inside a `Form` submit it; a `Button` outside a `Form` does not.

### Number, range, file, hidden inputs

`NumberInput` is a `TextInput` plus `.min(v: Double)`, `.max(v: Double)`, `.step(v: Double)` (clamped: any `step <= 0` becomes `1.0`), and `.onChangeNumeric(f: Double => Unit < Async)` for a typed-as-`Double` change handler.

```scala
import UI.*
import kyo.*

val ageField: UI < Async =
    for age <- Signal.initRef("0")
    yield numberInput.id("age").value(age).min(0).max(150).step(1)
```

`RangeInput` is a slider with a `Double` value (NOT string): `.value(v: Double)`, `.value(ref: SignalRef[Double])`, `.min/.max/.step` (same clamping as `NumberInput`), `.onChange(f: Double => Unit < Async)`.

```scala
import UI.*
import kyo.*

val volume: UI < Async =
    for v <- Signal.initRef(0.5)
    yield rangeInput.id("vol").value(v).min(0.0).max(1.0).step(0.05)
```

`FileInput.accept(vs: FileAccept*)` constrains which files the browser offers. `FileAccept` carries enum cases for known media kinds and string escape hatches:

```scala
import UI.*
import kyo.*

val avatarUpload: UI = fileInput.id("avatar").accept(
    FileAccept.Image(ImageExt.Png),
    FileAccept.Image(ImageExt.Jpeg),
    FileAccept.Extension(".heic")
)
```

`HiddenInput` carries no UI but participates in form submission and signal binding:

```scala
import UI.*
import kyo.*

val csrf: UI < Async =
    for token <- Signal.initRef("")
    yield hiddenInput.value(token)
```

### `Select` vs `Dropdown`

Both `Select` (native `<select>`) and `Dropdown` (a custom `<div>`-based overlay) implement `PickerInput`. The API surface is identical: `.value(String)`, `.value(SignalRef[String])`, `.disabled`, `.onChange`. The difference is what they render and how you supply options.

`Select` accepts a `(Opt | Reactive | Foreach[?] | Fragment)*` child list. Each `Opt` is `UI.option.value(v).selected(b)(label)`:

```scala
import UI.*
import kyo.*

val sortBy: UI < Async =
    for choice <- Signal.initRef("date")
    yield select.id("sort").value(choice)(
        option.value("date")("By date"),
        option.value("name")("By name")
    )
```

`Dropdown` takes its options up front at construction as `(String, String)*` pairs, NOT as children:

```scala
import UI.*
import kyo.*

val sortByCustom: UI < Async =
    for choice <- Signal.initRef("date")
    yield dropdown("date" -> "By date", "name" -> "By name").id("sort").value(choice)
```

Use `Select` when you want native browser controls (keyboard navigation, screen-reader semantics) and the default chrome. Use `Dropdown` when you want to style the overlay yourself with `.style(...)`.

### Domain enums for attributes

Several attributes accept typed enums rather than raw strings.

`Href` for `Anchor.href`:

```scala
import UI.*
import kyo.*

val externalLink: UI =
    a("kyo on github").href(Href.External("https", "github.com/getkyo/kyo"))

val internalLink: UI = a("home").href(Href.Path("/"))

val anchor: UI = a("top").href(Href.Fragment("top"))
```

`Target` for the `target` attribute: `Self`, `Blank`, `Parent`, `Top` (mapped to `_self`, `_blank`, `_parent`, `_top` by the renderer).

```scala
import UI.*
import kyo.*

val newTab: UI =
    a("docs").href(Href.Path("/docs"), Target.Blank)
```

`ImgSrc` for `Img.src`:

```scala
import UI.*
import kyo.*

val logo: UI    = img(ImgSrc.Path("/logo.svg"), "Logo")
val dataUri: UI = img(ImgSrc.Data("image/svg+xml", "<svg ... />"), "inline")
```

`ImageExt`: `Png`, `Jpeg`, `Webp`, `Gif`, `Svg`, `Avif`. Used inside `FileAccept.Image(ext)`.

`FileAccept`: `AnyImage`, `AnyVideo`, `AnyAudio`, `Pdf`, `Image(ext)`, plus string escape hatches `Extension(ext: String)` and `MediaType(mime: String)` for arbitrary extensions / MIME types.

`Keyboard` and `KeyboardEvent`: the value handed to `onKeyDown` / `onKeyUp`. `Keyboard` is an enum of named keys (`Enter`, `Tab`, `Escape`, `Space`, arrow keys, function keys) plus `Char(c: scala.Char)` for printable characters and `Unknown(raw: String)` for everything else.

```scala
import UI.*
import kyo.*

val onlyOnEnter: UI < Async =
    for query <- Signal.initRef("")
    yield input.id("q").value(query).onKeyDown {
        case KeyboardEvent(Keyboard.Enter, _, _) => Console.printLine("search!")
        case _                                   => Sync.defer(())
    }
```

`KeyboardEvent.key` is always populated. `KeyboardEvent.modifiers` is a `Modifiers(ctrl, alt, shift, meta)` value; all flags default to `false`. `KeyboardEvent.targetId` is `Absent` when the target element has no id attribute. `Keyboard.fromString` is the parser used by JS event-listener glue: any 1-character string becomes `Char(c)`; anything not in the enumerated set becomes `Unknown(raw)`. `Keyboard.charValue` returns `Present(c.toString)` for `Char` and `Present(" ")` for `Space`, `Absent` otherwise.

## Lengths and sizing

`Length` is a sealed ADT with four variants: `Px(value)`, `Pct(value)`, `Em(value)`, and `Auto`. Every style method that takes a length is typed to a union of variants that makes sense for that property. Passing the wrong variant is a compile error, not a runtime one.

```scala
import UI.*
import kyo.*

val padded: Style  = Style.padding(16.px)  // Px | Pct | Em
val percent: Style = Style.padding(10.pct) // Px | Pct | Em
val emPad: Style   = Style.padding(1.em)   // Px | Pct | Em
// Style.padding(Length.Auto) -- compile error: Auto not in the Px | Pct | Em union
```

The four typical width restrictions:

- `.padding(...)`, `.margin(...)` extras: `Px | Pct | Em`. `.margin(...)` also accepts `Auto` for centering.
- `.gap(v)`, `.fontSize(v)`, `.letterSpacing(v)`: `Px | Em` only (no `Pct`, no `Auto`).
- `.borderWidth(...)`, all `.border*` widths, `.shadow(...)` offsets, `.blur(v)`: `Px` only.
- `.rounded(v)` 1-corner: `Px | Pct`. 4-corner: `Px | Pct` per corner.
- `.width`, `.height`, `.min/maxWidth/Height`, `.translate`: any `Length`, including `Auto`.

### Numeric literals: `.px` vs the implicit

Two coercion paths from numbers to `Length.Px` exist:

- Implicit conversions `Int => Px` and `Double => Px`. These fire when an `Int` or `Double` literal is passed to a parameter declared as `Length.Px`. Example: `Style.width(100)` works because `100: Int` converts to `Length.Px(100)`.
- Extensions `.px`, `.pct`, `.em` on `Int` and `Double`. Use these whenever the parameter type allows a wider union (`Px | Pct | Em`, etc.) and you want to pick a specific variant.

Only the `Px` path is implicit. For `Pct` or `Em` you must use the suffix:

```scala
import UI.*
import kyo.*

val explicit: Style = Style.padding(50.pct)
// Style.padding(50) -- compile error: Int converts only to Px, not to Pct
```

### Resolution helpers

`Length.resolve(length, parentPx): Int` converts any variant to a pixel count. `Auto` fills the parent. `Pct(v)` is `(v * parentPx / 100).toInt`. `Em(v)` is `v.toInt` (one em is one pixel in the resolver; the actual font-size-relative mapping happens later in CSS).

`Length.resolveOrAuto(length, parentPx): Maybe[Int]` is the same except `Auto` returns `Absent`, letting the caller decide what auto means in context.

```scala
import UI.*
import kyo.*

val pixels: Int               = Length.resolve(Length.Pct(50), 800)    // 400
val absentForAuto: Maybe[Int] = Length.resolveOrAuto(Length.Auto, 800) // Absent
```

`Length.zero` is the named `Px(0)` constant.

## Styles

`Style` is an immutable record of style properties (`Span[Style.Prop]`). You build a `Style` value separately from the UI tree and attach it with `.style(...)`. Two styles compose with `++`, and the merge is last-write-wins per property KIND, not per property instance:

```scala
import UI.*
import kyo.*

val s1: Style     = Style.padding(10.px)
val s2: Style     = Style.padding(20.px, 30.px)
val merged: Style = s1 ++ s2
// merged has s2's padding only. s1's padding(10) was dropped because s2 wrote a Padding prop.
```

The "kind" key is the runtime class of the property case. `Padding(t, r, b, l)` and `Padding(v, h, v, h)` are both `Prop.Padding`, so one replaces the other. `BgColor` and `TextColor` are different kinds, so both survive.

Composition is associative on kinds, not on individual setting calls.

`Style.empty` is the identity; `.isEmpty` / `.nonEmpty` report it. Every instance method has a matching `Style.<name>(...)` factory on the companion that builds from `empty`. Both produce the same value:

```scala
import UI.*
import kyo.*
import kyo.Style.*

val a: Style = Style.empty.bg(Color.slate).padding(12.px)
val b: Style = Style.bg(Color.slate).padding(12.px)
// a == b
```

### Attaching a style

```scala
import UI.*
import kyo.*
import kyo.Style.*

val card: UI =
    div(h2("Title"))
        .style(Style.padding(16.px).bg(Color.slate).rounded(8.px))
```

The function-form overload reads cleaner for long chains:

```scala
import UI.*
import kyo.*
import kyo.Style.*

val card2: UI = div(h2("Title")).style { s =>
    s.padding(16.px).bg(Color.slate).rounded(8.px).color(Color.white)
}
```

### Pseudo-states

`.hover(s: Style)`, `.focus(s: Style)`, `.active(s: Style)`, `.disabled(s: Style)` take a NESTED `Style`. They embed the inner style as a pseudo-state property; the inner block applies when the user hovers / focuses / activates / disables the element.

```scala
import UI.*
import kyo.*
import kyo.Style.*

val interactiveButton: Style =
    Style.bg(Color.blue).color(Color.white).padding(8.px, 16.px)
        .hover(Style.bg(Color.indigo))
        .focus(Style.border(2.px, Color.purple))
```

The shorthand form takes a `Style.type => Style`:

```scala
import UI.*
import kyo.*
import kyo.Style.*

val sameButton: Style =
    Style.bg(Color.blue).color(Color.white).padding(8.px, 16.px)
        .hover(_.bg(Color.indigo))
        .focus(_.border(2.px, Color.purple))
```

> **Caution:** `.hover(Color.blue)` does NOT compile. The argument is a `Style`, not a `Color`. Either wrap the color in a `Style` (`.hover(Style.bg(Color.blue))`) or use the functional form (`.hover(_.bg(Color.blue))`).

### Colors

`Color` is a sealed ADT with private constructors. Use the factories or the named constants:

- `Color.hex(s: String): Maybe[Color]`. Validates: accepts 3, 4, 6, or 8 hex digits with or without a leading `#`. Returns `Absent` on any other length or any non-hex character. No exception is thrown.
- `Color.rgb(r, g, b)`: channels clamped to `[0, 255]`.
- `Color.rgba(r, g, b, a)`: channels clamped to `[0, 255]`, alpha clamped to `[0, 1]`.
- Named constants: `Color.white`, `black`, `transparent`, `red`, `orange`, `yellow`, `green`, `blue`, `indigo`, `purple`, `pink`, `gray`, `slate`.

```scala
import UI.*
import kyo.*
import kyo.Style.*

val brand: Color           = Color.hex("#3b82f6").getOrElse(Color.blue)
val translucent: Color     = Color.rgba(0, 0, 0, 0.5)
val rejected: Maybe[Color] = Color.hex("not a color") // Absent
```

### Spacing, layout, sizing

- `.padding(all)`, `.padding(vertical, horizontal)`, `.padding(top, right, bottom, left)`: all variants accept `Px | Pct | Em`.
- `.margin(all)` / `.margin(v, h)` / `.margin(t, r, b, l)`: all variants accept any `Length` including `Auto`.
- `.gap(v: Px | Em)`: flex gap.
- `.row`, `.column`: flex-direction shortcuts.
- `.flexWrap(v: FlexWrap)`, `.align(v: Alignment)`, `.justify(v: Justification)`, `.overflow(v: Overflow)`, `.flexGrow(v)`, `.flexShrink(v)`, `.position(v: Position)`, `.displayNone`.
- `.width(v: Length)`, `.height`, `.minWidth`, `.maxWidth`, `.minHeight`, `.maxHeight`.

```scala
import UI.*
import kyo.*

val flexRow: Style   = Style.row.gap(8.px).align(Style.Alignment.center)
val fullWidth: Style = Style.width(100.pct).maxWidth(960.px)
```

### Typography

- `.fontSize(v: Px | Em)`: silently clamped to a minimum of `Px(1)` or `Em(0.1)`.
- `.fontWeight(v: FontWeight)` with shorthand `.bold`.
- `.fontStyle(v: FontStyle)` with shorthand `.italic`.
- `.fontFamily(v: FontFamily)`: enum (`SansSerif`, `Serif`, `Monospace`, `Cursive`, `Fantasy`, `SystemUi`) or `FontFamily.Custom("Inter")`.
- `.textAlign(v)`, `.textDecoration(v)` with shorthands `.underline` and `.strikethrough`.
- `.lineHeight(v: Double)`: clamped to `>= 0.1`.
- `.letterSpacing(v: Px | Em)`, `.textTransform(v)`, `.textOverflow(v)`, `.textWrap(v)`.

### Borders, corners, shadows

- `.border(width: Px, style: BorderStyle, c: Color)` plus 2-arg shorthand `.border(width, color)` (style defaults to `solid`).
- `.borderTop/Right/Bottom/Left(width: Px, c: Color)`.
- `.borderColor(c)` / `.borderColor(top, right, bottom, left)`.
- `.borderWidth(v: Px)` / `.borderWidth(t, r, b, l: Px)`.
- `.borderStyle(v: BorderStyle)`.
- `.rounded(v: Px | Pct)` (1 corner) or 4-corner overload.
- `.shadow(x: Px, y: Px, blur: Px, spread: Px, c: Color)`.

```scala
import UI.*
import kyo.*
import kyo.Style.*

val pill: Style    = Style.bg(Color.blue).color(Color.white).padding(6.px, 12.px).rounded(999.px)
val outline: Style = Style.border(1.px, Color.slate).rounded(4.px)
```

### Effects, filters, gradients

- `.opacity(v: Double)`: clamped to `[0, 1]`.
- `.cursor(v: Cursor)`: enum includes `defaultCursor`, `pointer`, `text`, `move`, `notAllowed`, `crosshair`, `help`, `wait_` (the underscore is because `wait` is reserved), `grab`, `grabbing`.
- `.translate(x: Px | Pct, y: Px | Pct)`.
- Filters: `.brightness`, `.contrast`, `.grayscale` (clamped `[0, 1]`), `.sepia` (clamped `[0, 1]`), `.invert` (clamped `[0, 1]`), `.saturate`, `.hueRotate`, `.blur(Px)`.
- `.bgGradient(direction: GradientDirection, stop1: (Color, Double), stop2: (Color, Double), stops: (Color, Double)*)`: gradient stop positions are clamped to `[0, 100]`.

> **Note:** Out-of-range numeric inputs (an `opacity(2.0)`, a `grayscale(-1)`, a `lineHeight(0)`, a `fontSize(0.5.em)`) do NOT error. They are silently clamped. Read the per-method clamp in the source if you depend on exact values.

### Introspection: `.find`, `.filter`, `.without`

`Style` is queryable as a record of `Prop` values:

- `.find[A <: Prop]`: first prop of the given subtype, as `Maybe[A]`.
- `.filter(f: Prop => Boolean)`: keep matching props.
- `.without[A <: Prop]`: drop all props of the given subtype.

```scala
import UI.*
import kyo.*
import kyo.Style.*

val base: Style                        = Style.bg(Color.slate).padding(12.px)
val padOnly: Maybe[Style.Prop.Padding] = base.find[Style.Prop.Padding]
val noPadding: Style                   = base.without[Style.Prop.Padding]
```

The `Style.Prop` sum is the full property AST: `BgColor`, `TextColor`, `Padding`, `Width`, `Height`, `BorderWidthProp`, `HoverProp(style: Style)`, etc. You will rarely name these in app code, but they are useful for theme transforms (drop one property kind across an entire merged style, or query whether a hover variant exists).

## SVG

SVG is not a separate document model bolted on the side. Every SVG node is a `UI` element built by a `Svg.*` factory, reusing the same path/event/reactive engine as `div` and `button`. The one boundary that matters is HTML embedding: only `Svg.svg` (the `Root`) is also `HtmlContent`, so it embeds in an HTML container, while bare SVG primitives extend `SvgElement`/`SvgNode` but NOT `HtmlContent`. Reach for the `<svg>` root as the single embed point, then build shapes inside it.

```scala
import UI.*
import kyo.*

val drawing: UI =
    div(
        Svg.svg.width(120).height(120).viewBox(Svg.ViewBox(0, 0, 120, 120))(
            Svg.circle.cx(60).cy(60).r(40)
        )
    )
```

> **Caution:** `div(Svg.svg(...))` compiles; `div(Svg.circle(...))` does NOT. Bare SVG primitives extend `SvgElement`/`SvgNode` but not `HtmlContent`, so the only HTML embed point is the `<svg>` root. Build shapes inside it.

### Structure and grouping

`Svg.g` groups elements (and carries shared `fill`/`stroke`/`transform`); `Svg.defs` holds reusable definitions; `Svg.symbol` defines a template instantiated by `Svg.use`; `Svg.switch` and `Svg.a` (an `SvgAnchor`) round out the structural set. `Svg.use(target)` resolves the target's id automatically, so a symbol with no explicit `.id` still wires up:

```scala
import UI.*
import kyo.*

val reused: UI =
    Svg.svg.width(200).height(100).viewBox(Svg.ViewBox(0, 0, 200, 100))(
        Svg.defs(
            Svg.symbol.id("dot")(Svg.circle.cx(5).cy(5).r(5))
        ),
        Svg.g(
            Svg.use(Svg.symbol.id("dot")),
            Svg.use(Svg.symbol.id("dot")).x(20).y(0)
        )
    )
```

### Shapes and text

The shape factories are `Svg.rect`, `Svg.circle`, `Svg.ellipse`, `Svg.line`, `Svg.polyline`, `Svg.polygon`, and `Svg.path`. The text factories are `Svg.text`, `Svg.tspan`, and `Svg.textPath`; each accepts a plain `String` child. Which setters exist on a given element is gated by SVG capability traits (`HasFill`, `HasStroke`, `HasTransform`, `HasOpacity`, `Positioned`, `Sized`, `HasFilter`, ...): `Svg.line` has no `fill` (it mixes in `HasStroke` but not `HasFill`), and `Svg.rect` carries `x`/`y`/`width`/`height` from `Positioned` and `Sized`.

```scala
import UI.*
import kyo.*

val labeled: UI =
    Svg.svg.width(200).height(60).viewBox(Svg.ViewBox(0, 0, 200, 60))(
        Svg.rect.x(0).y(0).width(200).height(60).fill(Svg.Paint.Color(Style.Color.slate)),
        Svg.text.x(100).y(34)
            .textAnchor(Svg.TextAnchor.Middle)
            .fill(Svg.Paint.Color(Style.Color.white))
            .fontSize(Svg.SvgLength.px(18.0))("centered")
    )
```

### Typed value DSLs, no raw attribute strings

SVG attribute values are typed, never raw strings. A path's `d` is built from a `Svg.PathData` value: start at `PathData.from(x, y)`, then chain `moveTo`, `lineTo`, `cubicTo`, `arcTo`, and `close` (each appends a command). There is no raw `d` string overload. The same applies to `Svg.Points` (point sequences), `Svg.Transform` (translate/rotate/scale/skew/matrix), `Svg.ViewBox`, `Svg.PreserveAspectRatio`, and `Svg.SvgLength` (`px`/`pct`/`em`/`user`).

```scala
import UI.*
import kyo.*

val triangle: UI =
    Svg.svg.width(100).height(100).viewBox(Svg.ViewBox(0, 0, 100, 100))(
        Svg.path
            .d(Svg.PathData.from(10, 90).lineTo(50, 10).lineTo(90, 90).close)
            .fill(Svg.Paint.Color(Style.Color.blue))
            .transform(Svg.Transform.Translate(0, 0))
    )
```

### Constrained enums

Where SVG would otherwise take a magic token, kyo-ui takes a typed enum: `Svg.FillRule`, `Svg.StrokeLinecap`, `Svg.StrokeLinejoin`, `Svg.TextAnchor`, `Svg.DominantBaseline`, `Svg.SpreadMethod`, `Svg.Units`, `Svg.BlendMode`, and more (each renders to its exact SVG token). A misspelled `"middel"` is impossible because `textAnchor` takes `Svg.TextAnchor`, not a `String`:

```scala
import UI.*
import kyo.*

val capped: UI =
    Svg.svg.width(100).height(40).viewBox(Svg.ViewBox(0, 0, 100, 40))(
        Svg.line.x1(10).y1(20).x2(90).y2(20)
            .stroke(Svg.Paint.Color(Style.Color.black))
            .strokeWidth(6.0)
            .strokeLinecap(Svg.StrokeLinecap.Round)
    )
```

### Paint and typed references

A `fill` or `stroke` takes a `Svg.Paint`: `Paint.None`, `Paint.CurrentColor`, `Paint.Color(c)`, or `Paint.Ref(server)`. An ambient `Style.Color => Paint` conversion lets you pass a plain `Style.Color` wherever a `Paint` is expected (with `scala.language.implicitConversions` in scope):

```scala
import UI.*
import kyo.*
import scala.language.implicitConversions

val viaConversion: UI =
    Svg.svg.width(60).height(60).viewBox(Svg.ViewBox(0, 0, 60, 60))(
        Svg.circle.cx(30).cy(30).r(25).fill(Style.Color.green)
    )
```

References are typed handles, never raw `url(#id)` strings. A paint server (`Svg.linearGradient`, `Svg.radialGradient`, `Svg.pattern`, holding `Svg.stop` children) yields a `Paint.Ref` through `.paint`; `Svg.clipPath`, `Svg.mask`, `Svg.marker`, and `Svg.filter` yield `ClipPath.Ref`/`Mask.Ref`/`Marker.Ref`/`Filter.Ref` through `.clipRef`/`.maskRef`/`.markerRef`/`.filterRef`. Define the server once, take its handle, and apply it:

```scala
import UI.*
import kyo.*

val gradientFill: UI =
    val grad = Svg.linearGradient(
        Svg.stop.offset(0.0).stopColor(Style.Color.blue),
        Svg.stop.offset(1.0).stopColor(Style.Color.purple)
    )
    Svg.svg.width(120).height(80).viewBox(Svg.ViewBox(0, 0, 120, 80))(
        Svg.defs(grad),
        Svg.rect.x(0).y(0).width(120).height(80).fill(grad.paint)
    )
end gradientFill
```

> **Note:** SVG definition ids are derived deterministically from the construction-site `Frame` (`kyo-<hex(frame.hashCode)>`), not a global counter or randomness, so the id is stable across all three render targets. The `.paint`/`*Ref` handle and the emitted `id` attribute always agree, so a gradient referenced through `.paint` without an explicit `.id` still emits a matching `id` and never a dangling reference.

### Filters

`Svg.filter` defines a filter region and holds a pipeline of `fe*` primitives: `Svg.feGaussianBlur`, `Svg.feOffset`, `Svg.feBlend`, `Svg.feColorMatrix`, `Svg.feFlood`, `Svg.feComposite`, `Svg.feMerge` / `Svg.feMergeNode`, and more. Each primitive's `in`/`result` names wire the stages together; the consumer references the filter through the typed `Filter.Ref` from `.filterRef`:

```scala
import UI.*
import kyo.*

val blurred: UI =
    val blur = Svg.filter(
        Svg.feGaussianBlur.stdDeviation(2.0)
    )
    Svg.svg.width(80).height(80).viewBox(Svg.ViewBox(0, 0, 80, 80))(
        Svg.defs(blur),
        Svg.circle.cx(40).cy(40).r(30).fill(Svg.Paint.Color(Style.Color.red)).filter(blur.filterRef)
    )
end blurred
```

### SMIL animation

`Svg.animate`, `Svg.animateTransform`, `Svg.animateMotion`, and `Svg.set` are animation leaves placed INSIDE a shape (the `ShapeChild` content model), so the browser drives the tween with no server round-trip:

```scala
import UI.*
import kyo.*

val pulsing: UI =
    Svg.svg.width(80).height(80).viewBox(Svg.ViewBox(0, 0, 80, 80))(
        Svg.circle.cx(40).cy(40).r(20).fill(Svg.Paint.Color(Style.Color.blue))(
            Svg.animate.attributeName("r").from(20.0).to(35.0).dur("1s").repeatCount("indefinite")
        )
    )
```

### Embedding and metadata

`Svg.image(href: UI.ImgSrc)` embeds a raster or vector image into the SVG canvas. The `href` is typed as `UI.ImgSrc` (the same union used by `UI.img`): `ImgSrc.Path` for a relative path, `ImgSrc.Absolute` for a full URL, or `ImgSrc.Data` for an inline base64 data URI.

`Svg.foreignObject` re-enters the HTML content model inside SVG coordinate space. Its children are `HtmlContent` nodes (any `div`, `span`, or other HTML element), letting you position styled HTML fragments at an exact SVG coordinate. It is the only SVG surface that crosses back into `HtmlContent`.

`Svg.title(text)` and `Svg.desc(text)` attach an accessible name and description to the containing SVG element; screen readers surface these as the element's label. `Svg.metadata` holds arbitrary structured metadata (RDF, custom XML) for the SVG document and carries no visual output.

```scala
import UI.*
import kyo.*

val annotated: UI =
    Svg.svg.width(100).height(100).viewBox(Svg.ViewBox(0, 0, 100, 100))(
        Svg.title("Red circle"),
        Svg.desc("A filled red circle centered in the viewport"),
        Svg.circle.cx(50).cy(50).r(40).fill(Svg.Paint.Color(Style.Color.red))
    )
```

### Events on SVG

Because `Svg.Root` and the interactive SVG nodes mix in `Interactive`, the same typed event setters work as on HTML: `.onClick`, `.onHover((e: UI.MouseEvent) => ...)`, and `.onScroll((w: UI.WheelEvent) => ...)`, with the same payloads. Handlers usually live on the enclosing `Svg.g` (SVG hit-tests the topmost element, and dispatch delegates to ancestors), as the Flamegraph demo does:

```scala
import UI.*
import kyo.*

val interactiveCell: UI < Async =
    for hovered <- Signal.initRef(false)
    yield Svg.svg.width(60).height(60).viewBox(Svg.ViewBox(0, 0, 60, 60))(
        Svg.g
            .onClick(Console.printLine("clicked"))
            .onHover((e: UI.MouseEvent) => hovered.set(true))
            .onUnhover(hovered.set(false))(
                Svg.rect.x(5).y(5).width(50).height(50).fill(Svg.Paint.Color(Style.Color.green))
            )
    )
```

### A worked example: a small bar chart

Putting the pieces together, a bar chart is one `Svg.rect` per value (positioned via typed lengths), an axis baseline via `Svg.line`, and centered labels via `Svg.text`. This mirrors the in-repo `demo/BarChart.scala`:

```scala
import UI.*
import kyo.*

val barChart: UI =
    val values = Chunk(("kyo", 61.0), ("cats", 49.0), ("zio", 52.0))
    val maxV   = 64.0
    val baseY  = 110.0
    val bars = values.zipWithIndex.flatMap { case ((label, v), i) =>
        val x = 20.0 + i * 60.0
        val h = (v / maxV) * 90.0
        Chunk(
            Svg.rect.x(x).y(baseY - h).width(40).height(h)
                .fill(Svg.Paint.Color(Style.Color.blue)),
            Svg.text.x(x + 20).y(baseY + 14)
                .textAnchor(Svg.TextAnchor.Middle)
                .fontSize(Svg.SvgLength.px(10.0))(label)
        )
    }
    val axis = Svg.line.x1(15).y1(baseY).x2(205).y2(baseY)
        .stroke(Svg.Paint.Color(Style.Color.gray)).strokeWidth(1.0)
    div(
        Svg.svg.width(220).height(130).viewBox(Svg.ViewBox(0, 0, 220, 130))(
            (axis +: bars)*
        )
    )
end barChart
```

## Running a UI

The same `UI` value plugs into different targets. The runner picks the transport; the UI shape is unchanged.

### `UI.runMount(ui)` (Scala.js)

The typical client app entrypoint. `mount` is a JS-only extension method on `UI.type` requiring `Async & Scope` in the effect row. The lifecycle is `Scope`-bound: closing the scope removes the DOM nodes and detaches all listeners. There is no separate "unmount" call.

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*

object App extends KyoApp:
    run {
        for
            counter <- Signal.initRef(0)
            ui = div(
                button("+1").id("inc").onClick(counter.getAndUpdate(_ + 1)),
                counter.render(n => span(n.toString).id("count"))
            )
            _ <- runMount(ui)
        yield ()
    }
end App
```

The 2-arg overload mounts into a specific container by CSS selector instead of `document.body`:

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*

val ui: UI                          = div(button("+1").id("inc"))
val mountTo: Unit < (Async & Scope) = runMount(ui, "#app")
```

### `UI.runHandlers(basePath)(ui)`

Server-push deployment. Returns `Seq[HttpHandler[?, ?, ?]] < Sync`: three handlers in one sequence, a GET that serves the initial page, a POST that receives client events, and an SSE stream that pushes diff updates. You wire them into `HttpServer.init` alongside your other routes.

```scala
import UI.*
import kyo.*

val server: Unit < (Async & Scope) =
    for
        counter <- Signal.initRef(0)
        page = div(
            button("+1").id("inc").onClick(counter.getAndUpdate(_ + 1)),
            counter.render(n => span(n.toString).id("count"))
        )
        uiHandlers <- runHandlers("/app")(page)
        otherHandlers = Seq.empty[HttpHandler[?, ?, ?]]
        _ <- HttpServer.init((uiHandlers ++ otherHandlers)*)
    yield ()
```

The `ui` parameter is `UI < Async`, so you can build a UI inside a `for` comprehension that allocates state. Each connected client gets its own copy of the UI evaluation (a fresh `for` invocation).

### `UI.runRender(ui)`

`Stream[String, Async]` of full HTML. First emission is the initial render; subsequent emissions are full re-renders on any signal change. Use for SSR, tests, snapshot exports, or a custom transport.

```scala
import UI.*
import kyo.*

val page: UI                         = div(h1("Hello"), p("world"))
val snapshots: Stream[String, Async] = runRender(page)
val firstFrame: String < Async       = snapshots.take(1).run.map(_.headMaybe.getOrElse(""))
```

> **Note:** `UI.runRender` re-emits the WHOLE document on every change, not a diff. The HTTP handlers do diff-pushing; if you want diff semantics in a custom transport, port the logic from `UIServer` and `UIExchange`.

When to use which target. `UI.runMount` is the right answer for any Scala.js client. `UI.runHandlers` is the right answer when you want server-rendered + server-driven (the server holds the state, the client is a thin presenter over SSE). `UI.runRender` is for everything else: a test that asserts on HTML, an SSR pre-render, a custom WebSocket transport you want to write yourself.

## Window and routing (Scala.js)

`UIWindow` and `UILocation` are JS-only namespaces useful inside `UI.runMount` apps. Both expose reactive `Signal`s for browser state plus thin wrappers over the underlying browser APIs, so a Scala.js component reads window size or the current path the same way it reads any other signal.

### `UIWindow`: viewport size, page visibility, document keys

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*

val responsiveLayout: UI < Async =
    UIWindow.size.render { case (w, h) =>
        if w < 600 then div.text(s"mobile $w x $h")
        else div.text(s"desktop $w x $h")
    }
```

`UIWindow.size: Signal[(Int, Int)]` updates on `resize`. `UIWindow.visibility: Signal[Boolean]` is `!document.hidden`, updates on `visibilitychange`. Both install a single listener the first time the signal is read.

Document-level key handlers attach for the lifetime of the enclosing scope; closing the scope removes the listener.

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*

val keyboardShortcuts: Unit < (Async & Scope) =
    UIWindow.onKeyDown { ke =>
        ke.key match
            case Keyboard.Escape => closeDialog
            case _               => Sync.defer(())
    }
```

The handler closure receives a `UI.KeyboardEvent` (key, modifiers, targetId), matching the typed payload from in-element `onKeyDown` handlers.

### `UILocation`: client-side routing

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*

val router: UI < Async =
    UILocation.current.render { path =>
        path match
            case "/"          => homePage
            case "/about"     => aboutPage
            case s"/user/$id" => userPage(id)
            case _            => notFoundPage
    }
```

`UILocation.current: Signal[String]` is `pathname + search`. It updates on `push`, `replace`, the browser back/forward buttons, and intercepted anchor clicks.

```scala doctest:platform=js expect=skipped
import kyo.*

val navigate: Unit < Sync = UILocation.push("/about?ref=home")
val back: Unit < Sync     = UILocation.back
val jumpBack: Unit < Sync = UILocation.go(-2)
```

A document-level click interceptor installed at first use rewrites plain anchor clicks into `pushState`, so `a(href := "/foo")` participates in routing without explicit `onClick` wiring. Modifier-key clicks (ctrl/cmd/shift/alt), middle clicks, and `target="_blank"` anchors are passed through to the browser unchanged so new-tab/window/save-as still work natively.

```scala
import UI.*
import kyo.*

val navBar: UI =
    nav(
        a.href(Href.Path("/"))("home"),
        a.href(Href.Path("/about"))("about"),
        a.href(Href.External("https", "example.com"), Target.Blank)("external") // not intercepted
    )
```

## Pattern-matching on UI (AST access)

Every element factory returns an `UI.Ast.*` case class. `UI.Ast.Element` is the sealed base trait; the case classes are `Div`, `P`, `Section`, `Main`, `Header`, `Footer`, `Pre`, `Code`, `Ul`, `Ol`, `Table`, `H1`..`H6`, `SpanElement`, `Nav`, `Li`, `Tr`, `Form`, `Textarea`, `Select`, `Hr`, `Br`, `Td`, `Th`, `Label`, `Opt`, `Button`, `Checkbox`, `Radio`, `Input`, `PasswordInput`, `EmailInput`, `TelInput`, `UrlInput`, `SearchInput`, `NumberInput`, `DateInput`, `TimeInput`, `ColorInput`, `RangeInput`, `FileInput`, `HiddenInput`, `Anchor`, `Img`, `Dropdown`. The non-element AST cases are `Text(value)`, `Reactive(signal)`, `Foreach[A](signal, key, render)`, `Fragment(children)`.

Capability traits surface here too: `Interactive`, `Block`, `Inline`, `Void`, `Focusable`, `HasDisabled`, `TextInput`, `PickerInput`, `BooleanInput`, `Activatable`, `Clickable`. They let you pattern-match on capability rather than a specific element class.

Typical app code never names `UI.Ast`. The AST is here for two consumers: tests that assert on tree shape, and custom backends that walk the rendered tree (the JS DOM backend in `kyo.internal.DomBackend` and the server-push backend in `kyo.internal.UIServer` are the two we ship).

```scala
import UI.*
import kyo.*

val captured: UI = div(button("Click").id("b"))

val isDiv: Boolean = captured match
    case _: Ast.Div => true
    case _          => false

val buttonId: Maybe[String] = captured match
    case Ast.Div(_, children) =>
        children.collectFirst { case b: Ast.Button => b.attrs.identifier }.getOrElse(Absent)
    case _ => Absent
```

For reactive nodes:

```scala
import UI.*
import kyo.*

val tree: UI < Async =
    for ref <- Signal.initRef("")
    yield input.id("q").value(ref)

val isInputWithRef: Boolean < Async =
    tree.map {
        case i: Ast.Input =>
            i.value match
                case Present(Bound.Ref(_))   => true
                case Present(Bound.Const(_)) => false
                case Absent                  => false
        case _ => false
    }
```

`Foreach[A]` carries an existential `A`. To re-introduce the type parameter inside a custom backend, use `applyTyped` (the source documents it as the audited single-cast escape hatch).

## Putting it together: a todo list

A complete client-side todo list demonstrating reactive state, keyed list rendering, conditional empty-state, per-row interaction, and styles:

```scala doctest:platform=js expect=skipped
import UI.*
import kyo.*
import kyo.Style.*

case class Todo(id: String, text: String, done: Boolean)

val todoApp: UI < (Async & Scope) =
    for
        todos <- Signal.initRef(Chunk.empty[Todo])
        draft <- Signal.initRef("")
        addTodo = draft.get.map { text =>
            if text.isEmpty then ()
            else Random.uuid.map(id => todos.updateAndGet(_ :+ Todo(id, text, false))).andThen(draft.set(""))
        }
        toggle = (id: String) => todos.updateAndGet(_.map(t => if t.id == id then t.copy(done = !t.done) else t))
        remove = (id: String) => todos.updateAndGet(_.filterNot(_.id == id))

        row = (t: Todo) =>
            li.style(Style.row.gap(8.px).align(Style.Alignment.center))(
                checkbox.id(s"chk-${t.id}").checked(t.done).onChange(_ => toggle(t.id)),
                span(t.text).style(
                    if t.done then Style.color(Color.gray).strikethrough else Style.empty
                ),
                button("x").id(s"rm-${t.id}").onClick(remove(t.id))
            )

        ui = main.style(Style.padding(24.px).width(480.px))(
            h1("Todos"),
            form.id("new").onSubmit(addTodo)(
                input.id("draft").placeholder("What needs doing?").value(draft),
                button("Add").id("add")
            ),
            when(todos.map(_.isEmpty))(
                p("Nothing yet.").style(Style.color(Color.slate).italic)
            ),
            ul(todos.foreachKeyed(_.id)(row))
        )
        _ <- runMount(ui)
    yield ()
```

Why every piece is there:

- `Signal.initRef(Chunk.empty[Todo])` holds the list; `Signal.initRef("")` holds the in-progress text. Both are `< Sync` so the `for` allocates them.
- `addTodo` reads `draft`, appends, clears. It runs inside `Form.onSubmit`, which fires on both Enter and the Add button click.
- `todos.foreachKeyed(_.id)` uses the row id as a key, so toggling done state on one row does not lose focus on another. If we used `foreach` (no key) and an item moved, the row inputs would re-render and lose focus.
- `UI.when(todos.map(_.isEmpty))` is the empty-state placeholder. The mapped `Signal[Boolean]` flips when the list goes empty / non-empty.
- `.checked(t.done)` uses the constant `Boolean` overload, NOT a ref. The row's checked state is derived from the parent list, and we update via `onChange(_ => toggle(t.id))` which writes back to `todos`. Each row re-renders on list change, so the checkbox stays in sync.
- `.style(if t.done then Style.color(Color.gray).strikethrough else Style.empty)`: per-row conditional style, evaluated each time the row is rendered (which happens whenever `todos` changes).
- `UI.runMount(ui)` runs the whole thing in `Async & Scope`. Closing the scope tears down listeners and removes nodes.

This is the same value you would pass to `UI.runHandlers("/todos")(todoApp.map(_ => initialUiValue))` or `UI.runRender(initialUiValue)` to drive an SSE-backed or stream-backed deployment. Swap the runner; keep the UI.

## Demos

Demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo) and cover all three runners. Run any with `sbt 'kyo-ui/Test/runMain demo.<Name>'`; the server-push demos print a `localhost` URL to open.

- [**Kanban**](shared/src/test/scala/demo/Kanban.scala): Trello-style board over server-push: add, move, and delete cards across columns.
- [**Signup**](shared/src/test/scala/demo/Signup.scala): registration form with live reactive validation, inline errors, and a submit gated until valid.
- [**Dashboard**](shared/src/test/scala/demo/Dashboard.scala): live metrics pushed over SSE from a background fiber, with no client code.
- [**Search**](shared/src/test/scala/demo/Search.scala): live Wikipedia search via `HttpClient`, with loading and error states.
- [**Cart**](shared/src/test/scala/demo/Cart.scala): shopping cart with quantity steppers and a derived running total.
- [**Playground**](shared/src/test/scala/demo/Playground.scala): HTML playground: a textarea feeds a live `iframe` preview.
- [**Router**](shared/src/test/scala/demo/Router.scala): signal-routed multi-view SPA, including a parameterized `/users/:id` route.
- [**HtmlSnapshot**](shared/src/test/scala/demo/HtmlSnapshot.scala): server-side render via `UI.runRender`; prints the HTML, no browser.
- [**Flamegraph**](shared/src/test/scala/demo/Flamegraph.scala): interactive [SVG](#svg) flamegraph of a real kyo-http profile, with click-to-zoom, hover-highlight, and wheel-zoom. Reads its profile from the test resources via `kyo.Path`.
- [**BarChart**](shared/src/test/scala/demo/BarChart.scala): animated [SVG](#svg) bar chart with a SMIL grow-in and a signal-driven refresh tween.
- [**LineChart**](shared/src/test/scala/demo/LineChart.scala): animated [SVG](#svg) line chart with a stroke-dashoffset draw-in, point markers, and an area fill.
