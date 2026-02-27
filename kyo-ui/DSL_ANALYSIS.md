# Kyo UI DSL — Proposed Improvements (v2)

## 1. `apply()` should append children, not replace

Currently every element's `apply` method does `copy(children = Chunk.from(cs))`. Calling `apply` twice silently discards the first set of children:

```scala
div("a")("b")
// Step 1: Div(children = Chunk("a"))
// Step 2: Div(children = Chunk("b"))  — "a" is gone
```

The compiler gives no warning. This is silent data loss.

**Change**: Make `apply` append:

```scala
// Before:
def apply(cs: UI*): Div = copy(children = Chunk.from(cs))

// After:
def apply(cs: UI*): Div = copy(children = children ++ Chunk.from(cs))
```

After this change:
```scala
div("a")("b")       // Chunk("a", "b")
div("a", "b")       // Chunk("a", "b")  — same result
div("a")("b")("c")  // Chunk("a", "b", "c")
```

---

## 2. `ForeachIndexed` should use `Chunk` instead of `List`

The entire kyo-ui AST uses `Chunk[UI]` for children, but reactive list rendering uses `Signal[List[A]]`:

```scala
// Current:
extension [A](signal: Signal[List[A]])
    def foreach(render: A => UI): ForeachIndexed[A]

case class ForeachIndexed[A](signal: Signal[List[A]], render: (Int, A) => UI)
```

Users working with `Signal[Chunk[A]]` (natural in kyo) must convert:

```scala
items.map(_.toList).foreach(i => li(i))  // wasteful conversion
```

**Change**: Use `Chunk[A]` throughout:

```scala
extension [A](signal: Signal[Chunk[A]])
    def foreach(render: A => UI): ForeachIndexed[A]
    def foreachIndexed(render: (Int, A) => UI): ForeachIndexed[A]

case class ForeachIndexed[A](signal: Signal[Chunk[A]], render: (Int, A) => UI)
```

---

## 3. Fix `main` element conflict with `KyoApp.main` via `export`

When extending `KyoApp`, the inherited `main(args: Array[String])` method shadows `import UI.*`'s `val main`:

```scala
object MyApp extends KyoApp:
    import kyo.UI.*
    run {
        backend.render(
            div(
                header("Title"),
                main("Content"),     // resolves to KyoApp.main, not UI.main
                footer("Footer")
            )
        )
    }
```

`import` doesn't help because Scala's name resolution gives inherited members priority over imports. However, `export` creates member definitions that do take priority over inherited methods with different signatures. Verified experimentally:

```scala
// This works — export creates a member that shadows the inherited main method:
object MyApp extends KyoApp:
    export UI.*     // NOT import — export
    run {
        backend.render(
            div(
                header("Title"),
                main("Content"),   // resolves to UI.main ✓
                footer("Footer")
            )
        )
    }
```

The JVM still finds the inherited `main(Array[String])` as the entry point because it exists at the bytecode level — `export` doesn't remove it.

**Change**: Provide a `UIScope` trait that users mix in:

```scala
// In kyo-ui:
trait UIScope:
    export UI.*

// Usage — no import needed, main works:
object MyApp extends KyoApp with UIScope:
    run {
        backend.render(
            div(
                header("Title"),
                main("Content"),   // resolves to UI.main ✓
                footer("Footer")
            )
        )
    }
```

This keeps `val main: Main` as-is — no renaming needed. The fix is in how the DSL enters scope. `export` is the right mechanism because it creates true member definitions that participate in overload resolution alongside inherited members, rather than imports which are always shadowed by inherited names.

---

## 4. Rename `label.\`for\`` to a single word

`for` is a Scala keyword, requiring backticks:

```scala
label.`for`("email")("Email")
```

The internal field is already called `forId`. Expose it directly:

```scala
label.forId("email")("Email")
```

