package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.ffi.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

class ProcessNodeInputStreamPermissionTest extends kyo.test.Test[Any]:
    "cancelling a descriptor read requires unsafe permission" in {
        typeCheckFailure("""def cancel(stream: ProcessNodeInputStream) = stream.cancelRead()""")("AllowUnsafe")
        typeCheck("""def cancel(stream: ProcessNodeInputStream)(using AllowUnsafe) = stream.cancelRead()""")
    }

    "sending a process control byte requires unsafe permission" in {
        typeCheckFailure("""def send(stream: ProcessNodeInputStream) = stream.sendRequest(1)""")("AllowUnsafe")
        typeCheck("""def send(stream: ProcessNodeInputStream)(using AllowUnsafe) = stream.sendRequest(1)""")
    }
end ProcessNodeInputStreamPermissionTest

class ProcessNodeInputStreamTest extends kyo.test.Test[Any]:

    // Unsafe: real descriptor and callback tests at the Node host boundary. Every native producer is joined before release.
    import AllowUnsafe.embrace.danger

    private def withPipes[A, S](script: String)(body: (Int, ProcessNodeInputStream, ProcessNodeInputStream) => A < S)(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & S) =
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val launchBindings = Ffi.load[ProcessLaunchBindings]
            val pipeBindings   = Ffi.load[ProcessPipeBindings]
            var helper         = Maybe.empty[Process.Unsafe]
            Scope.run {
                Scope.ensure {
                    helper.fold(Kyo.unit) { process =>
                        process.waitFor().safe.get.map { code =>
                            try process.closeStreams()
                            catch case error: Throwable => fail(s"Supervisor stream cleanup failed: $error")
                            assert(code == ExitCode.Success)
                        }
                    }
                }.andThen {
                    Scope.run {
                        Scope.acquireRelease(Sync.defer {
                            val error = Buffer.alloc[Int](1)
                            try (error, launchBindings.init("", error))
                            catch
                                case failure: Throwable =>
                                    error.close()
                                    throw failure
                            end try
                        }) { (error, producer) =>
                            producer.uninterruptible().safe.get.map {
                                case Present(handle) => launchBindings.release(handle).safe.get.map(code => assert(code == 0))
                                case Absent          => Kyo.unit
                            }.handle(Sync.ensure(error.close()))
                        }.map { (error, producer) =>
                            producer.uninterruptible().safe.get.map { handle =>
                                assert(error.get(0) == 0)
                                val launch = handle.get
                                Sync.defer {
                                    helper = Present(Command(
                                        launchBindings.executable(launch).value,
                                        launchBindings.endpoint(launch).value,
                                        "",
                                        "7",
                                        "/bin/sh",
                                        "-c",
                                        script
                                    ).unsafe.spawnRaw().getOrThrow)
                                }.andThen {
                                    Scope.acquireRelease(Sync.defer(launchBindings.await(launch, 7))) { acquisition =>
                                        Sync.defer(
                                            assert(launchBindings.cancel(launch) == 0)
                                        ).andThen(acquisition.uninterruptible().safe.get.unit)
                                    }.map { acquisition =>
                                        acquisition.uninterruptible().safe.get.map { pid =>
                                            assert(pid > 0)
                                            def output(slot: Int): ProcessNodeInputStream < (Sync & Scope) =
                                                Scope.acquireRelease(Sync.defer {
                                                    ProcessNodeInputStream.init(launchBindings.take_stream(launch, slot), pipeBindings)
                                                })(_.closeAndWait())
                                            Scope.acquireRelease(Sync.defer(launchBindings.take_stream(launch, 0)))(fd =>
                                                Sync.defer(ProcessNodeFs.closeSync(fd))
                                            ).map { input =>
                                                output(1).map { stdout =>
                                                    output(2).map(stderr => body(input, stdout, stderr))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end withPipes

    private def writeLine(descriptor: Int)(using Frame, kyo.test.AssertScope): Unit < Sync = Sync.defer {
        val bytes = new Uint8Array(1)
        bytes(0) = 10
        assert(ProcessNodeFs.writeSync(descriptor, bytes) == 1)
    }

    private def readCount(stream: ProcessNodeInputStream, length: Int)(using Frame): String < Sync =
        Sync.defer {
            val bytes = new Array[Byte](length)
            Loop(0) { position =>
                Sync.defer {
                    val count = stream.read(bytes, position, length - position)
                    if count < 0 then throw new java.io.EOFException
                    else if position + count == length then Loop.done(new String(bytes, StandardCharsets.UTF_8))
                    else Loop.continue(position + count)
                }
            }
        }

    "a paused libuv reader preserves buffered and kernel prefixes but excludes subsequent writes" in {
        withPipes(
            "printf first; IFS= read -r line; printf kernel; printf ready >&2; IFS= read -r line; printf late; printf finished >&2; IFS= read -r line"
        ) {
            (input, output, errors) =>
                for
                    _     <- assertEventually(output.available() == 5)
                    _     <- writeLine(input)
                    ready <- readCount(errors, 5)
                    _     <- Sync.defer {
                        assert(ready == "ready")
                        assert(output.available() == 5)
                        output.cancelRead()
                        assert(output.available() == 11)
                    }
                    _        <- writeLine(input)
                    finished <- readCount(errors, 8)
                    prefix   <- readCount(output, 11)
                    result   <- Sync.defer {
                        assert(finished == "finished")
                        assert(prefix == "firstkernel")
                        assert(output.read() == -1)
                        output.cancelRead()
                        output.close()
                        output.cancelRead()
                        assert(output.available() == 0)
                    }
                yield result
        }
    }

    "libuv backpressure preserves output larger than the reusable read buffer" in {
        withPipes("i=0; while [ \"$i\" -lt 4000 ]; do printf abcd; i=$((i+1)); done") { (_, output, _) =>
            Scope.run(StreamCoreExtensions.streamFromJavaInputStream(output).run).map { bytes =>
                assert(new String(bytes.toArray, StandardCharsets.UTF_8) == "abcd" * 4000)
                assert(output.read() == -1)
            }
        }
    }

    "cancelling an idle reader returns EOF while its writer remains live" in {
        withPipes("IFS= read -r line; printf finished >&2; IFS= read -r line") { (input, output, errors) =>
            Sync.defer {
                assert(output.read(new Array[Byte](1)) == 0)
                output.cancelRead()
                assert(output.read() == -1)
            }.andThen(writeLine(input)).andThen(readCount(errors, 8)).map { result =>
                assert(result == "finished")
            }
        }
    }

    "a descriptor rejected by the libuv adapter is closed by its new owner" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val bindings   = Ffi.load[ProcessPipeBindings]
            val descriptor = ProcessNodeFs.openSync("/dev/null", "r")
            val rejected   = intercept[sjs.JavaScriptException] {
                ProcessNodeInputStream.init(descriptor, bindings)
            }
            // Node's documented errno errors carry a code property; these casts read the external exception record.
            assert(rejected.exception.asInstanceOf[sjs.Dynamic].code.asInstanceOf[String] == "ERR_INVALID_FD_TYPE")
            val closed = intercept[sjs.JavaScriptException](ProcessNodeFs.closeSync(descriptor))
            assert(closed.exception.asInstanceOf[sjs.Dynamic].code.asInstanceOf[String] == "EBADF")
            assert(bindings.available_descriptor(-1) == -9)
        }
    }
end ProcessNodeInputStreamTest
