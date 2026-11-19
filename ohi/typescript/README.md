# ohi SDK for TypeScript

Search ontologies on [ohi.dev](https://ohi.dev), inspect their metadata, download selected files and save file selections.

Works with Node 22 or later and browsers with Fetch. Browsers need CORS access to the catalog, publication and artifact endpoints.

```sh
npm install @neverblink/ohi
```

Search, inspect the first hit and download its first file:

```typescript
import { CatalogClient } from "@neverblink/ohi";

const client = new CatalogClient();
const page = await client.search({ q: "sensors", page: 1, per_page: 10 });
if (page.hits.length > 0) {
  const publication = await client.inspect(page.hits[0].base_url);
  const artifact = publication.artifacts[0];
  const content = await client.get(artifact.url);
}
```

`inspect` and `latest` also take an ontology reference instead of a URL. `w3c/dcat` means the latest revision of the latest version, and `w3c/dcat@3` means the latest revision of version 3 (or version 3 itself when it has no revisions). `inspect` takes a revision version such as `w3c/dcat@3-revision-2026-12-01` exactly as written.

```typescript
const hit = await client.latest("w3c/dcat@3"); // search hit, or undefined
const publication = await client.inspect("w3c/dcat@3");
```

`get` returns the file's bytes as a `Uint8Array`. File reading and writing are in `@neverblink/ohi/node`.

**See the [SDK documentation](https://ohi.dev/docs/reference/sdks/)** for configuration, file selections, metadata and errors.
