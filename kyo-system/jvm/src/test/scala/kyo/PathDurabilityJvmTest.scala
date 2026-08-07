package kyo

import java.nio.file.Files as JFiles
import java.nio.file.FileSystems as JFileSystems
import java.nio.file.attribute.PosixFilePermissions
import kyo.internal.Platform

class PathDurabilityJvmTest extends kyo.test.Test[Any]:

    private def directory(using Frame): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("durable-jvm-paths"))(handle =>
            // Unsafe: this scope owns the temporary directory and its contents.
            Sync.Unsafe.defer(handle.remove())
        ).map(_.path)

    for operation <- Seq("replace", "create", "ensure") do
        s"an unloadable native helper produces typed failures before $operation, including repeated calls" in {
            directory.map { root =>
                val invalidLibrary = root / "invalid-native-library"
                val javaName       = if Platform.isWindows then "java.exe" else "java"
                val javaBin        = Path(java.lang.System.getProperty("java.home").nn) / "bin" / javaName
                for
                    _ <- FileSystem.host.writeBytes(invalidLibrary, Span(1.toByte, 2.toByte), Path.WriteOptions())
                    _ <- FileSystem.host.writeBytes(root / "target.bin", Span(7.toByte, 8.toByte), Path.WriteOptions())
                    result <- Command(
                        javaBin.toString,
                        "--enable-native-access=ALL-UNNAMED",
                        s"-Dkyo.ffi.kyo_system_durable.path=$invalidLibrary",
                        "-cp",
                        java.lang.System.getProperty("java.class.path").nn,
                        "kyo.PathDurabilityJvmTestMain",
                        root.toString,
                        operation
                    ).redirectErrorStream(true).textWithExitCode
                yield
                    assert(result._2 == ExitCode.Success, result._1)
                    assert(result._1.linesIterator.filter(_ == "typed-failure-twice").toSeq == Seq("typed-failure-twice"))
                end for
            }
        }
    end for

    if !Platform.isWindows then
        "a literal POSIX backslash path fails before replacement or temporary creation" in {
            directory.map { root =>
                val nativeTarget = root.toJava.resolve("literal\\name.bin")
                val target       = Path.of(nativeTarget)
                val original     = Span(8.toByte, 9.toByte)
                for
                    _ <- Sync.defer {
                        discard(JFiles.write(nativeTarget, original.toArray))
                        discard(JFiles.setPosixFilePermissions(nativeTarget, PosixFilePermissions.fromString("rw-------")))
                    }
                    result  <- Abort.run[FileSystemException](FileSystem.host.durableReplace(target, Span(1.toByte)))
                    actual  <- Sync.defer(Span.from(JFiles.readAllBytes(nativeTarget)))
                    mode    <- Sync.defer(PosixFilePermissions.toString(JFiles.getPosixFilePermissions(nativeTarget)))
                    entries <- FileSystem.host.list(root)
                yield
                    result match
                        case Result.Failure(_: FileInvalidPathException) => ()
                        case other => fail(s"Expected an invalid native path before replacement, got $other")
                    assert(actual.is(original))
                    assert(mode == "rw-------")
                    assert(entries == Chunk(target))
                end for
            }
        }
    end if

    "a nondefault NIO provider cannot create host directories or replace its entry" in {
        directory.map { root =>
            val archive    = root / "fixture.zip"
            val hostParent = root.toJava.resolve("provider-parent")
            val original   = Span(7.toByte, 8.toByte)
            Scope.acquireRelease(
                Sync.defer(JFileSystems.newFileSystem(archive.toJava, java.util.Map.of[String, String]("create", "true")))
            )(zip => Sync.defer(zip.close())).map { zip =>
                val nativeTarget = zip.getPath(hostParent.resolve("target.bin").toString.replace('\\', '/'))
                val target       = Path.of(nativeTarget)
                for
                    _ <- Sync.defer {
                        discard(JFiles.createDirectories(nativeTarget.getParent))
                        discard(JFiles.write(nativeTarget, original.toArray))
                    }
                    absentBefore <- Sync.defer(!JFiles.exists(hostParent))
                    result       <- Abort.run[FileSystemException](FileSystem.host.durableReplace(target, Span(1.toByte)))
                    actual       <- Sync.defer(Span.from(JFiles.readAllBytes(nativeTarget)))
                    absentAfter  <- Sync.defer(!JFiles.exists(hostParent))
                    entries      <- FileSystem.host.list(root)
                yield
                    assert(absentBefore)
                    result match
                        case Result.Failure(_: FileInvalidPathException) => ()
                        case other => fail(s"Expected rejection of a nondefault filesystem before host mutation, got $other")
                    assert(actual.is(original))
                    assert(absentAfter, "Native replacement created directories outside the NIO provider")
                    assert(entries == Chunk(archive))
                end for
            }
        }
    }

end PathDurabilityJvmTest

/** Fresh JVM entry point: a failed generated binding initializer must not poison filesystem error handling. */
object PathDurabilityJvmTestMain:
    def main(args: Array[String]): Unit =
        // Unsafe: this standalone test process evaluates operations only within its parent's owned temporary directory.
        import kyo.AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        val root    = Path(args(0))
        val target  = root / "target.bin"
        for _ <- 1 to 2 do
            val operation: Unit < (Sync & Abort[FileSystemException]) = args(1) match
                case "replace" => FileSystem.host.durableReplace(target, Span(1.toByte, 2.toByte))
                case "create"  => FileSystem.host(root).map(_.privateTempDir("private-create").unit)
                case "ensure"  => FileSystem.host.privateMkDir(root / "private-ensure")
                case other     => throw new IllegalArgumentException(s"Unknown operation: $other")
            Sync.Unsafe.evalOrThrow(Abort.run[FileSystemException](operation)) match
                case Result.Failure(_: FileIOException) => ()
                case other                              => throw new AssertionError(s"Expected a typed FileIOException, got $other")
            require(java.util.Arrays.equals(JFiles.readAllBytes(target.toJava), Array[Byte](7, 8)), "Target bytes changed")
            val entries = JFiles.list(root.toJava)
            try
                val names = entries.toArray.map(_.asInstanceOf[java.nio.file.Path].getFileName.toString).toSet
                require(names == Set("target.bin", "invalid-native-library"), s"Unexpected temporary: $names")
            finally entries.close()
            end try
        end for
        java.lang.System.out.println("typed-failure-twice")
    end main
end PathDurabilityJvmTestMain
