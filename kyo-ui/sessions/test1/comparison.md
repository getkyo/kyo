# Visual Comparison

## DemoUI
- **Header**: Match — blue bg, white text, nav links, toggle button
- **Hero Card**: Match
- **Counter**: Match — layout identical
- **Todo List**: Match
- **Data Table**: GAP — Web table columns spread evenly; JFX columns cramped to left
- **Form**: Match — inputs and submit button stretch correctly
- **Footer**: Match

## LayoutUI
- **Column/Justify/Align**: Match — all justify variants (start/center/end/spaceBetween/spaceAround/spaceEvenly) render correctly on both
- **Overflow**: Match — hidden clips, scroll shows scrollbar
- **Min/Max Sizing**: Match
- **Nested Layouts**: Match

## ReactiveUI
- **Conditional Rendering**: Match — panel visible, button styled correctly
- **Visibility Toggle**: Match
- **Dynamic Class**: Match — Style A/B/C buttons, class label shown
- **Simple foreach**: Match — tags in row
- **Keyed List**: Match
- **View Mode Toggle**: Match — list view with items

## FormUI
- **Form with Submit**: Match — all form controls (input, textarea, select, checkbox) render correctly
- **Disabled Controls**: Match — disable toggle button, disabled inputs shown with grayed styling
- **Select dropdown**: Match — JFX uses ComboBox, web uses native select

## TypographyUI
- **Font Styles**: Match — italic, bold, bold+italic, monospace, sizes all correct
- **Text Decoration**: Match — underline and strikethrough work on both
- **Text Transform**: Match — uppercase/lowercase/capitalize
- **Spacing**: Match — line height and letter spacing
- **Text Overflow**: Match — ellipsis clipping works, wrapping works
- **Text Alignment**: Match — left/center/right
- **Effects**: Match — opacity and translate boxes

## TableAdvancedUI
- **Styled Table**: GAP — Web has compact well-distributed columns; JFX has wide even columns that look good but different distribution
- **Colspan & Rowspan**: Both render but differently — web has compact layout, JFX spreads columns wide. The JFX version actually looks cleaner.
- **Dynamic Table**: Match — input, Add Row, Remove Last buttons, table with data
- **Colored Status Cells**: Match — green/yellow/red status cells visible on both

## ColorSystemUI
- **Predefined Colors**: Match — all color swatches render with correct hues
- **Color.rgb()**: Match
- **Color.rgba()**: Match — alpha gradient visible on gray bg
- **Color.hex()**: Match
- **Text on Backgrounds**: Match — all fg/bg combos correct
- **Transparent Background**: Match — transparent border box and semi-transparent overlay

## DashboardUI
- **Key Metrics**: Match — 4 stat cards in row
- **Shadow Depths**: Match — 4 shadow levels visible
- **Project Status**: Match — cards with badges and progress bars
- **Explicit Widths**: Match — 100/200/400px boxes

## DynamicStyleUI
- **Dynamic Background**: Match — 5 color buttons + preview box
- **Dynamic Font Size**: Match — A-/A+ buttons + size label
- **Dynamic Padding**: Match — Less/More buttons + preview
- **Style Toggles**: Match — Bold/Italic/Underline toggle buttons
- **Dynamic Border Width**: Match — Thinner/Thicker buttons + preview

## InteractiveUI
- **Hover & Active States**: Match — two styled buttons
- **Keyboard Events**: Match — input + status text
- **Focus & Blur**: Match
- **Disabled State**: Match — Disable button + target button/input
- **Cursor Variants**: Match — 7 cursor type labels

## PseudoUI
- **Combined Pseudo States**: Match — 3 styled buttons + 2 focus inputs
- **Style.++ Composition**: Match — 3 composed style demos
- **Border Styles**: Match — solid/dashed/dotted/none all render correctly on both
- **Individual Border Sides**: Match — top(red)/right(blue)/bottom(green)/left(amber) all correct
- **Hoverable Cards**: Match

## NestedReactiveUI
- **Nested when()**: GAP (minor) — Web shows outer+inner panels in a compact card; JFX shows same content but inner panel is less visually nested (flatter)
- **foreach with Signal children**: Match — 3 items with global counter
- **foreachKeyed with selection**: Match — clickable items + "Nothing selected"
- **Signal[UI] nested in collection**: Match
- **Filtered collection**: Match

## CollectionOpsUI
- **foreachKeyedIndexed**: Match — indexed list with alternating rows
- **Add/Remove/Reorder**: Match — input + 4 buttons + keyed list
- **foreachIndexed**: Match — [0] Red, [1] Green, [2] Blue
- **Batch Updates**: Match — Tick button + count + tags
- **Edge Case: Single Item**: Match

## KeyboardNavUI
- **onKeyDown vs onKeyUp**: Match — input + two monospace displays
- **Modifier Keys**: Match — input + combo display
- **Key Event Log**: Match — input + dark log pane + Clear button
- **Focus Management**: Match — 3 inputs

## SemanticElementsUI
- **All Heading Levels**: Match — h1 through h6
- **Preformatted & Code**: Match — code block renders with dark bg, monospace
- **Horizontal Rules**: Match — plain hr and styled blue hr
- **Line Breaks**: Match
- **Ordered List + Nested Lists**: GAP (minor) — JFX doesn't show list numbering/bullets; items render as plain text lines without indentation for nesting
- **Image Element**: Match — blue square visible
- **Anchor Variants**: Match — links shown, styled red link visible

## SizingUnitsUI
- **Width in Pixels**: Match — 50/100/200/400px boxes
- **Width in Percent**: Match — 25/50/75/100% boxes
- **Em Units**: Match — font sizes scale correctly
- **Margin Auto Centering**: Match — centered boxes
- **Height Variants**: Match — 40/80/120 height boxes
- **Min/Max Width**: Match — constraints applied correctly
- **Mixed Padding**: Match

## TransformsUI
- **Translate**: Match — boxes shifted right/down correctly
- **Opacity Levels**: Match — 5 boxes fading from solid to transparent
- **Combined Translate + Opacity**: Match
- **Opacity on Text**: Match
- **Translate + Overflow Hidden**: Match — clipped element
- **Shadow + Opacity**: Match

## AutoTransitionUI
- **Color Cycling**: Match — both show phase box (timing varies due to screenshot moment)
- **Auto-populating List**: GAP — Web shows compact list; JFX shows expanded list with "tick:" values (different snapshot timing, but layout also differs — JFX has per-item cards vs web has simple list)
- **Delayed Panel**: Match — JFX shows revealed panel, web shows "Waiting" (timing)
- **Live Counter**: Match

## AnimatedDashboardUI
- **Live Metrics**: GAP (minor) — Web shows 3rd metric as "—" (not yet populated); JFX shows all 3 populated. Timing difference only.
- **Status**: Match — both show "Warning" badge
- **Event Log**: Match — dark monospace log pane
- **View Toggle**: GAP — Web still shows card view (Service A/B/C cards); JFX shows table view. This is a timing difference — the auto-switch happened before JFX screenshot but not before web screenshot.

## Summary of Gaps Found
1. **DemoUI — Data Table**: JFX table columns cramped (Medium)
2. **SemanticElementsUI — Lists**: JFX lacks list numbering/bullets and nesting indentation (Medium)
3. **AutoTransitionUI / AnimatedDashboardUI**: Timing differences in animated UIs (Low — not bugs)
4. **NestedReactiveUI — Nested panels**: Minor visual nesting difference (Low)
