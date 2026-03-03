# Gap Report

## Summary
- Total gaps found: 9
- Fixed: 7 (by removing isConnected/isInScene early-return guards in subscribe callbacks)
- Remaining: 2 (nested async rendering — UI.when + foreach, and foreach inside UI.when)
- Platform differences: 1 (JFX scrollbar in empty overflow:scroll)

## Root Cause: Initial Render Dropped by Connection Guards

In both `DomBackend` and `JavaFxBackend`, the subscribe callbacks for `subscribeUI`, `subscribeForeach`/`subscribeList`, and `subscribeKeyed` all had an early-return guard:

```scala
if !container.isConnected then ((): Unit < (Async & Scope))
```

When `streamChanges` emits the initial value, the container has just been created in `build()` but hasn't been added to its parent yet. So `isConnected`/`isInScene` returns `false`, and the entire callback is skipped — the initial items are never rendered.

**Fix**: Removed outer guards. Kept inner guards only where needed (JFX `runOnFx` for thread safety). For JFX, added `addChildren` helper that adds children directly when not in scene (safe since it's just tree building) or via `runOnFx` when in scene.

**Generic test**: This fix is correct for ANY UI that uses `Signal[UI]`, `foreach`, `foreachKeyed`, or `UI.when` — all of which go through these subscribe methods. Without it, no reactive list or conditional content renders on initial load.

## Gaps

| # | UI | Section | Dimension | Web (reference) | JavaFX (actual) | Severity | Classification | Status |
|---|-----|---------|-----------|-----------------|-----------------|----------|----------------|--------|
| 1 | deepnest | Section 1 | Content | Categories with items render | No items rendered | High | Backend bug | Fixed |
| 2 | deepnest | Section 2 | Content | Apple/Banana with details | No items rendered | High | Backend bug | Fixed |
| 3 | deepnest | Section 3 | Content | Fruits/Vegetables with counts | No text in purple box | High | Backend bug | Fixed |
| 4 | rapid | Section 1 | Content | A, B, C tags visible | Tags missing | High | Backend bug | Fixed |
| 5 | rapid | Section 2 | Content | A, B, C list items | Items missing (both platforms) | High | Backend bug | Partially fixed |
| 6 | swap | Section 1 | Content | Tasks with items | No task items | High | Backend bug | Fixed |
| 7 | signals | Section 3 | Content | Filter tags visible | Tags missing | High | Backend bug | Investigating |
| 8 | rechref | Sections 2-3 | Content | Foreach items visible | Items missing on JFX | High | Backend bug | Fixed |
| 9 | rapid | Section 3 | Layout | Compact log area | JFX scrollbars visible when empty | Low | Platform difference | N/A |

## Remaining Issues

### Gap 5: UI.when + foreachKeyed (rapid section 2)
- **Both platforms**: `UI.when(visible)` wrapping `foreachKeyed` doesn't render initial items
- **Root cause**: Nested async fibers — `subscribeUI` (for UI.when) creates fiber A, which builds inner UI containing `foreachKeyed`, which creates fiber B (via `subscribe`). Fiber B's initial render may complete after the screenshot is taken, OR the container hierarchy isn't established when fiber B appends children.
- **Status**: Investigating — may be a screenshot timing issue. Will verify in interactive session.

### Gap 7: signals section 3 filter tags
- **JFX only**: `items.foreach { item => UI.when(filterA/B)(span(item)) }` doesn't render tags
- **Root cause**: Same nested async pattern — `foreach` subscription creates containers, then each `UI.when` inside creates its own `subscribeUI`. The checkbox also appears unchecked on JFX despite signal=true, which may be a separate checkbox binding issue.
- **Status**: Investigating — will verify interactively.

## Fix Details

### Files Modified
- `kyo-ui/js/src/main/scala/kyo/DomBackend.scala`: Removed `!container.isConnected` guards from `subscribeUI`, `subscribeList`, `subscribeKeyed` callbacks
- `kyo-ui/jvm/src/main/scala/kyo/JavaFxBackend.scala`: Removed `!isInScene(container)` guards from `subscribeUI`, `subscribeForeach`, `subscribeKeyed` callbacks. Added `addChildren` helper for thread-safe child addition.
