import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { createServer } from "node:http";
import { once } from "node:events";
import { CatalogClient, CatalogException } from "@neverblink/ohi";
import { binary, page, publication, server } from "./server.mjs";

const searchPath = "/search/collections/packages/documents/search";
const latestPath = "/search/collections/packages_latest/documents/search";
const latestRevisionPath = "/search/collections/packages_latest_revision/documents/search";

// A search page whose hits are [base_url, namespace, name, original_version] tuples.
function releases(entries) {
  const wire = page(entries.map(([baseUrl]) => baseUrl), 1, 10, entries.length);
  entries.forEach(([, namespace, name, original], index) => {
    Object.assign(wire.hits[index].document, { namespace, name, version: `${original}-revision-2026-12-01`,
      original_version: original, revision_id: "2026-12-01" });
  });
  return wire;
}

function failure(code, operation, url, status) {
  return (error) => {
    assert.ok(error instanceof CatalogException);
    assert.equal(error.message, error.error.message);
    assert.equal(error.error.code, code);
    assert.equal(error.error.operation, operation);
    assert.equal(error.error.url, url);
    assert.equal(error.error.status, status);
    if (status === undefined) assert.equal("status" in error.error, false);
    if (url === undefined) assert.equal("url" in error.error, false);
    return true;
  };
}

const catalog = (fixture) => new CatalogClient({ search_url: `${fixture.url}${searchPath}`, query_by: "name" });

test("omitted search settings use production catalog and preserve timeout", async (t) => {
  const fetched = t.mock.method(globalThis, "fetch", async () =>
    new Response(JSON.stringify(page([], 1, 10, 0))));
  const scheduled = t.mock.method(globalThis, "setTimeout");
  const result = await new CatalogClient({ timeout_ms: 275 }).search({ q: "*" });
  assert.equal(fetched.mock.calls.length, 1);
  const url = fetched.mock.calls[0].arguments[0];
  assert.equal(`${url.origin}${url.pathname}`, "https://api.ohi.dev/search/collections/packages_latest/documents/search");
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    q: "*", query_by: "title,name,description,namespace", page: "1", per_page: "10",
  });
  assert.equal(scheduled.mock.calls[0].arguments[1], 275);
  assert.deepEqual(result, { hits: [], total: 0, page: 1, per_page: 10, search_cutoff: false });
});

for (const [name, config] of Object.entries({
  "endpoint without fields": { search_url: "http://localhost/search" },
  "fields without endpoint": { query_by: "name" },
  "endpoint query": { search_url: "http://localhost/search?q=a", query_by: "name" },
  "endpoint fragment": { search_url: "http://localhost/search#x", query_by: "name" },
  "empty endpoint query": { search_url: "http://localhost/search?", query_by: "name" },
  "endpoint without the packages path": { search_url: "http://localhost/search", query_by: "name" },
})) {
  test(`configuration rejects ${name}`, () => {
    assert.throws(() => new CatalogClient(config), failure("INVALID_INPUT", "CONFIGURE", config.search_url));
  });
}

for (const url of ["urn:ohi:one", "/relative", "https://user:secret@example.org/file"]) {
  test(`HTTP boundary rejects ${url}`, async () => {
    await assert.rejects(new CatalogClient().get(url), failure("INVALID_INPUT", "GET", url));
  });
}

