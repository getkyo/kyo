export declare class ParseState {
  readonly failures: Chunk<ParseFailure>;
  readonly input: ParseInput<In>;
  readonly isDiscarded: (x1: In) => boolean;
};