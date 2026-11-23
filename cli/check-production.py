#!/usr/bin/env python3
"""Check api.ohi.dev with a released ohi executable and the installed ohi-sdk release, then check
every search hit and publication it serves against the contract's JSON Schemas and that release.

    check-production.py <ohi executable> <directory of package.schema.json and search.schema.json>
"""

from concurrent.futures import ThreadPoolExecutor
from itertools import chain, count
import json
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory

from jsonschema import Draft202012Validator
from ohi import CatalogClient, CatalogException, SearchRequest, decode_publication

binary = str(Path(sys.argv[1]).resolve())
schemas = Path(sys.argv[2])
# Locked by ohi 0.18.7. The hub generated the locked file, so a regeneration that changes it
# fails the sync.
baseline = Path(__file__).resolve().parent / "contract" / "baseline"


def ohi(*arguments):
    return subprocess.run(
        [binary, *arguments], check=True, stdout=subprocess.PIPE, text=True, timeout=120,
    ).stdout


hits = json.loads(ohi("search", "prov-o", "--json"))["hits"]
assert any((hit["namespace"], hit["name"]) == ("w3c", "prov-o") for hit in hits), hits
ohi("inspect", "w3c/prov-o")
with TemporaryDirectory(prefix="ohi-production-") as directory:
    ohi("get", "w3c/prov-o@2013", "--to", directory)
    project = shutil.copytree(baseline, Path(directory) / "project")
    ohi("sync", "--locked", "--project", str(project))
print("The released ohi searched, inspected, downloaded and synced w3c/prov-o.")

client = CatalogClient()
found = client.search(SearchRequest(q="prov-o")).hits
assert any((hit.namespace, hit.name) == ("w3c", "prov-o") for hit in found), found
assert client.latest("w3c/prov-o")
client.get(client.inspect("w3c/prov-o@2013").artifacts[0].url)
print("The released ohi-sdk searched, inspected and downloaded w3c/prov-o.")


def served(url):
    """The text at a URL. The released SDK fetches it, so a redirect or a compressed answer fails
    here as it does for clients."""
    try:
        return client.get(url).decode("utf-8")
    except CatalogException as failure:
        sys.exit(f"{url}: {failure}")


def documents(collection):
    search = f"https://api.ohi.dev/search/collections/{collection}/documents/search"
    for page in count(1):
        hits = json.loads(served(f"{search}?q=*&per_page=250&page={page}"))["hits"]
        if not hits:
            return
        yield from (hit["document"] for hit in hits)


def validator(file):
    schema = json.loads((schemas / f"{file}.schema.json").read_text(encoding="utf-8"))
    return Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)


search_hit = validator("search")
publication = validator("package")


def mismatches(schema, value, source):
    return [f"{source}: {error.json_path} {error.message}" for error in schema.iter_errors(value)]


def publication_mismatches(base_url):
    text = served(base_url)
    found = mismatches(publication, json.loads(text), base_url)
    # The SDK also refuses what the schema cannot express, such as two files with one URL.
    try:
        decode_publication(text)
    except ValueError as refusal:
        found.append(f"{base_url}: the released ohi-sdk refuses it. {refusal}")
    return found


problems = []
releases = set()
for collection in ("packages", "packages_latest"):
    indexed = list(documents(collection))
    assert indexed, f"{collection} is empty"
    for document in indexed:
        releases.add(document["base_url"])
        problems += mismatches(search_hit, document, f"{collection}, {document['base_url']}")
with ThreadPoolExecutor(12) as pool:
    problems += chain.from_iterable(pool.map(publication_mismatches, releases))

print(f"Checked the search hits and publications of {len(releases)} releases.")
if problems:
    sys.exit("\n".join(["These do not match the contract:", *problems]))
