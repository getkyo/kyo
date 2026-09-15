package kyo.internal

import kyo.AllowUnsafe
import kyo.ffi.*

/** Native fixtures for durable replacement permission tests.
  *
  * Fixtures expose the host ACL representation independently of the replacement implementation.
  * Configuration kinds select a distinct file ACL, inheritable parent ACL, or Windows empty/null DACL.
  * A negative return value reports a host error; snapshots return their byte length.
  */
private[kyo] trait PathPermissionTestBindings extends Ffi:
    def configure(path: String, kind: Int)(using AllowUnsafe): Int
    def snapshot(path: String, out: Buffer[Byte], capacity: Int)(using AllowUnsafe): Int
    def privateAccess(path: String)(using AllowUnsafe): Int
    def canInspectSecurity(path: String)(using AllowUnsafe): Int
end PathPermissionTestBindings

private[kyo] object PathPermissionTestBindings
    extends Ffi.Config(library = "kyo_system_permissions_test", symbolPrefix = "kyo_permissions_test_", nativeBundled = true)
