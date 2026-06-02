package kyo.website

import kyo.*

/** The complete render input for one documentation version: the root-README intro text, the
  * grouped modules, and the version record. A value (not files) so the renderer on `main` can
  * render any tag's content (render-from-tags, INV-006).
  */
final case class WebsiteContent(
    intro: String,
    groups: Chunk[WebsiteContent.Group],
    version: WebsiteVersion
) derives CanEqual

object WebsiteContent:
    /** One sidebar group: the group name (a root-README `### <Group>` heading) and its modules in
      * README order.
      */
    final case class Group(name: String, modules: Chunk[WebsiteModule]) derives CanEqual

    /** Read the root README + each module README from a checked-out tree and parse the model.
      *
      * The deploy flow extracts each `v*` tag into `root` with `git archive <tag> README.md
      * '*'/README.md | tar -x`, so `root` holds the tag's root `README.md` and a `<slug>/README.md`
      * per module. Degrade-not-fail (INV-007): a root README with no `## Modules` section yields an
      * intro-only `WebsiteContent` (empty groups), the common shape for the 87 pre-`v1.0.0-RC2` tags.
      *
      * Only a genuinely absent referenced README aborts (`WebsiteReadmeException(Missing)`). A
      * present-but-corrupt module table aborts `MalformedTable`; a `### <Group>` heading inside
      * `## Modules` with no table following it aborts `MalformedGroups`.
      *
      * @param root
      *   The checked-out/extracted tree root holding `README.md` and the per-module subtrees.
      * @param version
      *   The version record (tag, label, latest) this content is rendered as.
      */
    def fromRepo(root: Path, version: WebsiteVersion)(using Frame): WebsiteContent < (Sync & Abort[WebsiteException]) =
        for
            rootReadme <- readRequired(root / "README.md")
            intro = parseIntro(rootReadme)
            groups <- parseGroups(root, rootReadme)
        yield WebsiteContent(intro, groups, version)

    // ---- Private helpers ----

    private def readRequired(path: Path)(using Frame): String < (Sync & Abort[WebsiteException]) =
        Abort.run[FileReadException](path.read).map {
            case Result.Success(s) => s
            case Result.Failure(_) => Abort.fail(WebsiteReadmeException(path, WebsiteReadmeException.ReadmeFailure.Missing))
            case p: Result.Panic   => Abort.error(p)
        }

    /** The text between `## Introduction` and `## Modules`, or the whole body up to `## Modules`
      * when `## Introduction` is absent, or the whole body when neither is present. Pure string slice.
      */
    private def parseIntro(rootReadme: String): String =
        val afterIntro = sectionMarker(rootReadme, "## Introduction") match
            case Present(idx) => rootReadme.substring(idx)
            case Absent       => rootReadme
        sectionMarker(afterIntro, "## Modules") match
            case Present(idx) => afterIntro.substring(0, idx).trim
            case Absent       => afterIntro.trim
        end match
    end parseIntro

    /** Find the `## Modules` section; if absent, return `Chunk.empty` (INV-007 degrade). When present,
      * parse each `### <Group>` heading and its following GFM pipe table into a `Group`, reading each
      * module's README from `root/<slug>/README.md`.
      */
    private def parseGroups(root: Path, rootReadme: String)(using Frame): Chunk[Group] < (Sync & Abort[WebsiteException]) =
        sectionMarker(rootReadme, "## Modules") match
            case Absent => Chunk.empty
            case Present(modulesIdx) =>
                val modulesBody = sliceUntilTopLevelSection(rootReadme, modulesIdx)
                val rawGroups   = splitGroups(modulesBody)
                Kyo.foreach(rawGroups) { case (name, tableLines) => buildGroup(root, name, tableLines) }
        end match
    end parseGroups

    /** The index where `marker` begins as a standalone heading line (start of file or after a
      * newline), or `Absent`.
      */
    private def sectionMarker(text: String, marker: String): Maybe[Int] =
        if text.startsWith(marker) then Present(0)
        else
            val idx = text.indexOf("\n" + marker)
            if idx >= 0 then Present(idx + 1) else Absent
        end if
    end sectionMarker

    /** The body of the `## Modules` section: from just after its heading line to the next top-level
      * `## ` heading (exclusive) or end of file.
      */
    private def sliceUntilTopLevelSection(text: String, sectionStart: Int): String =
        val afterHeading = text.indexOf('\n', sectionStart)
        if afterHeading < 0 then ""
        else
            val rest = text.substring(afterHeading + 1)
            sectionMarker(rest, "## ") match
                case Present(idx) => rest.substring(0, idx)
                case Absent       => rest
            end match
        end if
    end sliceUntilTopLevelSection

    /** Split the `## Modules` body into `(groupName, tableLines)` pairs, one per `### <Group>`
      * heading, preserving README order. Lines before the first `### ` heading are ignored.
      *
      * The accumulator threads the groups closed so far plus the group currently being collected
      * (its name and its lines), so no mutable state escapes the fold.
      */
    private def splitGroups(modulesBody: String): Chunk[(String, Chunk[String])] =
        val lines = Chunk.from(modulesBody.split("\n", -1).toIndexedSeq)
        val (done, openName, openRows) =
            lines.foldLeft((Chunk.empty[(String, Chunk[String])], Maybe.empty[String], Chunk.empty[String])) {
                case ((acc, current, rows), line) =>
                    if line.startsWith("### ") then
                        val closed = current.map(name => acc.append(name -> rows)).getOrElse(acc)
                        (closed, Present(line.substring(4).trim), Chunk.empty[String])
                    else if current.isDefined then (acc, current, rows.append(line))
                    else (acc, current, rows)
            }
        openName.map(name => done.append(name -> openRows)).getOrElse(done)
    end splitGroups

    /** Build one `Group` from a `### <Group>` heading name and the raw lines following it. The lines
      * must contain a GFM pipe table (header row, separator row, then module rows). Aborts
      * `MalformedGroups` if no table is present and `MalformedTable` if a module row is corrupt.
      */
    private def buildGroup(root: Path, name: String, tableLines: Chunk[String])(using Frame): Group < (Sync & Abort[WebsiteException]) =
        val tableRows = tableLines.filter(l => l.trim.startsWith("|"))
        // The first pipe row is the header and one is the separator; the remaining rows are modules.
        val moduleRows = tableRows.filter(l => !isSeparatorRow(l)).drop(1)
        if tableRows.size < 2 || !tableRows.exists(isSeparatorRow) then
            Abort.fail(WebsiteReadmeException(root / "README.md", WebsiteReadmeException.ReadmeFailure.MalformedGroups))
        else
            // buildModule yields Absent for a directory-link row (a module table entry pointing at a
            // bare directory rather than a `<slug>/README.md`, e.g. `[kyo-examples](kyo-examples)`):
            // such a module ships no doc-page README, so it is dropped from the rendered group rather
            // than aborting (degrade-not-fail, INV-007). Present rows keep README order.
            Kyo.foreach(moduleRows)(row => buildModule(root, name, row)).map(mods => Group(name, mods.flatMap(_.toChunk)))
        end if
    end buildGroup

    private def isSeparatorRow(line: String): Boolean =
        val cells = pipeCells(line)
        cells.nonEmpty && cells.forall(c => c.nonEmpty && c.forall(ch => ch == '-' || ch == ':'))
    end isSeparatorRow

    /** Parse one module table row into a `WebsiteModule`, reading its README, or `Absent` when the row
      * is a directory-link entry that ships no doc-page README (degrade-not-fail, INV-007). The columns
      * are `| [slug](target) | JVM | JS | Native | Identity |`. Aborts `MalformedTable` if the row does
      * not have at least the 5 cells or the link cannot be parsed.
      *
      * A `<slug>/README.md` link (`[kyo-data](kyo-data/README.md)`) is a documentation module: its
      * README is read from `root/<slug>/README.md` and a genuinely-absent file aborts `Missing`. A
      * bare-directory link (`[kyo-examples](kyo-examples)`) points at a directory with no module-level
      * README (the deploy's `git archive` README pathspec extracts no `<slug>/README.md` for it), so it
      * yields `Absent` and the module is dropped from the rendered group rather than failing the whole
      * site.
      */
    private def buildModule(root: Path, group: String, row: String)(using Frame): Maybe[WebsiteModule] < (Sync & Abort[WebsiteException]) =
        val cells = pipeCells(row)
        if cells.size < 5 then Abort.fail(WebsiteReadmeException(root / "README.md", WebsiteReadmeException.ReadmeFailure.MalformedTable))
        else
            extractLink(cells(0)) match
                case Absent => Abort.fail(WebsiteReadmeException(root / "README.md", WebsiteReadmeException.ReadmeFailure.MalformedTable))
                case Present(ModuleLink(slug, hasReadme)) =>
                    if !hasReadme then Maybe.empty[WebsiteModule]
                    else
                        val platforms = WebsiteModule.Platforms(
                            jvm = isSupported(cells(1)),
                            js = isSupported(cells(2)),
                            native = isSupported(cells(3))
                        )
                        // title = slug by design: the root README module table has no separate title
                        // column (`| [slug](target) | JVM | JS | Native | Identity |`), and the slug
                        // (`kyo-core`, `kyo-data`, ...) is the display title for kyo modules.
                        readRequired(root / slug / "README.md").map(readme => Present(WebsiteModule(slug, group, slug, readme, platforms)))
                    end if
            end match
        end if
    end buildModule

    /** The cells of a GFM pipe row, trimmed, with the leading and trailing empty cells (from the
      * surrounding pipes) removed.
      */
    private def pipeCells(line: String): Chunk[String] =
        val trimmed = line.trim
        if !trimmed.startsWith("|") then Chunk.empty
        else
            val inner = trimmed.stripPrefix("|").stripSuffix("|")
            Chunk.from(inner.split("\\|", -1).toIndexedSeq).map(_.trim)
        end if
    end pipeCells

    /** A parsed module-table link: the directory `slug` and whether the link targets a module README
      * (`<slug>/README.md`) versus a bare directory. `hasReadme` is the degrade signal: `false` means
      * the row is a directory pointer with no doc-page README (see [[buildModule]]).
      */
    final private case class ModuleLink(slug: String, hasReadme: Boolean)

    /** Parse the module DIRECTORY slug and the link kind from a Markdown link cell `[text](target)`, or
      * `Absent` if the cell is not a link. The real README links point at `<slug>/README.md` (e.g.
      * `[kyo-data](kyo-data/README.md)`), so the target is reduced to the directory: leading `./`/`../`
      * are stripped, then a trailing `/README.md` (which sets `hasReadme = true`) and any trailing `/`.
      * The slug is BOTH the URL slug and the directory under `root`, so `root/<slug>/README.md` resolves
      * the module README. `kyo-data/README.md` yields `ModuleLink("kyo-data", hasReadme = true)`; a bare
      * `kyo-examples` yields `ModuleLink("kyo-examples", hasReadme = false)`.
      */
    private def extractLink(cell: String): Maybe[ModuleLink] =
        val open  = cell.indexOf('(')
        val close = cell.indexOf(')', open + 1)
        if open >= 0 && close > open then
            val target    = cell.substring(open + 1, close).trim.stripPrefix("./").stripPrefix("../")
            val hasReadme = target.endsWith("/README.md")
            val slug      = target.stripSuffix("/README.md").stripSuffix("/")
            if slug.nonEmpty then Present(ModuleLink(slug, hasReadme)) else Absent
        else Absent
        end if
    end extractLink

    private def isSupported(cell: String): Boolean =
        val c = cell.trim
        c.contains("✅") || c.equalsIgnoreCase("yes") || c == "x"
    end isSupported
end WebsiteContent
