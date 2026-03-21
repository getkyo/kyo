# Visual Test Plan

Comprehensive visual test plan for kyo-ui. One file per widget, each covering rendering, interaction, edge cases, containment, and cross-widget interactions. Every test asserts the exact full-frame output.

## How to Write a Visual Test

Visual tests assert **exact frame output** — the full rendered grid as a string. No character-level checks, no `contains`, no `indexOf`. The entire UI must match the expected string.

### Pattern: `assertRender` for static rendering (no interaction)

Uses `RenderToString.render` to produce the frame, then compares the entire grid against a triple-quoted expected string:

```scala
"border with content" in run {
    assertRender(
        UI.div.style(Style.border(1.px, gray).width(7.px).height(3.px))("hi"),
        7, 3
    )(
        """
        |┌─────┐
        |│hi   │
        |└─────┘
        """
    )
}
```

### Pattern: `Screen` + `assertFrame` for interaction tests

When tests involve dispatch (click, type, tab), use `Screen` which manages state across render-dispatch-render cycles:

```scala
"type AB then check frame" in run {
    val ref = SignalRef.Unsafe.init("")
    val s = screen(UI.input.value(ref.safe), 10, 1)
    for
        _ <- s.render
        _ <- s.tab
        _ <- s.typeChar('A')
        _ <- s.typeChar('B')
    yield
        s.assertFrame(
            """
            |AB
            """
        )
        assert(s.cursorCol == 2)
    end for
}
```

### Expected string format

Both `assertRender` and `Screen.assertFrame` process the expected string:
1. `stripMargin` — removes leading whitespace up to `|`
2. Strip leading `\n` — from the opening `"""`
3. Drop trailing whitespace-only line — from the closing `"""`
4. `padTo(cols, ' ')` — each line padded to viewport width

Empty lines (bare `|`) produce a row of spaces. No manual padding needed.

### IMPORTANT: Triple-quote format rules

ALWAYS use this exact format — no exceptions:

```scala
// CORRECT:
assertRender(ui, 10, 1)(
    """
    |hello
    """
)

// CORRECT — empty row:
assertRender(ui, 5, 3)(
    """
    |
    | X
    |
    """
)

// WRONG — do NOT put content on the same line as """:
assertRender(ui, 10, 1)("hello     ")

// WRONG — do NOT use .stripMargin manually:
assertRender(ui, 5, 3)("""┌───┐
    |│   │
    |└───┘""".stripMargin)
```

### Cursor assertions

Cursor is NOT in the grid (terminal-native). Assert separately:

```scala
yield
    s.assertFrame(
        """
        |hello
        """
    )
    assert(s.hasCursor)
    assert(s.cursorCol == 5)
```

### Viewport sizing

Pick the smallest viewport that fits. Keeps expected strings readable, catches overflow:
- Single widget: `10x1`
- Widget + label: `15x2`
- Form: `30x10`

---

## Cross-Cutting Scenarios

Every widget test file includes a "containment" section:

1. **Explicit width** — content truncated to `Style.width(N.px)` container
2. **Row sibling** — beside another widget in `Style.row`, no overlap
3. **Column sibling** — above/below sibling, no overlap
4. **Bordered container** — content inside border, not overlapping
5. **Small container** — 3x1 or 1x1, no crash
6. **Zero-width container** — `Style.width(0.px)`, no crash
7. **Long content** — truncated, not overflowing

---

## File 1: VisualTextInputTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualTextInputTest.scala`
**Widget:** `UI.input` (single-line text input)

### 1.1 Static rendering
- Empty input → blank row
- Input with value "hello" → "hello" visible
- Input with value containing spaces "a b c" → spaces preserved
- Input with value at exact viewport width "abcde" in 5-col → fills exactly
- Input with value exceeding viewport width → truncated

### 1.2 Placeholder
- Empty input with placeholder "Name" unfocused → shows "Name"
- Empty input with placeholder focused → placeholder still visible, cursor at 0
- Type one char → placeholder disappears, char visible
- Type char then backspace → placeholder reappears
- Placeholder longer than container → truncated
- Input with value AND placeholder → value shown, not placeholder

