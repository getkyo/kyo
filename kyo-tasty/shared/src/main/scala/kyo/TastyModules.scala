// flow-allow: PUBLIC per-type satellite for JPMS module ADT types; re-exported under kyo.Tasty.ModuleDescriptor etc.
package kyo

/** Per-type satellite for `ModuleDescriptor`, `ModuleRequires`, `ModuleExports`, `ModuleOpens`, `ModuleProvides`. Types live in `object
  * Tasty`; this file satisfies Rule 8b.
  */
object TastyModules:
    type ModuleDescriptor = Tasty.ModuleDescriptor
    val ModuleDescriptor = Tasty.ModuleDescriptor
    type ModuleRequires = Tasty.ModuleRequires
    val ModuleRequires = Tasty.ModuleRequires
    type ModuleExports = Tasty.ModuleExports
    val ModuleExports = Tasty.ModuleExports
    type ModuleOpens = Tasty.ModuleOpens
    val ModuleOpens = Tasty.ModuleOpens
    type ModuleProvides = Tasty.ModuleProvides
    val ModuleProvides = Tasty.ModuleProvides
end TastyModules
