# Phase 09 Decisions

## Finding addressed: B5

`ConstantPool.scala` cross-entry references (e.g. `ClassRef.nameIdx` expecting `Utf8`) previously fell through to a cryptic match failure when the referenced entry was the wrong kind. The existing typed accessors (`utf8`, `classRef`, etc.) already validated entry kinds, but error messages only stated what was expected, not what was found, and the `CpEntry.Hole` sentinel was not guarded in `entry()`.

## Changes made

### `ConstantPool.scala`

1. `entry()` now also guards against `CpEntry.Hole`: after the `null` check, an identity comparison (`eq`) detects `Hole` and returns a structured error naming the slot as "the unused second slot of a Long/Double entry".

2. Added private `tagName(e: CpEntry): String` that maps every `CpEntry` subtype to a short human-readable tag. The `case object Hole` cannot be matched with a direct singleton pattern in Scala 3.8 when the scrutinee is `CpEntry` and all other subtypes are already covered, so the fallback `case _ => "Hole"` is used (exhaustive since all named subtypes are listed above it).

3. All typed accessor error messages updated to include `, found ${tagName(other)}` so a malformed classfile produces messages like "Expected Utf8 at pool[5], found ClassRef" instead of just "Expected Utf8 at pool[5]".

## Scala 3.8 pattern matching note

`case CpEntry.Hole =>` inside a match on `CpEntry | Null` produces E172 ("Values of types object ... and ... cannot be compared with =="). Inside `entry()`, the `null` check is a separate `case null =>` arm; the `Hole` check uses `case e: CpEntry => if e eq CpEntry.Hole then ... else e` to avoid the E172. Inside `tagName` (scrutinee `CpEntry`), the same E172 appears even though `CpEntry` is a sealed trait - resolved by listing all class-based subtypes with `_:` patterns and using `case _ => "Hole"` as the final catch-all.

## Tests added (ConstantPoolTest.scala)

4 new tests, all passing on JVM/JS/Native:

- **B5-1**: `utf8(1)` on a pool where slot 1 is a `ClassRef` fails with `ClassfileFormatError` mentioning both "Utf8" and "ClassRef".
- **B5-2**: `classRef(1)` resolves `ClassRef(nameIdx=2)` to `Utf8("scala/Int")` correctly.
- **B5-3**: `classRef(2)` where slot 2 is `Utf8` fails with an error mentioning both "ClassRef" and "Utf8".
- **B5-4**: `utf8(2)` on a pool where slot 2 is a `Long/Double` hole fails with an error mentioning "hole" or "Long/Double".

Helper builders added: `buildClassRefThenUtf8Bytes(s)` and `buildLongPoolBytes(value)`.

## Cross-platform

JVM, JS, Native all compile cleanly. Tests run on JVM: 6 passed (2 pre-existing + 4 new).

## HEAD

`23fb0bed8` - unchanged.
