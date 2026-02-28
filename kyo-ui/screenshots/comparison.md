# Screenshot Comparison Results

## DemoUI

### Section 1: Header
**Match.** Blue bg, white h1 "Kyo UI Demo", nav links, Toggle Theme button. No gaps.

### Section 2: Hero
**Match.** White card, bold h2, paragraph text, vertical layout, padding. No gaps.

### Section 3: Counter
**Match.** Row with -/0/+ buttons, vertically centered, gap. No gaps.

### Section 4: Todo List
**Match.** Input + Add button in a row. No gaps.

### Section 5: Data Table
**Match.** 3-column table with bold headers, 3 data rows. No gaps.

### Section 6: Form Example
**GAP — Layout direction (High):**
- Web: Vertical stack — "Name" label, full-width input, "Email" label, full-width input, full-width Submit button. Each on its own line.
- JavaFX: Horizontal row — "Name" label, narrow input, "Email" label, narrow input, small Submit button, all on one line.
- Classification: Layout semantic gap — label/input/button are block-level in HTML but render inline in JavaFX's section (card) container.

### Section 7: Footer
**GAP — Text alignment (Medium):**
- Web: "Built with Kyo UI" centered horizontally, gray text.
- JavaFX: "Built with Kyo UI" left-aligned, darker text.
- Classification: Style bug (textAlign not applied) + Platform difference (text color).

## InteractiveUI

### Section 1: Header
**Match.** Purple bg, white bold h1. No gaps.

### Section 2: Hover & Active States
**Match.** Blue + green buttons in a row, rounded, white text, gap. No gaps.

### Section 3: Keyboard Events
**GAP — Input sizing (Medium):**
- Web: Full-width text input spanning the card width, placeholder "Press any key...", label above, status text below.
- JavaFX: Narrow text input (not full width), same label and status text.
- Classification: Layout semantic gap — HTML inputs are block-level and fill parent width by default; JavaFX TextField does not auto-stretch.

