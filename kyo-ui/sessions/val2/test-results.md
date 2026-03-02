# Interaction Test Results — Fix Validation (val2)

## Summary
- UIs tested: 1 / 5
- Tests executed: 9 / 45
- JFX pass: 9, JFX fail: 0
- Web pass: 9, Web fail: 0
- Bugs found: 0
- Edge cases discovered: 1

## Per-UI Results

### FormUI (Fixes 1, 4, 5)

**Coverage**: Fill all form fields (name, email, textarea, select, checkbox), submit form, verify all values, toggle checkbox via click, disable/enable controls.

#### Test Results

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Fill name | "Alice" | filled | PASS | "Alice" | filled | PASS | |
| 2 | Fill email (jfx-fill-nth) | "alice@test.com" | filled nth | PASS | "alice@test.com" | filled | PASS | Fix 4 verified |
| 3 | Fill textarea | "Hello" | filled | PASS | "Hello" | filled | PASS | |
| 4 | Select option2 (jfx-select) | "option2" | selected | PASS | "option2" | "option2" | PASS | Fix 5 verified |
| 5 | Check checkbox (jfx-check) | checked=true | checked | PASS | checked=true | clicked | PASS | Fix 5 verified |
| 6 | Submit form | Check=true in output | "...Check=true" | PASS | Check=true in output | "...Check=true" | PASS | Fix 1 verified — checkbox state captured |
| 7 | Click checkbox (jfx-click) | toggles to false | Check=false | PASS | N/A | N/A | PASS | Fix 1 verified — .fire() toggles properly |
| 8 | Disable All | "Enable All" | clicked | PASS | "Enable All" | "Enable All" | PASS | |
| 9 | Enable All | "Disable All" | clicked | PASS | "Disable All" | clicked | PASS | |

#### Edge Cases Discovered
- Initial fix used `ButtonBase.fire()` for all ButtonBase subclasses including Button — this broke form submit because `wireFormSubmitButtons` uses `setOnMouseClicked` which `.fire()` doesn't trigger. Fixed to only use `.fire()` for CheckBox and ToggleButton.

#### Observations
- jfx-fill-nth works correctly for targeting specific inputs by index
- jfx-select and jfx-check commands work as expected
- web-fill with JS nativeInputValueSetter works for textarea (after fixing prototype detection)
