// Tests for the address reported to Plausible. Run with `npm test` (see run.mjs).
import { test } from "node:test";
import assert from "node:assert/strict";
import { reportedUrl } from "../src/lib/analytics.js";

test("a share link is reported without the schema it carries", () => {
  assert.equal(
    reportedUrl("https://linkml.ohi.dev/playground/#s=H4sIAAAAAAAAA"),
    "https://linkml.ohi.dev/playground/",
  );
  assert.equal(
    reportedUrl("https://linkml.ohi.dev/playground/?url=https%3A%2F%2Fexample.org%2Fs.yaml&step=owl"),
    "https://linkml.ohi.dev/playground/",
  );
});

test("campaign parameters are kept", () => {
  assert.equal(
    reportedUrl("https://linkml.ohi.dev/?utm_source=newsletter&url=x&ref=github#top"),
    "https://linkml.ohi.dev/?utm_source=newsletter&ref=github",
  );
});
