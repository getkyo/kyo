# QA Feedback — UI DSL Coverage Gaps

## Missing HTML Elements

### 1. `thead` / `tbody` / `tfoot` / `caption` (Table Semantics)
**Priority: Medium**

The DSL has `table`, `tr`, `td`, `th` but lacks `thead`, `tbody`, `tfoot`, and `caption`. These are important for:
- **Accessibility**: Screen readers use `thead`/`tbody` to distinguish header rows from data rows
- **Styling**: CSS allows `thead { position: sticky }` for frozen headers in scrollable tables
- **Semantic correctness**: Browsers auto-wrap rows in an implicit `tbody` anyway, but explicit markup is standard practice

The `AnimatedDashboardUI` originally used `thead`/`tbody` and had to be rewritten without them.

**Recommendation**: Add `Thead`, `Tbody`, `Tfoot`, `Caption` as Element case classes in UI.scala, and corresponding constructors in UIScope. They are structurally identical to `Table` (container with children, no special attributes).

### 2. `nav` / `aside` / `article` / `footer` (Semantic Containers)
**Priority: Low**

Currently only `header`, `main`, `section`, `div` exist as container elements. Standard HTML5 semantic elements like `nav`, `aside`, `article`, `footer` are missing. These don't affect rendering but are important for accessibility and SEO.

### 3. `fieldset` / `legend` (Form Grouping)
**Priority: Low**

Missing form grouping elements. The `FormUI` demo groups inputs manually with divs, but `fieldset`/`legend` provide native grouped borders and labels.

## Style DSL Gaps Found During Demo Creation

### 4. `style(Signal[String])` — Dynamic Inline CSS
**Priority: Medium**

This works on web but needs verification on JavaFX. The `DynamicStyleUI` and transition demos rely on `style(signal.map(...))` for dynamic backgrounds, font sizes, and padding. JavaFX must parse these CSS strings at runtime.

**Observation from screenshots**: Dynamic inline CSS appears to work in JavaFX (color cycling box shows correctly) but the "Phase 0" text suggests the first update didn't fire before screenshot. Timing-dependent.

### 5. `overflow` property support
**Priority: Low**

`Style.overflow(_.hidden)` / `_.scroll` / `_.auto` — present in the DSL, used in LayoutUI, but JavaFX mapping may not handle all variants (especially `overflow: auto` with scrollbars).

### 6. `textOverflow(_.ellipsis)` with `wrapText(false)`
**Priority: Low**

Used in TypographyUI. Requires both `text-overflow: ellipsis` AND `overflow: hidden` AND `white-space: nowrap` to work. JavaFX equivalent is `setTextOverrun(OverrunStyle.ELLIPSIS)` — may need verification.

## Transition Demo Observations

### 7. Screenshot Timing
**Priority: Info**

The JavaFX screenshot tool waits 2 seconds (`Async.sleep(2.seconds)`) before capturing. The web screenshot waits only 500ms (`setTimeout(r, 500)`). This means:
- JavaFX screenshots show more advanced animation state
- Web screenshots show earlier state

**Recommendation**: Increase web wait to 2 seconds to match, or make both configurable.

### 8. `Fiber.initUnscoped` Lifecycle
**Priority: Info**

The transition demos fork background fibers with `Fiber.initUnscoped`. These fibers are never explicitly cancelled. For demos this is fine, but the pattern raises a question: should `UISession.stop` also cancel fibers started during `build`? Currently it doesn't, since the fibers are disconnected from the session.

## Items Verified Working

- `Signal.initRef` with all primitive types (Boolean, Int, String, Chunk)
- `signal.map(...)` for derived text and UI
- `signal.foreach` / `foreachKeyed` / `foreachKeyedIndexed` / `foreachIndexed`
- `UI.when(signal)(...)` conditional rendering
- `Signal[UI]` for swapping entire UI subtrees
- `.hidden(signal)` visibility toggle
- `.cls(signal)` dynamic class
- `.style(Signal[String])` dynamic inline CSS
- `Fiber.initUnscoped` + `Async.sleep` for scheduled transitions
- `Async.delay` pattern for sequential timed updates
- `Loop.forever` for continuous signal updates
- Nested `when()` inside `when()`
- `foreach` with Signal children (global counter inside each item)
- Table elements: `table`, `tr`, `td`, `th` with `colspan`/`rowspan`
- All Color constructors: `Color.rgb`, `Color.rgba`, `Color.hex`, predefined colors
- Size units: `px`, `pct`, `em`, `auto`
- Shadow depths, transforms (translate, rotate, scale), opacity
