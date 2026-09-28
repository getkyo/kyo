package kyo

/** The conformance suite for the write tier of a [[FileSystem]] backend, every backend implementing [[FileSystem.Write]].
  *
  * One class carries every suite of the tier: writes ([[FileSystemWriteTest]]) and positioned channels ([[FileSystemChannelTest]]). A
  * backend extends it once and supplies [[withFileSystem]], a fresh backend and a root to work under for each assertion; a suite added to
  * the tier later reaches it with no new declaration. A backend at this tier also reads, so its tests declare a subclass of
  * [[FileSystemReadConformanceTest]] as well, which is where locking is checked.
  *
  * `S >: Async` bounds the backend effect as described on [[FileSystemReadConformanceTest]].
  */
abstract class FileSystemWriteConformanceTest[S >: Async]
    extends kyo.test.Test[Any]
    with FileSystemWriteTest[S]
    with FileSystemChannelTest[S]
