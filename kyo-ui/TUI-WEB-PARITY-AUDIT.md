# TUI2 Backend — Web Parity Audit

Traces each UI component type through the TUI2 rendering and event handling code, comparing behavior to a web browser.

**Legend**: ✅ matches web | ⚠️ partial | ❌ missing/broken

---

## 1. Block Containers (Div, Section, Main, Header, Footer, Form)

**Rendering**: All route through `Container.render` → `FlexLayout.arrange`. Identical behavior — pure flex containers with children laid out by direction, gap, alignment.

**Web parity**:
- ✅ Flex direction (row/column)
- ✅ Flex grow distribution
- ✅ Gap between children
- ✅ Justify-content (start, center, end, between, around, evenly)
- ✅ Align-items (start, center, end, stretch)
- ✅ Padding (all sides)
- ✅ Border (solid, dashed, dotted + per-side colors + rounded corners)
- ✅ Background color
- ✅ Overflow hidden/scroll with clip rect
- ✅ Box shadow (offset + spread)
- ✅ Translate transform
- ❌ **Flex shrink** — stored but never applied. When children overflow, they clamp to 0 instead of shrinking proportionally.
- ❌ **Flex wrap** — not implemented. Single-line layout only.
- ❌ **Margins** — parsed and stored but never applied in layout positioning.
- ❌ **Percentage sizing** — resolves to 0. Comment says "resolved in layout" but no code exists.
- ❌ **Min/max width/height** — stored but never read in layout or measure.
- ❌ **CSS Grid** — not supported, flex only.

**Form-specific**: `Form.onSubmit` fires when Enter is pressed on a focused non-textarea input inside the form. ✅ Correct behavior.

---

## 2. Headings (H1–H6)

**Rendering**: All route through `Container.render` (same as Div).

**Theme**: All headings get `Style.bold`. No size differentiation.

**Web parity**:
- ✅ Bold text
- ❌ **No size differentiation** — H1 through H6 all render identically (just bold). In web, H1 is ~2em, H2 ~1.5em, etc. Terminal cells are uniform size so font-size can't change, but vertical padding or extra line height could approximate heading hierarchy.

---

## 3. Lists (Ul, Ol, Li)

**Rendering**: All route through `Container.render`. No special handling.

**Theme**: No default styling.

**Web parity**:
- ❌ **No bullet markers for Ul/Li** — web shows •, ◦, ▪ etc.
- ❌ **No number markers for Ol/Li** — web shows 1., 2., 3. etc.
- ❌ **No indentation** — Li renders flush with parent.

---

## 4. Text Elements (P, Span)

**Rendering**: Route through `Container.render`. Text children render via `Canvas.drawText`.

**Web parity**:
- ✅ Text color (fg)
- ✅ Bold, italic, underline, strikethrough, dim
- ✅ Text alignment (left, center, right, justify)
- ✅ Text transform (uppercase, lowercase, capitalize)
- ✅ Text wrap / nowrap
- ✅ Text overflow ellipsis
- ⚠️ **Line wrapping** — wraps on character boundary, not word boundary (no word-wrap).

---

## 5. Pre / Code

**Rendering**: Route through `Container.render`. No special handling.

**Theme**: No default styling.

**Web parity**:
- ❌ **No whitespace preservation for Pre** — web preserves literal spaces/newlines.
- ⚠️ **Monospace is already the terminal default** — so font is correct by nature of the medium.

---

## 6. Anchor (a)

**Rendering**: Routes through `Container.render`. Children render as text.

**Theme**: No default styling (DomStyleSheet sets `color: inherit; text-decoration: none`).

**Event handling**: Enter/Space → `PlatformCmd.openBrowser(url)`. ✅ Opens system browser.

**Web parity**:
- ✅ Click/activate opens URL
- ❌ **No default underline or blue color** — web browsers show blue underlined links by default.
- ❌ **`target` field ignored** — parsed (`Self`, `Blank`, `Parent`, `Top`) but `openBrowser` always uses same behavior regardless.

