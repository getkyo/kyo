# Interactive UI Testing - JFX vs Web Comparison

## UIs Tested (10 interactive UIs)

### 1. DemoUI ✅
- **Counter**: +/- works on both platforms ✅
- **Todo list**: Add items works on both ✅
- **Theme toggle**: Works (not tested this session but verified previously)
- **Visual match**: Good. Minor difference: Data Table columns more spaced on web.

### 2. InteractiveUI ⚠️
- **Disable toggle**: Works on both - button text changes, target becomes disabled ✅
- **Visual differences**:
  - JFX truncates cursor variant labels ("poi...", "t...", "m...") — spans too narrow
  - Web truncates "grab" at right edge
  - JFX disabled state shows clearer opacity fade than web

### 3. FormUI 🐛
- **Form submit (onSubmit)**: **BROKEN on JFX** — "Not submitted yet" stays after click. Web correctly shows "Submitted: Name=John, Email=, Text=, Select=option1, Check=false"
- **Input fill**: Works on both ✅
- **Visual differences**:
  - Select dropdown: JFX shows "option1", web shows "Option 1"
  - Disabled Select: JFX empty, web shows "Alpha"
  - Textarea: web uses monospace font, JFX uses system font

### 4. ReactiveUI ✅
- **Conditional rendering (UI.when)**: Works on both — panel hides/shows ✅
- **Visual match**: Very good

### 5. DynamicStyleUI ✅
- **Dynamic background color**: Works on both ✅
- **Visual match**: Very good

### 6. CollectionOpsUI ⚠️
- **Add item**: Works on both ✅
- **Visual difference**: JFX truncates button labels ("...", "Remove ...", "Rev...", "C...")

### 7. TableAdvancedUI 🐛
- **Styled Table**: Matches well ✅
- **Colspan & Rowspan**: **BROKEN on JFX** — columns missing (Score, Budget), headers misaligned. Web renders correctly with proper colspan/rowspan layout.
- **Dynamic Table**: Matches ✅
- **Colored Status Cells**: Matches ✅

### 8. NestedReactiveUI 🐛🐛
- **Nested when()**: JFX shows inner panel, **web doesn't render inner conditional content**
- **Signal[UI] in collection**: JFX shows Alpha/Beta/Gamma, **web shows nothing**
- **Filtered collection**: JFX shows items, **web shows nothing**
- This is a significant **web-side DomBackend bug** with nested reactive content

### 9. KeyboardNavUI ✅
- **Visual match**: Very good across all 4 sections

### 10. AnimatedDashboardUI (not tested this session - auto-transitions)

## Summary of Bugs Found

### JFX Bugs
1. **form.onSubmit not firing** (FormUI) — clicking Submit button inside a form doesn't trigger onSubmit handler
2. **Table colspan/rowspan broken** (TableAdvancedUI) — columns missing, headers misaligned
3. **Button/span text truncation** — padding + fixed-width causes label clipping

### Web (DomBackend) Bugs
1. **Nested when() content not rendering** (NestedReactiveUI) — inner conditional panel missing
2. **Signal[UI] nested in collection not rendering** (NestedReactiveUI) — items below button missing
3. **Filtered collection not rendering** (NestedReactiveUI) — filtered items missing

### Platform Differences (expected)
- Web renders text ~20% larger than JFX (font size scaling)
- JFX uses system font for textarea, web uses monospace
- JFX disabled state more visually obvious (opacity)
- Select dropdown text differs between platforms

## Session Infrastructure Changes
- Fixed `Abort.run[Throwable]` error handling — prevents session crash on command errors
- Fixed `JavaFxBackend.startToolkit` — handles already-initialized toolkit
- Fixed `InteractiveSession` render — closes old JFX windows, reloads web page with new hash
- `Platform.setImplicitExit(false)` — prevents JFX shutdown when closing stages
