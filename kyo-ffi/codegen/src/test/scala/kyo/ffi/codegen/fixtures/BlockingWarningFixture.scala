package kyo.ffi.codegen.fixtures

import kyo.*
import kyo.ffi.*

/** Fixture whose `read` method resolves to C symbol `read`, a member of the blocking allowlist. The method is *not* marked
  * `@Ffi.blocking`, so the generator should emit a warning. It still takes a trailing `(using AllowUnsafe)`: every binding method does.
  */
trait BlockingWarningFixture extends Ffi:
    def read(fd: Int, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int
end BlockingWarningFixture
