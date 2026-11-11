import java.nio.ByteBuffer
import java.nio.ByteOrder
import sbt.*
import scala.sys.process.Process

/** Builds the standalone parent process and embeds target-checked images in the C bridge. */
object KyoProcessBuild {
    val supervisorImage            = taskKey[File]("Build and embed the process supervisor for the native bridge.")
    val supervisorPublicationCheck = taskKey[Unit]("Require every Unix supervisor image before publishing the portable Native jar.")
    val nativeTests                = taskKey[Unit]("Test the process supervisor, launch protocol and cancellable pipes.")
    val nodeTests                  = taskKey[Unit]("Test descriptor transfer and libuv readiness with one foreign-call worker.")

    val targets = for {
        os   <- Seq("darwin", "linux", "linux-musl")
        arch <- Seq("aarch64", "x86_64")
    } yield s"$os-$arch"

    def filename(target: String): String = s"kyo-process-supervisor-$target"

    def test(source: File, image: File, output: File, compiler: String, flags: Seq[String], target: String, log: Logger): Unit = {
        if (!target.startsWith("windows-")) {
            val tests = output / "tests"
            IO.createDirectory(tests)
            val production = source / "main" / "c"
            val fixtures   = source / "test" / "c"
            val cc         = compiler.split("\\s+").toSeq ++ Seq("-std=c11", "-Wall", "-Wextra", "-Werror", "-pthread") ++ flags ++
                Seq("-UNDEBUG", "-I", production.getAbsolutePath, "-I", image.getParent)
            def run(command: Seq[String], environment: (String, String)*): Unit = {
                log.info(s"[kyo-system test] ${command.mkString(" ")}")
                require(Process(command, None, environment*).! == 0, "Native process test failed")
            }
            val pipe       = tests / "pipe.o"
            val executable = tests / "pipe-test"
            run(cc ++ Seq(
                "-Dpoll=kyo_process_pipe_test_poll",
                "-c",
                (production / "kyo_process_pipe.c").getAbsolutePath,
                "-o",
                pipe.getAbsolutePath
            ))
            run(cc ++ Seq((fixtures / "kyo_process_pipe_test.c").getAbsolutePath, pipe.getAbsolutePath, "-o", executable.getAbsolutePath))
            run(Seq(executable.getAbsolutePath))
            val failureTest = tests / "supervisor-failure-test"
            run(cc ++ Seq((fixtures / "kyo_process_supervisor_failure_test.c").getAbsolutePath, "-o", failureTest.getAbsolutePath))
            run(Seq(failureTest.getAbsolutePath))
            val library = tests / (if (target.startsWith("darwin-")) "launch.dylib" else "launch.so")
            run(cc ++ Seq(
                "-shared",
                "-fPIC",
                (production / "kyo_process_launch.c").getAbsolutePath,
                (production / "kyo_process_pipe.c").getAbsolutePath,
                "-o",
                library.getAbsolutePath
            ))
            val race = if (target.startsWith("linux-")) {
                val objectFile = tests / "supervisor-race.o"
                val binary     = tests / "supervisor-race"
                run(cc ++ Seq(
                    "-Dopendir=kyo_process_test_opendir",
                    "-Dfopen=kyo_process_test_fopen",
                    "-Dmain=kyo_process_supervisor_main",
                    "-c",
                    (source / "main" / "c-process" / "kyo_process_supervisor.c").getAbsolutePath,
                    "-o",
                    objectFile.getAbsolutePath
                ))
                run(cc ++ Seq(
                    "-static",
                    (fixtures / "kyo_process_supervisor_race.c").getAbsolutePath,
                    objectFile.getAbsolutePath,
                    "-o",
                    binary.getAbsolutePath
                ))
                Seq("KYO_PROCESS_SUPERVISOR_RACE" -> binary.getAbsolutePath)
            } else Nil
            val environment = Seq(
                "PYTHONDONTWRITEBYTECODE" -> "1",
                "KYO_PROCESS_SUPERVISOR"  -> (output / filename(target)).getAbsolutePath,
                "KYO_PROCESS_LIBRARY"     -> library.getAbsolutePath
            ) ++ race
            Seq("kyo_process_supervisor_test.py", "kyo_process_launch_test.py").foreach { name =>
                run(Seq("python3", (fixtures / name).getAbsolutePath, "-v"), environment*)
            }
        }
    }

