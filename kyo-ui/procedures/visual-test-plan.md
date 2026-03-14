# Visual & Interaction Test Plan

Comprehensive tests to ensure every widget renders correctly and interacts properly. Each test verifies exact visual output, not just content presence.

Tests are organized by category. Each test describes: setup, action, assertion.

---

## 1. Cursor Behavior

### 1.1 Single cursor rule
- Render two inputs, focus neither → zero `█` in frame
- Render two inputs, tab to first → exactly one `█` in frame
- Render two inputs, tab to second → exactly one `█` in frame, on second input's row
- Render input + checkbox + button → tab to input → `█` only on input row
- Focus input, tab to button → `█` gone (buttons don't show text cursor)

### 1.2 Cursor position
- Empty input focused → `█` at position 0 within the input
- Input with "hello" focused → `█` at position 5 (after last char)
- Type "AB" → `█` at position 2
- Type "AB", ArrowLeft → `█` at position 1 (between A and B)
- Type "AB", Home → `█` at position 0
- Type "AB", Home, ArrowRight → `█` at position 1
- Password "abc" focused → `█` after third dot

### 1.3 Cursor movement across renders
- Focus input, type "A", verify cursor at 1. Type "B", verify cursor at 2. Backspace, verify cursor at 1.
- Tab from input1 to input2 → cursor disappears from input1 row, appears on input2 row
- Shift+Tab back → cursor returns to input1 row

---

## 2. Text Input Visual

### 2.1 Input box appearance
- Empty input with no focus → shows placeholder if set, otherwise empty area
- Empty input focused → shows cursor
- Input with value "hello" → value visible, no cursor (unfocused)
- Input with value "hello" focused → value visible with cursor after it
- Input disabled → value visible, visually dimmed, no cursor even if focused
- Input readonly → value visible, no cursor on typing

### 2.2 Input in context
- Label above input (column layout) → label on one row, input on next
- Label beside input (row layout) → label and input on same row
- Input inside bordered container → input text stays within borders
- Two inputs stacked → no visual overlap, each on its own row
- Input with very long value (50 chars in 20-col container) → truncated, not overflowing

### 2.3 Placeholder
- Empty input with placeholder → placeholder text visible
- Type one char → placeholder disappears, char visible
- Backspace to empty → placeholder reappears
- Placeholder style different from value (if theme supports dimmed)

---

## 3. Password Visual

### 3.1 Masking
- Value "secret" → shows "••••••", never "secret"
- Type "ab" → shows "••" with cursor after
- Backspace → shows "•" with cursor after
- Focused password → dots + cursor
- Unfocused password → dots only, no cursor

---

## 4. Select Dropdown

### 4.1 Collapsed state
- Shows selected value text + ▼ arrow
- Arrow visible at right side of value
- No options visible

### 4.2 Expanded state
- Click opens → all option texts visible below
- Options have readable text (not blank/black)
- Selected option visually distinct (if highlighting implemented)
- Escape closes → back to collapsed, no selection change
- ArrowDown highlights next option
- ArrowUp highlights previous option
- Enter selects highlighted option, closes dropdown
- Click on option selects it, closes dropdown

### 4.3 Edge cases
- Zero options → renders without crash, shows empty
- One option → shows it when expanded
- Many options (20) in small viewport (5 rows) → options clipped or scrolled, not overflowing
- Disabled select → click does nothing, no expand

---

## 5. Checkbox & Radio

### 5.1 Visual
- Unchecked checkbox: `[ ]`
- Checked checkbox: `[x]`
- Unchecked radio: `( )`
- Checked radio: `(•)`
- Disabled checkbox: same glyphs but visually dimmed
- Checkbox with label beside it → label on same row, proper spacing

### 5.2 Interaction
- Click toggles checkbox state and visual
- Double-click returns to original state
- Click on disabled checkbox → no change
- Tab reaches checkbox → visual focus indicator (if implemented)

---

## 6. Button Visual

### 6.1 Appearance
- Has border (┌─┐ / │ │ / └─┘) in default theme
- Text centered within border
- Disabled button → same border but visually dimmed
- Button with long text → text fits or wraps within border

### 6.2 Interaction
- Click fires onClick
- Space on focused button fires onClick
- Enter on focused button fires onClick (if not in form)
- Disabled button ignores click and keyboard

---

## 7. Form Submission

### 7.1 Basic
- Enter in text input inside form → fires form onSubmit
- Enter in textarea inside form → does NOT fire onSubmit (inserts newline)
- Enter in text input NOT inside form → does not fire onSubmit (no form)

### 7.2 Nested
- Form inside div inside another form → Enter fires innermost form's onSubmit
- Form with multiple inputs → Enter in any input fires the form's onSubmit
- Form with input + select + checkbox + button → Enter in input fires onSubmit

### 7.3 After submission
- All field values readable in onSubmit handler
- Can clear fields in onSubmit handler → fields visually empty after submit

---

## 8. Focus & Tab Order

### 8.1 Tab cycling
- Tab visits all focusable elements in document order
- Shift+Tab visits in reverse order
- Tab wraps from last to first
- Shift+Tab wraps from first to last
- Disabled elements skipped

### 8.2 Tab order matches visual order
- Column of inputs → tab goes top to bottom
- Row of buttons → tab goes left to right
- Two columns side by side → tab goes through left column top-to-bottom, then right column top-to-bottom
- Mixed: input, checkbox, select, button → tab visits in visual order

### 8.3 Click focus
- Click on input → focuses it
- Click on button → focuses it
- Click on label → does NOT focus (unless forId)
- Click on disabled element → does NOT focus
- Click outside all elements → no focus change

### 8.4 Focus visual
- Focused input looks different from unfocused (cursor visible, potentially different border)
- Only one element focused at a time
- Blur fires on previous element when focus moves

---

## 9. Layout Visual

### 9.1 Column stacking
- Div with children → children stack vertically
- Form with children → children stack vertically
- No overlap between stacked children

### 9.2 Row flow
- Div.style(Style.row) with children → children side by side
- Gap between children when gap style set
- Children don't overlap

### 9.3 Border containment
- Child content stays within parent border
- Nested borders render correctly (outer and inner both visible)
- Text inside bordered box truncates, doesn't overflow

### 9.4 Cross-axis sizing
- Text fills parent width in column layout (for text-align)
- Child node doesn't exceed parent width
- Percentage width respected (50% of parent)

---

## 10. Complex Multi-Widget Scenarios

### 10.1 Toggle visibility
- Checkbox checked → section visible. Unchecked → section hidden.
- Hidden section not in tab order
- Re-showing section restores its content
- Focus doesn't jump to hidden elements
- Sibling content unaffected by toggle

### 10.2 Shared SignalRef between widgets
- Two inputs bound to same ref → typing in one updates the other on re-render
- Display text (span) bound to same ref → updates as input changes

### 10.3 Tab order across complex layout
- Row of two column containers, each with 2 inputs and a button (6 focusables total)
- Tab visits all 6 in visual order: left-col input1, left-col input2, left-col button, right-col input1, right-col input2, right-col button
- Verify by recording focus order via onFocus callbacks

### 10.4 Nested form submission
- Outer div → inner form → input + button
- Enter in input fires inner form onSubmit
- Button click also fires inner form onSubmit (if button is inside form)

### 10.5 Form with all widget types
- Form with: text input, email input, password, select, checkbox, radio, textarea, button
- All widgets render correctly in initial state
- Tab through all in order
- Type in each text field → all others survive
- Toggle checkbox → all others survive
- Open/close select → all others survive
- Submit form → onSubmit fires with all values readable

### 10.6 Dynamic content after interaction
- Input with character counter: span shows "N/max" updating on each keystroke
- The span and input are siblings in a row → both visible after each keystroke
- Counter value matches typed text length

### 10.7 Multiple independent forms
- Two forms side by side, each with own inputs and submit
- Typing in form1 input doesn't affect form2 input
- Submitting form1 doesn't fire form2 onSubmit
- Focus in form1 doesn't blur form2 elements (they were never focused)

### 10.8 Dependent selects
- Country select + city select
- Changing country updates city options (via reactive signal)
- City dropdown shows new options after country change
- Previously selected city cleared

### 10.9 Form reset
- Fill all fields, click Reset button → all fields cleared
- Checkboxes unchecked, selects back to default
- Visual matches cleared state (no stale text visible)

### 10.10 Real-time filter
- Text input + list of items below
- Type "app" → only items containing "app" visible
- Clear input → all items back
- List updates on every keystroke (not just on Enter)

---

## 11. Theme & Styling

### 11.1 Default theme
- Text visible (not transparent on transparent)
- Borders visible (gray characters)
- Headings bold
- Hr renders as horizontal line

### 11.2 Element-specific styles
- H1: bold, with padding
- H2: bold
- Button: bordered with padding
- Hr: horizontal rule (─ characters)
- Br: line break (content below moves down)

### 11.3 User styles override theme
- Div with explicit color → that color used, not theme default
- Div with explicit border → that border used

---

## 12. Edge Cases

### 12.1 Zero-size viewport
- Render with 0 cols or 0 rows → no crash

### 12.2 Single-cell viewport
- Render with 1x1 → first character visible

### 12.3 Empty UI
- Render empty div → blank frame, no crash
- Render fragment with no children → blank frame

### 12.4 Unicode
- Input with emoji → renders (may take 2 columns)
- Input with CJK characters → renders (may take 2 columns)
- Input with combining characters → renders

### 12.5 Very long content
- 1000-character text in 10-col container → truncated
- 100 items in a list in 5-row viewport → clipped

### 12.6 Rapid interactions
- Type 20 characters rapidly → all 20 in the input value, all visible
- Tab through 10 elements → focus lands on last, all elements still visible

---

## Implementation Priority

**P0 — Blocks demo from working correctly:**
1. Cursor behavior (§1) — only focused input shows cursor
2. Select dropdown rendering (§4.2) — options visible, not black box
3. Focus visual (§8.4) — user can see which element is focused

**P1 — Core widget correctness:**
4. Text input visual (§2) — box/border, placeholder
5. Form submission nested (§7.2)
6. Tab order across containers (§8.2, §10.3)

**P2 — Complex scenarios:**
7. Toggle visibility (§10.1)
8. Form with all widget types (§10.5)
9. Shared SignalRef (§10.2)
10. Dynamic content (§10.6)

**P3 — Polish:**
11. Theme styling (§11)
12. Edge cases (§12)
13. Dependent selects (§10.8)
14. Real-time filter (§10.10)