### 1.3 Focus and cursor — initial state
- Unfocused → no cursor
- Tab to focus → cursor appears
- Focus empty input → cursor at column 0
- Focus "hello" → cursor at column 5 (end)
- Focus "abc" in 10-col → cursor at 3
- Click at (0,0) on input → focuses, cursor at end
- Two inputs unfocused → zero cursors

### 1.4 Cursor movement
- Type "AB" → cursor at 2
- Type "AB", ArrowLeft → cursor at 1, frame still "AB"
- Type "AB", ArrowLeft, ArrowLeft → cursor at 0
- Type "AB", Home → cursor at 0
- Type "AB", End → cursor at 2
- Type "AB", Home, ArrowRight → cursor at 1
- ArrowLeft at position 0 → stays at 0
- ArrowRight at end of text → stays at end
- Home on empty → cursor stays at 0
- End on empty → cursor stays at 0

### 1.5 Typing — appending
- Type "A" → frame "A", cursor 1
- Type "AB" → frame "AB", cursor 2
- Type "ABC" → frame "ABC", cursor 3
- Type space → space visible in frame
- Type multiple then verify ref value matches frame

### 1.6 Typing — insertion at cursor
- Type "AC", ArrowLeft, type "B" → frame "ABC", cursor at 2
- Type "AD", Home, type "B" → frame "BAD", cursor at 1
- Type "AE", Home, ArrowRight, type "B" → frame "ABE", cursor at 2
- Insert at beginning of non-empty → prepended correctly
- Insert at middle multiple times → all inserted in order

### 1.7 Backspace
- Type "AB", backspace → frame "A", cursor 1
- Type "A", backspace → frame empty, cursor 0
- Backspace on empty → no change, cursor stays 0
- Type "ABC", ArrowLeft, backspace → frame "AC", cursor 1 (deletes B)
- Type "ABC", Home, backspace → no change (nothing before cursor)
- Type "ABC", Home, ArrowRight, backspace → frame "BC", cursor 0

### 1.8 Delete key
- Type "ABC", Home, Delete → frame "BC", cursor 0
- Type "ABC", Home, ArrowRight, Delete → frame "AC", cursor 1
- Delete at end → no change
- Delete on empty → no change

### 1.9 Cursor across multiple renders
- Type "A" (verify cursor=1), type "B" (verify cursor=2), backspace (verify cursor=1)
- Tab to input1 (verify cursor row), tab to input2 (verify cursor moved to new row)
- Shift+tab back → cursor returns to first input's row

### 1.10 Disabled
- Disabled with value "hello" → frame shows "hello", no cursor
- Tab to disabled input → skipped, next focusable gets focus
- Click on disabled input → not focused
- Type on disabled input → no change to frame or ref
- Disabled input in focusable list → absent
- Two inputs (disabled, enabled) → tab goes to enabled

### 1.11 ReadOnly
- ReadOnly with value "AB" focused → frame "AB", cursor at end
- Type on readonly → frame unchanged, ref unchanged
- ArrowLeft on readonly → cursor moves (movement allowed)
- Home on readonly → cursor moves to 0
- Backspace on readonly → no change
- Delete on readonly → no change

### 1.12 Event handlers
- onInput: type "A" → handler receives "A"
- onInput: type "AB" then backspace → handler receives "A"
- onChange: type "A" → handler receives "A"
- Both onInput and onChange: type "A" → both fire
- onFocus: tab to input → handler fires
- onBlur: tab away from input → handler fires
- onKeyDown: press ArrowLeft → handler receives key event
- onClick: click on input → handler fires

### 1.13 Multiple inputs interaction
- Two inputs stacked → frame shows both on separate rows
- Type in first, tab, type in second → both values visible, independent
- Two inputs sharing same ref → both show same value after typing in either
- Three inputs → tab cycles through all three in order

### 1.14 Containment
- Long value in `width(8.px)` container → truncated at 8
- Input beside label in row → both visible, no overlap
- Input below label in column → both on separate rows
- Input inside bordered container → text inside border
- Two inputs stacked → no overlap
- Zero-width container → no crash, blank
- Input in `width(3.px)` → "abc" fits, "abcde" truncated
- Input in `width(50.pct)` of 20-col → ~10 cols
- Empty input in row layout → should have usable width (KNOWN BUG: zero width)

