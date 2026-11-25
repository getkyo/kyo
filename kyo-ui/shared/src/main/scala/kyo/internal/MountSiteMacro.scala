package kyo.internal

import scala.quoted.*
import scala.util.hashing.MurmurHash3

/** Home of [[kyo.UI.MountSite]], in an object of its own because an opaque type declared inside `UI` would stand in for `Int`
  * throughout `UI`, where schemas are derived.
  */
object MountSites:

    opaque type MountSite = Int

    object MountSite:
        inline given derive: MountSite = ${ MountSiteMacro.siteImpl }

        /** The site of a node that has no key. */
        private[kyo] val none: MountSite = 0
    end MountSite

end MountSites

/** Derives a [[kyo.UI.MountSite]]: a hash of where the expression that asks for it is written, fixed at compile time.
  *
  * The file name, the enclosing class and the start and end offsets of the expression together tell any two places in a build apart.
  * The file name stands in for the path so the value does not depend on where the build ran, and both offsets are needed because a
  * chained call such as `UI.mounted(a).keyed(k)` starts where its receiver starts.
  */
private[kyo] object MountSiteMacro:

    def siteImpl(using Quotes): Expr[Int] =
        val pos   = quotes.reflect.Position.ofMacroExpansion
        val owner = FindEnclosing(_.isClassDef).fold("")(_.fullName)
        Expr(MurmurHash3.stringHash(s"${pos.sourceFile.name}|$owner|${pos.start}|${pos.end}"))
    end siteImpl

end MountSiteMacro
