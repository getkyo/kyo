package kyo.internal

import kyo.*
import kyo.ffi.*

class ProcessLaunchBindingsTest extends kyo.test.Test[Any]:

    // Unsafe: this suite exercises the native allocation boundary and joins each producer before freeing its memory.
    import AllowUnsafe.embrace.danger

    private def withLaunch[A, S](directory: String)(body: (
        Maybe[Ffi.Handle[ProcessLaunchHandle]],
        Int,
        ProcessLaunchBindings
    ) => A < S)(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & S) =
        Scope.run {
            Sync.defer {
                val bindings = Ffi.load[ProcessLaunchBindings]
                Scope.acquireRelease(Sync.defer {
                    val error = Buffer.alloc[Int](1)
                    try (error, bindings.init(directory, error))
                    catch
                        case failure: Throwable =>
                            error.close()
                            throw failure
                    end try
                }) { (error, producer) =>
                    producer.uninterruptible().safe.get.map {
                        case Present(handle) => bindings.release(handle).safe.get.map(result => assert(result == 0))
                        case Absent          => Kyo.unit
                    }.handle(Sync.ensure(error.close()))
                }.map { (error, producer) =>
                    producer.uninterruptible().safe.get.map(handle => body(handle, error.get(0), bindings))
                }
            }
        }

    "preparation returns private paths and release removes them" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        withLaunch("") { (handle, error, bindings) =>
            assert(error == 0)
            handle match
                case Present(value) =>
                    val executable = Path(bindings.executable(value).value)
                    val endpoint   = Path(bindings.endpoint(value).value)
                    assert(executable.unsafe.exists().getOrThrow)
                    assert(endpoint.unsafe.exists().getOrThrow)
                    (executable, endpoint)
                case Absent => fail("Expected a prepared supervisor")
            end match
        }.map { (executable, endpoint) =>
            assert(!executable.unsafe.exists().getOrThrow)
            assert(!endpoint.unsafe.exists().getOrThrow)
        }
    }

    "an invalid temporary directory reports errno without a handle" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        // /dev/null is a file on supported Unix hosts, so creating a directory beneath it deterministically fails with ENOTDIR.
        withLaunch("/dev/null/child") { (handle, error, _) =>
            assert(handle.isEmpty)
            assert(error == 20)
        }
    }

    "cancel joins an acquisition without a connected supervisor before releasing its native owner" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        withLaunch("") { (handle, error, bindings) =>
            assert(error == 0)
            handle match
                case Present(value) =>
                    Scope.run {
                        // The carrier call can park on JVM/Native. Own its producer before awaiting it, and cancel without interrupting
                        // that producer: completion, rather than a fiber's interruption result, proves native memory is no longer in use.
                        Scope.acquireRelease(Sync.defer {
                            Fiber.Unsafe.init(Sync.defer(bindings.await(value, 7).uninterruptible().safe.get))
                        }) { producer =>
                            Sync.defer(assert(bindings.cancel(value) == 0)).andThen(producer.uninterruptible().safe.get.unit)
                        }.map { producer =>
                            Sync.defer(assert(bindings.cancel(value) == 0)).andThen {
                                producer.uninterruptible().safe.get.map { result =>
                                    assert(result == (if Platform.isMac then -89 else -125))
                                    assert(bindings.take_control(value) == -9)
                                }
                            }
                        }
                    }
                case Absent => fail("Expected a prepared supervisor")
            end match
        }
    }

    "interrupting a consumer preserves the native producer until cancellation and join" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        withLaunch("") { (handle, error, bindings) =>
            assert(error == 0)
            val launch = handle.get
            Scope.run {
                Scope.acquireRelease(Sync.defer {
                    Fiber.Unsafe.init(Sync.defer(bindings.await(launch, 7).uninterruptible().safe.get))
                }) { producer =>
                    Sync.defer(assert(bindings.cancel(launch) == 0)).andThen(producer.uninterruptible().safe.get.unit)
                }.map { producer =>
                    Sync.defer(producer.uninterruptible()).map { observer =>
                        for
                            consumer    <- Fiber.init(observer.safe.get)
                            _           <- assertEventually(observer.waiters() == 1)
                            _           <- consumer.interrupt
                            interrupted <- consumer.getResult
                            _           <- Sync.defer {
                                assert(interrupted.isPanic)
                                assert(producer.poll().isEmpty)
                                assert(bindings.cancel(launch) == 0)
                            }
                            result <- producer.uninterruptible().safe.get
                        yield assert(result == (if Platform.isMac then -89 else -125))
                    }
                }
            }
        }
    }
end ProcessLaunchBindingsTest
