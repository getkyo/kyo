package kyo.internal

import kyo.*
import kyo.ffi.*

/** Owns the parent's ends of an inter-stage pipe until every helper has inherited its configured endpoint. */
final private[kyo] class ProcessDescriptorPair private (input: Int, output: Int):
    // Unsafe: descriptor transfers and closes are synchronous on Node's event loop, with no callback retaining these descriptors.
    private var inputOwned                     = true
    private var outputOwned                    = true
    def readDescriptor(using AllowUnsafe): Int =
        require(inputOwned)
        input
    def writeDescriptor(using AllowUnsafe): Int =
        require(outputOwned)
        output
    def takeRead()(using AllowUnsafe): Int =
        require(inputOwned)
        inputOwned = false
        input
    end takeRead
    def closeWrite()(using AllowUnsafe): Unit =
        if outputOwned then
            outputOwned = false
            ProcessNodeFs.closeSync(output)
    def close()(using AllowUnsafe): Unit =
        try
            if inputOwned then
                inputOwned = false
                ProcessNodeFs.closeSync(input)
        finally closeWrite()
end ProcessDescriptorPair

private[kyo] object ProcessDescriptorPair:
    def init(command: String)(using Frame): ProcessDescriptorPair < (Sync & Scope & Abort[CommandException]) =
        Scope.acquireRelease(Sync.Unsafe.defer {
            val bindings    = Ffi.load[ProcessPipeBindings]
            val descriptors = Buffer.alloc[Int](2)
            try
                val result = bindings.pair(descriptors)
                if result < 0 then Abort.fail(ProcessLaunchException(command, -result))
                else new ProcessDescriptorPair(descriptors.get(0), descriptors.get(1))
            finally descriptors.close()
            end try
        })(pair => Sync.Unsafe.defer(pair.close()))
end ProcessDescriptorPair
