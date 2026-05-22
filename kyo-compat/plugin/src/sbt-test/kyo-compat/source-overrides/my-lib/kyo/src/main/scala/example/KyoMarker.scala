package example

// Per-backend override. Lives at
// <base>/<KyoLib.directorySuffix>/src/main/scala/, i.e. `my-lib/-kyo/...`.
// Expected to reach the Kyo/JVM, Kyo/JS, Kyo/Native cells and NONE of the
// Future cells.
object KyoMarker { val tag = "kyo-only" }
