# Phase 26 decisions

## build.sbt structure

Added `kyo-tasty-examples` as a `crossProject(JSPlatform, JVMPlatform, NativePlatform)` using:
- `.withoutSuffixFor(JVMPlatform)` (consistent with `kyo-tasty` and `kyo-tasty-fixtures`)
- `.crossType(CrossType.Full)`
- `.dependsOn(`kyo-tasty`)`
- `.settings(`kyo-settings`)`
- Platform-specific: `.jvmSettings(mimaCheck(false))`, `.nativeSettings(`native-settings`)`, `.jsSettings(`js-settings`)`
- `.disablePlugins(MimaPlugin)` (examples are not a published library artifact; no binary compatibility check needed)

The module was added to all three aggregate projects (`kyoJVM`, `kyoJS`, `kyoNative`) immediately after `kyo-tasty-fixtures` (JVM) or `kyo-tasty` (JS/Native, since `kyo-tasty-bench` is JVM-only).

The plan snippet showed `jvmSettings(`kyo-settings`*)` etc., but the established pattern for similar modules (`kyo-tasty-fixtures`, `kyo-tasty`) uses `.settings(`kyo-settings`)` at the top level, with platform-specific overrides for `native-settings` and `js-settings` applied via the platform-specific settings methods. That pattern was used here.

## Test 3 deferral

Test 3 (compile check: that kyo-tasty-examples compiles against kyo-tasty) cannot be implemented as a runtime test inside `TastyTest.scala` because it would require forking an sbt subprocess or using `javax.tools.JavaCompiler` equivalent for Scala, which is not available in a standard test harness. This test is instead covered by the sbt verification step `sbt 'kyo-tasty-examples/Test/compile'` in the verification matrix and does not appear as a test in `TastyTest.scala`.

## Platform split for filesystem tests

The two INV-022 tests (source-tree checks) use `java.nio.file.Files` and `java.nio.file.Paths`, which are JVM-only APIs. Placing them in `shared/src/test/` would compile but produce ScalaJS linker errors at `fastLinkJS` time due to missing `java.nio.file.LinkOption` and related classes. The tests were therefore placed in `kyo-tasty/jvm/src/test/scala/kyo/TastyExamplesLayoutTest.scala`, which is JVM-only by location. No `taggedAs jvmOnly` annotation is needed in the JVM-only file since there are no JS/Native counterparts.

## Package transition

The 4 example files had their package declaration changed from `package kyo.tasty.examples` to `package examples`. The body of each file was preserved verbatim. The imports (`import kyo.*`, `import kyo.Tasty.*`) remain unchanged and resolve correctly because `kyo-tasty-examples` depends on `kyo-tasty`.

## Empty directory cleanup

After deleting the 4 `.scala` files from `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/`, that directory was empty and was removed. The parent `kyo-tasty/shared/src/main/scala/kyo/tasty/` also became empty and was removed. This leaves `kyo-tasty/shared/src/main/scala/kyo/` with only `Tasty.scala`, `TastyError.scala`, and the `internal/` subtree.
