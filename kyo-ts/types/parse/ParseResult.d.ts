export declare class ParseResult {
  readonly errors: Chunk<ParseFailure>;
  readonly fatal: boolean;
  readonly isFailure: boolean;
  orAbort(): Kyo<Out, Abort<ParseError>>;
  readonly out: Maybe<Out>;
};