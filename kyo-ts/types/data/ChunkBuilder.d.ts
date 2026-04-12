export declare class ChunkBuilder {
  addAll(elems: IterableOnce<A>): this;
  addOne(elem: A): this;
  clear(): void;
  readonly knownSize: number;
  result(): Indexed<A>;

  static init<A>(): ChunkBuilder<A>;
  static initTransform<A, B>(f: (x1: ChunkBuilder<B>, x2: A) => void): (x1: A) => void & ChunkBuilder<B>;
};