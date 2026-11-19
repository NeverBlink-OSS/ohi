# ohi SDK for Python

Search ontologies on [ohi.dev](https://ohi.dev), inspect their metadata, download selected files and save file selections.

Install on Python 3.10 or later:

```sh
python -m pip install ohi-sdk
```

Search, inspect the first hit and download its first file:

```python
from ohi import CatalogClient, SearchRequest

client = CatalogClient()
page = client.search(SearchRequest(q="sensors", page=1, per_page=10))
if page.hits:
    publication = client.inspect(page.hits[0].base_url)
    artifact = publication.artifacts[0]
    content = client.get(artifact.url)
```

`inspect` and `latest` also take an ontology reference instead of a URL. `w3c/dcat` means the latest revision of the latest version, and `w3c/dcat@3` means the latest revision of version 3 (or version 3 itself when it has no revisions). `inspect` takes a revision version such as `w3c/dcat@3-revision-2026-12-01` exactly as written.

```python
hit = client.latest("w3c/dcat@3")  # search hit, or None
publication = client.inspect("w3c/dcat@3")
```

`get` returns the file's bytes.

**See the [SDK documentation](https://ohi.dev/docs/reference/sdks/)** for configuration, file selections, metadata and errors.