### 1.15 Other TextInput variants
- `UI.email.value("a@b")` → renders "a@b" (same as input)
- `UI.tel.value("123")` → renders "123"
- `UI.search.value("query")` → renders "query"
- `UI.urlInput.value("http://x")` → renders "http://x"
- `UI.numberInput.value("42")` → renders "42"

---

## File 2: VisualPasswordTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualPasswordTest.scala`
**Widget:** `UI.password`

### 2.1 Masking
- Value "abc" unfocused → frame shows "•••", never "abc"
- Value "secret" → six dots
- Empty value → blank
- Value "a" → one dot
- Dots use "•" character (U+2022)
- Plaintext NEVER appears in frame for any value

### 2.2 Typing and masking
- Type "A" → frame shows "•", cursor 1
- Type "AB" → frame shows "••", cursor 2
- Type "ABC" → frame shows "•••", cursor 3
- Backspace → dot count decreases by 1
- Type "AB", backspace → frame "•", cursor 1
- All backspace to empty → blank frame

### 2.3 Cursor position
- Focused "abc" → cursor at 3 (after dots)
- Type "A", ArrowLeft → cursor at 0, frame still "•"
- Home → cursor at 0
- End → cursor at dot count

### 2.4 Placeholder
- Empty password with placeholder "Enter password" unfocused → shows "Enter password" (NOT masked)
- Empty with placeholder focused → placeholder visible, cursor at 0
- Type one char → placeholder disappears, one dot
- Backspace to empty → placeholder reappears

### 2.5 Disabled
- Disabled with value → dots visible, no cursor
- Tab to disabled password → skipped
- Type on disabled → no change

### 2.6 Event handlers
- onInput fires with actual plaintext value (not dots)
- onChange fires with actual plaintext value

### 2.7 Containment
- Password in narrow container → dots truncated
- Password beside label in row → no overlap
- Long password (20 chars) in 10-col container → 10 dots visible

---

## File 3: VisualTextareaTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualTextareaTest.scala`
**Widget:** `UI.textarea`

### 3.1 Static rendering
- Empty textarea → blank
- Textarea with value "hello" → "hello" on first row

### 3.2 Enter inserts newline
- Type "A", Enter, type "B" → "A" on row 0, "B" on row 1
- Multiple Enter → multiple blank rows
- Enter does NOT fire form onSubmit (even inside form)

### 3.3 Multi-line display
- Value "line1\nline2" → two rows
- Value with three lines → three rows
- Long single line → wraps or truncates within width

### 3.4 Cursor in multi-line
- Type "A", Enter, type "B" → cursor on row 1 at column 1
- Home → cursor at column 0 (same row or first row depending on impl)

### 3.5 Disabled and readonly
- Same behavior as input: disabled blocks all, readonly blocks editing but allows cursor

### 3.6 Containment
- Textarea in bounded height container → content clipped
- Textarea in 10x3 viewport with 5 lines → only 3 visible

---

## File 4: VisualSelectTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualSelectTest.scala`
**Widget:** `UI.select` with `UI.option`

### 4.1 Collapsed rendering
- Select with value "b", options (a=Alpha, b=Beta) → shows "Beta ▼"
- Select with non-matching value "x" → shows "x ▼"
- Select with empty value → shows " ▼"
- Select with no options → shows " ▼", no crash

### 4.2 Expand/collapse
- Tab to select, then click → expanded (popup visible)
- Escape → collapsed, selection unchanged
- Enter on highlighted → collapsed, selection changed
- Click outside select → should collapse (if implemented)

### 4.3 Expanded popup content
- Three options (A, B, C) → popup shows all three texts on separate lines
- Popup text is readable (not black-on-black) — KNOWN BUG
- Popup uses overlay (Popup tag), doesn't push siblings down
- Popup positioned below the select row

### 4.4 Keyboard navigation
- ArrowDown from first → highlight moves to second
- ArrowDown from last → stays on last
- ArrowUp from second → highlight moves to first
- ArrowUp from first → stays on first
- ArrowDown, ArrowDown, Enter → selects third option
- Enter selects current highlight → onChange fires, display updates

### 4.5 Selection persistence
- Select option B → display shows "Beta ▼"
- Re-expand → previously selected still indicated
- onChange handler receives correct value string

### 4.6 Disabled
- Disabled select → shows value, click does nothing
- Disabled select → tab skips it
- Disabled select → keyboard ignored

