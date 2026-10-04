import java.io.File
import sbt.Logger
import scala.sys.process.*
import scala.util.Try

/** Points the repository's `core.hooksPath` at `scripts/hooks` when the build loads, so the pre-push format check runs with no setup step.
  *
  * `core.hooksPath` lives in the repository's common config, which every worktree shares, and a relative path resolves against the top of
  * the working tree a hook runs in, so one setting serves the main checkout and every worktree. Only an unset path or one naming the default
  * `hooks` directory is replaced: a path set to anything else was chosen deliberately, so it is left alone with a warning that the format
  * hook is not active.
  */
object GitHooks {

    val repoHooks = "scripts/hooks"

    def enable(root: File, log: Logger): Unit =
        if (new File(root, repoHooks).isDirectory) {
            def git(args: String*): Option[String] =
                Try(Process("git" +: args, root).!!(ProcessLogger(_ => ()))).toOption.map(_.trim)
            git("rev-parse", "--git-common-dir").foreach { commonDir =>
                val common       = canonical(root, commonDir)
                val defaultHooks = new File(common, "hooks").getCanonicalFile
                git("config", "--get", "core.hooksPath") match {
                    case Some(`repoHooks`)                                   => ()
                    case Some(path) if canonical(root, path) != defaultHooks =>
                        log.warn(
                            s"core.hooksPath is $path, so the pre-push format check in $repoHooks is not active. " +
                                s"Run scripts/format.sh --check --changed before pushing."
                        )
                    case _ =>
                        if (git("config", "core.hooksPath", repoHooks).isDefined)
                            log.info(s"Set core.hooksPath to $repoHooks: pushes now run the format check.")
                }
            }
        }

    private def canonical(root: File, path: String): File = {
        val file = new File(path)
        (if (file.isAbsolute) file else new File(root, path)).getCanonicalFile
    }
}
