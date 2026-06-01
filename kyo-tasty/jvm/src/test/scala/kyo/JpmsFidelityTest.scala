package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for JPMS module discovery and Symbol.Module.
  *
  * Pins findings F-D-001, F-D-002, and F-G-003. All leaves are PENDING until Phase 10 un-pends them by adding `Symbol.Module` to
  * `Tasty.scala`, implementing `Classpath.findModule` and `Classpath.initWithPlatformModules`, and routing `module-info.class` through
  * `ModuleInfoReader` in `ClasspathOrchestrator`.
  */
class JpmsFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-D-001 / F-D-002 leaf 1 (Phase 10): jdk-module-discoverable
    // Given: a JVM with java.home set; user roots from TestClasspaths.withClasspath, then extended via Classpath.initWithPlatformModules
    // When: calling cp.findModule("java.base")
    // Then: post-fix Present(m: Symbol.Module) with exports containing "java.lang" and "java.util";
    //       before fix cp.moduleIndex.size == 0 because initWithPlatformModules did not exist
    //       and Classpath.init only walked the user-supplied roots (no JDK module discovery)
    // Pins: F-D-001, F-D-002
    "F-D-001 (Phase 10): Classpath.initWithPlatformModules discovers java.base with exports" in pending

    // F-G-003 leaf 2 (Phase 10): java-lang-string-resolves
    // Given: the classpath loaded via Classpath.initWithPlatformModules with an empty roots Chunk
    // When: calling cp.findClassLike("java.lang.String")
    // Then: post-fix Present(_);
    //       before fix Absent because JDK classes were never on the classpath
    // Pins: F-G-003
    "F-G-003 (Phase 10): cp.findClassLike(java.lang.String) returns Present after initWithPlatformModules" in pending

    // HARD RULE 4 leaf 3 (Phase 10): user-roots-still-load
    // Given: Classpath.initWithPlatformModules(Chunk(kyoTastyJar)) where kyoTastyJar is from TestClasspaths
    // When: calling cp.findClassLike("kyo.Tasty")
    // Then: post-fix Present(_); user roots are merged with JDK module discovery;
    //       HARD RULE 4 (layer-don't-restrict): initWithPlatformModules must not break init contract
    // Pins: HARD RULE 4 (helper preserves init contract)
    "Phase 10 HARD RULE 4: initWithPlatformModules merges user roots with JDK modules" in pending

    // F-D-001 cross-platform leaf 4 (Phase 10): js-native-throws-unsupported
    // Given: the JS / Native test runtime (or simulated via a platform guard)
    // When: calling Tasty.Classpath.initWithPlatformModules(Chunk.empty)
    // Then: post-fix the call fails with TastyError.UnsupportedPlatform whose message mentions JVM-only;
    //       before fix the method did not exist at all
    // Pins: F-D-001 cross-platform contract
    "F-D-001 (Phase 10): initWithPlatformModules on non-JVM platform fails with TastyError.UnsupportedPlatform" in pending

end JpmsFidelityTest
