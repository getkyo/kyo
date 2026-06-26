package kyo

import scala.scalajs.js

/** The loaded-asset handle a loader returns, a sealed union so future loaders add `Asset.Obj` and
  * `Asset.Fbx` variants.
  *
  * [[Asset.Gltf]] carries the loaded subtree as a [[Three.Ast.Custom]] `root` (so handlers attach
  * directly), a `nodes` index of named sub-nodes (so a handler can attach to a specific door or
  * wheel), and the `animations` clip names.
  */
sealed trait Asset derives CanEqual

object Asset:
    /** A loaded glTF/GLB: the root subtree, the named-sub-node index, and the animation clip names. */
    final case class Gltf(
        root: Three.Ast.Custom[js.Dynamic],
        nodes: Map[String, Three.Ast.Custom[js.Dynamic]],
        animations: Chunk[String]
    ) extends Asset
end Asset
