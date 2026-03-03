# Gap Report — bugfix-val

## Summary
- Total gaps found: 2
- Fixed from previous session: 6 bugs addressed (synchronous initial rendering, simplified subscribeUI, .on() handlers)
- Remaining: 2 (signals Section 3 filter tags on JFX, deepnest Section 2 partial content on JFX)

## Gaps

| # | UI | Section | Dimension | Web (reference) | JavaFX (actual) | Severity | Classification | Status |
|---|-----|---------|-----------|-----------------|-----------------|----------|----------------|--------|
| 1 | signals | Section 3 (Combined Filters) | Content | Alpha/Beta/Gamma/Delta tags visible, checkboxes checked | Checkboxes unchecked, no tags visible | High | Backend bug | Investigating |
| 2 | deepnest | Section 2 (UI.when inside foreach) | Content | Apple + Banana both with detail text | Apple with detail, Banana without detail | Medium | Backend bug | Investigating |

## Previously Fixed Bugs (verified in screenshots)

| # | Bug | Status | Evidence |
|---|-----|--------|----------|
| 1 | Signal[UI] content doesn't render on web | Fixed | web-signals shows full-name, web-swap shows tasks, web-deepnest shows categories |
| 2 | Mutating foreach inside Signal[UI] crashes JFX | Needs interactive verification | subscribeUI simplified, no more Fiber.initUnscoped in callback |
| 3 | items.map() reactive text stale | Fixed | javafx-rapid shows "Count: 3" |
| 4 | .on() generic event handler not on JFX | Fixed (code added) | Needs interactive verification |
| 5 | Programmatic signal.set("") doesn't update JFX | Needs interactive verification | subscribe now processes initial value synchronously |
| 6 | UI.when inside foreach doesn't render on JFX | Partially fixed | deepnest Section 2 renders Apple detail but not Banana; signals Section 3 still empty |

## Gap 1 Investigation: signals filter tags

The JFX tree dump shows `.filter-results` HBox contains nested VBoxes (foreach → subscribeUI containers) but all are empty (size=672x-0.0). The checkboxes also appear unchecked despite `filterA`/`filterB` signals initialized to `true`.

The pattern is: `items.foreach { item => UI.when(filterA/filterB)(span(item)) }` — foreach iterates static items, each item creates a ReactiveNode from `filterA.map(...)`.

Possible cause: The `subscribe` in JFX for the checkbox checked binding calls `runOnFx(updates)(cb.setSelected(v))` which goes through the channel. The checkbox isn't visually checked because the channel thunk hasn't executed by screenshot time. But the signal value IS true — the `currentWith` in the derived signal for `UI.when` should still return the correct UI.

Will verify interactively.

## Gap 2 Investigation: deepnest Banana detail

Section 2 has `items.foreachIndexed { (idx, item) => ... UI.when(showInner)(detail text) }` where `showInner` starts as `true`. Apple shows its detail but Banana doesn't. This could be a rendering order issue where the second item's subscribeUI hasn't completed.

Will verify interactively.
