# Visual Comparison — bugfix-val session

## SignalCombinatorUI (signals)
- **Section 1 (Combined Signals: Name)**: Match. Two inputs, "Enter a name..." display.
- **Section 2 (Computed Value)**: Match. Qty=1, $10, Total: $10.
- **Section 3 (Combined Filters)**: **GAP**. Web shows Alpha/Beta/Gamma/Delta tags with checked checkboxes. JFX shows checkboxes (unchecked appearance) but NO filter tags. Bug 6 (UI.when inside foreach) still present on JFX static screenshot.
- **Section 4 (Rapid Updates)**: Match. Button + "Quantity after rapid updates: 1".

## RapidMutationUI (rapid)
- **Section 1 (Rapid Add/Remove)**: Match. A/B/C tags, "Count: 3" — count text now renders (Bug 3 fixed).
- **Section 2 (Toggle Visibility)**: Match. Hide List button, A/B/C items visible.
- **Section 3 (Operation Log)**: Match (JFX has scrollbar — platform difference).

## DeepNestingUI (deepnest)
- **Section 1 (Signal[UI] → foreach)**: Match. Fruits (Apple, Banana) + Vegetables (Carrot, Pea) with click counts. Previously empty on JFX — now fixed.
- **Section 2 (UI.when inside foreach)**: Minor gap. Apple with detail expanded on both. Banana detail text missing on JFX (only "Banana" header, no "Detail for Banana — expanded").
- **Section 3 (Toggle Cycles)**: Match. Toggle button, "Visible", Fruits/Vegetables with "0 updates".

## SignalSwapUI (swap)
- **Section 1 (Swap Between Views)**: Match. Tasks/Notes/Tags buttons, Swaps: 0, input, 3 task items with checkboxes + × buttons. Previously empty on JFX — now fixed.
- **Section 2 (Rapid Swap)**: Match. Cycle button + "Current view: tasks".

## GenericAttrUI (attrs)
- **Section 1 (Static Attributes)**: Match. Three elements with data-* attribute descriptions.
- **Section 2 (Reactive Attributes)**: Match. Cycle button, "Current: data-initial", title input.
- **Section 3 (Generic Event Handlers)**: Match. Click button, dblclick element, event log area, Clear Log.

## ReactiveHrefUI (rechref)
- **Section 1 (Reactive Anchor href)**: Match. Three buttons, "Visit: Example.com", href display.
- **Section 2 (Fragment as Multi-Root Pattern)**: Match. Static fragment (First/Second/Third), foreach fragment (One/Two/Three with details), Signal[UI] fragment with buttons. Previously foreach items missing on JFX — now fixed.
- **Section 3 (Fragment + Mutation)**: Match. Add/Remove/Clear buttons, One/Two/Three tags, "3 items".

## FormResetUI (formreset)
- **Section 1 (Contact Form)**: Match. Name/Email/Message fields, Submit/Clear buttons, preview showing "(empty)".
- **Section 2 (Settings Form)**: Match. Username input, Theme select ("light"), checkbox, Priority select ("medium"), Save/Reset buttons, preview.
- **Section 3 (Submission History)**: Match. "No submissions yet.", Clear History button.

## Summary
- **6 of 7 UIs**: All sections match between web and JFX
- **1 remaining gap**: signals Section 3 — UI.when inside foreach doesn't render filter tags on JFX (checkboxes also appear unchecked despite initial signal=true)
- **1 minor gap**: deepnest Section 2 — Banana detail text partially missing on JFX
