package kyo.bench

import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        "-XX:+UnlockDiagnosticVMOptions",
        // "-Dgraal.PrintCompilation=true",
        // "-Dgraal.Log",
        // "-Dgraal.MethodFilter=kyo.scheduler.IOFiber.*",
        // "-XX:+TraceDeoptimization",
        // "-XX:+LogCompilation",
        // "-Dgraal.Dump=", //, "-Dgraal.MethodFilter=kyo2.*",
        // "-XX:+PrintCompilation", "-XX:+PrintInlining",
        // "-XX:+TraceTypeProfile"
        "-Dcats.effect.tracing.mode=DISABLED"
    ),
    jvm = "/Users/flavio.brasil/Downloads/graalvm-ce-java17-22.3.0/Contents/Home/bin/java"
)
@BenchmarkMode(Array(Mode.Throughput))
abstract class Bench