test("search encodes original values once and preserves complete summaries in backend order", async (t) => {
  const wire = page(["https://example.org/z", "https://example.org/a", "https://example.org/z"], 2, 3, 7);
  wire.hits[1].document.licenses = [];
  wire.hits[0].document.keywords = ["sensors", "observations"];
  wire.hits[1].document.keywords = [];
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
  const config = { search_url: `${fixture.url}${searchPath}`, query_by: "name,description" };
  const client = new CatalogClient(config);
  config.search_url = "http://invalid.invalid/";
  config.query_by = "mutated";
  const result = await client.search({ q: " µ+& sensors ", page: 2, per_page: 3,
    filter_by: " namespace:=`a+b&c` ", sort_by: "published_at:desc,name:asc", facet_by: " licenses,µ+&formats ",
    max_facet_values: 30 });
  assert.deepEqual(result, {
    hits: ["https://example.org/z", "https://example.org/a", "https://example.org/z"].map((base_url, index) => ({
      base_url, namespace: "example", name: "sensors", version: "2026.1+amendment", title: "Sensors",
      description: "Sensor observations and devices.",
      formats: ["ossie-yaml", "owl-rdfxml", "linkml-yaml", "owl-turtle", "rdfs-turtle", "shacl-turtle"],
      licenses: index === 1 ? [] : ["MIT", "Apache-2.0"], published_at: "2026-10-13T14:30:00+02:00", ignored: true,
      ...[{ keywords: ["sensors", "observations"] }, { keywords: [] }, {}][index],
    })),
    total: 7, page: 2, per_page: 3, search_cutoff: false,
  });
  assert.equal(fixture.requests.length, 1);
  const sent = new URL(fixture.requests[0].url, fixture.url);
  assert.equal(sent.pathname, latestPath);
  assert.deepEqual(Object.fromEntries(sent.searchParams), { q: " µ+& sensors ", query_by: "name,description",
    page: "2", per_page: "3", filter_by: " namespace:=`a+b&c` ", sort_by: "published_at:desc,name:asc",
    facet_by: " licenses,µ+&formats ", max_facet_values: "30" });
});

for (const [name, request, expectedPage, expectedSize, hits] of [
  ["defaults", { q: "*" }, 1, 10, []],
  ["minimum page size", { q: "*", per_page: 1 }, 1, 1, []],
  ["maximum page size", { q: "*", per_page: 250 }, 1, 250, []],
  ["full first page", { q: "*", per_page: 2 }, 1, 2, ["https://example.org/a", "https://example.org/b"]],
  ["short second page", { q: "*", page: 2, per_page: 2 }, 2, 2, ["https://example.org/c"]],
  ["empty page past end", { q: "*", page: 3, per_page: 2 }, 3, 2, []],
]) {
  test(`search accepts ${name}`, async (t) => {
    const fixture = await server(t, (_, res) => res.end(JSON.stringify(page(hits, expectedPage, expectedSize))));
    const result = await catalog(fixture).search(request);
    assert.deepEqual(result.hits.map((hit) => hit.base_url), hits);
    assert.equal(result.total, 3);
    assert.equal(result.page, expectedPage);
    assert.equal(result.per_page, expectedSize);
    assert.equal(fixture.requests.length, 1);
    const sent = new URL(fixture.requests[0].url, fixture.url);
    assert.equal(sent.searchParams.get("page"), String(expectedPage));
    assert.equal(sent.searchParams.get("per_page"), String(expectedSize));
    assert.equal(sent.searchParams.has("max_facet_values"), false);
  });
}

test("search accepts an early cutoff with a short capped page", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify({ ...page([], 1, 10, 100), search_cutoff: true })));
  const result = await catalog(fixture).search({ q: "*" });
  assert.deepEqual(result, { hits: [], page: 1, per_page: 10, total: 100, search_cutoff: true });
  assert.equal(fixture.requests.length, 1);
});

test("search preserves ordered facet values and safe counts and keeps nested extras", async (t) => {
  const wire = { ...page(), facet_counts: [
    { field_name: "licenses", counts: [
      { value: "MIT", count: 0, highlighted: "ignored" },
      { value: "Apache-2.0", count: 9007199254740991, highlighted: "ignored" },
    ], stats: { total_values: 2 } },
    { field_name: "formats", counts: [], stats: {} },
  ] };
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
  const result = await catalog(fixture).search({ q: "*" });
  assert.deepEqual(result.facet_counts, wire.facet_counts);
});

