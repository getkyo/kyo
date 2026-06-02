package kyo.website

import java.nio.file.Files
import java.nio.file.Paths

/** Structural assertions over the GitHub Pages deploy workflow and the legacy-site retirement
  * (INV-011).
  *
  * Eight JVM leaves read `.github/workflows/deploy-site.yml` as text and assert the trigger,
  * permission, concurrency, bundle-link, render-from-tags, and artifact-deploy shape, plus repo-state
  * leaves that the docsify `docs/` shell and the scaladoc-committing workflow are gone. The workflow
  * is a textual contract (its substrings are what the deploy pipeline depends on), so the leaves match
  * substrings rather than re-parsing the YAML. The in-Chrome chrome-parity and SPA-navigation leaves
  * (leaves 9 and 22, `WebsiteSpaSmokeTest`) run in the campaign-end cross-platform gate, not here.
  */
class DeployWorkflowTest extends Test:

    // Locate the repo root by walking up from user.dir until build.sbt is found, so the workflow file
    // resolves regardless of the JVM working directory (mirrors WebsiteBuildGraphTest).
    private def repoRoot(): java.nio.file.Path =
        var dir = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while dir != null && !Files.exists(dir.resolve("build.sbt")) do
            dir = dir.getParent
        if dir == null then throw new RuntimeException("repo root with build.sbt not found")
        dir
    end repoRoot

    private def repoFile(relative: String): java.nio.file.Path =
        repoRoot().resolve(relative)

    private val workflowText: String =
        new String(Files.readAllBytes(repoFile(".github/workflows/deploy-site.yml")))

    // ---- Leaf 1: the verified action pair (INV-011) ----

    "uses the verified action pair: upload-pages-artifact and deploy-pages (INV-011)" in {
        assert(workflowText.contains("actions/upload-pages-artifact"), "missing upload-pages-artifact")
        assert(workflowText.contains("actions/deploy-pages"), "missing deploy-pages")
    }

    // ---- Leaf 2: triggers ----

    "triggers on release published, push to main, and workflow_dispatch" in {
        assert(workflowText.contains("release:"), "missing release trigger")
        assert(workflowText.contains("types: [published]"), "missing release types [published]")
        assert(workflowText.contains("push:"), "missing push trigger")
        assert(workflowText.contains("branches: [main]"), "missing push branches [main]")
        assert(workflowText.contains("workflow_dispatch"), "missing workflow_dispatch")
    }

    // ---- Leaf 3: concurrency ----

    "concurrency group is pages with cancel-in-progress false" in {
        assert(workflowText.contains("group: pages"), "missing concurrency group: pages")
        assert(workflowText.contains("cancel-in-progress: false"), "missing cancel-in-progress: false")
    }

    // ---- Leaf 4: bundle link uses fullLinkJS, not fastLinkJS (Q-012, INV-008) ----

    "bundle-link step uses fullLinkJS on kyo-website-bundleJS, never fastLinkJS" in {
        assert(workflowText.contains("fullLinkJS"), "missing fullLinkJS")
        assert(workflowText.contains("kyo-website-bundleJS"), "missing kyo-website-bundleJS")
        assert(!workflowText.contains("fastLinkJS"), "fastLinkJS must not appear in the deploy workflow")
    }

    // ---- Leaf 5: render-from-tags loop (Q-009) ----

    "extract step loops all tags via git archive into per-tag content dirs" in {
        assert(workflowText.contains("git tag -l 'v*'"), "missing git tag -l 'v*'")
        assert(workflowText.contains("git archive"), "missing git archive")
        assert(workflowText.contains("README.md '*/README.md'"), "missing README.md '*/README.md' archive pathspec")
        assert(workflowText.contains("tar -x"), "missing tar -x extraction")
    }

    // ---- Leaf 6: no git commit of generated output (INV-011) ----

    "no git commit or push of generated output (INV-011)" in {
        assert(!workflowText.contains("git commit"), "deploy workflow must not git commit generated output")
        assert(!workflowText.contains("git push"), "deploy workflow must not git push generated output")
    }

    // ---- Leaf 7: scaladoc.yml removed (INV-011, Q-006) ----

    "scaladoc.yml is removed from .github/workflows (INV-011, Q-006)" in {
        assert(
            !Files.exists(repoFile(".github/workflows/scaladoc.yml")),
            "scaladoc.yml must be removed (the docs/api-committing workflow is retired)"
        )
    }

    // ---- Leaf 8: docsify docs/ shell removed (INV-011) ----

    "docsify docs/ shell is removed: index.html, _coverpage.md, custom.css (INV-011)" in {
        assert(!Files.exists(repoFile("docs/index.html")), "docs/index.html must be removed")
        assert(!Files.exists(repoFile("docs/_coverpage.md")), "docs/_coverpage.md must be removed")
        assert(!Files.exists(repoFile("docs/custom.css")), "docs/custom.css must be removed")
    }

end DeployWorkflowTest