### 4.7 SignalRef binding
- Select with `value(ref)` → reflects ref value
- Selecting option updates ref

### 4.8 Edge cases
- One option only → shows it when expanded
- 20 options in 5-row viewport → renders without crash
- Option text longer than container → truncated
- Rapidly opening/closing → no visual corruption

### 4.9 Containment
- Select in narrow container → text and arrow truncated
- Collapsed select beside other widgets in row → no overlap
- Popup doesn't overflow viewport width
- Select in bordered container → popup renders outside border (overlay)

---

## File 5: VisualCheckboxTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualCheckboxTest.scala`
**Widget:** `UI.checkbox`

### 5.1 Static rendering
- Unchecked → "[ ]"
- Checked → "[x]"
- Default (no checked attr) → "[ ]"
- Exact frame: checkbox alone in 5x1 → "[ ]  "

### 5.2 Signal binding
- `checked(signal)` where signal = true → "[x]"
- Signal changes to false → "[ ]"

### 5.3 Toggle via click
- Click unchecked → becomes "[x]"
- Click checked → becomes "[ ]"
- Double click → back to original
- Triple click → opposite of original
- onChange fires with new value on each click

### 5.4 Toggle via keyboard
- Tab to checkbox → focused
- Space → toggles
- Space again → toggles back
- Enter does NOT toggle (Enter is for forms)

### 5.5 Disabled
- Disabled checked → shows "[x]", click does nothing
- Disabled unchecked → shows "[ ]", click does nothing
- Tab skips disabled checkbox
- Space on disabled → no change

### 5.6 With label
- `UI.div.style(Style.row)(UI.checkbox, UI.span(" Accept"))` → "[ ] Accept"
- Click on checkbox toggles it, label doesn't toggle
- Label with forId + checkbox with id → label click focuses checkbox

### 5.7 Multiple checkboxes
- Three checkboxes stacked → "[ ]" on three rows
- Toggle middle one → first and third unchanged
- Independent state per checkbox

### 5.8 Containment
- Checkbox in 3-col container → "[ ]" fits exactly
- Checkbox in 2-col container → truncated, no crash
- Checkbox in 1-col container → truncated, no crash
- Checkbox beside label in row → no overlap
- Multiple checkboxes in column → no overlap

---

## File 6: VisualRadioTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualRadioTest.scala`
**Widget:** `UI.radio`

### 6.1 Static rendering
- Unchecked → "( )"
- Checked → "(•)"
- Default → "( )"

### 6.2 Toggle
- Click unchecked → becomes "(•)"
- Click checked → becomes "( )" (toggle behavior)
- onChange fires

### 6.3 Keyboard
- Space on focused radio → toggles

### 6.4 Disabled
- Disabled → click/space no effect
- Tab skips

### 6.5 With label
- Radio beside label text → "( ) Option A"

### 6.6 Multiple radios
- Three radios stacked → "( )" on three rows
- Toggle is independent per radio (no radio group behavior yet)

### 6.7 Containment
- Radio in 3-col → "( )" fits
- Radio in 2-col → truncated
- Multiple in column → no overlap

---

## File 7: VisualButtonTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualButtonTest.scala`
**Widget:** `UI.button`

### 7.1 Rendering — Default theme
- Button "OK" → bordered box with "OK" inside
- Exact frame: `┌──┐` / `│OK│` / `└──┘` (with padding from theme)
- Button with padding in theme → text has space around it
- Empty button → border only, no crash
- Button "X" → minimal border

### 7.2 Rendering — other themes
- Plain theme → no border, just text "OK"
- Minimal theme → no border, just text

### 7.3 Click handler
- Click fires onClick once
- Click twice → fires twice
- onClick handler receives no arguments (Unit)

### 7.4 Keyboard
- Tab to button → focused
- Space on focused → fires onClick
- Enter on focused → does NOT fire onClick (Enter is for form submit)

### 7.5 Focus
- Focused button has no text cursor (hasCursor = false)
- Tab away → blur handler fires
- Tab to → focus handler fires
- Only one element focused at a time

### 7.6 Disabled
- Disabled button renders text and border
- Click → no effect
- Space → no effect
- Tab skips disabled
- Click doesn't focus disabled

