package kyo.internal

import java.io.IOException
import kyo.*
import kyo.ffi.*

private[kyo] object ProcessControlPlatform:
    def init(launch: ProcessLaunch)(using Frame): ProcessControl < (Sync & Scope) =
        // Unsafe: transfer and libuv adoption happen in one acquisition step, with no interruptible handover.
        Scope.acquireRelease(Sync.Unsafe.defer {
            val bindings   = Ffi.load[ProcessPipeBindings]
            val descriptor = launch.bindings.take_control(launch.handle)
            if descriptor < 0 then throw new IOException(s"Process control transfer failed: errno ${-descriptor}")
            val stream = ProcessNodeInputStream.initControl(descriptor, bindings)
            try ProcessControl.init(stream.readAsync, stream.sendRequest, () => stream.closeAndWait())
            catch
                case error: Throwable =>
                    stream.close()
                    throw error
            end try
        })(_.close())
end ProcessControlPlatform
