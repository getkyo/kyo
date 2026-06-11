package kyo.test.runner.internal

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kyo.Chunk

/** Tests for [[SuiteDiscovery.discover]].
  *
  * The service-loader mechanism is tested by constructing a custom [[ClassLoader]] that serves a synthetic
  * `META-INF/services/kyo.test.Test` resource, then passing it to [[SuiteDiscovery.discover(ClassLoader)]].
  */
class SuiteDiscoveryTest extends kyo.test.Test[Any]:

    /** A minimal ClassLoader that delegates everything to `parent` except one synthetic resource. */
    private class FakeServiceLoader(
        parent: ClassLoader,
        serviceContent: String
    ) extends URLClassLoader(Array.empty[URL], parent):
        private val ServiceFile = "META-INF/services/kyo.test.Test"
        private val bytes       = serviceContent.getBytes(StandardCharsets.UTF_8)

        override def getResources(name: String): java.util.Enumeration[URL] =
            if name == ServiceFile then
                // Provide a synthetic in-memory resource using a custom URL
                val url = new URL(
                    "jar", // protocol placeholder (unused, just needs a scheme)
                    null,
                    0,
                    "synthetic-service-file",
                    new java.net.URLStreamHandler:
                        def openConnection(u: URL): java.net.URLConnection =
                            new java.net.URLConnection(u):
                                def connect(): Unit = ()
                                override def getInputStream: java.io.InputStream =
                                    new ByteArrayInputStream(bytes)
                )
                Collections.enumeration(java.util.List.of(url))
            else
                super.getResources(name)
    end FakeServiceLoader

    // ── Test: discover returns classes listed in the service file ─────────────────────────────

    "discovers Test subclasses listed in the service-loader file" in {
        // Use a real Test subclass that is already on the test classpath.
        // We pick a concrete class that extends Test and can be loaded by the parent CL.
        val targetClass = classOf[kyo.test.runner.SuiteDiscoveryFixture]
        val fqn         = targetClass.getName

        val loader = new FakeServiceLoader(
            Thread.currentThread().getContextClassLoader,
            fqn + "\n"
        )

        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.size == 1): Unit
        assert(discovered.head.getName == targetClass.getName): Unit
    }

    // ── Test: blank lines and comments are ignored ────────────────────────────────────────────

    "ignores blank lines and comment lines in the service file" in {
        val targetClass = classOf[kyo.test.runner.SuiteDiscoveryFixture]
        val fqn         = targetClass.getName

        val content =
            s"""# This is a comment
               |
               |$fqn
               |  # another comment
               |
               |""".stripMargin

        val loader     = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, content)
        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.size == 1): Unit
        assert(discovered.head.getName == targetClass.getName): Unit
    }

    // ── Test: non-Test classes are skipped with a warning ────────────────────────────────────

    "skips classes that do not extend kyo.test.Test" in {
        val content = "java.lang.String\n"
        val loader  = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, content)
        // Should not throw; should return empty because String does not extend Test
        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.isEmpty): Unit
    }

    // ── Test: unknown class names are skipped with a warning ──────────────────────────────────

    "skips class names that cannot be loaded" in {
        val content = "com.nonexistent.Totally\n"
        val loader  = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, content)
        // Should not throw; should return empty
        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.isEmpty): Unit
    }

    // ── Test: empty service file returns empty Chunk ──────────────────────────────────────────

    "returns empty Chunk when service file is empty" in {
        val loader     = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, "")
        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.isEmpty): Unit
    }

    // ── Test: no service file returns empty Chunk ─────────────────────────────────────────────

    "returns empty Chunk when no service file is present" in {
        // Use the real CL but a custom loader that never serves the service file
        val loader = new URLClassLoader(
            Array.empty[URL],
            Thread.currentThread().getContextClassLoader
        ):
            override def getResources(name: String): java.util.Enumeration[URL] =
                if name == "META-INF/services/kyo.test.Test" then
                    Collections.emptyEnumeration()
                else
                    super.getResources(name)
        val discovered = SuiteDiscovery.discover(loader)
        assert(discovered.isEmpty): Unit
    }

    // ── Test 15: malformed service file entry propagates to DiscoveryResult.errors ──────────────

    "test-15: discoverDetailed with an unloadable class name returns DiscoveryResult with non-empty errors" in {
        val content = "com.totally.NonexistentClass\n"
        val loader  = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, content)
        val result  = SuiteDiscovery.discoverDetailed(loader)
        assert(result.classes.isEmpty): Unit
        assert(result.errors.nonEmpty): Unit
        // The error message must mention the offending class name
        assert(result.errors.exists(_.contains("com.totally.NonexistentClass"))): Unit
    }

    // ── Test 16: clean service file returns DiscoveryResult with empty errors ──────────────────

    "test-16: discoverDetailed with a valid service file returns errors == Chunk.empty" in {
        // Use a real Test subclass that is on the test classpath
        val targetClass = classOf[kyo.test.runner.SuiteDiscoveryFixture]
        val content     = targetClass.getName + "\n"
        val loader      = new FakeServiceLoader(Thread.currentThread().getContextClassLoader, content)
        val result      = SuiteDiscovery.discoverDetailed(loader)
        assert(result.errors == Chunk.empty): Unit
        assert(result.classes.size == 1): Unit
        assert(result.classes.head.getName == targetClass.getName): Unit
    }

    // ── Test 17: readLines does not leak InputStream when IOException thrown during construction ──

    "phase7-leaf-9: readLines closes the underlying InputStream when the reader's first read throws" in {
        // InputStreamReader(in, "UTF-8") wraps the InputStream in a StreamDecoder.
        // If the InputStream's read(byte[], int, int) throws during the very first
        // decoder initialisation read, it is equivalent to a construction-path throw:
        // the BufferedReader object exists but its first use (lines()) immediately
        // re-throws, while the outer finally block must still call in.close().
        // We verify that by tracking close() calls on a custom InputStream.
        val closeCount = new AtomicInteger(0)
        // An InputStream that succeeds on read() returning -1 (empty), so that the
        // InputStreamReader constructor itself completes, but throws IOException on
        // the first real buffered read triggered by lines().iterator().
        val failingAfterConstruct = new InputStream:
            private val callCount    = new AtomicInteger(0)
            override def read(): Int = -1
            override def read(b: Array[Byte]): Int =
                if callCount.getAndIncrement() == 0 then
                    throw new IOException("simulated first-read failure after IS construction")
                else -1
            override def read(b: Array[Byte], off: Int, len: Int): Int =
                if callCount.getAndIncrement() == 0 then
                    throw new IOException("simulated first-read failure after IS construction")
                else -1
            override def close(): Unit = closeCount.incrementAndGet(): Unit

        // Build a URL whose openStream() returns the stream above.
        val url = new URL(
            "jar",
            null,
            0,
            "failing-stream",
            new java.net.URLStreamHandler:
                def openConnection(u: URL): java.net.URLConnection =
                    new java.net.URLConnection(u):
                        def connect(): Unit                      = ()
                        override def getInputStream: InputStream = failingAfterConstruct
        )

        // Build a ClassLoader that serves one URL with the failing stream.
        val loader = new URLClassLoader(Array.empty[URL], Thread.currentThread().getContextClassLoader):
            override def getResources(name: String): java.util.Enumeration[URL] =
                if name == "META-INF/services/kyo.test.Test" then
                    Collections.enumeration(java.util.List.of(url))
                else
                    super.getResources(name)

        // discoverDetailed calls readLines; the IOException propagates wrapped in UncheckedIOException.
        var caughtException = false
        try SuiteDiscovery.discoverDetailed(loader): Unit
        catch
            case _: java.io.UncheckedIOException => caughtException = true
            case _: IOException                  => caughtException = true
        end try

        assert(
            caughtException,
            "Expected IOException/UncheckedIOException to propagate from discoverDetailed"
        ): Unit
        assert(
            closeCount.get() >= 1,
            s"Expected in.close() to be called at least once (outer finally), but closeCount == ${closeCount.get()}"
        ): Unit
    }

end SuiteDiscoveryTest
