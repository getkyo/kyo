package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import scala.scalanative.unsafe.*

/** Phase 2 isolation tests: systematically narrow down what causes the TLS crash.
  *
  * Phase 1 found that NativeTlsBaselineTest crashes on the VERY FIRST TLS connection, but HTTP-level tests pass ~15-33 tests (including TLS
  * variants) before crashing. These experiments isolate exactly what triggers the crash.
  */
class NativeTlsIsolationTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isMacOS = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")

    private def onMacOS(
        f: => Assertion < (Async & Abort[HttpException] & Scope)
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        if !isMacOS then succeed
        else f

    // ── helpers ──────────────────────────────────────────────

    private def acceptOne(
        listener: TransportListener[KqueueConnection]
    )(using Frame): KqueueConnection < (Async & Abort[HttpException]) =
        listener.connections.take(1).run.map { chunk =>
            if chunk.isEmpty then
                Abort.panic(new Exception("No connection accepted"))
            else
                chunk(0)
        }

    private def readN(
        conn: KqueueConnection,
        limit: Int
    )(using Frame): Array[Byte] < Async =
        val acc = new java.io.ByteArrayOutputStream()
        Loop.foreach {
            if acc.size() >= limit then
                Loop.done(acc.toByteArray)
            else
                conn.read.take(1).run.map { chunk =>
                    if chunk.isEmpty then
                        Loop.done(acc.toByteArray)
                    else
                        val span = chunk(0)
                        acc.write(span.toArrayUnsafe, 0, span.size)
                        if acc.size() >= limit then Loop.done(acc.toByteArray)
                        else Loop.continue
                }
        }
    end readN

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 1: C-level sanity (no Kyo, no fibers)
    // Test the OpenSSL bindings directly without any Kyo effects.
    // ─────────────────────────────────────────────────────────────────────────

    "C-level: create and free client ctx" in run {
        onMacOS {
            Sync.Unsafe.defer {
                import TlsBindings.*
                val ctx = tlsCtxNew(0) // client
                assert(ctx != 0L, "tlsCtxNew(0) returned null")
                tlsCtxFree(ctx)
                succeed
            }
        }
    }

    "C-level: create and free server ctx" in run {
        onMacOS {
            Sync.Unsafe.defer {
                import TlsBindings.*
                val ctx = tlsCtxNew(1) // server
                assert(ctx != 0L, "tlsCtxNew(1) returned null")
                Zone {
                    val certResult = tlsCtxSetCert(
                        ctx,
                        toCString(TlsTestHelper.certPath),
                        toCString(TlsTestHelper.keyPath)
                    )
                    assert(certResult == 0, s"tlsCtxSetCert failed: $certResult")
                }
                tlsCtxFree(ctx)
                succeed
            }
        }
    }

    "C-level: create ssl from ctx" in run {
        onMacOS {
            Sync.Unsafe.defer {
                import TlsBindings.*
                val ctx = tlsCtxNew(0)
                assert(ctx != 0L, "tlsCtxNew returned null")
                val ssl = Zone { tlsNew(ctx, toCString("localhost")) }
                assert(ssl != 0L, "tlsNew returned null")
                tlsFree(ssl)
                tlsCtxFree(ctx)
                succeed
            }
        }
    }

    "C-level: multiple ssl from same ctx" in run {
        onMacOS {
            Sync.Unsafe.defer {
                import TlsBindings.*
                val ctx = tlsCtxNew(0)
                assert(ctx != 0L, "tlsCtxNew returned null")
                // Create 5 SSL objects from one context
                val ssl0 = Zone { tlsNew(ctx, toCString("localhost")) }
                val ssl1 = Zone { tlsNew(ctx, toCString("localhost")) }
                val ssl2 = Zone { tlsNew(ctx, toCString("localhost")) }
                val ssl3 = Zone { tlsNew(ctx, toCString("localhost")) }
                val ssl4 = Zone { tlsNew(ctx, toCString("localhost")) }
                assert(ssl0 != 0L, "tlsNew returned null for ssl 0")
                assert(ssl1 != 0L, "tlsNew returned null for ssl 1")
                assert(ssl2 != 0L, "tlsNew returned null for ssl 2")
                assert(ssl3 != 0L, "tlsNew returned null for ssl 3")
                assert(ssl4 != 0L, "tlsNew returned null for ssl 4")
                // Free all ssl objects first, then the ctx
                tlsFree(ssl0)
                tlsFree(ssl1)
                tlsFree(ssl2)
                tlsFree(ssl3)
                tlsFree(ssl4)
                tlsCtxFree(ctx)
                succeed
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 2: TLS handshake without data exchange
    // Same as the baseline test's "single TLS connection" but DON'T exchange
    // any data — just connect, handshake, close. This tells us if the crash
    // is in the handshake or in the read/write path.
    // ─────────────────────────────────────────────────────────────────────────

    "TLS handshake only (no data exchange)" in run {
        Scope.run {
            onMacOS {
                val transport = new KqueueNativeTransport
                transport.listen("127.0.0.1", 0, 128, Present(TlsTestHelper.serverTlsConfig)).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            // No data exchange — just close immediately after handshake
                            transport.closeNow(serverConn)
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Present(TlsTestHelper.clientTlsConfig)).map { clientConn =>
                            // Handshake has completed (connect returns after handshake).
                            // No data exchange — just close.
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 3: Server-only TLS setup
    // Create a TLS server (listen with serverTlsConfig) but DON'T connect any
    // clients. Just create the server and immediately close it. Does creating
    // the TLS server context itself crash?
    // ─────────────────────────────────────────────────────────────────────────

    "server-only TLS setup (no client connects)" in run {
        Scope.run {
            onMacOS {
                val transport = new KqueueNativeTransport
                transport.listen("127.0.0.1", 0, 128, Present(TlsTestHelper.serverTlsConfig)).map { listener =>
                    // Server is up with TLS context. Don't connect any clients.
                    // Just verify the listener was created successfully and close.
                    assert(listener.port > 0)
                    listener.close.andThen(succeed)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 4: Client-only TLS to a plain server
    // Skip the TLS server, just try transport.connect with TLS config to a
    // non-TLS server. The client will try TLS handshake to a non-TLS server,
    // which should fail — but does it crash or just return an error?
    // ─────────────────────────────────────────────────────────────────────────

    "client TLS to plain server (should error, not crash)" in run {
        Scope.run {
            onMacOS {
                val transport = new KqueueNativeTransport
                // Create a plain (non-TLS) server
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        // Accept one connection on the plain server — it will receive
                        // TLS handshake bytes as raw TCP data, which it won't understand.
                        acceptOne(listener).map { serverConn =>
                            // Read whatever the client sends (it will be TLS ClientHello bytes)
                            // and just close — the client should get a handshake failure.
                            readN(serverConn, 1024).map { _ =>
                                transport.closeNow(serverConn)
                            }
                        }
                    }
                    serverFiber.andThen {
                        // Try to connect with TLS to a plain server — should fail with an error
                        Abort.run[HttpException] {
                            transport.connect("127.0.0.1", listener.port, Present(TlsTestHelper.clientTlsConfig))
                        }.map { result =>
                            // We expect this to fail (handshake error), NOT crash.
                            // Either result is acceptable — the key question is: does it crash?
                            java.lang.System.err.println(s"[Experiment 4] TLS to plain server result: $result")
                            succeed
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 5: Stack size check
    // We can't set env vars from test code. This test prints the current stack
    // size env var so the user can confirm whether a larger stack is in effect.
    //
    // To test the stack size hypothesis, run with:
    //   SCALANATIVE_THREAD_STACK_SIZE=67108864 sbt 'kyo-httpNative/testOnly kyo.internal.NativeTlsIsolationTest'
    // (67108864 = 64MB, which is 4x the default 16MB)
    // ─────────────────────────────────────────────────────────────────────────

    "stack size: report current settings" in run {
        onMacOS {
            Sync.defer {
                val stackSize    = java.lang.System.getenv("SCALANATIVE_THREAD_STACK_SIZE")
                val defaultStack = "16777216" // 16MB default
                java.lang.System.err.println(s"[Experiment 5] SCALANATIVE_THREAD_STACK_SIZE=$stackSize")
                java.lang.System.err.println(s"[Experiment 5] Default is $defaultStack (16MB)")
                if stackSize != null then
                    java.lang.System.err.println(s"[Experiment 5] Custom stack size detected: ${stackSize.toLong / 1024 / 1024}MB")
                else
                    java.lang.System.err.println("[Experiment 5] Using default stack size. To test larger stack:")
                    java.lang.System.err.println("[Experiment 5]   SCALANATIVE_THREAD_STACK_SIZE=67108864 sbt '...'")
                end if
                succeed
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Experiment 6: Handshake with explicit stack usage measurement
    // Before and after the TLS handshake, log the approximate stack depth
    // using Thread.currentThread().getStackTrace.length.
    // ─────────────────────────────────────────────────────────────────────────

    "stack depth: measure before and after TLS handshake" in run {
        Scope.run {
            onMacOS {
                val transport   = new KqueueNativeTransport
                val depthBefore = Thread.currentThread().getStackTrace.length
                java.lang.System.err.println(s"[Experiment 6] Stack depth before listen: $depthBefore")

                transport.listen("127.0.0.1", 0, 128, Present(TlsTestHelper.serverTlsConfig)).map { listener =>
                    val depthAfterListen = Thread.currentThread().getStackTrace.length
                    java.lang.System.err.println(s"[Experiment 6] Stack depth after listen: $depthAfterListen")

                    val serverFiber = Fiber.initUnscoped {
                        val serverDepth = Thread.currentThread().getStackTrace.length
                        java.lang.System.err.println(s"[Experiment 6] Server fiber stack depth before accept: $serverDepth")
                        acceptOne(listener).map { serverConn =>
                            val serverAcceptDepth = Thread.currentThread().getStackTrace.length
                            java.lang.System.err.println(s"[Experiment 6] Server fiber stack depth after accept: $serverAcceptDepth")
                            serverConn.write(Span.fromUnsafe("hello".getBytes(Utf8))).andThen {
                                val serverWriteDepth = Thread.currentThread().getStackTrace.length
                                java.lang.System.err.println(s"[Experiment 6] Server fiber stack depth after write: $serverWriteDepth")
                                readN(serverConn, 5).map { bytes =>
                                    val serverReadDepth = Thread.currentThread().getStackTrace.length
                                    java.lang.System.err.println(s"[Experiment 6] Server fiber stack depth after read: $serverReadDepth")
                                    assert(new String(bytes, Utf8) == "world")
                                    transport.closeNow(serverConn)
                                }
                            }
                        }
                    }
                    serverFiber.andThen {
                        val clientDepthBeforeConnect = Thread.currentThread().getStackTrace.length
                        java.lang.System.err.println(s"[Experiment 6] Client stack depth before connect: $clientDepthBeforeConnect")

                        transport.connect("127.0.0.1", listener.port, Present(TlsTestHelper.clientTlsConfig)).map { clientConn =>
                            val clientDepthAfterConnect = Thread.currentThread().getStackTrace.length
                            java.lang.System.err.println(
                                s"[Experiment 6] Client stack depth after connect (post-handshake): $clientDepthAfterConnect"
                            )

                            readN(clientConn, 5).map { bytes =>
                                val clientReadDepth = Thread.currentThread().getStackTrace.length
                                java.lang.System.err.println(s"[Experiment 6] Client stack depth after read: $clientReadDepth")
                                assert(new String(bytes, Utf8) == "hello")
                                clientConn.write(Span.fromUnsafe("world".getBytes(Utf8))).andThen {
                                    val clientWriteDepth = Thread.currentThread().getStackTrace.length
                                    java.lang.System.err.println(s"[Experiment 6] Client stack depth after write: $clientWriteDepth")
                                    transport.closeNow(clientConn).andThen(succeed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end NativeTlsIsolationTest