test("search keeps fields and ignores formats added later", async (t) => {
  const wire = readFileSync(new URL("../../examples/future-search.json", import.meta.url));
  const fixture = await server(t, (_, res) => res.end(wire));
  const result = await catalog(fixture).search({ q: "*" });
  assert.deepEqual(result.hits.map(({ name, formats }) => [name, formats]),
    [["sensors", ["owl-turtle", "linkml-yaml"]], ["units", ["owl-turtle"]]]);
  assert.equal(result.total, 3);
  const expected = JSON.parse(wire);
  assert.deepEqual(result.facet_counts, expected.facet_counts);
  assert.deepEqual(result.hits[0], { ...expected.hits[0].document, formats: ["owl-turtle", "linkml-yaml"] });
});

test("search keeps every field that the model declares", async (t) => {
  const wire = readFileSync(new URL("../../examples/complete-search.json", import.meta.url));
  const expected = JSON.parse(wire);
  const fixture = await server(t, (_, res) => res.end(wire));
  const result = await catalog(fixture).search({ q: "*" });
  assert.deepEqual(result.hits, expected.hits.map(({ document }) => document));
  assert.deepEqual(result.facet_counts, expected.facet_counts);
});

test("search preserves an explicitly empty facet list", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify({ ...page(), facet_counts: [] })));
  const result = await catalog(fixture).search({ q: "*" });
  assert.deepEqual(result.facet_counts, []);
});

for (const [name, mutate] of Object.entries({
  "nonobject document": (wire) => { wire.hits = [{ document: null }]; },
  "missing request parameters": (wire) => { delete wire.request_params; },
  "nonarray hits": (wire) => { wire.hits = {}; },
  "grouped hits": (wire) => { wire.grouped_hits = []; },
  "page mismatch": (wire) => { wire.page = 2; },
  "page size mismatch": (wire) => { wire.request_params.per_page = 2; },
  "too many hits": (wire) => { wire.hits = page(["urn:a", "urn:b"]).hits; },
  "null facet list": (wire) => { wire.facet_counts = null; },
  "nonarray facet list": (wire) => { wire.facet_counts = {}; },
  "missing facet counts": (wire) => { wire.facet_counts = [{ field_name: "licenses" }]; },
})) {
  test(`search rejects ${name}`, async (t) => {
    const wire = page(["https://example.org/a"], 1, 1);
    mutate(wire);
    const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
    await assert.rejects(catalog(fixture).search({ q: "*", per_page: 1 }),
      failure("INVALID_RESPONSE", "SEARCH", `${fixture.url}${latestPath}`, 200));
  });
}

test("search sends all_releases true to search_url and otherwise to the latest endpoint, never as a parameter", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(page())));
  const client = catalog(fixture);
  await client.search({ q: "*" });
  await client.search({ q: "*", all_releases: false });
  await client.search({ q: "*", all_releases: true });
  const sent = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.deepEqual(sent.map((url) => url.pathname), [latestPath, latestPath, searchPath]);
  for (const url of sent) {
    assert.deepEqual(Object.fromEntries(url.searchParams), { q: "*", query_by: "name", page: "1", per_page: "10" });
  }
});

test("a search_url under a path prefix keeps the prefix for the latest endpoint and release URLs", async (t) => {
  const fixture = await server(t, (req, res) =>
    res.end(req.url.startsWith("/hub/search/") ? JSON.stringify(page()) : publication));
  const client = new CatalogClient({ search_url: `${fixture.url}/hub/search/collections/packages/documents/search`, query_by: "name" });
  await client.search({ q: "*" });
  await client.inspect("w3c/dcat@2");
  assert.deepEqual(fixture.requests.map((req) => new URL(req.url, fixture.url).pathname),
    ["/hub/search/collections/packages_latest/documents/search",
      "/hub/search/collections/packages_latest_revision/documents/search",
      "/hub/search/collections/packages/documents/search", "/hub/files/packages/w3c/dcat/2/"]);
});

