# TUI2 Architecture Review — Session Notes

## Context

We ran the full QA validation (105 visual tests across 15 sections) for the tui2 renderer,
then analyzed the bugs for shared root causes, then did a deep architectural fragility analysis.

**The goal is NOT to fix individual bugs yet.** The goal is to identify architectural improvements
that make the code more modular, simpler, and safer — so that bugs become structurally impossible
rather than fixed one at a time.

## Key Files

All work happens in the **kyo-ui worktree**:
```
cd /Users/fwbrasil/workspace/kyo/.claude/worktrees/kyo-ui
```

- **QA results**: `kyo-ui/QA-VALIDATION.md` (105 tests, all reviewed, 50 pass / 54 fail)
- **Test harness**: `kyo-ui/jvm/src/test/scala/kyo/TerminalEmulatorQA.scala`
- **Run tests**: `sbt 'kyo-ui/testOnly kyo.TerminalEmulatorQA'` (generates `/tmp/tui-qa/frames.txt` + PNGs)

### Core renderer files (all under `kyo-ui/jvm/src/main/scala/kyo/internal/tui2/`):

| File | Role |
|------|------|
| `FlexLayout.scala` | CSS Flexbox layout engine. measureW/H + arrange (CPS). Zero-alloc via scratch arrays. |
| `ResolvedStyle.scala` | 34+ field mutable struct. Accumulates style from theme → user → pseudo-state. |
| `RenderCtx.scala` | God object. Holds screen, canvas, focus, theme, signals, inherited style, layout buffers, element positions. |
| `widget/Render.scala` | Tree walker. Style resolution → box painting (margin/shadow/bg/border/content/filters) → widget dispatch. |
| `widget/Container.scala` | Runs FlexLayout.arrange for elements with children. Also has renderColumn (different semantics). |
| `widget/WidgetDispatch.scala` | Giant pattern match dispatching element types to widget render/handleKey/handlePaste. |
| `widget/TextInputW.scala` | Input/Password/Textarea/Number. Single-line scroll, textarea multiline, selection, placeholder. |
| `widget/PickerW.scala` | Select dropdown. Renders option text + `▼`. Arrow key navigation. |
| `widget/RangeW.scala` | Range slider. Filled/unfilled bar via fillBg. |
| `widget/CheckboxW.scala` | Checkbox `[x]`/`[ ]` and Radio `(*)`/`( )`. drawString of glyph. |
| `widget/HrW.scala` | Draws hline of `─`. BUT: dead code — theme renders hr via borderBottom, content rect has ch=0. |
| `widget/ScrollW.scala` | Draws ▲/▼ scroll indicators. |
| `widget/TableW.scala` | Table layout — column width distribution. |
| `Canvas.scala` | Stateless drawing: drawString, drawText (wrap/align/transform/ellipsis), border, hline, vline. |
| `Screen.scala` | Double-buffered cell grid. Diff-based ANSI flush. Clip stack (8 levels). |
| `FocusRing.scala` | Parallel arrays for 64 focusable elements. Scan/migrate/sort phases. Per-element cursor/scroll/selection state. |
| `ResolvedTheme.scala` | Pattern-match element type → default Style. Three themes: Default, Minimal, Plain. |
| `TextMetrics.scala` | Zero-alloc text utilities: naturalWidth, lineCount, foldLines, applyTransform. |
| `CellStyle.scala` | Opaque Long packing fg+bg+bold+italic+underline+strikethrough+dim. |
| `ColorUtils.scala` | Color resolution, blending, ANSI tier mapping. |

## QA Bug Summary (21 bugs found)

### Already fixed pre-QA
- **B1**: FlexLayout shared buffer corruption (siblings disappearing)
- **B2**: HitTest ignoring element positions

### Active bugs grouped by root cause

**Root Cause 1 — Cross-axis stretch missing** (B8, B9, B23, B25):

`FlexLayout.arrange()` line 239: `crossSizes(i) = measured width`. In column layout, children
get their intrinsic width instead of stretching to container width. Elements with no children
(input, checkbox, hr, range) measure to width ≈ 0 and render tiny/invisible.

