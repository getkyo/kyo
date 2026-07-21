package kyo.internal

/** Documents the test-file naming convention enforced by [[kyo.sql.TestNamingConventionTest]].
  *
  * A test file at `kyo-sql/{shared,jvm}/src/test/scala/.../FooTest.scala` must satisfy ONE of:
  *
  *   1. **Unit-test rule**: `Foo` (the basename minus the `Test` suffix) has at least one production source-file basename in
  *      `kyo-sql/{shared,jvm,native,js}/src/main/scala/` that is a prefix of `Foo`. Example: `SqlUrlTest` matches `SqlConfig.Url.scala` (because
  *      `SqlConfig.Url` is a prefix of `SqlConfig.Url`); `SqlUrlParseTest` also matches (because `SqlConfig.Url` is a prefix of `SqlUrlParse`).
  *   2. **Categorical rule**: the test file's name ends in one of these documented suffixes:
  *      - `IntegrationTest`, exercises a feature flow that spans multiple source files.
  *      - `ConsistencyTest`, verifies cross-backend invariants.
  *      - `RoundTripTest`, exercises encode/decode round-trips spanning many encoder/decoder files.
  *      - `MessagesTest`, covers a wire-protocol message family.
  *
  * **Exempted infrastructure files** (end in `Test` but are not test leaves):
  *   - `SqlDbTest.scala`, a base class that other tests extend.
  *   - `Test.scala` (in any test root), the kyo-core test base class.
  *
  * This stub exists so that the class name `TestNamingConvention` (prefix of `TestNamingConventionTest`) satisfies rule 1.
  */
private[kyo] object TestNamingConvention
