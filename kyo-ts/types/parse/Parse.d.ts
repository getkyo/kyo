export declare class Parse {

  static any<A>(): Kyo<A, Parse<A>>;
  static anyIf<A>(f: (x1: A) => boolean, errorMessage: (x1: A) => string): Kyo<A, Parse<A>>;
  static anyIn<A>(values: A[]): Kyo<A, Parse<A>>;
  static anyMatch<A, In>(pf: PartialFunction<In, A>): Kyo<A, Parse<In>>;
  static anyNotIn<A>(values: A[]): Kyo<A, Parse<A>>;
  static boolean(): Kyo<boolean, Parse<Char>>;
  static decimal(): Kyo<number, Parse<Char>>;
  static end<In>(): Kyo<void, Parse<In>>;
  static firstOf<In, Out, S>(parsers: () => Kyo<Out, Parse<In> & S>[]): Kyo<Out, Parse<In> & S>;
  static identifier(): Kyo<Text, Parse<Char>>;
  static int(): Kyo<number, Parse<Char>>;
  static literal<A>(value: A): Kyo<A, Parse<A>>;
  static modifyState<Out, In>(modify: (x1: ParseState<In>) => [ParseState<In>, Maybe<Out>]): Kyo<Out, Parse<In>>;
  static position<In>(): Kyo<number, Parse<In>>;
  static read<In, Out>(f: (x1: ParseInput<In>) => Result<Chunk<ParseFailure>, [ParseInput<In>, Out]>): Kyo<Out, Parse<In>>;
  static readOne<In, Out>(f: (x1: In) => Result<Chunk<string>, Out>): Kyo<Out, Parse<In>>;
  static readWhile<A>(f: (x1: A) => boolean): Kyo<Chunk<A>, Parse<A>>;
  static regex(pattern: Regex): Kyo<Text, Parse<Char>>;
  static whitespaces(): Kyo<Text, Parse<Char>>;
};