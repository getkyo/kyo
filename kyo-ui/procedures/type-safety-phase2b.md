# Type Safety Phase 2b — Remove BaseStyle + Split Radio

## Issue 1: Remove BaseStyle

BaseStyle exists only to prevent `Style.hover(Style.hover(...))` — cosmetically odd but not a bug (renderer flattens it). Meanwhile it doubles the API surface and forces users to learn a second type.

### Changes
- Delete `BaseStyle` case class and `object BaseStyle` from Style.scala (~140 lines of duplicated factory methods)
- Change `hover`/`focus`/`active`/`disabled` signatures: `BaseStyle` → `Style`
- Delete `toStyle` conversion method
- Update StyleTest.scala: `BaseStyle.bg(...)` → `Style.bg(...)`
- Update TuiSimulatorTest.scala: same

## Issue 2: Split Radio from Input

`Input` has `checked: Maybe[Boolean | Signal[Boolean]]` which only makes sense for Radio (Checkbox already split). Radio also needs `onChange: Boolean => Unit < Async`, not `String => Unit < Async`.

### New Radio case class
```scala
final case class Radio(
    attrs: Attrs = Attrs(),
    checked: Maybe[Boolean | Signal[Boolean]] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[Boolean => Unit < Async] = Absent
) extends Inline with Focusable with HasDisabled with Void
```

### Changes
- Add `Radio` case class to UI.scala (same shape as Checkbox)
- Remove `checked` field from `Input`
- Remove `Radio` from `InputType` enum
- Add `val radio: Radio = Radio()` factory
- Update backends:
  - TuiFlatten: render Radio as `(•)` / `( )` (similar to Checkbox `[x]`/`[ ]`)
  - TuiFocus: handle Radio toggle (same as Checkbox — Enter/Space)
  - DomBackend: add Radio case, set `type="radio"`
  - JavaFxBackend: add Radio case in `elemToHtml`
- Update tests referencing `Input.checked`

### InputType enum after
```
Text, Password, Number, Email, Tel, Url, Search, Date, Time, Color, File, Hidden, Range
```
(13 variants, down from 14)
