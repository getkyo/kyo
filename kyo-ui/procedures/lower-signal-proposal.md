# Lower Signal Materialization Proposal

## Problem

Lower currently uses `Sync.Unsafe.evalOrThrow(signal.current)` to read `Signal` values. This is wrong — it bypasses the signal tracking system. Changes to the Signal won't trigger re-renders because the pipeline never subscribed to the Signal.

## Correct approach

Two-phase design inside `lower`:

1. **Materialize phase** — walk the UI tree, find every `Signal`, call `asRef` to get a `SignalRef`, then get `.unsafe` to get `SignalRef.Unsafe`. Cache the unsafe ref in `WidgetStateCache`. This phase is effectful (`< (Async & Scope)`).

2. **Lower phase** — walk the UI tree again. All code receives and uses `SignalRef.Unsafe` values directly. Reads via `.get()` under `AllowUnsafe`. No `Signal` touching at all. This phase is pure (given `AllowUnsafe`).

The materialized `SignalRef.Unsafe` refs are cached in `WidgetStateCache` keyed by `WidgetKey + suffix`. On subsequent frames, the cached ref is reused — `asRef` is NOT called again. The piping fiber created by `asRef` keeps the ref in sync automatically.

## Where Signals appear in UI

1. `Attrs.uiStyle: Style | Signal[Style]` — reactive styles
2. `Attrs.hidden: Maybe[Boolean | Signal[Boolean]]` — reactive visibility
3. `HasDisabled.disabled: Maybe[Boolean | Signal[Boolean]]` — reactive disabled
4. `BooleanInput.checked: Maybe[Boolean | Signal[Boolean]]` — reactive checked
5. `Reactive(signal: Signal[UI])` — reactive UI subtree
6. `Foreach(signal: Signal[Chunk[A]], ...)` — reactive list
7. `Anchor.href: Maybe[String | Signal[String]]` — reactive href
8. `Img.src: Maybe[String | Signal[String]]` — reactive image source
9. `Opt.selected: Maybe[Boolean | Signal[Boolean]]` — reactive option selected

## Design

### API change

```scala
def lower(ui: UI, state: ScreenState)(using AllowUnsafe, Frame): LowerResult < (Async & Scope)
```

Returns an effect because the first call needs to create piping fibers. Subsequent calls reuse cached refs and the effect resolves immediately.

### Phase 1: Materialize

Walk the UI tree, collect all Signals, materialize each into a cached `SignalRef.Unsafe`:

```scala
private def materialize(ui: UI, state: ScreenState, dynamicPath: Chunk[String])(using Frame): Unit < (Async & Scope) =
    ui match
        case UI.internal.Text(_) | UI.internal.Fragment(_) => () // no signals
        case UI.internal.Reactive(signal) =>
            materializeSignal(signal, keyFor(ui, dynamicPath), "reactive", state)
                .map(ref => materialize(ref.get(), state, dynamicPath)) // recurse into current value
        case UI.internal.Foreach(signal, _, _) =>
            materializeSignal(signal, keyFor(ui, dynamicPath), "items", state)
                .map(_ => ()) // items materialized, children will be materialized during walk
        case elem: UI.Element =>
            val key = WidgetKey(elem.frame, dynamicPath)
            // Materialize style if Signal
            val styleEffect = elem.attrs.uiStyle match
                case _: Style => ()
                case sig: Signal[?] => materializeSignal(sig, key, "style", state).map(_ => ())
            // Materialize hidden if Signal
            val hiddenEffect = materializeMaybeBoolSignal(elem.attrs.hidden, key, "hidden", state)
            // Materialize disabled if Signal
            val disabledEffect = elem match
                case hd: UI.HasDisabled => materializeMaybeBoolSignal(hd.disabled, key, "disabled", state)
                case _ => ()
            // Materialize checked if Signal
            val checkedEffect = elem match
                case bi: UI.BooleanInput => materializeMaybeBoolSignal(bi.checked, key, "checked", state)
                case _ => ()
            // Compose all effects, then recurse children
            styleEffect
                .andThen(hiddenEffect)
                .andThen(disabledEffect)
                .andThen(checkedEffect)
                .andThen(materializeChildren(elem.children, state, dynamicPath))
```

