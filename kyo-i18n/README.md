# kyo-i18n

Translate an application from `.ftl` bundles, with the active locale held as a `Signal` so a language switch re-renders exactly the affected text and nothing else. Bundles are parsed and formatted in pure Scala, so the module works across JVM, JavaScript, Scala Native, and WebAssembly and depends only on `kyo-core`. Translations read like plain calls: the resolved handle is provided once at the application root and read back ambiently, so no dependency is threaded through call sites.

<!-- doctest:setup
```scala
val demoBundles = Dict(
    Locale("en") -> "greet = Hello, { $name }!",
    Locale("de") -> "greet = Hallo, { $name }!"
)
```
-->

```scala
I18n.initInMemory(demoBundles, Locale("en")).map { i18n =>
    I18n.let(i18n) {
        val greeting: Signal[String] = I18n.t("greet", Dict("name" -> "Sam"))
        greeting.current
    }
}
// "Hello, Sam!"; set the locale to Locale("de") and every t leaf re-renders to German
```

`I18n.t` is the center: it returns a bare `Signal[String]` that a UI layer lifts into a reactive text node, so switching the locale patches the affected leaves in place. `I18n.now` and `I18n.at` are the point-in-time counterparts for values that must not re-translate. A handle built by `I18n.init` is made ambient with `I18n.let`; everything else reads it back.

## Installation

Add the dependency to your `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-i18n" % "<latest version>"
```

All public types live in the `kyo` package:

```scala
import kyo.*
```

**Note:** the effectful methods take an implicit `kyo.Frame` used for source-location reporting. The signatures shown here omit `(using Frame)` for readability; the compiler synthesizes it at each call site.

## Locales

A `Locale` is a normalized primary language subtag. `apply` and `parse` lowercase the input and drop any region or script, so a value from a config file, a stored preference, and a browser `navigator.language` all compare equal:

```scala doctest:scope=nested
Locale.parse("de-DE") // Present(Locale("de"))
Locale.parse("EN")    // Present(Locale("en"))
Locale.parse("")      // Absent

Locale("de").code // "de"
```

`Locale.default` reads the host environment: `navigator.language` in a browser, otherwise the `user.language` property or `LC_ALL`/`LANG`. It reports whatever the host prefers, which need not be a language the application has a bundle for, so `Locale.preferred` picks the host locale only when it is one of the supported set and falls back otherwise:

```scala doctest:scope=nested
val supported = Seq(Locale("en"), Locale("de"))

Locale.preferred(supported, Locale("en"))
// the host locale if it is en or de, otherwise en
```

When the host names no language at all, `Locale.default` is `Locale.Undetermined`, the BCP-47 `und` subtag.

The module fixes no locale set. The consumer decides which locales exist by which bundles it loads and which start locale it passes to `init`.

## Providing translations

`initInMemory` builds a handle from bundle text you already hold:

```scala doctest:scope=nested
val bundles = Dict(
    Locale("en") -> "hello = Hello",
    Locale("de") -> "hello = Hallo"
)

I18n.initInMemory(bundles, Locale("en")).map { i18n =>
    I18n.let(i18n)(I18n.now("hello"))
}
// "Hello"
```

`init` is the usual choice for an application: it loads each locale's text through a function you supply, so the source of the bundles stays in your code. On the client that loader is typically an HTTP fetch, on the server a classpath resource; its effects flow into the result:

```scala doctest:scope=nested
val supported = Seq(Locale("en"), Locale("de"))

def loadBundle(locale: Locale): String < Async =
    Async.defer(s"hello = Hello from ${locale.code}")

I18n.init(supported, Locale("en"))(loadBundle).map { i18n =>
    I18n.let(i18n)(I18n.now("hello"))
}
// "Hello from en"
```

Pair it with `Locale.preferred` to start in the reader's own language whenever a bundle exists for it:

```scala doctest:expect=skipped
for
    start <- Locale.preferred(supported, Locale("en"))
    i18n  <- I18n.init(supported, start)(loadBundle)
yield i18n
end for
```

On the JVM, Scala Native, and Node the bundles usually sit on disk, so `init` also takes a directory in place of the loader and reads `<locale>.ftl` from it:

```scala doctest:expect=skipped
for
    start <- Locale.preferred(supported, Locale("en"))
    i18n  <- I18n.init(supported, start)(Path("resources") / "i18n")
yield i18n
// reads resources/i18n/en.ftl and resources/i18n/de.ftl
```

That overload's reads carry `Abort[FileReadException]`, so a missing or unreadable bundle surfaces at the caller. A browser build never links it, since nothing there calls it.

`let` provides the handle as the ambient source for its body. A `t` leaf built anywhere resolves against whichever handle is ambient when it is sampled; a leaf sampled outside any `let` renders miss markers.

## Translating

`t` is the reactive default. `now` reads the active locale once (for toasts, log lines, exception text). `at` reads an explicit locale, ignoring the active one:

```scala doctest:scope=nested
val bundles = Dict(
    Locale("en") -> "hello = Hello",
    Locale("de") -> "hello = Hallo"
)

I18n.initInMemory(bundles, Locale("en")).map { i18n =>
    I18n.let(i18n) {
        for
            reactive <- I18n.t("hello").current        // "Hello"
            german   <- I18n.at(Locale("de"), "hello") // "Hallo"
            _        <- I18n.setLocale(Locale("de"))
            switched <- I18n.now("hello")              // "Hallo"
        yield (reactive, german, switched)
    }
}
```

Interpolate named arguments with `{ $name }` placeholders. An argument that is itself a `Signal[String]` (typically a nested `t`) keeps the composed message reactive to that argument as well as to the locale:

```scala doctest:scope=nested
val bundles = Dict(Locale("en") -> "greet = Hello, { $name }!")

I18n.initInMemory(bundles, Locale("en")).map { i18n =>
    I18n.let(i18n)(I18n.t("greet", Dict("name" -> "Sam")).current)
}
// "Hello, Sam!"
```

The `i18n"..."` interpolator composes literal text and arguments into one reactive `Signal[String]`:

```scala doctest:scope=nested
val bundles = Dict(Locale("en") -> "hello = Hello")

I18n.initInMemory(bundles, Locale("en")).map { i18n =>
    I18n.let(i18n) {
        val hello = I18n.t("hello")
        i18n"[$hello]".current
    }
}
// "[Hello]"
```

## Missing keys

A key that is absent, or present but value-less, renders as `‹key›` rather than falling back to another locale or an empty string, so a gap in a bundle is visible in the output:

```scala doctest:scope=nested
val bundles = Dict(Locale("en") -> "hello = Hello")

I18n.initInMemory(bundles, Locale("en")).map { i18n =>
    I18n.let(i18n)(I18n.now("missing"))
}
// "‹missing›"
```

## Bundle format

Bundles are the interpolation-only subset of [Fluent](https://projectfluent.org/): `id = pattern` messages, indented continuation lines, `#` comments, and `{ $name }` placeholders. Select expressions, message attributes, and term references are not parsed; when a count-bearing string first needs plural selection it becomes a new pattern case handled in the parser, not a change to any message that already parses.

A line outside that subset is ignored, but not silently: parsing reports the ignored lines through `kyo.Log` at warn level, so a bundle written against full Fluent shows up in the log rather than as mysteriously absent text. The two behaviors that are specified rather than accidental stay quiet: a value-less message is absent per Fluent semantics, and a brace that is not a `{ $name }` placeholder is literal by design.