### 7.7 Long text
- Button "Submit Application" in 12-col → text truncated within border
- Button text shorter than border → text left-aligned within border

### 7.8 Containment
- Button beside input in row → no overlap
- Button in narrow container → truncated
- Multiple buttons stacked → no overlap
- Multiple buttons in row → side by side
- Button in bordered container → button border inside container border

---

## File 8: VisualFormTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualFormTest.scala`
**Widget:** `UI.form`

### 8.1 Rendering
- Form with children renders as column container (children stacked)
- Form doesn't add visual decoration itself (no border)
- Form with label + input + button → all three visible in correct order

### 8.2 Submit via Enter in text input
- Input inside form → Enter fires onSubmit
- Two inputs in form → Enter in either fires onSubmit
- onSubmit fires exactly once per Enter

### 8.3 Textarea does NOT submit
- Textarea inside form → Enter inserts newline, does NOT fire onSubmit

### 8.4 No form → no submit
- Input NOT inside form → Enter does nothing

### 8.5 Nested forms
- Inner form + outer form → Enter in inner input fires inner onSubmit only
- Input in outer form (outside inner) → Enter fires outer onSubmit
- Inner and outer each fire independently

### 8.6 Field values at submit time
- Type "John" in name, "john@x" in email, Enter → onSubmit reads refs correctly
- Values are current (not stale from previous render)

### 8.7 Post-submit
- onSubmit clears refs → next render shows empty inputs
- Placeholder reappears after clear
- Can submit again after clear (not stuck)

### 8.8 Form with mixed widgets
- Form with input + select + checkbox + button → Enter in input fires onSubmit
- Click on button inside form → fires button onClick, NOT form onSubmit
- Space on focused button inside form → fires button onClick, NOT form onSubmit

### 8.9 Containment
- Form in bounded container → children within bounds
- Form with many fields → stacked vertically, no overlap

---

## File 9: VisualFocusTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualFocusTest.scala`
**Cross-widget focus/tab system**

### 9.1 Tab cycling
- Single input → tab focuses it, shift+tab focuses it
- Two inputs → tab visits first then second
- Three inputs → tab visits 1→2→3→1 (wraps)
- Shift+tab → 3→2→1→3 (wraps reverse)
- No focusable elements → tab does nothing

### 9.2 Tab skips
- Disabled input skipped by tab
- Hidden input not in tab order
- tabIndex(-1) skipped by tab
- Div, Span, Label, H1 not in tab order
- Only Focusable elements (input, button, checkbox, radio, select, anchor) in tab order

### 9.3 Tab order = document order
- Column of 3 inputs → tab 1→2→3
- Row of 3 buttons → tab left→middle→right
- Mixed: input, checkbox, select, button in column → tab visits in that order
- Nested: div(div(input1), div(input2)) → tab 1→2 (depth-first)

### 9.4 Click focus
- Click on input → focuses it (cursor appears)
- Click on button → focuses it
- Click on checkbox → focuses it (and toggles)
- Click on select → focuses it
- Click on disabled → does NOT focus
- Click on div → does NOT change focus
- Click on label without forId → does NOT change focus
- Click on label with forId → focuses target element
- Click elsewhere after focusing → focus stays (no blur on empty click)