Single word, self-descriptive (it's the ID of the target element), and matches the internal representation.

---

## 5. Conditional rendering with `UI.when`

A common UI pattern: show or hide a subtree based on a boolean signal. Currently there's no direct way to express this. Users must manually map a signal to either content or empty:

```scala
// Verbose — intent not clear from reading the code:
val errorPanel: Signal[UI] = hasError.map(err =>
    if err then div.cls("error")("Something went wrong")
    else UI.empty
)
div(
    h1("Dashboard"),
    errorPanel  // implicit conversion to ReactiveNode
)
```

The problem is readability: you have to create a named signal, map it, handle both branches, and know about the implicit `Signal[UI] → ReactiveNode` conversion. The intent ("show this div when there's an error") is buried in boilerplate.

**Change** — add `UI.when`:

```scala
// In UI object:
def when(condition: Signal[Boolean])(ui: => UI): UI =
    AST.ReactiveNode(condition.map(v => if v then ui else UI.empty))
```

Usage:
```scala
div(
    h1("Dashboard"),
    UI.when(hasError)(
        div.cls("error")("Something went wrong")
    )
)
```

The `=> UI` (by-name parameter) ensures the subtree is only evaluated when the condition is true. Under the hood, `ReactiveNode` tears down the old subtree and builds the new one on each toggle. When toggling to `false`, the content is removed from the DOM (not just hidden).

---

## 6. Type-level CSS class tracking using Record

Currently `.cls()` takes a plain string. There's no compile-time knowledge of which classes are applied. Using kyo's `Record` type with `~` bindings, we could track CSS classes at the type level:

```scala
// Instead of stringly-typed classes:
div.cls("card active")("Content")

// Type-level tracking:
div.cls("card").cls("active")("Content")
// Type: Div & HasClass["card"] & HasClass["active"]
```

This enables:
- **Compile-time conditional classes**: `.clsWhen("active", isSelected)` returns a type that includes `HasClass["active"]` when the signal is true
- **Duplicate detection**: Adding `.cls("card").cls("card")` could warn at compile time
- **CSS-in-Scala validation**: A macro could check that referenced class names exist in a stylesheet

**Sketch using Record's `~` pattern:**

```scala
// Phantom type for class tracking:
final infix class HasClass[Name <: String] private ()

// Element tracks its classes in its type:
sealed abstract class Element extends UI:
    type Self <: Element
    type Classes  // intersection of HasClass[name] types

    def cls(name: String): Self  // adds HasClass[name] to Classes type
    def clsWhen(name: String, condition: Signal[Boolean]): Self
```

However, this is a significant type-level machinery addition. A simpler first step that still improves over plain strings:

```scala
// CommonAttrs tracks classes as a structured collection:
final private[kyo] case class CommonAttrs(
    classes: Chunk[(String, Maybe[Signal[Boolean]])] = Chunk.empty,
    // ... rest unchanged
)

// In Element:
def cls(name: String): Self  // static class, always present
def clsWhen(name: String, condition: Signal[Boolean]): Self  // conditional

// In DomBackend — efficient per-class toggling:
classes.foreach { (name, maybeSig) =>
    maybeSig match
        case Absent      => el.classList.add(name)
        case Present(sig) => subscribe(sig)(v => el.classList.toggle(name, v))
}
```

Usage:
```scala
div.cls("card")
   .clsWhen("active", isSelected)
   .clsWhen("error", hasValidationError)
   .clsWhen("loading", isLoading)(
       p("Content")
   )
```

**Recommendation**: Start with the structured `Chunk[(String, Maybe[Signal[Boolean]])]` approach. It solves the ergonomic problem immediately. Type-level tracking with `Record`-style `~` types can be added later as an enhancement.

---

## 7. Event handler errors via `Abort` in the render effect type

Currently `runHandler` swallows all errors silently:

```scala
private def runHandler(action: Unit < Async)(using Frame): Unit =
    val _ = Fiber.initUnscoped(action)
```

Instead of a callback, errors should flow through kyo's effect system. Change `UIBackend.render` to include `Abort[Throwable]` in its return type:

```scala
// Before:
abstract class UIBackend:
    def render(ui: UI)(using Frame): Unit < (Async & Scope)

// After:
abstract class UIBackend:
    def render(ui: UI)(using Frame): Unit < (Async & Scope & Abort[Throwable])
```

In `DomBackend`, when an event handler fails, the error propagates through the render fiber via `Abort.fail`:

```scala
private def runHandler(action: Unit < Async)(using Frame): Unit =
    val _ = Fiber.initUnscoped {
        Abort.catching[Throwable](action)
    }
```

At the call site, the user explicitly handles errors:

```scala
Abort.run[Throwable] {
    backend.render(myUI)
}.map {
    case Result.Success(_)   => // running
    case Result.Failure(err) => // handler error — log, display, etc.
    case Result.Panic(ex)    => // unexpected
}
```

This is idiomatic kyo: errors are typed, explicit in the effect signature, and handled by the caller. No silent swallowing, no callbacks. The effect type itself documents that rendering can fail.

However, there's a subtlety: `render` sets up subscriptions and returns — event handlers fire later, asynchronously. The `Abort` in the render type would only catch errors during initial rendering (building the DOM, setting up subscriptions). For async handler errors, we need the handler fibers to propagate errors back to the render scope.

**Approach**: Use a shared error channel. When `render` is called, create a `Channel[Throwable]` (or use `Abort` directly through the subscription fiber). Handler errors are sent to this channel. The render computation joins on this channel so that any handler error surfaces as an `Abort[Throwable]` to the caller. The render computation only completes (with success or failure) when the `Scope` closes.

---

## 8. `UI.fragment` convenience constructor

Creating a fragment requires importing `AST` types and using `Chunk`:

```scala
import kyo.UI.AST.*
Fragment(Chunk(div("a"), div("b")))
```

**Change** — add varargs factory:

```scala
def fragment(cs: UI*): UI = AST.Fragment(Chunk.from(cs))
```

Usage:
```scala
UI.fragment(div("a"), div("b"))
```

---

## 9. Keyed reconciliation for `ForeachIndexed`

Currently every signal emission destroys all DOM children and rebuilds from scratch:

```scala
clearChildren(container)
Kyo.foreach(items.zipWithIndex) { (item, idx) =>
    for node <- build(render(idx, item))
    yield val _ = container.appendChild(node)
}.unit
```

For a list of 100 items where one item changes, all 100 DOM nodes are destroyed and recreated. This causes visible flicker, loss of input focus, reset of scroll position, and poor performance.

**Change**: Add a `foreachKeyed` variant that takes a key function, and implement keyed reconciliation in the backend.

AST change:
```scala
case class ForeachKeyed[A](
    signal: Signal[Chunk[A]],
    key: A => String,
    render: (Int, A) => UI
) extends UI

extension [A](signal: Signal[Chunk[A]])
    def foreachKeyed(key: A => String)(render: A => UI): ForeachKeyed[A]
    def foreachKeyedIndexed(key: A => String)(render: (Int, A) => UI): ForeachKeyed[A]
```

Backend reconciliation algorithm — on each signal emission:

1. Build a map of `key → DOM node` from the current container children
2. For each item in the new list:
   - If a node with that key exists, move it to the correct position (no rebuild)
   - If no node exists for that key, build a new one and insert
3. Remove any nodes whose keys are no longer in the list
4. Update indices for items that moved

```scala
private def subscribeKeyed[A](
    container: dom.Element,
    signal: Signal[Chunk[A]],
    key: A => String,
    render: (Int, A) => UI
)(using Frame): Unit < (Async & Scope) =
    for
        nodeMap <- AtomicRef.init(Map.empty[String, dom.Node])
        _ <- subscribe(signal) { items =>
            for
                oldMap <- nodeMap.get
                newEntries <- Kyo.foreach(items.zipWithIndex) { (item, idx) =>
                    val k = key(item)
                    oldMap.get(k) match
                        case Some(existing) =>
                            // Reuse existing node, just reposition
                            val _ = container.appendChild(existing) // moves to end
                            (k, existing)
                        case None =>
                            // Build new node
                            for node <- build(render(idx, item))
                            yield
                                val _ = container.appendChild(node)
                                (k, node)
                }
                newMap = newEntries.toMap
                // Remove nodes no longer in list
                _ = oldMap.foreach { (k, node) =>
                    if !newMap.contains(k) then
                        val _ = container.removeChild(node)
                }
                _ <- nodeMap.set(newMap)
            yield ()
        }
    yield ()
```

The unkeyed `foreach` can keep the current full-rebuild behavior (simple, correct for small lists). `foreachKeyed` adds the efficient path for larger or more dynamic lists.

---

## Summary

| # | Change | Type |
|---|--------|------|
| 1 | `apply()` appends instead of replacing children | Bug fix |
| 2 | `ForeachIndexed` uses `Chunk` not `List` | Consistency |
| 3 | `UIScope` trait with `export UI.*` fixes `main` conflict | Scoping |
| 4 | `label.forId()` instead of `label.\`for\`()` | Naming |
| 5 | `UI.when(signal)(ui)` conditional rendering | New primitive |
| 6 | Type-tracked CSS classes with `cls`/`clsWhen` | New primitive |
| 7 | Event errors via `Abort[Throwable]` in render type | Effect safety |
| 8 | `UI.fragment(cs*)` convenience | Ergonomics |
| 9 | Keyed reconciliation for `foreachKeyed` | Performance |
