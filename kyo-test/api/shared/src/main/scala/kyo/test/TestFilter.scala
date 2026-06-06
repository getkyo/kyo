package kyo.test

import kyo.Chunk

/** Selects the subset of leaves to execute in a test run.
  *
  * Globs in `pathInclude`/`pathExclude` are matched against the full dot-joined leaf path (e.g., `"outer.inner"`). Tag sets are matched by
  * exact string equality.
  *
  * An empty filter (all fields at their defaults) means "run everything". Include lists act as allow-lists; exclude lists are applied after
  * includes. Matching logic is implemented in the runner module.
  *
  * @param pathInclude
  *   glob patterns for leaf paths to include; empty means include all
  * @param pathExclude
  *   glob patterns for leaf paths to exclude; applied after `pathInclude`
  * @param tagsInclude
  *   exact tag names to include; empty means include all
  * @param tagsExclude
  *   exact tag names to exclude; applied after `tagsInclude`
  * @see
  *   [[kyo.test.RunConfig]] where a TestFilter is supplied via the filter field
  * @see
  *   [[kyo.test.TestBuilder]] which carries the tags field matched by tagsInclude/tagsExclude
  * @see
  *   [[kyo.test.LeafInfo]] whose path and suite fields are the match targets for path patterns
  * @see
  *   `kyo.test.runner.Cli` which parses `--include`, `--exclude`, `--tag` flags into a TestFilter
  */
final case class TestFilter(
    pathInclude: Chunk[String] = Chunk.empty,
    pathExclude: Chunk[String] = Chunk.empty,
    tagsInclude: Set[String] = Set.empty,
    tagsExclude: Set[String] = Set.empty
) derives CanEqual

object TestFilter:
    /** An empty filter that matches every test: all include lists are empty (include all), all exclude lists are empty (exclude nothing).
      */
    val empty: TestFilter = TestFilter()
end TestFilter