- B8: Inputs/number/range render as tiny `┌──┐` (4-char box) inside multi-child containers
- B9: Checkbox/radio glyph invisible in divs with siblings (width 0 → can't fit `[ ]`)
- B23: Li text truncated — marker consumes width from the too-small measured allocation
  ("First" → "Fir" because li gets width=5, marker=2, content=3)
- B25: Hr invisible — width 0, so border draws nothing and HrW.render never called (ch=0)

**Root Cause 2 — Text styles not inherited by Text nodes** (B28, B29, B30):

`Render.scala:23` hardcodes `drawText(value, ..., align=0, transform=0, wrap=true, ellipsis=false)`.
Parent's `rs.textAlign`, `rs.textTransform`, `rs.textWrap`, `rs.textOverflow` are resolved but
never forwarded. Canvas.drawText fully implements these features — they just never receive the values.

- B28: textTransform (uppercase/capitalize) has no effect
- B29: textAlign (center/right) has no effect
- B30: textWrap(ellipsis) doesn't truncate with `…`

**Root Cause 3 — No default pseudo-state styles in theme** (B4, B5, B6):

`Render.resolveAllStyles()` applies focus/disabled/hover styles only if user explicitly defines them.
`ResolvedTheme` provides NO default visual feedback for any state. No focus highlight, no disabled
dim, no placeholder rendering when unfocused.

- B4: Disabled button looks identical to enabled
- B5: No focus indicator anywhere (buttons, inputs, textareas all identical focused/unfocused)
- B6: Placeholder text not rendering (may be separate — TextInputW has placeholder code but
  it needs `cursor < 0` which requires focus tracking to work)

**Root Cause 4 — Width/height constraints ignored at root level** (B27 → B26, B30):

`paintElement` uses caller-provided (w, h), never checks `rs.sizeW`/`rs.sizeH`. Root rendering
passes viewport dimensions. So `div("box").style(_.width(10.em))` measures as 10 but renders at 30.

- B27: Explicit width/height not respected
- B26: flexWrap depends on width constraint (no constrained parent → nothing to wrap)
- Cascades to overflow:hidden, overflow:scroll, text ellipsis

**Root Cause 5 — Widget-specific** (B20, B22, B24):

- B20: PickerW renders option text with `drawString(cx, cy, max(0, cw-2), displayText, ...)` —
  at full width the text should appear, but something causes it not to. At narrow widths text IS
  visible but truncated (B21).
- B22: RangeW renders filled/unfilled bar via `fillBg` (background color only, no character content).
  When range has cross-axis stretch bug, it gets tiny. When it's the sole element, it renders as a
  bordered box (theme gives it border) but the fill is `fillBg` using fg color on bg — may not be
  visible if fg=bg or if the fillBg area is inside the border content rect.
- B24: Ordered list numbers all "1." — `ctx.listIndex` incremented in WidgetDispatch but might be
  reset per-li instead of per-ol. Need to verify.

**Other bugs**:
- B3: Bold not visually distinct in monospace (cosmetic, low priority)
- B7: typeText() in test harness drops spaces (Keyboard.Space not handled as char input)

## Architectural Fragility Analysis

We identified 8 structural problems. **These are what we want to address with improvements.**

### 1. Split Inheritance Model

Two unrelated mechanisms for inheriting style:
- **RenderCtx mutable fields** (fg, bg, bold, italic, underline, strikethrough, dim) — manual save/restore in Render.paintElement lines 139-171
- **Function parameters** to drawText (textAlign, textTransform, wrap, ellipsis) — hardcoded to defaults

No single "inherited properties" abstraction. Adding a new inheritable property requires editing
BOTH mechanisms.

### 2. Measurement/Rendering Divergence

FlexLayout.measureW/H only sees children. But widgets draw additional content at paint time:
- Li: marker ("• ") not in measurement
- Checkbox: glyph "[x]" not in measurement (no children → measures to 0)
- Select: "▼" indicator not in measurement
- Range: filled bar not in measurement
- Hr: rendered via theme border, HrW.render is dead code

No `Widget.intrinsicWidth()` concept. Every new widget with non-child content will have sizing bugs.

### 3. No Cross-Axis Stretch

FlexLayout line 239: `crossSizes(i) = min(measured, available)`. Never expands to container width.
CSS default is stretch. Fix requires distinguishing "explicit size" (width:10em → respect it) from
"intrinsic size" (content width → stretch it). Currently both produce the same measurement value.

### 4. Size Authority Ambiguity

Three different things determine size depending on render path:
- Root: viewport size (ignores element's own sizeW/sizeH)
- FlexLayout.arrange: flex-distributed based on measurement
- Container.renderColumn: full width, measured height

No single resolution step. Element's explicit size is only a hint for parent's measurement,
not a constraint on the element itself during rendering.

### 5. ResolvedStyle Conflates Three Concerns

One 34-field mutable struct holds:
- **Layout geometry** (direction, grow, shrink, gap, sizeW/H, min/max) — consumed by FlexLayout
- **Box model** (padding, margin, border, shadow, transform) — consumed by Render.paintElement
- **Visual style** (fg, bg, bold, textAlign, textTransform, filters, gradients) — consumed by Canvas/Screen
- **Pseudo-state config** (hoverStyle, focusStyle, activeStyle, disabledStyle)

No separation of concerns. FlexLayout has access to gradient fields. Canvas doesn't need layout fields.

### 6. Theme vs Widget Responsibility Blurred

Hr is the clearest example:
- Theme gives `borderBottom(1px, #666)` → rendered by paintElement's border code
- HrW.render draws hline → never called because content rect has ch=0
- Two rendering strategies for one element, no documentation of which wins

### 7. RenderCtx God Object

Holds everything: screen, canvas, focus, theme, signals, overlays, inherited style, list context,
layout scratch buffers, element positions, identifiers, image cache, file input paths, hover/active
targets, terminal reference. Every widget can read/write anything. Save/restore is manual.

### 8. Implicit Widget Contract

No Widget trait. Widgets are functions matched in 3 separate pattern matches in WidgetDispatch
(render, handleKey, handlePaste). No mechanism to declare intrinsic size, focusability, or handled
events. Adding a widget = adding cases in 3 places and hoping measurement works.

## Next Steps

The user wants to **explore improvements** that make the code more **modular, simpler, and safer**.
This is a design/refactoring task, not a bug-fix task. The approach should be:

1. **Read the remaining files** we haven't fully explored yet:
   - UI model (shared): UI.scala or equivalent — element type hierarchy
   - Style API (shared): Style.scala — how users define styles
   - Backend/Terminal integration: how render loop works, event dispatch, viewport sizing
   - ValueResolver, SignalCollector, InputEvent, etc.

2. **Design improvements** that address the 8 fragility points above. Key themes:
   - Single inheritance mechanism for all style properties
   - Widget trait with intrinsic sizing, focusability, event handling
   - Separate layout spec from paint spec (split ResolvedStyle)
   - Make cross-axis stretch the default, with explicit size override
   - Scoped access to RenderCtx (layout phase gets layout fields only)
   - Self-size respected at render time (not just measurement time)

3. **Be creative** — the user explicitly asked for creative solutions. Consider:
   - Whether FlexLayout should know about widgets at all
   - Whether measurement should be a widget responsibility
   - Whether the save/restore pattern can be replaced with scoping
   - Whether ResolvedStyle should be replaced with multiple smaller types
   - Whether WidgetDispatch should be replaced with a trait

4. **Present a plan** and wait for approval before implementing (per CLAUDE.md workflow).

## Important Workflow Rules (from CLAUDE.md)

- **Never commit** — user handles all git commits
- Always create analysis files before making changes
- Present plans and wait for approval before implementing
- Make changes one at a time, waiting for feedback between each
- Always use the worktree: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/kyo-ui`
