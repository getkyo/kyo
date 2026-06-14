package kyo.ffi.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Emits a GitHub Actions workflow template that builds `ffiCompile` across the
  * supported OS/arch matrix. Users adapt it to their publish flow.
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
               |          - { os: ubuntu-latest,    target: linux-x86_64,   cc: gcc }
               |          - { os: ubuntu-24.04-arm, target: linux-aarch64,  cc: gcc }
               |          - { os: macos-13,         target: darwin-x86_64,  cc: clang }
               |          - { os: macos-14,         target: darwin-aarch64, cc: clang }
               |          - { os: windows-latest,   target: windows-x86_64, cc: cl }
               |    runs-on: $${{ matrix.os }}
               |    steps:
               |      - uses: actions/checkout@v4
               |      - run: sbt ffiCompile
               |      - uses: actions/upload-artifact@v4
               |        with:
               |          name: $libraryId-$${{ matrix.target }}
               |          path: target/ffi/**/*
               |""".stripMargin
        Files.write(out.toPath, yaml.getBytes(StandardCharsets.UTF_8))
    }
}
