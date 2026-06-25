package kyo.ffi

/** Exception representing a C errno error.
  *
  * User code can throw this when inspecting [[Ffi.Outcome.errorCode]] and deciding an errno value constitutes a failure. The `apply`
  * factory method formats a human-readable message with binding and method context.
  *
  * @param errorCode
  *   the non-zero errno value captured after the C call
  * @param message
  *   human-readable message including binding name, method name, and error code
  */
class FfiErrno(val errorCode: Int, message: String) extends RuntimeException(message)

object FfiErrno:
    def apply(errorCode: Int, binding: String, method: String): FfiErrno =
        new FfiErrno(errorCode, s"C call $binding.$method failed with error code $errorCode")
end FfiErrno
