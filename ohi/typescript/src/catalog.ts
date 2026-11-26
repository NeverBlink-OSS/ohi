import type {
  CatalogConfig,
  CatalogError,
  CatalogOperation,
  FacetCount,
  Publication,
  SearchHit,
  SearchRequest,
  SearchResult,
  TagList,
} from "./generated.js";
import { decodePublication, formats as knownFormats, isNew } from "./publication.js";
import { parseVersionTag } from "./version-tag.js";

const searchPath = "/search/collections/packages/documents/search";
const referencePattern = /^([^/@\p{White_Space}`]+)\/([^/@\p{White_Space}`]+)(?:@([^/@\p{White_Space}+`]+))?$/u;

export class CatalogException extends Error {
  constructor(readonly error: CatalogError, options?: ErrorOptions) {
    super(error.message, options);
    this.name = "CatalogException";
  }
}

function httpUrl(value: string, operation: CatalogOperation): URL {
  try {
    const url = new URL(value);
    if (!(url.protocol === "http:" || url.protocol === "https:") || !url.hostname || url.username || url.password) {
      throw new TypeError("Invalid HTTP(S) URL.");
    }
    return url;
  } catch (cause) {
    throw new CatalogException({ code: "INVALID_INPUT", operation, url: value,
      message: "Supply an absolute HTTP(S) URL with a host and no credentials." }, { cause });
  }
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function json(bytes: Uint8Array): unknown {
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

function searchResult(bytes: Uint8Array, page: number, perPage: number): SearchResult {
  const raw = json(bytes);
  if (!object(raw) || "grouped_hits" in raw || !Array.isArray(raw.hits) || !object(raw.request_params)) {
    throw new Error("Expected an ungrouped search response with hits and request_params.");
  }
  if (raw.facet_counts !== undefined &&
      !(Array.isArray(raw.facet_counts) && raw.facet_counts.every((facet) => object(facet) && Array.isArray(facet.counts)))) {
    throw new Error("Expected facet_counts to be a list of facets with counts.");
  }
  const result: SearchResult = {
    hits: raw.hits.map((hit: unknown) => {
      if (!object(hit) || !object(hit.document)) throw new Error("Expected a search hit document.");
      return hit.document as SearchHit;
    }),
    total: raw.found as number,
    page: raw.page as number,
    per_page: raw.request_params.per_page as number,
    search_cutoff: raw.search_cutoff as boolean,
    ...(raw.facet_counts === undefined ? {} : { facet_counts: raw.facet_counts as FacetCount[] }),
  };
  if (result.page !== page || result.per_page !== perPage || result.hits.length > perPage) {
    throw new Error("Search response does not match the requested page and page size.");
  }
  return {
    ...result,
    // Formats added after this SDK version are discarded
    hits: result.hits.flatMap((hit) => {
      const { formats } = hit;
      const known = Array.isArray(formats) ? formats.filter((format) => !isNew(format, knownFormats)) : formats;
      if (Array.isArray(formats) && formats.length > 0 && known.length === 0) return [];
      return [{ ...hit, formats: known }];
    }),
  };
}

/** HTTP operations. Omitting both search settings selects ohi.dev. */
export class CatalogClient {
  private readonly config: CatalogConfig;
  private readonly timeoutMs: number;
  private readonly base: string;
  private readonly latestUrl: string;
  private readonly latestRevisionUrl: string;

  constructor(config: CatalogConfig = {}) {
    const url = config.search_url;
    if ((config.search_url === undefined) !== (config.query_by === undefined)) {
      throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE",
        ...(url === undefined ? {} : { url }), message: "Supply both search_url and query_by, or omit both." });
    }
    if (url !== undefined) {
      httpUrl(url, "CONFIGURE");
      if (url.includes("?") || url.includes("#")) {
        throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE", url,
          message: "Supply a search_url without a query or fragment." });
      }
      if (!url.endsWith(searchPath)) {
        throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE", url,
          message: `Supply a search_url that ends with ${searchPath}.` });
      }
    }
    this.config = {
      ...config,
      search_url: config.search_url ?? "https://api.ohi.dev/search/collections/packages/documents/search",
      query_by: config.query_by ?? "title,name,description,namespace",
    };
    this.timeoutMs = config.timeout_ms ?? 30000;
    this.base = this.config.search_url!.slice(0, -searchPath.length);
    this.latestUrl = `${this.base}/search/collections/packages_latest/documents/search`;
    this.latestRevisionUrl = `${this.base}/search/collections/packages_latest_revision/documents/search`;
  }

  async search(request: SearchRequest): Promise<SearchResult> {
    return this.searchAt(request.all_releases ? this.config.search_url! : this.latestUrl, request);
  }

  /** Run a search against one collection's search URL. */
  private async searchAt(endpoint: string, request: SearchRequest): Promise<SearchResult> {
    const page = request.page ?? 1;
    const perPage = request.per_page ?? 10;
    const url = new URL(endpoint);
    url.searchParams.set("q", request.q);
    url.searchParams.set("query_by", this.config.query_by!);
    url.searchParams.set("page", String(page));
    url.searchParams.set("per_page", String(perPage));
    if (request.filter_by !== undefined) url.searchParams.set("filter_by", request.filter_by);
    if (request.sort_by !== undefined) url.searchParams.set("sort_by", request.sort_by);
    if (request.facet_by !== undefined) url.searchParams.set("facet_by", request.facet_by);
    if (request.max_facet_values !== undefined) url.searchParams.set("max_facet_values", String(request.max_facet_values));
    const bytes = await this.request(url, "SEARCH", endpoint);
    try {
      return searchResult(bytes, page, perPage);
    } catch (cause) {
      throw new CatalogException({ code: "INVALID_RESPONSE", operation: "SEARCH", url: endpoint, status: 200,
        message: "The search response is invalid." }, { cause });
    }
  }

  /** Return the search hit of the release a reference requests, or undefined when the catalog has none.
   *
   * `namespace/name` requests the latest version, `namespace/name@1.0` the latest revision of 1.0,
   * `namespace/name@1.0-no-revision` the 1.0 version without its revisions, and `namespace/name@1.0-revision-2026-12-01`
   * exactly that revision.
   */
  async latest(reference: string): Promise<SearchHit | undefined> {
    const parts = referencePattern.exec(reference);
    if (parts === null) {
      throw new CatalogException({ code: "INVALID_INPUT", operation: "SEARCH", url: reference,
        message: "Supply an ontology reference namespace/name[@version]." });
    }
    const [, namespace, name, version] = parts;
    const filter = `namespace:=\`${namespace}\` && name:=\`${name}\``;
    // The := filter ignores case and separators, so look-alikes such as w3c/DCAT can match.
    const same = (hit: SearchHit) => hit.namespace === namespace && hit.name === name;
    if (version === undefined) {
      return (await this.search({ q: "*", filter_by: filter })).hits.find(same);
    }
    const { original, exact } = parseVersionTag(version);
    if (exact === undefined) {
      const hit = await this.latestRevision(filter, original, same);
      if (hit !== undefined) return hit;
    }
    // The release itself; for a version, also on a hub without revisions or before its revisions are indexed
    const release = exact ?? original;
    const releases = await this.searchAt(this.config.search_url!,
      { q: "*", filter_by: `${filter} && version:=\`${release}\`` });
    return releases.hits.find((hit) => same(hit) && hit.version === release);
  }

  /** The latest revision of a version from packages_latest_revision, or undefined when it has none there. A hub
   * before 0.2.0 doesn't have that collection.
   */
  private async latestRevision(filter: string, original: string, same: (hit: SearchHit) => boolean):
      Promise<SearchHit | undefined> {
    try {
      const result = await this.searchAt(this.latestRevisionUrl,
        { q: "*", filter_by: `${filter} && original_version:=\`${original}\`` });
      return result.hits.find((hit) => same(hit) && hit.original_version === original);
    } catch (error) {
      if (error instanceof CatalogException && error.error.code === "HTTP_ERROR" && error.error.status === 404) {
        return undefined;
      }
      throw error;
    }
  }

  /** Fetch the publication at an absolute HTTP(S) URL, or of the release a reference requests, as `latest` describes.
   */
  async inspect(baseUrl: string): Promise<Publication> {
    let url = baseUrl;
    const parts = referencePattern.exec(baseUrl);
    if (parts !== null) {
      const [, namespace, name, version] = parts;
      if (version !== undefined) {
        const { exact } = parseVersionTag(version);
        const release = `${this.base}/files/packages/${[namespace, name, exact ?? version].map(encodeURIComponent).join("/")}/`;
        // The release path when the catalog doesn't know the version
        url = exact !== undefined ? release : (await this.latest(baseUrl))?.base_url ?? release;
      } else {
        const hit = await this.latest(baseUrl);
        if (hit === undefined) {
          throw new CatalogException({ code: "HTTP_ERROR", operation: "INSPECT", url: baseUrl, status: 404,
            message: `No ontology matches ${baseUrl}.` });
        }
        url = hit.base_url;
      }
    } else if (!/^https?:\/\//i.test(baseUrl)) {
      // new URL repairs "http:/host" and strips tabs, so require the literal prefix before parsing.
      throw new CatalogException({ code: "INVALID_INPUT", operation: "INSPECT", url: baseUrl,
        message: "Supply an absolute HTTP(S) URL or an ontology reference namespace/name[@version]." });
    }
    const bytes = await this.request(httpUrl(url, "INSPECT"), "INSPECT", url);
    try {
      return decodePublication(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } catch (cause) {
      throw new CatalogException({ code: "INVALID_RESPONSE", operation: "INSPECT", url, status: 200,
        message: "The publication metadata is invalid." }, { cause });
    }
  }

  async get(url: string): Promise<Uint8Array> {
    return this.request(httpUrl(url, "GET"), "GET", url);
  }

  /**
   * Fetch the Hub's tag list, with the metadata of the tag ids that publications and search hits hold. Fetch it
   * again to see new tags. Fields that the model doesn't have are kept. Field values are not validated.
   */
  async tags(): Promise<TagList> {
    const url = `${this.base}/files/tags_v1.json`;
    const bytes = await this.request(httpUrl(url, "TAGS"), "TAGS", url);
    try {
      const data: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
      if (!object(data) || !Array.isArray(data.tags) || !Array.isArray(data.groups)) {
        throw new Error("The tag list must be an object with tags and groups lists.");
      }
      return data as unknown as TagList;
    } catch (cause) {
      throw new CatalogException({ code: "INVALID_RESPONSE", operation: "TAGS", url, status: 200,
        message: "The tag list is invalid." }, { cause });
    }
  }

  private async request(target: URL, operation: CatalogOperation, url: string): Promise<Uint8Array> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: Response | undefined;
    const transferError = (cause: unknown) => new CatalogException({
      code: controller.signal.aborted ? "TIMEOUT" : "CONNECTION_ERROR", operation, url,
      ...(response === undefined ? {} : { status: response.status }),
      message: controller.signal.aborted ? "The request exceeded timeout_ms." : "The HTTP transfer failed.",
    }, { cause });
    try {
      try {
        response = await fetch(target, { signal: controller.signal, credentials: "omit", redirect: "error" });
      } catch (cause) {
        throw transferError(cause);
      }
      if (response.status !== 200) {
        let responseJson: Record<string, unknown> | undefined;
        try {
          const value = json(new Uint8Array(await response.arrayBuffer()));
          if (object(value)) responseJson = value;
        } catch {
          // A known failure status takes precedence over an unreadable error body.
        }
        throw new CatalogException({ code: "HTTP_ERROR", operation, url, status: response.status,
          ...(responseJson === undefined ? {} : { response_json: responseJson }),
          message: `HTTP ${response.status} for ${url}` });
      }
      try {
        return new Uint8Array(await response.arrayBuffer());
      } catch (cause) {
        throw transferError(cause);
      }
    } finally {
      clearTimeout(timer);
      controller.abort();
    }
  }
}