---

## 7. Label

**Rendering**: Routes through `Container.render`.

**Theme**: No default styling.

**Event handling**: Click on label with `forId` focuses the target element via `IdentifierRegistry`. ✅ Correct behavior.

**Web parity**:
- ✅ `forId` → focuses target element on click
- ⚠️ No visual distinction from plain text (web also doesn't style labels by default, so this is acceptable).

---

## 8. Table / Tr / Td / Th

**Rendering**: `TableW.render` handles table layout with uniform column widths, colspan/rowspan support via occupancy buffer.

**Web parity**:
- ✅ Colspan
- ✅ Rowspan with occupancy tracking
- ✅ Uniform column distribution
- ❌ **Th renders identically to Td** — web makes Th bold and centered by default.
- ❌ **No proportional column sizing** — all columns are equal width (`cw / numCols`). Web auto-sizes columns based on content.
- ❌ **No table borders** — no cell-level border rendering (must use Style borders on individual cells).
- ❌ **Row height always 1** — rowspan allocates vertical space but each row is 1 cell tall.

---

## 9. Button

**Rendering**: Routes through `Container.render`.

**Theme**: `Style.border(1.px, BorderStyle.solid, "#888").padding(0.em, 1.em)` — border box with horizontal padding. ✅ Looks like a button.

**Event handling**: Enter/Space fires `onClick`. ✅ Correct.

**Web parity**:
- ✅ Border + padding visual
- ✅ Disabled state (checked via `HasDisabled`)
- ❌ **No button `type` field** — no distinction between `submit`, `reset`, `button`. All buttons are generic click handlers.

---

## 10. Hr

**Rendering**: `HrW.render` draws a line of `─` (U+2500) characters across full width.

**Theme**: `Style.borderBottom(1.px, "#666").borderStyle(BorderStyle.solid)`.

**Web parity**:
- ✅ Visual horizontal separator
- ⚠️ Always uses `─` character — color comes from cell style but doesn't use the border color from theme.

---

## 11. Br

**Rendering**: No-op in `WidgetDispatch` — `case _: UI.Br => ()`.

**Web parity**:
- ❌ **Br is completely ignored** — web inserts a line break. In TUI, Br should advance the vertical position by 1 row in column layout, or be treated as a newline in text flow.

---

## 12. TextInput (Input, Password, Email, Tel, Url, Search, NumberInput)

**Rendering**: `TextInputW.render` — shows text with cursor, horizontal scroll, placeholder support.

**Event handling**:
- ✅ Character insertion at cursor
- ✅ Backspace / Delete
- ✅ Arrow left/right cursor movement
- ✅ Home / End
- ✅ Paste (text inserted at cursor)
- ✅ Placeholder shown when empty + unfocused
- ✅ Password masking (shows `*`)
- ✅ `onInput` / `onChange` callbacks
- ✅ `readOnly` / `disabled` support
- ✅ NumberInput: ArrowUp/Down increment/decrement by step, clamped to min/max

**Web parity**:
- ✅ Core text editing
- ❌ **No text selection** (Shift+Arrow, Shift+Home/End, Ctrl+A)
- ❌ **No Ctrl+C / Ctrl+X** (copy/cut)
- ❌ **No word navigation** (Ctrl+Arrow left/right)
- ❌ **No undo/redo** (Ctrl+Z / Ctrl+Shift+Z)

---

## 13. Textarea

**Rendering**: `TextInputW.render` in textarea mode — word-wrapped text with vertical scroll, cursor as (line, column).

**Event handling**: Same as TextInput plus:
- ✅ Enter inserts newline
- ✅ ArrowUp / ArrowDown navigate between lines
- ✅ Vertical scroll auto-adjusts to keep cursor visible

**Web parity**: Same gaps as TextInput (no selection, no word nav, no undo), plus:
- ⚠️ Wraps on character boundary, not word boundary.

---

## 14. Checkbox / Radio (BooleanInput)

**Rendering**: `CheckboxW.render`:
- Checkbox: `[x]` / `[ ]`
- Radio: `(*)` / `( )`

**Event handling**: Space or Enter toggles checked state, fires `onChange`.

**Web parity**:
- ✅ Toggle behavior
- ❌ **No radio group exclusion** — multiple radios can be checked simultaneously. Web radio buttons with the same `name` are mutually exclusive. There is no `name` field on Radio.
- ❌ **No indeterminate state** for Checkbox.

---

## 15. Select (PickerInput)

**Rendering**: `PickerW.render` — shows selected value text + `▼` arrow indicator.

**Event handling**: ArrowUp/Down cycle through `Opt` children.

**Web parity**:
- ✅ Arrow navigation through options
- ✅ `Opt.selected` initial selection
- ✅ `onChange` fires on selection change
- ❌ **No dropdown visual** — web shows expanded option list. TUI only shows current value.
- ❌ **No type-ahead search** — typing doesn't jump to matching option.
- ❌ **No multi-select** support.
- ❌ **No option groups** (`<optgroup>`).

---

## 16. DateInput / TimeInput / ColorInput (PickerInput)

**Rendering**: `PickerW.render` — shows value string only.

**Event handling**: ArrowUp/Down — but these have no `Opt` children to cycle through, so they effectively do nothing useful.

**Web parity**:
- ❌ **No date/time/color picker UI** — web shows native picker dialogs. TUI shows raw string value with no picker interaction.

---

## 17. RangeInput

**Rendering**: `RangeW.render` — filled bar visualization (filled portion = value position).

**Event handling**: ArrowRight/Up increment, ArrowLeft/Down decrement, clamped to min/max.

**Web parity**:
- ✅ Step-based keyboard navigation
- ✅ Visual position indicator
- ❌ **No thumb indicator** — web shows draggable thumb.
- ❌ **No mouse drag** — keyboard only.

---

## 18. FileInput

**Rendering**: `FileInputW.render` — shows `[Choose File]` label.

**Event handling**: Enter/Space → suspend terminal → OS file picker → resume → fire `onChange(path)`.

**Web parity**:
- ✅ Opens native file picker
- ✅ `accept` filter passed to OS
- ❌ **No selected filename display** — web shows chosen filename after selection.
- ❌ **No multiple file selection**.

---

## 19. Img

**Rendering**: `ImgW.render`:
1. If iTerm2/Kitty protocol available → encode and emit inline image
2. Else → render alt text in italic

**Web parity**:
- ✅ Image display (on supported terminals)
- ✅ Alt text fallback
- ✅ Aspect ratio preservation (via protocol params)
- ⚠️ Only works on iTerm2, Kitty, WezTerm, mintty. Other terminals get alt text only.

---

## 20. HiddenInput

**Rendering**: Not rendered (Void element with no visual).

**Web parity**: ✅ Correct — hidden inputs are invisible in web too.

---

## 21. Opt (inside Select)

**Rendering**: Not directly rendered — consumed by `PickerW` to build option list.

**Web parity**: ✅ Data-only element, correct.

---

## Style System Parity

| Feature | TUI | Web | Status |
|---------|-----|-----|--------|
| Color (fg/bg) | 24-bit RGB | Full CSS | ✅ |
| Bold/italic/underline/strikethrough | Bit flags | CSS properties | ✅ |
| Padding | All 4 sides | All 4 sides | ✅ |
| Margin | Parsed, not applied | Box model | ❌ |
| Border (style, color, per-side) | solid/dashed/dotted + per-side | Full CSS | ✅ |
| Border-radius | Corner characters (╭╮╰╯) | Pixel curves | ⚠️ |
| Box shadow | Offset + spread fill | Gaussian blur | ⚠️ |
| Flex direction | row/column | row/column/reverse | ⚠️ no reverse |
| Flex grow | Proportional distribution | Proportional | ✅ |
| Flex shrink | Stored, not applied | Proportional | ❌ |
| Flex wrap | Not supported | wrap/nowrap | ❌ |
| Gap | Between children | Between children | ✅ |
| Align-items | start/center/end/stretch | + baseline | ⚠️ |
| Justify-content | start/center/end/between/around/evenly | Full | ✅ |
| Width/height (px, em) | Converted to cells | Pixel/em | ✅ |
| Width/height (%) | Not resolved | Relative to parent | ❌ |
| Min/max width/height | Stored, not applied | Constrains layout | ❌ |
| Overflow hidden/scroll | Clip rect + scroll indicators | Scroll bars | ✅ |
| Opacity | Stored, not applied | Alpha compositing | ❌ |
| Gradients | Blended to single color | Per-pixel | ❌ |
| Filters (brightness, contrast, etc.) | Post-processing on cells | CSS filters | ✅ |
| Pseudo-states (:hover, :focus, :active, :disabled) | All applied | All applied | ✅ |
| Style inheritance (color, bold, etc.) | Parent→child propagation | Cascading | ✅ |
| Transitions/animations | Not supported | CSS transitions | ❌ |
| Z-index | Overlay flag only | Stacking context | ⚠️ |
| Position absolute/fixed | Not supported | Full | ❌ |

---

## Event System Parity

| Feature | TUI | Web | Status |
|---------|-----|-----|--------|
| onClick | ✅ | ✅ | ✅ |
| onKeyDown / onKeyUp | ✅ | ✅ | ✅ |
| onFocus / onBlur | ✅ | ✅ | ✅ |
| Tab navigation | ✅ with tabIndex sort | ✅ | ✅ |
| Mouse click / hit testing | ✅ | ✅ | ✅ |
| Mouse hover tracking | ✅ (triggers :hover styles) | ✅ | ✅ |
| Mouse scroll | Focused element only | Any scrollable | ⚠️ |
| Text paste | ✅ bracketed paste | ✅ | ✅ |
| Rich paste (Ctrl+V) | ✅ OS clipboard | ✅ | ✅ |
| Drag and drop | ❌ | ✅ | ❌ |
| Text selection | ❌ | ✅ | ❌ |
| Copy/cut (Ctrl+C/X) | ❌ | ✅ | ❌ |
| Context menu | ❌ | ✅ | ❌ |

---

## Priority Fixes (ordered by impact)

### High — breaks common layouts

1. **Flex shrink** — content overflows instead of shrinking. Any layout where children exceed container width/height will break.
2. **Margins** — stored but ignored. Layouts relying on margin for spacing between elements won't work.
3. **Min/max constraints** — stored but ignored. Can't constrain element sizes.
4. **Br ignored** — line breaks in text flow don't work.

### Medium — noticeable visual gaps

5. **Heading sizes** — H1–H6 all look identical (just bold). Add vertical padding: H1=2 cells top/bottom, H2=1, H3-H6=0.
6. **List markers** — Ul/Ol/Li have no bullets or numbers. Prepend `• ` for Li inside Ul, `N. ` for Li inside Ol.
7. **Th bold** — table headers look like regular cells. Apply bold to Th by default.
8. **Anchor underline** — links are invisible without user styling. Add underline + blue to anchor theme.
9. **Radio group exclusion** — multiple radios can be checked. Need group logic.
10. **Word wrapping** — text wraps mid-word. Break on word boundaries.

### Low — nice to have

11. **Percentage sizing** — needs parent dimension propagation.
12. **Flex wrap** — multi-line flex.
13. **Date/Time/Color pickers** — currently non-functional in TUI.
14. **Text selection** — Shift+Arrow, Ctrl+A.
15. **Gradients** — per-cell color interpolation.
16. **FileInput filename display** — show selected file after picking.