### Section 4: Focus & Blur
**GAP — Input sizing (Medium):**
- Web: Full-width text input with placeholder "Click to focus...", status text below.
- JavaFX: Narrow text input (not full width), same placeholder and status text.
- Classification: Layout semantic gap — same as Section 3 (inputs don't fill parent width in JavaFX).

### Section 5: Disabled State
**GAP — Button text missing (High):**
- Web: Green "Disable" button with white text, gray "Target Button", and "Target Input" text field, all in a row.
- JavaFX: Small blue square (no visible "Disable" text), gray "Target Button", and "Target Input" text field in a row.
- Classification: Style bug — button text not rendered; the button shrinks to a small colored square without its label.

### Section 6: Cursor Variants
**GAP — Border vs background styling (Low):**
- Web: 7 cursor labels in a row, each with light gray background, no visible border, small padding.
- JavaFX: 7 cursor labels in a row, each with a thin visible border, similar padding and gap.
- Classification: Platform difference — the labels use `border(1, "#e5e7eb").rounded(4).padding(4,8)` and `bg("#f9fafb")`; the border renders similarly but the bg appears more transparent in web. Minor visual difference only.

## FormUI

### Section 1: Header
**Match.** Red bg, white bold h1 "Form Showcase". No gaps.

### Section 2: Form with Submit
**GAP — Layout direction (High):**
- Web: Vertical stack — each label on its own line, each input/textarea/select full-width below its label, checkbox row, full-width Submit button.
- JavaFX: Horizontal row — "Enter your n..." and "you@exam..." inputs are narrow and side-by-side with textarea and dropdown all on one horizontal line. Labels appear as truncated text or dashes between controls.
- Classification: Layout semantic gap — form element is rendered as an HBox instead of VBox; labels and inputs are inline rather than block-level.

**GAP — Checkbox rendering (High):**
- Web: Small square checkbox next to "I agree to the terms" label.
- JavaFX: Large empty rectangle instead of a checkbox, with "I agree to the terms" text beside it.
- Classification: Style bug — checkbox input type not rendering correctly as a CheckBox control in JavaFX.

**GAP — Submit button sizing (Medium):**
- Web: Full-width blue Submit button spanning the card.
- JavaFX: Small blue Submit button, not full-width.
- Classification: Layout semantic gap — button doesn't fill parent width in JavaFX (same root cause as inputs not filling width).

### Section 3: Disabled Controls
**GAP — Layout direction (High):**
- Web: Vertical stack — "Disable All" button, then "Disabled Input:" label + full-width input, "Disabled Textarea:" label + full-width textarea, "Disabled Select:" label + full-width select, each on own line.
- JavaFX: Horizontal row — all controls (input, textarea, select) and truncated labels are side-by-side on one line. Labels show as "Disa..." and "Di..." (truncated).
- Classification: Layout semantic gap — section rendered as HBox; labels and controls are inline instead of block-level.

**GAP — Button text missing (High):**
- Web: Blue "Disable All" button with white text.
- JavaFX: Small blue square with no visible text.
- Classification: Style bug — same button text rendering issue as InteractiveUI Section 5.

## TypographyUI

### Section 1: Header
**Match.** Teal bg, white bold h1 "Typography Showcase". No gaps.

### Section 2: Font Styles
**GAP — Italic not rendering (Medium):**
- Web: "This text is italic" rendered in italic; "This text is bold italic" rendered in bold+italic.
- JavaFX: "This text is italic" rendered upright (not italic); "This text is bold italic" rendered bold but not italic.
- Bold, monospace, fontSize(24), fontSize(12) all match correctly.
- Classification: Style bug — `-fx-font-style: italic` not being applied to paragraph text nodes.

### Section 3: Text Decoration
**GAP — Underline not rendering (Medium):**
- Web: "This text is underlined" has underline; "Underlined span" has underline.
- JavaFX: No underline visible on any text.
- Classification: Style bug — `-fx-underline: true` not being applied to text nodes.

**GAP — Strikethrough not rendering (Medium):**
- Web: "This text has strikethrough" has strikethrough line; "Struck-through span" has strikethrough.
- JavaFX: No strikethrough visible on any text.
- Classification: Style bug — `-fx-strikethrough: true` not being applied to text nodes.

### Section 4: Text Transform
**GAP — Text transform not applied (Medium):**
- Web: "THIS SHOULD BE UPPERCASE" (transformed), "this should be lowercase" (transformed), "This Should Be Capitalized" (transformed).
- JavaFX: "this should be uppercase" (original case), "THIS SHOULD BE LOWERCASE" (original case), "this should be capitalized" (original case). No transformations applied.
- Classification: Missing mapping — TextTransformProp renders as empty string in FxCssStyleRenderer; JavaFX has no CSS equivalent, so this needs to be handled in the backend via Java API (transforming the text content).

### Section 5: Spacing
**GAP — Line height not applied (Medium):**
- Web: Paragraph with double line height — visible extra space between wrapped lines.
- JavaFX: Paragraph on single line, no extra line spacing, text truncated.
- Classification: Missing mapping — LineHeightProp renders as empty string in FxCssStyleRenderer; needs Java API handling (`setLineSpacing`).

**GAP — Letter spacing not applied (Medium):**
- Web: "W i d e  l e t t e r  s p a c i n g" with wide gaps between characters.
- JavaFX: "Wide letter spacing" with normal character spacing.
- Classification: Missing mapping — LetterSpacingProp renders as empty string; JavaFX Text has no direct CSS equivalent but could use `-fx-spacing` or Java API.

### Section 6: Text Overflow
**GAP — Wrap text not working (Medium):**
- Web: First box shows text wrapping across multiple lines (despite wrapText(false) + ellipsis); second box wraps text normally across multiple lines.
- JavaFX: First box correctly truncates with ellipsis on one line. Second box also truncates on one line (wrapText(true) not working — text does NOT wrap to multiple lines).
- Classification: Style bug — `-fx-wrap-text: true` is emitted but not taking effect on the text node inside the constrained container.

### Section 7: Text Alignment
**GAP — Text alignment not applied (Medium):**
- Web: "Left aligned" left, "Center aligned" centered, "Right aligned" right — all on gray bg strips.
- JavaFX: All three texts are left-aligned. Center and right alignment are not applied.
- Classification: Style bug — `-fx-text-alignment: center/right` not being applied to the text nodes. Same issue as DemoUI footer.

### Section 8: Effects
**Match.** Both opacity (50%) and translate render correctly. Blue semi-transparent box and purple translated box in a row. No gaps.

## LayoutUI

### Section 1: Header
**Match.** Orange bg, white bold h1 "Layout Showcase". No gaps.

### Section 2: Explicit Column
**GAP — Text alignment (Medium):**
- Web: "Item 1", "Item 2", "Item 3" centered text in full-width blue boxes, stacked vertically with gap.
- JavaFX: Same layout but text is left-aligned instead of centered.
- Classification: Style bug — `textAlign(_.center)` not applied. Same issue as TypographyUI text alignment.

### Section 3: Justify Content
**Partial match.** justify(_.start), justify(_.center), justify(_.end) all work correctly.

**GAP — spaceBetween/spaceAround/spaceEvenly not applied (High):**
- Web: spaceBetween spreads A/B/C across full width; spaceAround adds equal space around each; spaceEvenly distributes with equal gaps.
- JavaFX: spaceBetween shows A B C clustered left; spaceAround and spaceEvenly show A B C clustered center. No spacing distribution applied.
- Classification: Missing mapping — Justification.spaceBetween/spaceAround/spaceEvenly have no JavaFX HBox equivalent. The backend needs custom layout logic to distribute children.

### Section 4: Align Items
**GAP — Align items not working (High):**
- Web: align(_.start) has Short (30px) top-aligned, Tall (60px) beside it; align(_.center) has Short vertically centered; align(_.end) has Short bottom-aligned.
- JavaFX: All three variants look identical — both Short and Tall boxes stretch to fill the container height. No visible alignment difference between start/center/end.
- Classification: Style bug — Alignment prop is handled by backend but height constraints on children are not respected; children stretch to fill HBox height instead of maintaining their set height(30)/height(60).

### Section 5: Overflow
**GAP — Overflow hidden not clipping (High):**
- Web: overflow(_.hidden) with height(60) shows ~3 lines, content clipped. overflow(_.scroll) shows ~3 lines with scrollbar.
- JavaFX: overflow(_.hidden) shows ALL 5 lines — height(60) constraint not enforced, content not clipped. Scroll variant partially visible.
- Classification: Style bug — height constraint combined with overflow(_.hidden) not being applied; the container grows to fit all content instead of clipping.

### Section 6: Min/Max Sizing
**NOT VISIBLE** — JavaFX screenshot cut off at bottom due to overflow section not being height-constrained (content extends beyond 2400px viewport). Cannot compare.

### Section 7: Nested Layouts
**NOT VISIBLE** — Same reason as Section 6.

## ReactiveUI

### Section 1: Header
**Match.** Green bg, white bold h1 "Reactive Showcase". No gaps.

### Section 2: Conditional Rendering
**GAP — Button text missing (High):**
- Web: Full-width blue "Hide Panel" button, conditional blue panel below with text.
- JavaFX: Small blue square (no "Hide Panel" text), conditional panel renders correctly with text and bold blue styling.
- Classification: Style bug — same button text rendering issue seen across multiple UIs.

### Section 3: Visibility Toggle
**GAP — Button text missing (High):**
- Web: Full-width purple "Hide" button, purple panel with visibility text below.
- JavaFX: Small purple square (no "Hide" text), purple panel renders correctly.
- Classification: Style bug — same button text rendering issue.

### Section 4: Dynamic Class
**Match.** 3 style buttons in a row, gray "Current class: style-a" div below. No gaps.

### Section 5: Simple foreach
**GAP — foreach tag layout direction (Medium):**
- Web: "Apple", "Banana", "Cherry" tags in a HORIZONTAL row with gap, pill-shaped with blue bg.
- JavaFX: "Apple", "Banana", "Cherry" tags stacked VERTICALLY (column layout), with blue bg and rounded corners.
- Classification: Layout semantic gap — the container div has `Style.row.gap(4)` but foreach-generated spans render in a column in JavaFX.

### Section 6: Keyed List
**GAP — List item layout (Low):**
- Web: "AppleBananaCherry" all inline on one line (li items without block display).
- JavaFX: "Apple", "Banana", "Cherry" on separate lines (li items rendered as block-level).
- Classification: Platform difference — JavaFX rendering is arguably more correct (li should be block). Web may be missing default block display for li elements.

### Section 7: View Mode Toggle
**GAP — Button text missing (High):**
- Web: Full-width orange "Switch to Grid" button, list of items below.
- JavaFX: Small orange square (no "Switch to Grid" text), list items render correctly below.
- Classification: Style bug — same button text rendering issue seen across multiple UIs.

