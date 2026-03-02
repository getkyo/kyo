# QA Analysis — Full Verification

## UI Categories

### High Interactivity (8 UIs)
1. **DemoUI** — counter (+/-), todo CRUD, theme dark/light
2. **FormUI** — form submit (all field types), disable/enable controls
3. **ReactiveUI** — conditional render, hidden toggle, dynamic class (A/B/C), foreach add, view mode list↔grid
4. **CollectionOpsUI** — add/remove last/reverse/clear, tick counter, set single/reset
5. **InteractiveUI** — keyboard events, focus/blur, disabled toggle
6. **DynamicStyleUI** — bg color (5 options), font size (+/-), padding (+/-), bold/italic/underline toggles, border width (+/-)
7. **NestedReactiveUI** — nested when (outer+inner), foreach with global counter, selection, mode toggle (list↔tags), filter
8. **KeyboardNavUI** — keydown/keyup display, modifier combos, key log with clear

### Auto-Animated (2 UIs)
9. **AutoTransitionUI** — color cycling (500ms), auto-populating list, delayed panel, live counter
10. **AnimatedDashboardUI** — metrics populate, status badge changes, log entries, view toggle

### Static (8 UIs — visual only)
11-18. LayoutUI, TypographyUI, SemanticElementsUI, MultiPseudoStateUI, TransformsUI, ColorSystemUI, TableAdvancedUI, DashboardUI, SizingUnitsUI

## Risk Areas
- **Multiple reactive styles**: Bug #7 fix — verify on DynamicStyleUI (bold+italic+underline together)
- **Form onSubmit via button click**: Bug #3 fix — verify on FormUI (JFX)
- **Nested when()**: NestedReactiveUI — both conditions toggling independently
- **foreachKeyed with selection**: NestedReactiveUI — click to select, verify "(selected)" appears
- **Collection operations**: Clear + empty state, reverse ordering
- **Auto transitions**: Timing-dependent — need to verify after sufficient delay
- **JFX selector matching**: Button text with colons (e.g., "Bold: OFF") may not match correctly
