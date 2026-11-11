package kyo.internal

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.*

class ProcessPipeTest extends kyo.test.Test[Any]:

    // Unsafe: exercises java.io adapters and the synchronous JVM/Native foreign-call boundary with real owned descriptors.
    import AllowUnsafe.embrace.danger

    private def result[A](call: Fiber.Unsafe[A, Any])(using Frame): A = call.poll().get.getOrThrow.eval

    private def withPipes[A, S](script: String, transform: ProcessPipeBindings => ProcessPipeBindings)(
        body: (ProcessPipe, ProcessPipe, ProcessPipe) => A < S
    )(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & S) =
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val launchBindings = Ffi.load[ProcessLaunchBindings]
            val pipeBindings   = transform(Ffi.load[ProcessPipeBindings])
            val helper         = new AtomicReference[Maybe[Process.Unsafe]](Absent)
            Scope.run {
                // The inner scope closes control before this wait. Killing the helper would discard its ownership of the command.
                Scope.ensure {
                    Sync.defer(helper.get()).map {
                        case Present(process) =>
                            process.waitFor().safe.get.map { code =>
                                try assert(code == ExitCode.Success)
                                finally process.closeStreams()
                            }
                        case Absent => Kyo.unit
                    }
                }.andThen {
                    Scope.run {
                        Scope.acquireRelease(Sync.defer(Buffer.alloc[Int](1)))(error => Sync.defer(error.close())).map { error =>
                            Scope.acquireRelease(Sync.defer {
                                val handle = result(launchBindings.init("", error))
                                assert(error.get(0) == 0)
                                handle.get
                            })(handle => Sync.defer(assert(result(launchBindings.release(handle)) == 0))).map { launch =>
                                Sync.defer {
                                    val command = Command(
                                        launchBindings.executable(launch).value,
                                        launchBindings.endpoint(launch).value,
                                        "",
                                        "7",
                                        "/bin/sh",
                                        "-c",
                                        script
                                    )
                                    helper.set(Present(command.unsafe.spawnRaw().getOrThrow))
                                    assert(result(launchBindings.await(launch, 7)) > 0)
                                }.andThen {
                                    def pipe(slot: Int): ProcessPipe < (Sync & Scope) =
                                        Scope.acquireRelease(Sync.defer {
                                            val handle = result(launchBindings.take_pipe(launch, slot, error))
                                            assert(error.get(0) == 0)
                                            ProcessPipe.init(handle.get, pipeBindings)
                                        })(_.closeAndWait())
                                    for
                                        input  <- pipe(0)
                                        output <- pipe(1)
                                        errors <- pipe(2)
                                        value  <- body(input, output, errors)
                                    yield value
                                    end for
                                }
                            }
                        }
                    }
                }
            }
        }
    end withPipes

    "stdin writes preserve offsets and closing it lets stdout and stderr reach EOF" in {
        withPipes("cat; printf diagnostic >&2", identity) { (input, output, errors) =>
            Sync.defer {
                val writer = input.outputStream
                val bytes  = "xxpayloadyy".getBytes(StandardCharsets.UTF_8)
                writer.write(bytes, 2, 7)
                writer.close()
                assert(new String(output.inputStream.readAllBytes(), StandardCharsets.UTF_8) == "payload")
                assert(new String(errors.inputStream.readAllBytes(), StandardCharsets.UTF_8) == "diagnostic")
                input.close()
            }
        }
    }

    "ending stdout reads leaves its writer able to finish a separate handshake" in {
        withPipes("printf ready; IFS= read -r line; printf finished >&2", identity) { (input, output, errors) =>
            Sync.defer {
                val reader = output.inputStream
                val bytes  = new Array[Byte](5)
                assert(reader.read(bytes) == 5)
                assert(new String(bytes, StandardCharsets.UTF_8) == "ready")
                output.cancelRead()
                assert(reader.read() == -1)
                val writer = input.outputStream
                writer.write("continue\n".getBytes(StandardCharsets.UTF_8))
                writer.close()
                assert(new String(errors.inputStream.readAllBytes(), StandardCharsets.UTF_8) == "finished")
                reader.close()
                output.cancelRead()
            }
        }
    }

    "close during a retained read releases its native handle only after the call returns" in {
        Sync.defer {
            val output                                                        = new AtomicReference[Maybe[ProcessPipe]](Absent)
            val active                                                        = new AtomicBoolean(false)
            val releases                                                      = new AtomicInteger(0)
            def transform(delegate: ProcessPipeBindings): ProcessPipeBindings = new ProcessPipeBindings:
                def pair(descriptors: Buffer[Int])(using AllowUnsafe): Int        = delegate.pair(descriptors)
                def available_descriptor(descriptor: Int)(using AllowUnsafe): Int = delegate.available_descriptor(descriptor)
                def init(descriptor: Int, error: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Maybe[Ffi.Handle[ProcessPipeHandle]], Any] =
                    delegate.init(descriptor, error)
                def read(pipe: Ffi.Handle[ProcessPipeHandle], bytes: Buffer[Byte], capacity: Int)(using
                    AllowUnsafe
                ): Fiber.Unsafe[Int, Any] =
                    active.set(true)
                    try
                        // Force close at the native-call boundary, after the adapter has retained its handle.
                        output.get().get.close()
                        delegate.read(pipe, bytes, capacity)
                    finally active.set(false)
                    end try
                end read
                def write(pipe: Ffi.Handle[ProcessPipeHandle], bytes: Buffer[Byte], offset: Int, length: Int)(using
                    AllowUnsafe
                ): Fiber.Unsafe[Int, Any]                                               = delegate.write(pipe, bytes, offset, length)
                def cancel(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Int = delegate.cancel(pipe)
                def stop(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Int   = delegate.stop(pipe)
                def release(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
                    assert(!active.get())
                    releases.incrementAndGet(): Unit
                    delegate.release(pipe)
                end release
            withPipes("IFS= read -r line", transform) { (_, stream, _) =>
                Sync.defer {
                    output.set(Present(stream))
                    val reader = stream.inputStream
                    assert(reader.read() == -1)
                    assert(releases.get() == 1)
                    reader.close()
                    assert(releases.get() == 1)
                }
            }
        }
    }
end ProcessPipeTest
