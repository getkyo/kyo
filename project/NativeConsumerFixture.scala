import sbt.*
import sbt.Keys.*

/** `publishNativeConsumerClosure`: publishLocal everything the out-of-tree Native consumer fixture (kyo-ffi/plugin/src/consumer-fixture)
  * resolves, at the build's version.
  *
  * publishLocal is not transitive, so the fixture needs the whole compile closure of the modules it depends on, plus the plugin and the
  * codegen the plugin loads. The closure is read from the project graph rather than listed, so a dependency added to kyo-net or kyo-http
  * cannot leave the fixture resolving a stale or missing artifact. Run through scripts/native-consumer-check.sh.
  */
object NativeConsumerFixture {

    private val roots = Seq("kyo-netNative", "kyo-httpNative")
    private val tools = Seq("kyo-ffi-codegen", "kyo-ffi-plugin")

    val command: Command = Command.command("publishNativeConsumerClosure") { state =>
        val extracted = Project.extract(state)
        val build     = extracted.currentRef.build
        val data      = extracted.structure.data
        val deps      = extracted.get(buildDependencies)
        val closure   = roots.flatMap { id =>
            val ref = ProjectRef(build, id)
            ref.project +: Classpaths.interSort(ref, Compile, data, deps).map(_._1.project)
        }.distinct
        val projects = closure ++ tools
        state.log.info(s"[publishNativeConsumerClosure] ${projects.mkString(" ")}")
        projects.map(p => s"$p/publishLocal").mkString("all ", " ", "") :: state
    }
}
