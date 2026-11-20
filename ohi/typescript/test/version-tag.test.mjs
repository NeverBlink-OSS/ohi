import assert from "node:assert/strict";
import test from "node:test";
import { parseVersionTag } from "../dist/version-tag.js";

test("a version requests its latest revision", () => {
  assert.deepEqual(parseVersionTag("1.0"), { original: "1.0" });
});

test("a revision requests exactly that revision", () => {
  assert.deepEqual(parseVersionTag("1.0-revision-2026-12-31"),
    { original: "1.0", exact: "1.0-revision-2026-12-31" });
});

test("-no-revision requests the version without its revisions", () => {
  assert.deepEqual(parseVersionTag("1.0-no-revision"), { original: "1.0", exact: "1.0" });
});

test("a version that is only a suffix is kept as it is", () => {
  assert.deepEqual(parseVersionTag("-no-revision"), { original: "-no-revision" });
  assert.deepEqual(parseVersionTag("-revision-1"), { original: "-revision-1" });
});