test("inspect fetches the exact supplied path and query without following metadata references", async (t) => {
  const fixture = await server(t, (_, res) => res.end(publication));
  const result = await new CatalogClient().inspect(`${fixture.url}/opaque?signature=a%2Bb#entry`);
  assert.equal(result.title, "Sensors");
  assert.equal(result.artifacts[0].size_bytes, 42);
  assert.deepEqual(result.dependencies, [{ base_url: "https://example.org/packages/units/2.0.0/" }]);
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/opaque?signature=a%2Bb"]);
});

test("inspect of a versioned reference without a hit sends one GET of the percent-encoded release URL", async (t) => {
  const fixture = await server(t, (req, res) =>
    res.end(req.url.startsWith("/search/") ? JSON.stringify(page([], 1, 10, 0)) : publication));
  await catalog(fixture).inspect("team:a/model%x@v1.0é");
  const [search, releasesSearch, ...gets] = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.equal(search.pathname, latestRevisionPath);
  assert.equal(search.searchParams.get("filter_by"),
    "namespace:=`team:a` && name:=`model%x` && original_version:=`v1.0é`");
  assert.equal(releasesSearch.pathname, searchPath);
  assert.equal(releasesSearch.searchParams.get("filter_by"),
    "namespace:=`team:a` && name:=`model%x` && version:=`v1.0é`");
  assert.deepEqual(gets.map((url) => url.pathname), ["/files/packages/team%3Aa/model%25x/v1.0%C3%A9/"]);
});

test("inspect of a versioned reference fetches the base_url of the latest revision of that version", async (t) => {
  const fixture = await server(t, (req, res) => {
    if (!req.url.startsWith(latestRevisionPath)) return res.end(publication);
    res.end(JSON.stringify(releases([[`${fixture.url}/revision/`, "example", "sensors", "1.0"]])));
  });
  await catalog(fixture).inspect("example/sensors@1.0");
  assert.deepEqual(fixture.requests.map((req) => new URL(req.url, fixture.url).pathname),
    [latestRevisionPath, "/revision/"]);
});

test("inspect of a revision reference fetches that exact revision without searching", async (t) => {
  const fixture = await server(t, (_, res) => res.end(publication));
  await catalog(fixture).inspect("example/sensors@1.0-revision-2026-12-01");
  assert.deepEqual(fixture.requests.map((req) => req.url),
    ["/files/packages/example/sensors/1.0-revision-2026-12-01/"]);
});

test("inspect of a versioned reference on a hub without the latest-revision collection fetches the version itself", async (t) => {
  const fixture = await server(t, (req, res) => {
    if (req.url.startsWith(searchPath)) {
      const wire = page([`${fixture.url}/1.0/`], 1, 10, 1);
      wire.hits[0].document.version = "1.0";
      return res.end(JSON.stringify(wire));
    }
    if (!req.url.startsWith(latestRevisionPath)) return res.end(publication);
    res.writeHead(404);
    res.end('{"message":"Not Found"}');
  });
  await catalog(fixture).inspect("example/sensors@1.0");
  assert.deepEqual(fixture.requests.map((req) => new URL(req.url, fixture.url).pathname),
    [latestRevisionPath, searchPath, "/1.0/"]);
});

test("inspect of an unversioned reference fetches the latest-version hit with the exact namespace and name, skipping look-alikes", async (t) => {
  const fixture = await server(t, (req, res) => {
    if (!req.url.startsWith(latestPath)) return res.end(publication);
    const wire = page([`${fixture.url}/a/`, `${fixture.url}/b/`], 1, 10, 2);
    wire.hits[0].document.name = "SENSORS";
    res.end(JSON.stringify(wire));
  });
  await catalog(fixture).inspect("example/sensors");
  const [search, ...gets] = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.equal(search.pathname, latestPath);
  assert.deepEqual(Object.fromEntries(search.searchParams),
    { q: "*", query_by: "name", page: "1", per_page: "10", filter_by: "namespace:=`example` && name:=`sensors`" });
  assert.deepEqual(gets.map((url) => url.pathname), ["/b/"]);
});

