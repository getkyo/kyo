package kyo.ffi.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Emits a GitHub Actions workflow template that builds `ffiCompile` across an OS/arch matrix.
  * Users adapt it to their publish flow.
  *
  * Each leg names its platform through `ffiTargetOsArch`, so the artifact it uploads is named and
  * packaged for that platform rather than for whatever the runner happens to be. That is what lets
  * one arm64 macOS runner produce both Mac targets: `darwin-x86_64` is a cross-build there (the
  * target names it, `-arch x86_64` makes clang emit it), which is also why no Intel-Mac runner
  * appears. No Windows leg is emitted: add one only if the project actually ships Windows natives.
  */
private[sbt] object CiWorkflow {
    def writeTemplate(out: File, libraryId: String): Unit = {
        val parent = out.getParentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val yaml =
            s"""name: FFI native build
               |
               |on:
               |  workflow_dispatch:
               |
               |jobs:
               |  build:
               |    strategy:
               |      matrix:
               |        include:
               |          - { os: ubuntu-latest,    target: linux-x86_64,   cc: gcc,   cflags: 'Nil' }
               |          - { os: ubuntu-24.04-arm, target: linux-aarch64,  cc: gcc,   cflags: 'Nil' }
               |          - { os: macos-14,         target: darwin-aarch64, cc: clang, cflags: 'Nil' }
               |          # darwin-x86_64 is cross-built on the arm64 runner: the target names the
               |          # artifact and its resource directory, -arch x86_64 makes clang emit x86_64 code.
               |          - { os: macos-14,         target: darwin-x86_64,  cc: clang, cflags: 'Seq("-arch", "x86_64")' }
               |    runs-on: $${{ matrix.os }}
               |    steps:
               |      - uses: actions/checkout@v4
               |      - name: "ffiCompile ($${{ matrix.target }})"
               |        env:
               |          CC: $${{ matrix.cc }}
               |        run: |
               |          sbt 'set ThisBuild / ffiTargetOsArch := Some("$${{ matrix.target }}")' 'set ThisBuild / ffiCFlags ++= $${{ matrix.cflags }}' ffiCompile
               |      - uses: actions/upload-artifact@v4
               |        with:
               |          name: $libraryId-$${{ matrix.target }}
               |          path: target/ffi/**/*
               |""".stripMargin
        Files.write(out.toPath, yaml.getBytes(StandardCharsets.UTF_8))
    }
}