    def checkImage(file: File, target: String): Unit = {
        val bytes                                                  = IO.readBytes(file)
        def requireImage(condition: Boolean, reason: String): Unit =
            require(condition, s"${file.getAbsolutePath}: $reason (expected $target)")
        requireImage(bytes.length >= 64, "truncated executable")
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val arm  = target.endsWith("-aarch64")
        if (target.startsWith("darwin-")) {
            requireImage(data.getInt(0) == 0xfeedfacf, "not a 64-bit Mach-O executable")
            requireImage(data.getInt(4) == (if (arm) 0x0100000c else 0x01000007), "wrong CPU architecture")
            requireImage(data.getInt(12) == 2, "Mach-O is not an executable")
        } else {
            requireImage(data.getInt(0) == 0x464c457f && bytes(4) == 2 && bytes(5) == 1, "not little-endian ELF64")
            requireImage(data.getShort(18) == (if (arm) 183 else 62), "wrong CPU architecture")
            requireImage(Set[Short](2, 3).contains(data.getShort(16)), "ELF is not an executable")
            val offset = data.getLong(32)
            val size   = data.getShort(54) & 0xffff
            val count  = data.getShort(56) & 0xffff
            requireImage(
                offset >= 0 && offset <= bytes.length && size >= 56 && size.toLong * count <= bytes.length - offset,
                "invalid program headers"
            )
            val interpreters = (0 until count).flatMap { index =>
                val header = (offset + size.toLong * index).toInt
                if (data.getInt(header) != 3) Nil
                else {
                    val start  = data.getLong(header + 8)
                    val length = data.getLong(header + 32)
                    requireImage(
                        start >= 0 && length > 0 && start <= bytes.length && length <= bytes.length - start,
                        "invalid ELF interpreter"
                    )
                    Seq(new String(bytes, start.toInt, length.toInt - 1, java.nio.charset.StandardCharsets.US_ASCII))
                }
            }
            // Native consumers compile on their own libc, including fully static deployments. The helper must not introduce
            // a dependency on the producer's dynamic loader or glibc version into those binaries.
            requireImage(interpreters.isEmpty && data.getLong(24) != 0, "Linux supervisor must be a static executable")
        }
    }

    def requirePool(pool: Option[File]): Seq[(String, File)] = {
        val directory =
            pool.getOrElse(sys.error("Publishing kyo-system Native requires ffiPrebuiltPool with all Unix supervisor executables."))
        targets.map { target =>
            val binary = directory / filename(target)
            require(binary.isFile, s"Missing supervisor image: $binary")
            checkImage(binary, target)
            target -> binary
        }
    }

    def generate(
        source: File,
        header: File,
        output: File,
        compiler: String,
        flags: Seq[String],
        target: String,
        portable: Boolean,
        pool: Option[File],
        log: Logger
    ): File = {
        val include = output / "include" / "kyo_process_supervisor_image.h"
        val images  = if (portable && pool.nonEmpty) requirePool(pool)
        else if (target.startsWith("windows-")) Seq.empty
        else {
            require(targets.contains(target), s"Unsupported supervisor build target: $target")
            val binary = output / filename(target)
            IO.createDirectory(output)
            val command = compiler.split("\\s+").toSeq ++ Seq("-std=c11", "-Os", "-Wall", "-Wextra", "-Werror") ++ flags ++
                (if (target.startsWith("linux-")) Seq("-static") else Nil) ++
                Seq("-I", header.getParent, source.getAbsolutePath, "-o", binary.getAbsolutePath)
            val fingerprint = output / "command"
            val invocation  = command.mkString("\n")
            if (!fingerprint.isFile || IO.read(fingerprint) != invocation) IO.write(fingerprint, invocation)
            FileFunction.cached(output / "cache", FilesInfo.hash, FilesInfo.exists) { _: Set[File] =>
                log.info(s"[kyo-system] ${command.mkString(" ")}")
                require(Process(command).! == 0, "Process supervisor compilation failed")
                checkImage(binary, target)
                Set(binary)
            }(Set(source, header, fingerprint))
            checkImage(binary, target)
            Seq(target -> binary)
        }
        val text =
            new StringBuilder("/* Generated executable images. Do not edit. */\n#include <stddef.h>\n#ifndef _WIN32\n#include <unistd.h>\n")
        images.zipWithIndex.foreach { case ((platform, binary), index) =>
            val os = if (platform.startsWith("darwin-")) "defined(__APPLE__)"
            else if (platform.startsWith("linux-musl-")) "defined(__linux__) && !defined(__GLIBC__)"
            else "defined(__linux__) && defined(__GLIBC__)"
            val arch = if (platform.endsWith("aarch64")) "defined(__aarch64__)" else "defined(__x86_64__)"
            text.append(if (index == 0) "#if " else "#elif ").append(os).append(" && ").append(arch).append('\n')
            text.append("static const unsigned char kyo_process_supervisor_image[] = {\n")
            IO.readBytes(binary).grouped(24).foreach { row =>
                text.append(row.map(byte => f"0x${byte & 0xff}%02x").mkString(",")).append(",\n")
            }
            text.append("};\n")
        }
        if (images.nonEmpty) text.append("#else\n#error Missing process supervisor image for this target\n#endif\n")
        else text.append("#error Missing process supervisor image for this target\n")
        text.append("#endif\n")
        val contents = text.toString
        if (!include.isFile || IO.read(include) != contents) IO.write(include, contents)
        include
    }
}
