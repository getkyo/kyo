export declare class RecoverStrategy {
  apply(failedParser: Kyo<Out, Parse<In>>): Kyo<Out, Parse<In>>;

  static nestedDelimiters<In, Out>(left: In, right: In, others: [In, In][], fallback: Out): RecoverStrategy<In, Out>;
};