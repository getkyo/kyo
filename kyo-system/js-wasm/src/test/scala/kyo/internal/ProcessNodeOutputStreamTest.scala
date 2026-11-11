package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.ffi.*

class ProcessNodeOutputStreamPermissionTest extends kyo.test.Test[Any]:
    "submitting a process input write requires unsafe permission" in {
        typeCheckFailure("""def write(stream: ProcessNodeOutputStream) = stream.writePending(Array.emptyByteArray, 0, 0)""")("AllowUnsafe")
        typeCheck("""def write(stream: ProcessNodeOutputStream)(using AllowUnsafe) = stream.writePending(Array.emptyByteArray, 0, 0)""")
    }
end ProcessNodeOutputStreamPermissionTest

class ProcessNodeOutputStreamTest extends kyo.test.Test[Any]:

    // Unsafe: owns real descriptors and observes native callback completion through the Node boundary.
    import AllowUnsafe.embrace.danger

    private def withPipes[A, S](script: String)(body: (ProcessNodeOutputStream, ProcessNodeInputStream, ProcessControl) => A < S)(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & Abort[CommandException] & S) =
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Scope.run {
            ProcessLaunch.init("sh").map { launch =>
                launch.start("sh", 7) { (executable, endpoint) =>
                    Command(executable, endpoint, "", "7", "/bin/sh", "-c", script).unsafe.spawnRaw()
                }.map { _ =>
                    for
                        input <- Scope.acquireRelease(Sync.defer {
                            ProcessNodeOutputStream.init(launch.bindings.take_stream(launch.handle, 0))
                        })(_.closeAndWait())
                        output <- Scope.acquireRelease(Sync.defer {
                            ProcessNodeInputStream.init(launch.bindings.take_stream(launch.handle, 1), Ffi.load[ProcessPipeBindings])
                        })(_.closeAndWait())
                        control <- ProcessControlPlatform.init(launch)
                        value   <- body(input, output, control)
                    yield value
                }
            }
        }
    end withPipes

    "a callback-paced write preserves offsets and flushes before stdin EOF" in {
        withPipes("exec cat") { (input, output, control) =>
            val text  = "payload" * 32768
            val bytes = ("xx" + text + "yy").getBytes(StandardCharsets.UTF_8)
            for
                reader <- Fiber.init {
                    val result = new java.io.ByteArrayOutputStream
                    val buffer = new Array[Byte](8192)
                    Loop(()) { _ =>
                        output.readAsync(buffer, 0, buffer.length).map { count =>
                            if count < 0 then Loop.done(new String(result.toByteArray, StandardCharsets.UTF_8))
                            else
                                result.write(buffer, 0, count)
                                Loop.continue(())
                        }
                    }
                }
                _          <- input.writeAsync(bytes, 2, bytes.length - 4)
                _          <- input.finish()
                textResult <- reader.get
                code       <- control.waitFor.safe.get
                _          <- control.releaseGroup()
            yield
                assert(textResult == text)
                assert(code == ExitCode.Success)
            end for
        }
    }

    "closing stdin joins a queued write when the child does not read" in {
        withPipes("exec sleep 100000") { (input, _, control) =>
            Sync.defer(input.writePending(new Array[Byte](1024 * 1024), 0, 1024 * 1024)).map { producer =>
                input.closeAndWait().andThen(producer.safe.getResult).map { result =>
                    assert(result.isPanic)
                    control.releaseGroup()
                }
            }
        }
    }

    "writing after a child closes stdin reports the failed write" in {
        withPipes("exec 0<&-; printf ready; exec sleep 100000") { (input, output, control) =>
            val bytes = new Array[Byte](5)
            Loop(0) { offset =>
                output.readAsync(bytes, offset, bytes.length - offset).map { count =>
                    if count < 0 then fail("Command closed stdout before its stdin handshake")
                    else if offset + count == bytes.length then Loop.done(())
                    else Loop.continue(offset + count)
                }
            }.andThen {
                Sync.defer(input.writePending(Array[Byte](1), 0, 1)).map { producer =>
                    producer.safe.getResult.map { result =>
                        assert(result.isPanic)
                        assert(new String(bytes, StandardCharsets.UTF_8) == "ready")
                        control.releaseGroup()
                    }
                }
            }
        }
    }
end ProcessNodeOutputStreamTest
