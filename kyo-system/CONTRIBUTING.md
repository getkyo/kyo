# Contributing to kyo-system

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-system`. Read the root `CONTRIBUTING.md` first; everything there
applies here, and this file extends it with module-local rules.

---

## Path API

### Safe-tier surface

`Path` is an opaque type wrapping `Path.Unsafe`. The safe tier (extension
methods on `Path`, defined in `object Path`) provides effect-tracked I/O:

| Safe method                       | Effect row                                             |
|-----------------------------------|--------------------------------------------------------|
| `path.exists`                     | `Boolean < Sync`                                       |
| `path.isDirectory`                | `Boolean < Sync`                                       |
| `path.isRegularFile`              | `Boolean < Sync`                                       |
| `path.isSymbolicLink`             | `Boolean < Sync`                                       |
| `path.realPath`                   | `Path < (Sync & Abort[FileException])`                 |
| `path.size`                       | `Long < (Sync & Abort[FileReadException])`             |
| `path.read`                       | `String < (Sync & Abort[FileReadException])`           |
| `path.readBytes`                  | `Span[Byte] < (Sync & Abort[FileReadException])`       |
| `path.readLines`                  | `Chunk[String] < (Sync & Abort[FileReadException])`    |
| `path.readStream`                 | `Stream[String, Scope & Sync & Abort[...]]`            |
| `path.readBytesStream`            | `Stream[Byte, Scope & Sync & Abort[...]]`              |
| `path.readLinesStream`            | `Stream[String, Scope & Sync & Abort[...]]`            |
| `path.tail`                       | `Stream[String, Async & Scope & Abort[...]]`           |
| `path.write`, `writeBytes`, ...   | `Unit < (Sync & Abort[FileWriteException])`            |
| `path.append`, `appendBytes`, ... | `Unit < (Sync & Abort[FileWriteException])`            |
| `path.mkDir`, `mkFile`            | `Unit < (Sync & Abort[FileFsException])`               |
| `path.list`                       | `Chunk[Path] < (Sync & Abort[FileFsException])`        |
| `path.walk`                       | `Stream[Path, Sync & Scope & Abort[FileFsException]]`  |
| `path.move`, `copy`, `remove`     | `(Unit|Boolean) < (Sync & Abort[FileFsException])`     |

#### Companion-level constants

```scala
Path.pathSeparator  // ":" on Unix, ";" on Windows, Node's path.delimiter on JS
Path.fileSeparator  // "/" on Unix, "\\" on Windows, Node's path.sep on JS
```

Both are `val` on `object Path`, computed once at companion-object
initialization via `platformPathSeparator` / `platformFileSeparator` on the
`PathPlatformSpecific` trait.

#### Key design points

- `Path` is immutable. All I/O goes through `Sync.Unsafe.defer` at the safe
  tier and `AllowUnsafe` at the abstract-class tier.
- Inspection methods (`exists`, `isDirectory`, `isRegularFile`, `isSymbolicLink`)
  require only `Sync`, not `Abort`: they return `false` for inaccessible paths.
- Streaming methods carry `Scope` so the underlying OS handle is closed when the
  enclosing scope exits, regardless of whether it completes normally or aborts.
- `path.tail` is the only streaming method that adds `Async` (it sleeps between
  polls).

### Unsafe tier

`Path.Unsafe` is the abstract class that platform implementations extend. Each
abstract method takes `(using AllowUnsafe, Frame)` (or just `AllowUnsafe` for
handle operations). The safe-tier extension methods delegate to `self.unsafe.*`
inside `Sync.Unsafe.defer`.

The safe-tier lift for a method that returns a `Result` is always:

```scala
def myOp(using Frame): T < (Sync & Abort[SomeException]) =
    Sync.Unsafe.defer(Abort.get(self.unsafe.myOp()))
```

For a method that returns a plain value (no failure), omit the `Abort.get`:

```scala
def myFlag(using Frame): Boolean < Sync =
    Sync.Unsafe.defer(self.unsafe.myFlag())
```

### Adding a new Path operation

1. Add the abstract method to `Path.Unsafe` in
   `shared/src/main/scala/kyo/Path.scala`. Decide its `Result` error type:
   `FileReadException`, `FileWriteException`, or `FileFsException`.

2. Add the safe-tier extension method directly below the other safe methods in
   `object Path` (`extension (self: Path)`). Always lift with
   `Sync.Unsafe.defer(Abort.get(...))`.

3. Implement in `jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala`
   on `NioPathUnsafe` using `java.nio.file.*`.

4. Implement in `js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala`
   on the JS path class using the `NodeFs` / `NodePath` facades.

5. Add cross-platform test leaves in
   `shared/src/test/scala/kyo/PathTest.scala`. Use `runJVM { ... }` only for
   leaves that test mechanics that genuinely require JVM APIs (e.g., `mmap`
   internals). Contract-level tests belong in the cross-platform shared tree.

### Cross-platform discipline

Source tree layout for `Path`:

| Tree              | Content                                                     |
|-------------------|-------------------------------------------------------------|
| `shared/src/main` | `Path.scala` (opaque type, safe tier, abstract `Unsafe`)    |
| `shared/src/main` | `internal/PathDirectories.scala` (shared dir logic)         |
| `jvm-native/src/main` | `NioPathUnsafe` backed by `java.nio.file.Path`          |
| `js-wasm/src/main`    | Node.js-backed impl using `NodeFs` / `NodePath` facades |

The JS implementation uses `@JSImport("node:fs", ...)` and
`@JSImport("node:path", ...)` facades (`NodeFs`, `NodePath`, `NodeStats`).
These facades are the only place where `js.native` / `@js.native` appears in
path-related code.

When adding a new platform-specific capability, supply a stub in every platform
leaf. On a non-supporting platform the stub must raise the appropriate
`FileException` or return a documented no-op; it must never throw a raw
exception.

---

## FileException hierarchy

`FileException` is a sealed abstract base. `FileReadException`,
`FileWriteException`, and `FileFsException` are sealed marker traits on it,
one per operation category. Each concrete case class implements only the
traits of the operations that can actually raise it, so a single exception
can carry more than one marker:

| Concrete case class              | Read | Write | Fs |
|-----------------------------------|:----:|:-----:|:--:|
| `FileNotFoundException`           | x    | x     | x  |
| `FileAccessDeniedException`       | x    | x     | x  |
| `FileIsADirectoryException`       | x    | x     |    |
| `FileNotADirectoryException`      |      |       | x  |
| `FileAlreadyExistsException`      |      |       | x  |
| `FileDirectoryNotEmptyException`  |      |       | x  |
| `FileIOException`                 | x    | x     | x  |

Use the most specific subtype. Do not use `FileIOException` when a more
specific variant exists (e.g., `FileNotFoundException` for a missing file).