```scala
private def materializeSignal[A: CanEqual](
    signal: Signal[A], key: WidgetKey, suffix: String, state: ScreenState
)(using Frame): SignalRef.Unsafe[A] < (Async & Scope) =
    val cacheKey = key.child(suffix)
    state.widgetState.get[SignalRef.Unsafe[A]](cacheKey) match
        case Present(ref) => ref // already materialized on a previous frame
        case Absent =>
            signal.asRef.map { ref =>
                val unsafe = ref.unsafe
                discard(state.widgetState.getOrCreate(cacheKey, unsafe)) // cache it
                unsafe
            }
```

### Phase 2: Lower (side-effectful under AllowUnsafe, no Async & Scope)

Same as current `walk`, but all Signal reads replaced with cache lookups. Side-effectful because it reads `SignalRef.Unsafe.get()` and mutates `ChunkBuilder` — properly marked with `AllowUnsafe`. Does NOT create fibers or need `Async & Scope`.

```scala
private def readStyle(elem: UI.Element, key: WidgetKey, state: ScreenState)(using AllowUnsafe): Style =
    elem.attrs.uiStyle match
        case s: Style => s
        case _: Signal[?] =>
            // Already materialized in phase 1 — read from cache
            state.widgetState.get[SignalRef.Unsafe[Style]](key.child("style"))
                .map(_.get())
                .getOrElse(Style.empty)

private def readBooleanOrSignal(value: Maybe[Boolean | Signal[Boolean]], key: WidgetKey, suffix: String, state: ScreenState)(using AllowUnsafe): Boolean =
    if value.isEmpty then false
    else value.get match
        case b: Boolean => b
        case _: Signal[?] =>
            state.widgetState.get[SignalRef.Unsafe[Boolean]](key.child(suffix))
                .map(_.get())
                .getOrElse(false)
```

No `evalOrThrow` anywhere. No `Signal` API calls in phase 2. Only `SignalRef.Unsafe.get()`.

### Phase 2 for Reactive/Foreach

```scala
case UI.internal.Reactive(signal) =>
    val key = ... // derive from dynamicPath
    val ref = state.widgetState.get[SignalRef.Unsafe[UI]](key.child("reactive"))
    ref match
        case Present(r) => walk(r.get(), dynamicPath, ctx) // walk the current UI value
        case Absent     => Resolved.Text("") // shouldn't happen if materialize ran

case fe: UI.internal.Foreach[?] =>
    val key = ...
    val ref = state.widgetState.get[SignalRef.Unsafe[Chunk[Any]]](key.child("items"))
    ref match
        case Present(r) =>
            val items = r.get()
            // map items to children...
        case Absent => Resolved.Text("")
```

## Putting it together

```scala
def lower(ui: UI, state: ScreenState)(using AllowUnsafe, Frame): LowerResult < (Async & Scope) =
    // Phase 1: materialize all Signals into cached SignalRef.Unsafe (creates fibers)
    materialize(ui, state, Chunk.empty).map { _ =>
        // Phase 2: side-effectful lowering under AllowUnsafe
        // Reads cached SignalRef.Unsafe.get(), mutates ChunkBuilder
        // No Async & Scope needed — all fibers were created in phase 1
        val focusables = ChunkBuilder.init[WidgetKey]
        val ctx = Ctx(state, focusables, noop, noopKey, noopKey, noopInt, noop)
        val tree = walk(ui, Chunk.empty, ctx)
        LowerResult(tree, focusables.result())
    }
```

**Mental model:** Unsafe side-effectful code (reading refs, mutating builders) is suspended inside the `map` computation. The `Async & Scope` effect from phase 1 propagates through. The caller (Pipeline orchestrator) runs the whole thing in the proper effect context.

## Impact

- `Lower.lower` returns `LowerResult < (Async & Scope)` instead of `LowerResult`
- `WidgetStateCache` unchanged — existing `get`/`getOrCreate` sufficient
- All `Sync.Unsafe.evalOrThrow` calls removed
- All Signal reads in phase 2 go through `WidgetStateCache.get` → `SignalRef.Unsafe.get()`
- Phase 2 (`walk`) is side-effectful under `AllowUnsafe` — reads unsafe refs, mutates builders — but does NOT need `Async & Scope`
- Tests need to run Lower in `Async & Scope` context
- Pipeline orchestrator (Phase 9) runs Lower within its `Async & Scope`

## Key insight

The cache is the bridge. Phase 1 populates it (effectful, once per signal). Phase 2 reads it (pure, every frame). `SignalRef.Unsafe.get()` always returns the latest value because the piping fiber from `asRef` keeps it updated.
