package kyo.ffi.internal

/** koffi as a load dependency: the library id a JS or Wasm load reports when koffi itself cannot be reached, in place of the native
  * library being loaded through it.
  *
  * Shared although koffi exists only on JS and Wasm, because the code reading that id is shared. A caller deciding what fixes a
  * [[kyo.ffi.FfiLoadError.LibraryNotFound]] has to tell a missing npm runtime from a missing native, and the two have different remedies:
  * `npm i koffi` for the first, a path override or a native package for the second.
  */
object KoffiRuntime:

    /** The `libraryId` of a [[kyo.ffi.FfiLoadError.LibraryNotFound]] raised because the koffi npm package is not installed or not
      * resolvable.
      */
    val LibraryId: String = "koffi"

end KoffiRuntime
