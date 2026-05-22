package example

// Per-cell override. Lives at
// <base>/-kyo/native/src/main/scala/. Expected to reach ONLY myLibKyoNative
// (the Kyo/Native cell) and no other cell.
object KyoNativeMarker { val tag = "kyo-native-only" }