test("inspect of an unversioned reference without a latest-version hit of the exact namespace and name reports HTTP 404 without a GET", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(page([], 1, 10, 0))));
  await assert.rejects(catalog(fixture).inspect("w3c/nope"), failure("HTTP_ERROR", "INSPECT", "w3c/nope", 404));
  assert.deepEqual(fixture.requests.map((req) => new URL(req.url, fixture.url).pathname), [latestPath]);
});

test("inspect rejects a target that is neither a URL nor a reference without a request", async (t) => {
  const fetched = t.mock.method(globalThis, "fetch", async () => new Response("{}"));
  await assert.rejects(new CatalogClient().inspect("w3c"), failure("INVALID_INPUT", "INSPECT", "w3c"));
  assert.equal(fetched.mock.calls.length, 0);
});

test("inspect rejects http:/host with a trailing tab, which the reference grammar refuses and the URL parser would repair, without a request", async (t) => {
  const fixture = await server(t, (_, res) => res.end(publication));
  const target = `${fixture.url.replace("http://", "http:/")}\t`;
  await assert.rejects(new CatalogClient().inspect(target), failure("INVALID_INPUT", "INSPECT", target));
  assert.deepEqual(fixture.requests, []);
});

test("latest rejects a target that is not a reference without a request", async (t) => {
  const fetched = t.mock.method(globalThis, "fetch", async () => new Response("{}"));
  await assert.rejects(new CatalogClient().latest("w3c"), failure("INVALID_INPUT", "SEARCH", "w3c"));
  assert.equal(fetched.mock.calls.length, 0);
});

test("latest of a versioned reference searches the latest-revision collection and skips look-alikes", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(releases([
    ["https://example.org/a/", "example", "SENSORS", "1.0"],
    ["https://example.org/b/", "example", "sensors", "1-0"],
    ["https://example.org/c/", "example", "sensors", "1.0"],
  ]))));
  const hit = await catalog(fixture).latest("example/sensors@1.0");
  assert.equal(hit.base_url, "https://example.org/c/");
  assert.equal(hit.revision_id, "2026-12-01");
  assert.equal(fixture.requests.length, 1);
  const sent = new URL(fixture.requests[0].url, fixture.url);
  assert.equal(sent.pathname, latestRevisionPath);
  assert.deepEqual(Object.fromEntries(sent.searchParams), { q: "*", query_by: "name", page: "1", per_page: "10",
    filter_by: "namespace:=`example` && name:=`sensors` && original_version:=`1.0`" });
});

test("latest of a revision reference is exactly that revision, without looking for a newer one", async (t) => {
  // A newer revision, then the requested one
  const fixture = await server(t, (_, res) => {
    const wire = releases([["https://example.org/r2/", "example", "sensors", "1.0"],
      ["https://example.org/r1/", "example", "sensors", "1.0"]]);
    wire.hits[0].document.version = "1.0-revision-2026-12-02";
    res.end(JSON.stringify(wire));
  });
  const hit = await catalog(fixture).latest("example/sensors@1.0-revision-2026-12-01");
  assert.equal(hit?.base_url, "https://example.org/r1/");
  const sent = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.deepEqual(sent.map((url) => url.pathname), [searchPath]);
  assert.equal(sent[0].searchParams.get("filter_by"),
    "namespace:=`example` && name:=`sensors` && version:=`1.0-revision-2026-12-01`");
});

test("latest of a versioned reference without a hit of that original version returns undefined", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(page([], 1, 10, 0))));
  assert.equal(await catalog(fixture).latest("example/sensors@9"), undefined);
});

