package kyo

/** The loaded-asset handle a loader returns, a sealed union so future loaders add `Asset.Obj` and
  * `Asset.Fbx` variants.
  *
  * [[Asset.Gltf]] carries the loaded subtree as a [[Three.Ast.Custom]] `root` (so handlers attach
  * directly), a `nodes` index of named sub-nodes (so a handler can attach to a specific door or
  * wheel), and the `animations` clip names.
  *
  * The `Custom` type parameter is existential (`Custom[?]`): the loaded live object is a `js.Dynamic`
  * on the client, but no consumer reads the parameter (handlers attach through the node's own setters,
  * and `nodes`/`animations` are read by count/name), so the TYPE is cross-platform. Values are still
  * only constructed where a loader runs (js/wasm).
  */
sealed trait Asset derives CanEqual

object Asset:
    /** A loaded glTF/GLB: the root subtree, the named-sub-node index, and the animation clip names. */
    final case class Gltf(
        root: Three.Ast.Custom[?],
        nodes: Map[String, Three.Ast.Custom[?]],
        animations: Chunk[String]
    ) extends Asset
end Asset
