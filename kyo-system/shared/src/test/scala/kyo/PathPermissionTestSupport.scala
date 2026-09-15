package kyo

import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.PathPermissionTestBindings

private[kyo] object PathPermissionTestSupport:

    private def bindings(using AllowUnsafe): PathPermissionTestBindings = Ffi.load[PathPermissionTestBindings]

    def configure(path: Path, kind: Int)(using Frame): Unit < Sync =
        // Unsafe: the fixture changes only paths owned by this test's temporary directory.
        Sync.Unsafe.defer {
            val result = bindings.configure(path.unsafe.hostPath.get, kind)
            if result != 0 then throw new java.io.IOException(s"Permission fixture setup failed for $path: $result")
        }

    def snapshot(path: Path)(using Frame): Span[Byte] < Sync =
        // Unsafe: the native snapshot writes into this locally owned buffer, closed before returning.
        Sync.Unsafe.defer {
            val buffer = Buffer.alloc[Byte](131072)
            try
                val length = bindings.snapshot(path.unsafe.hostPath.get, buffer, buffer.size)
                if length < 0 then throw new java.io.IOException(s"Permission snapshot failed for $path: $length")
                Span.from(Array.tabulate(length)(buffer.get(_)))
            finally buffer.close()
            end try
        }

    def privateAccess(path: Path)(using Frame): Int < Sync =
        // Unsafe: the fixture reads permissions without opening the file for content access.
        Sync.Unsafe.defer(bindings.privateAccess(path.unsafe.hostPath.get))

    def directory(using Frame): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("durable-permissions"))(handle =>
            // Unsafe: the scoped test owns this temporary directory.
            Sync.Unsafe.defer(handle.remove())
        ).map(_.path)

    def canInspectSecurity(path: Path)(using Frame): Boolean < Sync =
        // Unsafe: this independent probe checks host metadata access before attempting replacement.
        Sync.Unsafe.defer {
            bindings.canInspectSecurity(path.unsafe.hostPath.get) match
                case 0     => false
                case 1     => true
                case error => throw new java.io.IOException(s"Security metadata access probe failed for $path: $error")
        }

end PathPermissionTestSupport
