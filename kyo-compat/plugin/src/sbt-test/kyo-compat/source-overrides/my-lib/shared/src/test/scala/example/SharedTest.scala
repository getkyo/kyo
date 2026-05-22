package example

// Test source under <base>/shared/src/test/scala. The plugin's customRow
// process closure adds this dir to every cell's `Test/unmanagedSourceDirectories`.
// We do NOT use a test runtime (scalatest etc.) — checkSharedTestSourcesReachAllCells
// verifies the directory entry only; if a test framework call is added here it must
// be available across all 6 cells (JVM/JS/Native x Future/Kyo).
object SharedTest { val tag = "shared-test" }