test("latest of a versioned reference gives the version itself when the hub has no latest-revision collection", async (t) => {
  const fixture = await server(t, (req, res) => {
    if (req.url.startsWith(latestRevisionPath)) {
      res.writeHead(404);
      return res.end('{"message":"Not Found"}');
    }
    // A look-alike version, then the version itself
    const wire = page([`${fixture.url}/1.0.0/`, `${fixture.url}/1.0/`], 1, 10, 2);
    wire.hits[0].document.version = "1.0.0";
    wire.hits[1].document.version = "1.0";
    res.end(JSON.stringify(wire));
  });
  const hit = await catalog(fixture).latest("example/sensors@1.0");
  assert.equal(hit?.base_url, `${fixture.url}/1.0/`);
  const [, search] = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.equal(search.pathname, searchPath);
  assert.equal(search.searchParams.get("filter_by"), "namespace:=`example` && name:=`sensors` && version:=`1.0`");
});

test("latest of a version without its revisions searches the version itself without looking for them", async (t) => {
  const fixture = await server(t, (_, res) => {
    const wire = page([`${fixture.url}/1.0/`], 1, 10, 1);
    wire.hits[0].document.version = "1.0";
    res.end(JSON.stringify(wire));
  });
  const hit = await catalog(fixture).latest("example/sensors@1.0-no-revision");
  assert.equal(hit?.base_url, `${fixture.url}/1.0/`);
  const searches = fixture.requests.map((req) => new URL(req.url, fixture.url));
  assert.deepEqual(searches.map((url) => url.pathname), [searchPath]);
  assert.equal(searches[0].searchParams.get("filter_by"), "namespace:=`example` && name:=`sensors` && version:=`1.0`");
});

test("inspect of a version without its revisions fetches its release URL without searching", async (t) => {
  const fixture = await server(t, (_, res) => res.end(publication));
  await catalog(fixture).inspect("example/sensors@1.0-no-revision");
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/files/packages/example/sensors/1.0/"]);
});

test("latest of a versioned reference reports other HTTP errors", async (t) => {
  const fixture = await server(t, (_, res) => { res.writeHead(503); res.end(); });
  await assert.rejects(catalog(fixture).latest("example/sensors@1.0"),
    failure("HTTP_ERROR", "SEARCH", `${fixture.url}${latestRevisionPath}`, 503));
});

test("get returns complete binary bytes and repeats an explicit request", async (t) => {
  const fixture = await server(t, (_, res) => res.end(binary));
  const client = new CatalogClient();
  assert.deepEqual([...await client.get(`${fixture.url}/file?signature=a%2Bb`)], [0, 255, 128, 65]);
  assert.deepEqual([...await client.get(`${fixture.url}/file?signature=a%2Bb`)], [0, 255, 128, 65]);
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/file?signature=a%2Bb", "/file?signature=a%2Bb"]);
});

test("get accepts an empty file", async (t) => {
  const fixture = await server(t, (_, res) => res.end());
  assert.deepEqual([...await new CatalogClient().get(fixture.url)], []);
});

