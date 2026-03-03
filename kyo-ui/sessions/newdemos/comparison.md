# Visual Comparison — New Demo UIs

## SignalCombinatorUI (`signals`)

### Section 1: Combined Signals: Name
- **Web**: Two inputs in a row with placeholders, green-bg derived display showing "Enter a name..."
- **JFX**: Same layout, inputs with visible borders, derived display matches
- **Verdict**: Match (platform font differences only)

### Section 2: Computed Value: Quantity × Price
- **Web**: Two groups (Quantity/Price) in a row. Blue buttons (-/+/-5/+5), values "1" and "$10". Yellow-bg total "Total: $10"
- **JFX**: Same layout, buttons match, values correct, total displays correctly
- **Verdict**: Match

### Section 3: Combined Filters
- **Web**: Two checkboxes (both checked) with labels. Below: 4 tags (Alpha, Beta, Gamma, Delta) in a row
- **JFX**: Two checkboxes visible with labels. **Tags (Alpha, Beta, Gamma, Delta) are MISSING** — the `items.foreach` with `UI.when` inside didn't render
- **Verdict**: **BUG** — foreach items with per-item UI.when not rendering on JFX initial load. Web shows items correctly.

### Section 4: Rapid Sequential Updates
- **Web**: Blue "Reset & Add 5" button, light-blue box showing "Quantity after rapid updates: 1"
- **JFX**: Same layout, button text matches, display shows "Quantity after rapid updates: 1"
- **Verdict**: Match

---

## RapidMutationUI (`rapid`)

### Section 1: Rapid Add/Remove
- **Web**: 5 buttons in a row (Add, Remove First, Remove Last, Clear All, Burst Add 5). Below: 3 tags (A, B, C) in a row. "Count: 3" text below
- **JFX**: 5 buttons match. **Tags (A, B, C) NOT visible** — only "Count: 3" shows. The `items.foreachKeyed(identity)` didn't render initial items
- **Verdict**: **BUG** — foreachKeyed initial items not rendering on JFX. Web renders correctly.

### Section 2: Toggle Visibility + Mutate
- **Web**: "Hide List" button + "Add (even if hidden)" button. Below: light-bg area but **no list items visible** despite visible=true and items=Chunk(A,B,C)
- **JFX**: Same — "Hide List" and "Add" buttons. Light bg area but **no list items**
- **Verdict**: **BUG on both platforms** — UI.when(visible) + foreachKeyed not rendering initial content. The foreachKeyed inside UI.when may not fire on initial render.

### Section 3: Operation Log
- **Web**: Empty log area with "Clear Log" button, compact
- **JFX**: ScrollPane with scrollbars (overflow:scroll rendering), "Clear Log" button
- **Verdict**: Match functionally. JFX shows scrollbars even when empty (platform difference)

---

## DeepNestingUI (`deepnest`)

### Section 1: Signal[UI] → foreach → per-item Signal
- **Web**: 4 buttons (Switch to Grid, Add Fruit, Add Veg, Increment Counter). **No category items rendered below** — the outerMode.map Signal[UI] containing categories.foreach didn't produce visible items
- **JFX**: Same — 4 buttons, **no category items**
- **Verdict**: **BUG on both platforms** — Signal[UI] containing foreach doesn't render initial items

### Section 2: UI.when Inside foreach
- **Web**: "Hide Details" button. Two items: Apple (with "Detail for Apple — expanded") and Banana (with detail). Yellow-bg cards
- **JFX**: "Hide Details" button but **no foreach items (Apple, Banana) visible**
- **Verdict**: **BUG on JFX** — foreachKeyed items not rendering. Web renders correctly.

### Section 3: Repeated Toggle Cycles
- **Web**: Toggle button, "Visible" status text. Purple-bg box with foreach content (should show "Fruits: 0 updates", "Vegetables: 0 updates")
- **JFX**: Toggle button, "Visible" status. Purple-bg bar visible but **no text content inside** — foreach inside UI.when didn't render
- **Verdict**: **BUG on JFX** — foreach inside UI.when not rendering initial content. Web shows partial content.

---

## SignalSwapUI (`swap`)

### Section 1: Swap Between Views
- **Web**: 3 purple buttons (Tasks, Notes, Tags), input + "Add to Current View" button. Swap counter "Swaps: 0". Purple-bg content area shows "Tasks" heading but **no task items** from foreachIndexed
- **JFX**: Same layout, 3 buttons, input, swap counter. Purple-bg content area — **no task items**
- **Verdict**: **BUG on both platforms** — Signal[UI] map with foreachIndexed inside doesn't render initial items

### Section 2: Rapid Swap Stress Test
- **Web**: Purple "Cycle: Tasks→Notes→Tags→Tasks" button. "Current view: tasks" text
- **JFX**: Same layout and text
- **Verdict**: Match

---

## GenericAttrUI (`attrs`)

### Section 1: Static Attributes
- **Web**: 3 styled divs with descriptive text about their data-* attributes. Green/yellow backgrounds
- **JFX**: Same 3 divs with matching text and backgrounds
- **Verdict**: Match

### Section 2: Reactive Attributes
- **Web**: "Cycle Attribute Value" button, "Current: data-initial" display, reactive-attr div, input + dynamic-title div
- **JFX**: Same layout, button text matches, input visible, all text content present
- **Verdict**: Match

### Section 3: Generic Event Handlers
- **Web**: "Click via .on('click')" button, pink-bg dblclick div, empty event log, "Clear Log" button
- **JFX**: Same layout, button and dblclick div present, log area empty, Clear Log button
- **Verdict**: Match

---

## ReactiveHrefUI (`rechref`)

### Section 1: Reactive Anchor href
- **Web**: 3 buttons (Example.com, GitHub, Scala). Orange link "Visit: Example.com". "Current href: https://example.com"
- **JFX**: 3 buttons. Link text present. Href display text present
- **Verdict**: Match (link styling may differ — platform difference)

### Section 2: Fragment as Multi-Root Pattern
- **Web**: Static fragment shows First/Second/Third in colors. Foreach fragment shows One/Two/Three with "detail" and hr separators. Signal[UI] fragment shows "Simple mode" content. 3 mode buttons below
- **JFX**: Static fragment renders correctly (First/Second/Third visible). "Fragment inside foreach" shows header but **no foreach items** (One/Two/Three missing). "Fragment inside Signal[UI]" shows Simple mode content with mode buttons.
- **Verdict**: **BUG on JFX** — foreach inside fragment section not rendering initial items. Static fragment and Signal[UI] fragment work correctly.

### Section 3: Fragment + Mutation
- **Web**: 3 buttons (Add Item, Remove Last, Clear). 3 orange tags (One, Two, Three). "3 items" count
- **JFX**: 3 buttons visible, "3 items" count text present, but **no tag items visible** — foreachKeyed not rendering initial items
- **Verdict**: **BUG on JFX** — foreachKeyed initial items not rendering (same root cause as rapid/deepnest)

---

## FormResetUI (`formreset`)

### Section 1: Contact Form
- **Web**: Name/Email inputs, Message textarea, Submit/Clear All buttons. Blue-bg preview showing "(empty)" for all fields
- **JFX**: Same layout with inputs and preview
- **Verdict**: Match

### Section 2: Settings Form
- **Web**: Username input, Theme select (Light), checkbox for notifications, Priority select (Medium). Save Settings/Reset to Defaults buttons. Yellow-bg preview
- **JFX**: Same layout
- **Verdict**: Match

### Section 3: Submission History
- **Web**: "No submissions yet." text. Clear History button
- **JFX**: Same text and button
- **Verdict**: Match
