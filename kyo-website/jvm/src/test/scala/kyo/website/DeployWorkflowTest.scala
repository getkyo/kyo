package kyo.website

import java.nio.file.Files
import java.nio.file.Paths

/** Structural assertions over the GitHub Pages deploy workflow and the legacy-site retirement.
  *
  * The JVM leaves read `.github/workflows/deploy-site.yml` as text and assert the trigger,
  * permission, concurrency, bundle-link, forward-only render, and artifact-deploy shape, plus
  * repo-state leaves that the docsify `docs/` shell and the scaladoc-committing workflow are gone. The
  * workflow is a textual contract (its substrings are what the deploy pipeline depends on), so the
  * leaves match substrings rather than re-parsing the YAML. The in-Chrome chrome-parity and
  * SPA-navigation leaves (`WebsiteSpaSmokeTest`) run in the cross-platform gate, not here.
  */
class DeployWorkflowTest extends WebsiteTest:

    // Locate the repo root by walking up from user.dir until build.sbt is found, so the workflow file
    // resolves regardless of the JVM working directory (mirrors WebsiteBuildGraphTest).
    private def repoRoot(): java.nio.file.Path =
        val start = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        Iterator
            .iterate(start)(_.getParent)
            .takeWhile(_ != null)
            .find(dir => Files.exists(dir.resolve("build.sbt")))
            .getOrElse(throw new RuntimeException("repo root with build.sbt not found"))
    end repoRoot

    private def repoFile(relative: String): java.nio.file.Path =
        repoRoot().resolve(relative)

    private val workflowText: String =
        new String(Files.readAllBytes(repoFile(".github/workflows/deploy-site.yml")))

    /** The shell content of every `run:` step in the workflow, with comment lines stripped.
      *
      * The no-git-write leaf asserts the deploy pipeline issues no `git commit` / `git push`. That is a property of
      * the executed shell, not of the file's prose: a YAML `#` comment that merely mentions
      * "git commit" (as the design note at the foot of the file does) must neither trip the assertion
      * (false positive) nor mask a real command added in a comment-heavy region. Scoping the check to
      * the `run:` bodies and dropping `#` comment lines pins it to what the pipeline actually runs.
      *
      * A `run:` step is either a single-line `run: <cmd>` or a YAML block scalar (`run: |` / `run: >`)
      * whose body is the more-indented lines that follow. This walker collects both forms.
      */
    private val runStepShell: String =
        val lines = workflowText.linesIterator.toList
        val out   = new StringBuilder
        // Match the `run:` key at any indentation, capturing its indent and the inline remainder.
        val runKey                   = """^(\s*)run:\s*(.*)$""".r
        def indentOf(s: String): Int = s.takeWhile(_ == ' ').length
        def collectBlock(rest: List[String], keyIndent: Int): List[String] =
            rest match
                case head :: tail if head.isBlank               => collectBlock(tail, keyIndent)
                case head :: tail if indentOf(head) > keyIndent => out.append(head).append('\n'); collectBlock(tail, keyIndent)
                case _                                          => rest
        def walk(rest: List[String]): Unit =
            rest match
                case Nil => ()
                case line :: tail =>
                    line match
                        case runKey(indent, inline) if inline == "|" || inline == ">" || inline.startsWith("|") || inline.startsWith(">") =>
                            walk(collectBlock(tail, indent.length))
                        case runKey(_, inline) =>
                            out.append(inline).append('\n'); walk(tail)
                        case _ => walk(tail)
        walk(lines)
        // Drop `#` comment lines so comment prose inside a block scalar cannot trip the check.
        out.toString.linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n")
    end runStepShell

    // ---- the verified action pair ----

    "uses the verified action pair: upload-pages-artifact and deploy-pages" in {
        assert(workflowText.contains("actions/upload-pages-artifact"), "missing upload-pages-artifact")
        assert(workflowText.contains("actions/deploy-pages"), "missing deploy-pages")
    }

    // ---- triggers ----

    "triggers on release published and workflow_dispatch, NOT on every main push" in {
        assert(workflowText.contains("release:"), "missing release trigger")
        assert(workflowText.contains("types: [published]"), "missing release types [published]")
        assert(workflowText.contains("workflow_dispatch"), "missing workflow_dispatch")
        // The site deploys on a release (the tag's checkout is the released content, so the docs match
        // their version label) or a manual dispatch, NOT on every push to main, which would publish
        // unreleased content under the last release's label and rebuild on non-doc commits.
        assert(!workflowText.contains("push:"), "must not deploy on a push trigger")
        assert(!workflowText.contains("branches: [main]"), "must not deploy on every main push")
    }

    // ---- concurrency ----

    "concurrency group is pages with cancel-in-progress false" in {
        assert(workflowText.contains("group: pages"), "missing concurrency group: pages")
        assert(workflowText.contains("cancel-in-progress: false"), "missing cancel-in-progress: false")
    }

    // ---- bundle link uses fullLinkJS, not fastLinkJS ----

    "bundle-link step uses fullLinkJS on kyo-website-bundleJS, never fastLinkJS" in {
        assert(workflowText.contains("fullLinkJS"), "missing fullLinkJS")
        assert(workflowText.contains("kyo-website-bundleJS"), "missing kyo-website-bundleJS")
        assert(!workflowText.contains("fastLinkJS"), "fastLinkJS must not appear in the deploy workflow")
    }

    // ---- forward-only render from the current repo ----

    "render step builds from the current repo via --repo-root, with no per-tag extraction" in {
        // The forward-only build renders the live repo: kyo-websiteJVM/run with --out and --repo-root,
        // no --content (the render emits only the current state).
        assert(workflowText.contains("kyo-websiteJVM/run"), "missing the kyo-websiteJVM/run render step")
        assert(workflowText.contains("--out $GITHUB_WORKSPACE/site"), "render must write the site to the workspace")
        assert(workflowText.contains("--repo-root $GITHUB_WORKSPACE"), "render must read the live repo via --repo-root")
        // The historical per-tag README extraction is gone.
        assert(!runStepShell.contains("git archive"), "the per-tag git archive extraction must be removed")
        assert(!runStepShell.contains("git tag -l"), "the per-tag tag loop must be removed")
        assert(!runStepShell.contains("tar -x"), "the per-tag tar extraction must be removed")
        assert(!runStepShell.contains("--content"), "the forward-only render must not pass --content")
    }

    // ---- no git commit of generated output ----

    "no git commit or push of generated output" in {
        // Scoped to the executed `run:` shell (comments stripped) so comment prose mentioning
        // "git commit" neither trips this nor masks a real command (see runStepShell).
        assert(!runStepShell.contains("git commit"), "deploy workflow must not git commit generated output")
        assert(!runStepShell.contains("git push"), "deploy workflow must not git push generated output")
    }

    // ---- scaladoc.yml removed ----

    "scaladoc.yml is removed from .github/workflows" in {
        assert(
            !Files.exists(repoFile(".github/workflows/scaladoc.yml")),
            "scaladoc.yml must be removed (the docs/api-committing workflow is retired)"
        )
    }

    // ---- docsify docs/ shell removed ----

    "docsify docs/ shell is removed: index.html, _coverpage.md, custom.css" in {
        assert(!Files.exists(repoFile("docs/index.html")), "docs/index.html must be removed")
        assert(!Files.exists(repoFile("docs/_coverpage.md")), "docs/_coverpage.md must be removed")
        assert(!Files.exists(repoFile("docs/custom.css")), "docs/custom.css must be removed")
    }

end DeployWorkflowTest
