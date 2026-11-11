package kyo.internal

import kyo.*

private[kyo] object ProcessControlPlatform:
    def init(launch: ProcessLaunch)(using Frame): ProcessControl < (Sync & Scope) =
        ProcessPipe.acquire(launch, 3).map { pipe =>
            // Unsafe: the pipe already has an owner before the reader starts; close joins the reader before the outer pipe release.
            Scope.acquireRelease(Sync.Unsafe.defer {
                ProcessControl.init(
                    (bytes, offset, length) => Sync.defer(pipe.read(bytes, offset, length)),
                    value => pipe.write(Array(value.toByte), 0, 1),
                    () => pipe.closeAndWait()
                )
            })(_.close())
        }
end ProcessControlPlatform
