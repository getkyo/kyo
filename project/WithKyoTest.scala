import sbt.*
import sbt.Keys.*
import sbtcrossproject.CrossPlugin.autoImport.*
import sbtcrossproject.CrossProject
import sbtcrossproject.Platform
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*
import scalanativecrossproject.ScalaNativeCrossPlugin.autoImport.*
import WasmCrossProject.*

/** Wires kyo-test into a CrossProject using task-level LocalProject references.
  *
  * A normal dependsOn(kyo-test-runner % Test) on kyo-data / kyo-kernel /
  * kyo-prelude / kyo-core would create a project-graph cycle (kyo-test-api
  * depends on kyo-core, kyo-core depends on kyo-test-runner). LocalProject
  * references are resolved at task evaluation, not at project-graph
  * construction, so the sbt cycle detector does not reject them. Validated
  * in /tmp/sbt-cross-bootstrap (00-locked-decisions.md:122-124).
  *
  * Per-platform settings are required: a single LocalProject for all
  * platforms links the wrong jar (JVM jar onto JS classpath)
  * (00-locked-decisions.md:126-128).
  *
  * Usage: one .withKyoTest call per module (the crossProject macro requires
  * a direct val binding, so zero-config is not achievable;
  * 00-locked-decisions.md:129-131).
  *
  * Example:
  *   lazy val `kyo-kernel` =
  *     crossProject(JSPlatform, JVMPlatform, NativePlatform)
  *       .withoutSuffixFor(JVMPlatform)
  *       ...
  *       .withKyoTest
  */
object WithKyoTest {

    implicit final class CrossProjectOps(val cp: CrossProject) extends AnyVal {

        def withKyoTest: CrossProject = {
            val base =
                cp.jvmSettings(
                    Test / unmanagedClasspath ++=
                        (LocalProject("kyo-test-runner") / Test / fullClasspath).value,
                    Test / testFrameworks +=
                        new TestFramework("kyo.test.runner.SbtFramework")
                )
            val withJs =
                if (cp.projects.contains(JSPlatform))
                    base.jsSettings(
                        Test / unmanagedClasspath ++=
                            (LocalProject("kyo-test-runnerJS") / Test / fullClasspath).value,
                        Test / testFrameworks +=
                            new TestFramework("kyo.test.runner.JsFramework")
                    )
                else base
            val withNative =
                if (cp.projects.contains(NativePlatform))
                    withJs.nativeSettings(
                        Test / unmanagedClasspath ++=
                            (LocalProject("kyo-test-runnerNative") / Test / fullClasspath).value,
                        Test / testFrameworks +=
                            new TestFramework("kyo.test.runner.NativeFramework")
                    )
                else withJs
            // WASM is a Scala.js linker backend, so it reuses the Scala.js test Framework (JsFramework).
            if (cp.projects.contains(WasmPlatform))
                withNative.wasmSettings(
                    Test / unmanagedClasspath ++=
                        (LocalProject("kyo-test-runnerWasm") / Test / fullClasspath).value,
                    Test / testFrameworks +=
                        new TestFramework("kyo.test.runner.JsFramework")
                )
            else withNative
        }
    }
}
