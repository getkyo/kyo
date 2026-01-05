# Record Compiler Issue #24596 - Test Suite

## Overview

This document describes the test suite created to help reproduce, minimize, and verify fixes for Scala compiler issue [#24596](https://github.com/scala/scala3/issues/24596) related to Records in Scala 3.8.0-RC2+.

## Issue Context

The issue was discovered when testing Scala 3.8.0-RC2 in OpenCB. The problem affects Record field access and tag handling, potentially causing compilation errors or runtime issues.

## Test File

**Location**: `kyo-data/shared/src/test/scala/kyo/RecordCompilerIssue24596Test.scala`

**Purpose**:
1. **Reproduce** the compiler issue with various Record operations
2. **Minimize** the problem to help identify root causes
3. **Verify** that fixes don't break TagTest/TagMacroTest
4. **Document** edge cases that might trigger the issue

## Test Categories

### 1. Basic Record Field Access
Tests fundamental Record operations:
- Simple field access
- Multiple fields
- Type-safe access
- Field lookup with tag subtyping

### 2. Tag Compatibility
Tests Tag behavior in Record context:
- Tag equality in record fields
- Tag subtyping
- Tag with generic types
- Tag consistency across operations

### 3. Edge Cases and Minimization
Minimal reproduction cases for common scenarios:
- Simple field access
- Field with tag
- Duplicate fields
- Nested records
- Record equality
- toMap operation
- compact operation
- getField method

## Current Status

✅ **All 31 tests passing** on Scala 3.8.0-RC4

The test suite validates that:
- Record field access works correctly
- Tag operations are compatible
- Edge cases are handled properly
- No regressions in TagTest/TagMacroTest

## Known Issues

The current implementation includes workarounds for Scala 3.8.0-RC4:
- `selectDynamic` and `getField` use `collectFirst` with tag subtyping (`t <:< tag`)
- `Field.equals` is overridden to use tag subtyping for compatibility

These workarounds are documented in the code with comments like:
```scala
// Fix for Scala 3.8, there are issues on tags
```

## How to Use

### Running the Tests

```bash
# Run all Record compiler issue tests
sbt "project kyo-data" "Test/testOnly kyo.RecordCompilerIssue24596Test"

# Run specific test category
sbt "project kyo-data" "Test/testOnly kyo.RecordCompilerIssue24596Test -- -z \"minimal reproduction\""
```

### Verifying Tag Compatibility

```bash
# Verify TagTest still passes
sbt "project kyo-data" "Test/testOnly kyo.TagTest"

# Verify TagMacroTest still works
sbt "project kyo-data" "Test/testOnly kyo.TagMacroTest"
```

## Contributing

When adding new test cases:
1. Focus on **minimal reproduction** - the simpler, the better
2. Document **expected behavior** vs **actual behavior**
3. Include **tag-related** scenarios if relevant
4. Verify tests don't break **TagTest** or **TagMacroTest**

## Related Files

- `kyo-data/shared/src/main/scala/kyo/Record.scala` - Record implementation
- `kyo-data/shared/src/main/scala/kyo/Tag.scala` - Tag implementation
- `kyo-data/shared/src/test/scala/kyo/RecordTest.scala` - Main Record tests
- `kyo-data/shared/src/test/scala/kyo/TagTest.scala` - Tag tests
- `kyo-data/shared/src/test/scala/kyo/internal/TagTestMacro.scala` - Tag macro tests

## References

- [Scala 3 Issue #24596](https://github.com/scala/scala3/issues/24596)
- [Scala 3.8.0 Release Thread](https://contributors.scala-lang.org/t/scala-3-8-0-release-thread/7291)

