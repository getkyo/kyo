// One suite forked twice, the way kyo-pod forks its container suites once per runtime: sbt keys a
// suite's results by the name the forked task reports, so two forks of one class collide unless
// each reports under its own label. Fork `a` fails the leaf and fork `b` cancels it, which is a
// podman failure beside an absent docker.
lazy val checkResults = taskKey[Unit]("asserts each fork's results are kept under its own name")

lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion"),
    libraryDependencies += "io.getkyo" %% "kyo-test-runner" % sys.props("plugin.version") % Test,
    Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework"),
    Test / fork := true,
    Test / testGrouping := {
      val tests = (Test / definedTests).value
      Seq("a" -> "fail", "b" -> "cancel").map { case (label, mode) =>
        Tests.Group(
          name = s"shared#$label",
          tests = tests,
          runPolicy = Tests.SubProcess(
            ForkOptions().withRunJVMOptions(Vector(s"-Dkyo.test.forkLabel=$label", s"-Dfixture.mode=$mode"))
          )
        )
      }
    },
    checkResults := {
      val out     = (Test / executeTests).value
      val results = out.events
      assert(results.keySet == Set("SharedSuite#a", "SharedSuite#b"), s"suite results filed under ${results.keySet}")
      assert(results("SharedSuite#a").failureCount == 1, s"fork a: ${results("SharedSuite#a")}")
      assert(results("SharedSuite#b").canceledCount == 1, s"fork b: ${results("SharedSuite#b")}")
      assert(out.overall == TestResult.Failed, s"overall ${out.overall}")
    }
  )
