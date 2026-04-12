export declare class ConcreteTag {

  static accepts<A>(value: unknown): boolean;
  static and<A, B>(): ConcreteTag<A & B>;
  static apply<A>(): ConcreteTag<A>;
  static given_CanEqual_ConcreteTag_ConcreteTag<A, B>(): CanEqual<ConcreteTag<A>, ConcreteTag<B>>;
  static or<A, B>(): ConcreteTag<A | B>;
  static show<A>(): string;
};