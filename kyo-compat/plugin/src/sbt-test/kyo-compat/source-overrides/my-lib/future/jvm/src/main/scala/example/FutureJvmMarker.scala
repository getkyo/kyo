package example

// Per-cell override. Lives at
// <base>/-future/jvm/src/main/scala/. Expected to reach ONLY myLibFuture
// (the Future/JVM cell) and no other cell.
object FutureJvmMarker { val tag = "future-jvm-only" }
