# Bug #7 Fix Validation Results

## Bug: Multiple reactive `.style()` calls only applied last signal's value

### Root Cause
1. `CommonAttrs.style` was `Maybe[String | Signal[String]]` — single slot, each `.style()` replaced previous
2. After fix to `Chunk[String | Signal[String]]`, the JS build was failing with discarded value error on `Kyo.foreach`, so web was running stale JS without the fix

### Fix Applied
1. Changed `style: Maybe[...]` → `styles: Chunk[...]` in `CommonAttrs` (UI.scala)
2. Changed `.style()` to append instead of replace
3. Rewrote `applyStyle` in both backends to subscribe to all signals with indexed array
4. Added `.unit` after `Kyo.foreach` in both backends to fix discarded value error

### Files Modified
- `kyo-ui/shared/src/main/scala/kyo/UI.scala` — CommonAttrs field change + style method
- `kyo-ui/js/src/main/scala/kyo/DomBackend.scala` — applyStyle rewrite
- `kyo-ui/jvm/src/main/scala/kyo/JavaFxBackend.scala` — applyStyle rewrite
- `kyo-ui/shared/src/test/scala/kyo/UITest.scala` — test updates
- `kyo-ui/shared/src/test/scala/kyo/StyleTest.scala` — test updates

### Verification
- **Tests**: 356/356 pass
- **Web validation**: All three toggles (Bold, Italic, Underline) apply simultaneously
  - Initial: `font-weight: normal; font-style: normal; text-decoration: none; padding: 16px...`
  - After all ON: `font-weight: bold; font-style: italic; text-decoration: underline; padding: 16px...`
- **Screenshot**: `dynamic-all-on-web.png` shows bold+italic+underlined text
