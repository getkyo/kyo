# Phase 21g Decisions

## SymbolKind case reconciliation

The plan draft listed cases `Module`, `ParamVal`, `ParamType`, and `Constructor` as expected
SymbolKind values. These names do not exist in the actual `Tasty.SymbolKind` enum. The real
cases (14 total, Tasty.scala lines 144-148) are:

    Package, Class, Trait, Object, Method, Field, Val, Var,
    TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter, Unresolved

Plan draft to actual mapping:
- `Module` -> does not exist; `Module` is a `Tasty.Flag`, not a SymbolKind. The object-kind is `Object`.
- `ParamVal` -> does not exist; value parameters use `Parameter`.
- `ParamType` -> does not exist; type parameters use `TypeParam`.
- `Constructor` -> does not exist; constructors are encoded as methods with `SymbolKind.Method`.

SymbolKindTest.scala tests both the count (14) and all 14 specific cases, plus a second test
asserting the plan-draft names are absent.

## makeNamed deduplication outcome

`makeNamed` was duplicated verbatim in `TastyAnnotationTest` and `TastyTypeTest`. A third
variant (`makeNamedSym`) existed in `TypeOpsTest` with a different name.

Extracted to `TastyTestSupport.scala` as a `trait TastyTestSupport` with `protected def makeNamed`.
Both `TastyAnnotationTest` and `TastyTypeTest` now `extend TastyTestSupport`. `TypeOpsTest` was
not changed because its method has a different name and is not a verbatim duplicate.

No package layering issues: `TastyTestSupport` lives in `package kyo` alongside the test files
that use it, matching the existing test-infrastructure pattern (`Test.scala`).

## OnceCell null-test approach (no asInstanceOf)

The plan draft used `OnceCell[String](() => null.asInstanceOf[String])` which violates the
no-casts rule. The implementation instead uses:

    val cell: OnceCell[String | Null] = new OnceCell[String | Null](() => null)

Scala 3 treats `null` as a valid inhabitant of `String | Null` (nullable union type), so no cast
is required. The `OnceCell` implementation stores the value as `AnyRef` and uses a separate
`Unset` sentinel object for the "not yet initialized" state; `null` and `Unset` are distinct
`AnyRef` references, so `null ne OnceCell.Unset` is true and null is stored and returned correctly.

## Error message reconciliation (SingleAssign)

The plan draft said the second-set message contains `"already assigned"`. The actual
`SingleAssign.scala` line 26 says `"SingleAssign already set"`. The test matches the
actual implementation: checks for `"already set"` as a substring.

## FqnCanonicalizer method name reconciliation

The plan draft referred to `FqnCanonicalizer.toDotted` which does not exist. The actual API is
`FqnCanonicalizer.toFullName(binaryName: String, innerClassTable: Map[String, (String, String)])`.
For the test to produce `"com.example.Foo.Inner"` from `"com/example/Foo$Inner"`, the
`innerClassTable` must contain the entry `"com/example/Foo$Inner" -> ("com/example/Foo", "Inner")`.
The test passes both the binary name and the table explicitly.

## CanEqual pattern in Constant enum

`Tasty.Constant` does not derive `CanEqual`. Pattern matching on a singleton case object with
`case Tasty.Constant.NullConst` triggers a Scala 3 strict-equality E172 error. The fix follows
the existing pattern in `TreeUnpicklerTest.scala` (line 488):

    case _: Tasty.Constant.NullConst.type => succeed
