export declare class Render {
  asString(value: A): string;
  asText(value: A): Text;

  static apply<A>(): Render<A>;
  static asText<A>(value: A): Text;
  static from<A>(impl: (x1: A) => Text): Render<A>;
  static given_Render_A<A>(): Render<A>;
};