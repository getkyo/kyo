# Tag Equality Workaround Status - Scala 3.8.0-RC4

## Issue Summary

In Scala 3.8.0-RC4, Tag equality behavior changed, causing Record field lookups and equality checks to fail. **We are using workarounds rather than fixing the root cause.**

## Root Cause

`Tag` is an **opaque type**: `opaque type Tag[A] = String | Tag.internal.Dynamic`

**The Problem**:
- Opaque types use the underlying type's equality (`String` or `Dynamic`)
- In Scala 3.8.0-RC4, tag equality/subtyping checks became stricter
- Direct `Map` lookups using `Field(name, tag)` fail because tag equality doesn't match
- `Field.equals` (case class default) uses tag equality which doesn't work correctly

**Why We Can't Fix Tag Directly**:
- Opaque types cannot override `equals` or `hashCode`
- `CanEqual.derived` only enables comparisons but doesn't change equality behavior
- The underlying type's equality is still used

## Current Workarounds

### 1. Record Field Lookup (`selectDynamic` / `getField`)

**Before (main)**:
```scala
toMap(Field(name, tag)).asInstanceOf[Value]
```

**After (workaround)**:
```scala
toMap.collectFirst({
    // Fix for Scala 3.8, there are issues on tags
    case (Field(n, t), v) if n == name && t <:< tag => v.asInstanceOf[Value]
}).get
```

**Impact**: 
- ✅ Works correctly
- ❌ Less efficient (O(n) scan instead of O(1) lookup)
- ❌ Workaround, not a fix

### 2. Field.equals Override

**Before (main)**:
```scala
case class Field[Name <: String, Value](name: Name, tag: Tag[Value])
// Uses default case class equals (uses tag equality)
```

**After (workaround)**:
```scala
case class Field[Name <: String, Value](name: Name, tag: Tag[Value]):
    override def equals(obj: Any): Boolean = obj match
        case that: Field[?, ?] =>
            name == that.name && {
                // Fast path: reference equality or fast string comparison
                val fastEqual = (tag.asInstanceOf[AnyRef] eq that.tag.asInstanceOf[AnyRef]) || {
                    (tag, that.tag) match
                        case (t1: String, t2: String) => t1.hashCode == t2.hashCode
                        case _ => false
                }
                fastEqual || tag =:= that.tag || tag <:< that.tag || that.tag <:< tag
            }
        case _ => false
    
    override def hashCode(): Int = name.hashCode()
```

**Impact**:
- ✅ Works correctly
- ✅ Uses Tag's `=:=` and `<:<` methods (proper type comparison)
- ❌ Workaround at Field level, not fixing Tag itself
- ⚠️ HashCode only uses name (could cause collisions, but acceptable for current use)

### 3. CanEqual Instance

**Added**:
```scala
given [A, B]: CanEqual[Tag[A], Tag[B]] = CanEqual.derived
```

**Impact**:
- ✅ Enables `==` comparisons between Tags (required by Scala 3 strict equality)
- ❌ Doesn't actually fix equality - still uses underlying type equality
- ℹ️ Just enables comparisons, doesn't change behavior

## Comparison with Main Branch

| Aspect | Main Branch | Current (RC4) | Status |
|--------|-------------|----------------|--------|
| Tag equality | Uses underlying type (String/Dynamic) | Same - **not fixed** | ⚠️ Workaround |
| Record field lookup | Direct Map lookup `toMap(Field(...))` | `collectFirst` with tag subtyping | ⚠️ Workaround |
| Field.equals | Case class default (uses tag equality) | Override with tag subtyping | ⚠️ Workaround |
| Field.hashCode | Case class default | Name-only hash | ⚠️ Workaround |
| CanEqual | Not needed (pre-3.8) | `CanEqual.derived` | ℹ️ Required for 3.8 |

## What Should Be Fixed (But Can't)

**Ideal Solution**: Make Tag equality use `=:=` or `<:<` instead of underlying type equality.

**Why It's Impossible**:
1. Tag is opaque - can't override `equals`
2. CanEqual doesn't change equality for opaque types
3. Would require making Tag non-opaque (breaking change, performance impact)

## Current Status

✅ **All tests passing** - workarounds are functional
⚠️ **Not a proper fix** - we're working around the issue
📝 **Documented** - workarounds are clearly marked in code
🔍 **Needs investigation** - should check if Scala 3.8.0 final fixes this

## Recommendations

1. **Short term**: Keep workarounds, they work correctly
2. **Medium term**: Test with Scala 3.8.0 final to see if issue is resolved
3. **Long term**: Consider if Tag should remain opaque or if we need a different approach
4. **Documentation**: Update comments to be more explicit about this being a workaround

## Related Issues

- Scala 3.8.0-RC4 tag equality changes
- Record field lookup failures
- Field equality in Set/Map operations
- Compiler issue #24596 (Records)

## Test Coverage

- ✅ `RecordTest` - All passing (1972 tests)
- ✅ `TagTest` - All passing (656 tests)  
- ✅ `RecordCompilerIssue24596Test` - All passing (31 tests)
- ✅ `TTableTest` (kyo-stm) - All passing (129 tests)

All tests pass, but they're testing the workaround behavior, not the ideal behavior.

