export declare class HttpRequest {
  addField<N, V>(name: N, value: V): HttpRequest<Fields & Field<unknown, V>>;
  addFields<Fields2>(r: Record<Fields2>): HttpRequest<Fields & Fields2>;
  addHeader(name: string, value: string): HttpRequest<Fields>;
  readonly fields: Record<Fields>;
  readonly headers: HttpHeaders;
  readonly method: HttpMethod;
  readonly path: string;
  query(name: string): Maybe<string>;
  queryAll(name: string): string[];
  setHeader(name: string, value: string): HttpRequest<Fields>;
  readonly url: HttpUrl;
};