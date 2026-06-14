package kyo.ffi

/** Thrown by generated code when a binding method declared with a bare `Handle[A]` return type receives a NULL pointer from C.
  *
  * A bare `Handle[A]` return is a non-null contract: the user asserts that the C function never returns NULL. If NULL is returned anyway,
  * this exception is thrown immediately rather than propagating a null handle. To accept nullable returns, declare the method as returning
  * `Maybe[Handle[A]]` instead.
  */
final class FfiNullPointer(msg: String) extends RuntimeException(msg)
