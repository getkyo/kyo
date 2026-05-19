package example

// Per-backend override. Lives at
// <base>/<FutureLib.directorySuffix>/src/main/scala/, i.e. `my-lib/-future/...`.
// Expected to reach the Future/JVM, Future/JS, Future/Native cells and NONE
// of the Kyo cells.
object FutureMarker { val tag = "future-only" }
