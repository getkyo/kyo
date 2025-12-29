# kyo-data Test Failures Analysis

**Date**: December 29, 2025  
**Scala Version**: 3.8.0-RC4  
**Module**: kyo-data

## Summary

| Status | Count | Details |
|--------|-------|---------|
| **Original (main branch)** | 8 failures | With original Record.scala |
| **After fixes** | 3 failures | After applying Scala 3.8 fixes |
| **Improvement** | ✅ **-5 failures** | Fixes are working! |

## Root Cause: Scala 3.8.0-RC4 Tag Comparison Issues

Scala 3.8.0-RC4 has stricter/more precise tag handling. The `Tag[Value]` instances used in `Field[Name, Value]` comparisons don't match exactly when:
- Records are upcast (e.g., `Record["name" ~ String & "age" ~ Int]` → `Record["name" ~ String]`)
- Tags are created at different points in compilation
- Type intersections are involved

## Changes Made

### ✅ 1. selectDynamic Fix (REQUIRED)

**File**: `kyo-data/shared/src/main/scala/kyo/Record.scala` (line ~93)

**Before** (main branch):
```scala
def selectDynamic[Name <: String & Singleton, Value](name: Name)(using
    ev: Fields <:< Name ~ Value,
    tag: Tag[Value]
): Value =
    toMap(Field(name, tag)).asInstanceOf[Value]  // Direct lookup - fails with 3.8
```

**After** (fixed):
```scala
def selectDynamic[Name <: String & Singleton, Value](name: Name)(using
    ev: Fields <:< Name ~ Value,
    tag: Tag[Value]
): Value =
    toMap.collectFirst({
        // Fix for Scala 3.8, there are issues on tags
        case (Field(n, t), v) if n == name && t <:< tag => v.asInstanceOf[Value]
    }).get
```

**Impact**: Fixed 4 test failures:
- ✅ "allows upcasting to fewer fields"
- ✅ "allows multiple upcasts"  
- ✅ "selectDynamic works"
- ✅ (1 other related test)

### ✅ 2. getField Fix (Already Applied)

**File**: `kyo-data/shared/src/main/scala/kyo/Record.scala` (line ~113)

Same fix as `selectDynamic` - uses `collectFirst` with type checking instead of direct map lookup.

## Remaining 3 Failures

### 1. "preserves defined fields" (compact test)
- **Location**: `RecordTest.scala:292`
- **Issue**: `Record.sizeOf(compacted) == 2` fails, returns 1 instead
- **Root Cause**: `compact` uses `AsFields.contains(_)` which relies on `Set.contains` with `Field` equality. Tag mismatches cause fields to not be found.
- **Status**: Needs investigation - may need to fix `AsFields.contains` or `Field.equals`

### 2. "removes extra fields" (compact test)
- **Location**: `RecordTest.scala:301`
- **Issue**: `Record.sizeOf(compacted) == 1` fails, returns 0 instead
- **Root Cause**: Same as #1 - `compact` filtering not working correctly
- **Status**: Same fix as #1

### 3. "toMap preserves fields"
- **Location**: `RecordTest.scala:645`
- **Issue**: Direct map access `map(Field("name", Tag[String]))` throws `NoSuchElementException`
- **Root Cause**: Test creates new `Field` instances that don't match map keys due to tag comparison
- **Status**: Test needs updating - should use `collectFirst` approach or access via `selectDynamic`

## Code Changes Required

### ✅ Applied Changes
1. `selectDynamic` - Fixed to use `collectFirst` with type checking
2. `getField` - Already had the fix
3. `compact` - Uses explicit parameter name (minor improvement)

### ⏳ Remaining Work

**Option A: Fix Field equality** (Recommended)
- Override `Field.equals` to use tag subtyping (`t1 <:< t2 || t2 <:< t1`)
- This would fix `compact` and make `AsFields.contains` work correctly

**Option B: Fix compact method**
- Change `compact` to use `collectFirst` approach similar to `selectDynamic`
- More complex but avoids changing `Field` equality

**Option C: Update test**
- Fix "toMap preserves fields" test to not use direct map access
- Use `selectDynamic` or `collectFirst` approach

## Verification

| Test | Before | After | Status |
|------|--------|-------|--------|
| Total tests | 1972 | 1972 | Same |
| Passed | 1964 | 1969 | ✅ +5 |
| Failed | 8 | 3 | ✅ -5 |
| Pass rate | 99.6% | 99.8% | ✅ Improved |

## Conclusion

✅ **The changes ARE needed and ARE working!**

- Reduced failures from 8 → 3
- Fixed all upcasting/variance-related failures
- Remaining 3 failures are related to `Field` equality in `Set.contains` operations
- These are likely fixable with `Field.equals` override or test updates

The Record.scala changes are **essential** for Scala 3.8.0-RC4 compatibility.

---
*Generated*: December 29, 2025

