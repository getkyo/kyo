# Remaining Bugs: Architectural Analysis

## The Bugs

**B20** — Select with no initial value shows empty (4 tests)
**B31** — overflow:hidden wraps text instead of clipping (1 test)
**B32** — overflow:scroll doesn't respond to keyboard scroll (1 test)

## Root Cause Analysis

### B20: Missing fallback in value resolution chain

`PickerW.render()` resolves the displayed value through this chain:
1. Read `pi.value` signal → if non-empty, use it
2. Call `findInitiallySelected()` → scan options for `selected=true`
3. If neither → return `""` (empty)

Step 3 should fall through to the first option. This is the HTML convention — `<select>` auto-selects the first `<option>` when nothing is explicitly selected. The fix is one line in `findInitiallySelected`: when no `selected` attribute is found, return the first option's value.

This is a simple omission, not an architectural issue.

### B31 + B32: Overflow is scattered across 4 layers with no shared contract

This is the interesting one. Overflow behavior requires coordination across:

| Concern | Where it lives now | What it does |
|---------|-------------------|--------------|
| **Storage** | `ResolvedStyle.overflow` (0/1/2) | Stores the policy |
| **Measurement** | `FlexLayout.measureH/W` | Does NOT consider overflow — always sizes container to fit content |
| **Clipping** | `Container.render` → `Screen.pushClip` | Pushes a clip rect during render |
| **Scroll offset** | `Container.render` → `ry - scrollY` | Shifts children up by scroll amount |
| **Scroll input** | `EventDispatch` mouse scroll only | Mouse ScrollUp/Down update scrollY |
| **Scroll indicator** | `ScrollW.paintIndicator` | Draws ▲/▼ arrows |

Each layer knows a fragment of the behavior. No single component owns "what overflow means."

#### Why B31 happens (overflow:hidden wraps instead of clips)

Trace through test 11.12: `div(span("ABCDEFGHIJKLMNOP")).style(_.width(8.em).overflow(hidden))`

1. **Measure** the div's children: `FlexLayout.measureH` for the Span → recurses into Text("ABCDEFGHIJKLMNOP") → `TextMetrics.lineCount("ABCDEFGHIJKLMNOP", availW=8)` → returns **2** (text wraps at 8 chars). The Span gets height=2.
2. **Measure** the div itself: height = auto, so it uses children's height = **2**. The div is 8×2.
3. **Render** the div: `Container.render` pushes clip rect (cx, cy, cx+8, cy+2).
4. **Render** children within clip: the Span renders text wrapped to 2 lines. Everything fits within the 8×2 clip. **Nothing is clipped**.

The clip is correct for the container's dimensions. But the container grew to fit the wrapped content. By the time clipping happens, there's nothing to clip.

**The fundamental problem**: Measurement determines container size from content (bottom-up), then clipping is applied to that already-correct-sized container (top-down). These two passes don't communicate about overflow policy.

#### Why B32 happens (keyboard scroll doesn't work)

`ContainerWidget.handleKey` only handles Enter/Space (for onClick). It has no scroll logic. Mouse scroll works (EventDispatch lines 81-90 handle ScrollUp/ScrollDown), but keyboard scroll (ArrowDown/Up) is not routed to scrollable containers.

The scroll behavior is split: rendering knows about scroll offset, mouse input handles it partially, keyboard input doesn't handle it at all, and measurement doesn't limit container height for scrollable containers.

## The Pattern

These bugs are the **same pattern** as the bugs we already fixed. The original bugs (B6-B9, B22-B25) were caused by widget measurement and rendering being in separate code paths that could diverge. The Widget trait + WidgetRegistry fixed that by bundling them.

The remaining bugs are the same divergence for **overflow**: measurement, clipping, input handling, and indicator painting are in separate code paths with no shared contract.

## What Would Be Simpler

### 1. Overflow should affect measurement, not just rendering

When a container has `overflow != visible` **AND** explicit dimensions, its measured size should be the explicit dimensions — not the content's natural size. The content size becomes the "scrollable extent," which is a separate concept from the container's layout size.

In `FlexLayout.measureH`, the logic should be:
```
if element has explicit height AND overflow != visible:
    return explicit height  // don't recurse into children for sizing
else:
    return children-based height  // current behavior
```

This is the missing link. Measurement and overflow are in separate modules that don't talk to each other. The measurement pass should respect overflow policy.

### 2. Scroll should be a complete behavior, not fragments

Currently scroll is 4 disconnected pieces: render offset (Container), mouse input (EventDispatch), state (FocusRing.scrollY), indicator (ScrollW). A container with `overflow: scroll` needs all 4 to work together.

The ContainerWidget should own scroll input handling: when the focused element is a scrollable container (overflow==2), ArrowDown/Up should update scrollY. This completes the behavior in one place.

### 3. Select value resolution should have a clear fallback chain

`PickerW.findInitiallySelected` should fall through to the first option when nothing is explicitly selected. This completes the initialization state machine:
```
signal value → selected attribute → first option → empty
```

## How These All Connect

The lesson from the full QA arc (50→99 pass) is that bugs cluster around **behavior that spans multiple components with no single owner**:

- **Round 1**: Widget measurement vs rendering → fixed by Widget trait (single owner for both)
- **Round 2**: Overflow measurement vs clipping vs input vs indicators → needs a similar consolidation

The Widget trait pattern worked because it made divergence between measurement and rendering impossible — they're methods on the same object. The same principle applies to overflow: if measurement, clipping, and scroll input are owned by the same abstraction, they can't diverge.