for (const status of [204, 206, 404]) {
  test(`get rejects HTTP ${status}`, async (t) => {
    const fixture = await server(t, (_, res) => { res.writeHead(status); res.end("unavailable"); });
    await assert.rejects(new CatalogClient().get(fixture.url), (error) => {
      failure("HTTP_ERROR", "GET", fixture.url, status)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
  });
}

test("HTTP errors preserve nested backend JSON without replacing the SDK category", async (t) => {
  const fixture = await server(t, (_, res) => {
    res.writeHead(503);
    res.end('{"code":"future","details":{"value":null},"recovery":{"arguments":[null,7,{"wait":true}]}}');
  });
  await assert.rejects(new CatalogClient().inspect(fixture.url), (error) => {
    failure("HTTP_ERROR", "INSPECT", fixture.url, 503)(error);
    assert.deepEqual(JSON.parse(JSON.stringify(error.error)).response_json,
      { code: "future", details: { value: null }, recovery: { arguments: [null, 7, { wait: true }] } });
    return true;
  });
});

for (const body of ["{", "[]"]) {
  test(`HTTP error omits invalid object body ${body}`, async (t) => {
    const fixture = await server(t, (_, res) => { res.writeHead(503); res.end(body); });
    await assert.rejects(new CatalogClient().get(fixture.url), (error) => {
      failure("HTTP_ERROR", "GET", fixture.url, 503)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
  });
}

function invalidUtf8(wire) {
  const bytes = Buffer.from(wire);
  bytes[bytes.indexOf("Sensors", bytes.indexOf('"title"'))] = 0xff;
  return bytes;
}

for (const [name, bytes] of [["malformed JSON", Buffer.from("{")], ["invalid UTF-8", invalidUtf8(JSON.stringify(page(["https://example.org/a"])))]]) {
  test(`SEARCH categorizes ${name}`, async (t) => {
    const fixture = await server(t, (_, res) => res.end(bytes));
    await assert.rejects(catalog(fixture).search({ q: "*" }), failure("INVALID_RESPONSE", "SEARCH", `${fixture.url}${latestPath}`, 200));
  });
}

for (const [name, bytes] of [["malformed JSON", Buffer.from("{")], ["invalid UTF-8", invalidUtf8(publication)]]) {
  test(`INSPECT categorizes ${name}`, async (t) => {
    const fixture = await server(t, (_, res) => res.end(bytes));
    await assert.rejects(catalog(fixture).inspect(fixture.url), failure("INVALID_RESPONSE", "INSPECT", fixture.url, 200));
  });
}

test("inspect rejects metadata the publication decoder rejects", async (t) => {
  const fixture = await server(t, (_, res) => res.end("{}"));
  await assert.rejects(new CatalogClient().inspect(fixture.url), failure("INVALID_RESPONSE", "INSPECT", fixture.url, 200));
});

test("a refused connection fails without a fabricated response status", async () => {
  const listener = createServer();
  listener.listen(0, "127.0.0.1");
  await once(listener, "listening");
  const url = `http://127.0.0.1:${listener.address().port}`;
  await new Promise((resolve) => listener.close(resolve));
  await assert.rejects(new CatalogClient().get(url), failure("CONNECTION_ERROR", "GET", url));
});

test("a truncated successful body fails without returning partial bytes", async (t) => {
  const fixture = await server(t, (_, res) => {
    res.writeHead(200, { "Content-Length": "100", Connection: "close" });
    res.end("partial");
  });
  await assert.rejects(new CatalogClient().get(fixture.url), failure("CONNECTION_ERROR", "GET", fixture.url, 200));
});

test("redirect refusal never requests its target", async (t) => {
  const fixture = await server(t, (_, res) => { res.writeHead(302, { Location: "/target" }); res.end(); });
  await assert.rejects(new CatalogClient().get(`${fixture.url}/redirect`), failure("CONNECTION_ERROR", "GET", `${fixture.url}/redirect`));
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/redirect"]);
});

for (const [status, code] of [[200, "TIMEOUT"], [503, "HTTP_ERROR"]]) {
  test(`stalled HTTP ${status} body yields ${code} and closes the transfer`, { timeout: 3000 }, async (t) => {
    let closed;
    const closure = new Promise((resolve) => { closed = resolve; });
    const fixture = await server(t, (_, res) => {
      res.on("close", closed);
      res.writeHead(status, { "Content-Length": "100" });
      res.write("{");
    });
    await assert.rejects(new CatalogClient({ timeout_ms: 100 }).get(fixture.url), (error) => {
      failure(code, "GET", fixture.url, status)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
    await closure;
    assert.equal(fixture.requests.length, 1);
  });
}