### 9.5 Focus indicators
- Focused input → hasCursor = true
- Unfocused input → hasCursor = false
- Focused button → hasCursor = false (buttons don't have text cursor)
- Focused checkbox → hasCursor = false
- Only one cursor visible across entire UI at any time

### 9.6 Focus/blur event handlers
- Tab to input → onFocus fires
- Tab away from input → onBlur fires
- Click on input2 while input1 focused → input1 onBlur fires, input2 onFocus fires
- Order: blur on old THEN focus on new

### 9.7 Focus across containers
- Input inside div inside div → still focusable
- Input inside form → still focusable
- Focus order correct when inputs are in different nested containers
- Row layout: focus order matches left-to-right visual order

### 9.8 Focus + interaction
- Focus input, type "A" → input receives keystroke
- Tab to button, Space → button onClick fires
- Tab to checkbox, Space → checkbox toggles
- Focus select, ArrowDown → select navigates

---

## File 10: VisualLayoutTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualLayoutTest.scala`
**Div, Span, row/column, flex, padding, border, overflow**

### 10.1 Column layout (default)
- `div("A", "B")` → "A" and "B" on separate rows (div wraps text in separate children? or single text?)
- Two child divs with h=1 → stacked vertically
- Three child divs → three rows
- Gap: `gap(1.px)` between children → 1 blank row between
- Gap: `gap(2.px)` → 2 blank rows between

### 10.2 Row layout
- `div.style(Style.row)(span("A"), span("B"))` → "A" and "B" side by side
- With explicit widths → positioned correctly
- Gap: `gap(2.px)` → 2 blank cols between
- Row children don't overflow parent width

### 10.3 Width
- `width(10.px)` → content area 10 cols
- `width(50.pct)` of 20-col parent → 10 cols
- `minWidth(5.px)` → at least 5 cols even in 3-col parent
- `maxWidth(5.px)` → at most 5 cols even in 20-col parent
- `width(0.px)` → no content visible

### 10.4 Height
- `height(3.px)` → exactly 3 rows
- `minHeight(2.px)` → at least 2 rows
- `maxHeight(3.px)` with 5 rows of content → clipped at 3

### 10.5 Flex grow
- Child with `flexGrow(1)` in row → fills remaining space
- Two children `flexGrow(1)` each → split equally
- `flexGrow(2)` beside `flexGrow(1)` → 2:1 ratio
- `flexGrow(0)` (default) → intrinsic width only

### 10.6 Flex shrink
- Two 10-col children in 15-col parent → shrink proportionally
- `flexShrink(0)` → doesn't shrink below intrinsic
- Default shrink → proportional reduction

### 10.7 Justify
- `justify(start)` → children at start (default)
- `justify(center)` → children centered
- `justify(end)` → children at end
- `justify(spaceBetween)` → first at start, last at end
- `justify(spaceAround)` → equal space around each
- `justify(spaceEvenly)` → equal space between and at edges

### 10.8 Align
- `align(start)` → cross-axis start (default)
- `align(center)` → cross-axis center
- `align(end)` → cross-axis end
- `align(stretch)` → fills cross-axis

### 10.9 Padding
- `padding(1.px)` → content inset by 1 on all sides
- `padding(1.px, 2.px)` → top/bottom 1, left/right 2
- `padding(1.px, 2.px, 3.px, 4.px)` → asymmetric
- Padding + content → text starts after padding
- Padding + border → content inside both

### 10.10 Margin
- `margin(1.px)` → element offset from siblings
- Margin between two divs in column → gap between them
- Margin between two spans in row → gap between them

### 10.11 Border
- Solid border `border(1.px, gray)` → ┌─┐│ │└─┘
- Rounded border `rounded(1.px)` → ╭╮╰╯
- Dashed border → ┄ characters
- Dotted border → · characters
- Border with content → text inside
- Border + padding → text inside padding inside border
- Left border only → │ on left side
- Top + bottom border only → ─ top and bottom

### 10.12 Overflow
- `overflow(hidden)` clips tall content
- `overflow(hidden)` clips wide content in row
- Default (visible) → content at position (viewport clips)
- `scrollTop(2)` → content shifted up by 2

### 10.13 Nesting
- div > div > "text" → text visible
- Nested borders → inner inside outer's content
- Row > column > row → correct at each level
- Parent padding + child border → properly nested
- 5 levels deep → no crash, content visible

### 10.14 Hidden
- `hidden(true)` → not rendered, no space taken
- `hidden(false)` → visible
- Hidden div with children → children also hidden
- Sibling after hidden element → takes hidden's space

### 10.15 Text properties
- `textAlign(center)` → text centered in available width
- `textAlign(right)` → text right-aligned
- `textTransform(uppercase)` → "hello" → "HELLO"
- `textTransform(lowercase)` → "HELLO" → "hello"
- `textWrap(noWrap)` → long text not wrapped
- `textOverflow(ellipsis)` → long text truncated with "…"
- `letterSpacing(1.px)` → gaps between characters
- `lineHeight(2)` → double-spaced lines

### 10.16 Edge cases
- Empty div → no content, no crash
- Viewport 1x1 → no crash
- Viewport 200x50 → correct rendering
- 10 levels of nesting → no crash
- All content in one row exceeding viewport → clipped

---

## File 11: VisualTableTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualTableTest.scala`
**Widget:** `UI.table`, `UI.tr`, `UI.td`, `UI.th`

### 11.1 Basic table
- 2x2 table with "A","B","C","D" → grid layout
- Columns aligned vertically
- Row 1 above row 2

### 11.2 Column width
- Column widths from widest cell content
- Short cell in wide column → padded
- All cells same width → equal columns

### 11.3 Colspan
- `td.colspan(2)` → cell spans two columns
- Colspan cell width = sum of spanned column widths

### 11.4 Table header
- `th` cells → rendered (same as td visually in TUI)
- Header row + data rows → aligned

### 11.5 Table fills width
- Extra width distributed to columns
- Table in 30-col viewport with 2 narrow columns → columns expanded

### 11.6 Edge cases
- Empty table → no crash
- Single cell table → renders
- Table with one column → column fills width
- Table with many columns in narrow viewport → squished

### 11.7 Containment
- Table in bounded container → cells within bounds
- Table content doesn't overflow container

---

## File 12: VisualReactiveTest

**File:** `kyo-ui/shared/src/test/scala/kyo/internal/tui/pipeline/VisualReactiveTest.scala`
**Signal bindings: `ref.render`, `foreachKeyed`, reactive hidden/style**

### 12.1 ref.render — text display
- `ref.render(v => span(s"Echo: $v"))` with ref="" → "Echo: "
- Type "AB" → "Echo: AB" (after signal settlement)
- Backspace → "Echo: A"

### 12.2 ref.render — counter
- Input + counter display → "0/10" initially
- Type one char → "1/10"
- Type three chars → "3/10"

### 12.3 ref.render — filtered list
- Input + filtered list from ref → all items initially
- Type filter → only matching items
- Clear → all items back

### 12.4 foreachKeyed — list rendering
- Signal of 3 items → 3 rows
- Add item → 4 rows
- Remove item → 2 rows
- Items rendered in order

### 12.5 Reactive hidden
- `hidden(signal)` true → not visible
- Signal changes to false → element appears
- Siblings shift when element hidden/shown

### 12.6 Reactive style
- `style(signal)` → visual updates when signal changes
- Background color toggle via signal

### 12.7 Shared refs — two-way binding
- Two inputs bound to same ref → type in first, second updates
- Type in second → first updates
- Both always show same value

### 12.8 Reactive + interaction
- Input types into ref → derived display updates → verify full frame
- Select changes value → derived display updates
- Checkbox toggles → derived display reflects

### 12.9 Settlement
- Derived signal (ref.render) updates within same dispatch cycle
- No stale values visible in the frame
- Counter display consistent with input value

### 12.10 Containment
- Reactive content growing (more text) → stays within bounds
- Reactive list growing (more items) → within container height
- Reactive content shrinking → siblings take freed space

---

## Known Bugs to Reproduce

Tests should REPRODUCE these bugs (fail), not work around them:

1. **Select popup black box** — popup has `Style.empty`, renders as blank/black area. Test: expanded select should show readable option text.
2. **Select popup positioning** — hover expands a box downward. Test: popup should overlay, not push content.
3. **Empty input zero width in row** — empty `UI.input` in row gets zero intrinsic width. Test: empty input should be clickable.
4. **Readonly cursor movement** — ArrowLeft on readonly input may not work. Test: cursor should move on readonly.
5. **Zero-width container** — may crash or produce garbage. Test: should degrade gracefully.

---

## Implementation Notes

- Each file: `class Visual{Widget}Test extends Test`
- Use `import AllowUnsafe.embrace.danger` at test class level
- Static tests: `assertRender(ui, cols, rows)(expectedString)` — exact full-frame
- Interaction tests: `Screen` + `s.assertFrame(expectedString)` — exact full-frame after dispatch
- **Never use `contains`, `indexOf`, or character-level checks** — always assert full frame
- Cursor: `s.hasCursor`, `s.cursorCol`, `s.cursorRow` — separate from frame assertion
- Expected strings: triple-quotes with `|` prefix, `"""` on its own line
- Each line padded to `cols` by framework — no manual padding
- Settlement: `Screen.dispatch` includes 5ms sleep for signal propagation
- Each test self-contained — no shared mutable state
- Smallest viewport that fits — readable strings, catches overflow
- Failing tests that expose bugs are GOOD — do not change tests to make them pass
