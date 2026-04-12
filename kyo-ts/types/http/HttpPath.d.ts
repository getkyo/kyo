export declare class HttpPath {
  readonly show: string;
  slash<B>(next: HttpPath<B>): HttpPath<A & B>;

  readonly empty: HttpPath<unknown>;
};