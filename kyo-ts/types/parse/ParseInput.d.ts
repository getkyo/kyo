export declare class ParseInput {
  advance(n: number): ParseInput<In>;
  advanceWhile(f: (x1: In) => boolean): ParseInput<In>;
  readonly done: boolean;
  readonly position: number;
  readonly remaining: Chunk<In>;
  readonly tokens: Chunk<In>;
};