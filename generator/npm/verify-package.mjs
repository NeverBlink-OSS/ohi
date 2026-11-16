// Packs and installs the built package into a throwaway project, then runs the
// packaged README's JS examples (skipping blocks tagged `no-test`) and, if a TS
// usage sample is given, type-checks it against the generated index.d.ts.
// If the examples of the docs are given, it runs them next to the files in
// <examples-dir>, and type-checks them as TypeScript.
// Finally, it checks that what the API really returns for the build info, validation
// reports and schema statistics fits the types generated from their LinkML models.
//
//   node verify-package.mjs <package-dir> [usage-sample.ts] [snippets.mjs examples-dir]
//
// Non-zero exit = install failed, an example threw, the sample did not compile, or
// a real return value does not fit its declared type.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, copyFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkgDir = process.argv[2];
const usageSample = process.argv[3];
const snippets = process.argv[4];
const examplesDir = process.argv[5];
if (!pkgDir) {
  console.error("Usage: node verify-package.mjs <package-dir> [usage-sample.ts]");
  process.exit(2);
}

function run(cmd, args, cwd) {
  return execFileSync(cmd, args, { cwd, encoding: "utf8" });
}

// Extract runnable JS blocks from the packaged README.
const readme = readFileSync(join(pkgDir, "README.md"), "utf8");
const blocks = [];
const fence = /```(\w+)([^\n]*)\n([\s\S]*?)```/g;
let match;
while ((match = fence.exec(readme)) !== null) {
  const lang = match[1].toLowerCase();
  const info = match[2] || "";
  if ((lang === "js" || lang === "javascript") && !info.includes("no-test")) {
    blocks.push(match[3]);
  }
}
if (blocks.length === 0) {
  console.error("No runnable ```js blocks found in README.md");
  process.exit(1);
}
console.log(`Found ${blocks.length} runnable JS example(s) in README.md`);

// Pack the package and install it into a throwaway consumer project, so imports
// resolve `@neverblink/linkml` through the published `exports` map.
const work = mkdtempSync(join(tmpdir(), "linkml-pkg-"));
run("npm", ["pack", "--pack-destination", work], pkgDir);
const tarball = readdirSync(work).find((f) => f.endsWith(".tgz"));
run("npm", ["init", "-y"], work);
run("npm", ["install", "--no-audit", "--no-fund", join(work, tarball)], work);

let failures = 0;

// 1. Run each README example verbatim.
blocks.forEach((code, i) => {
  const file = join(work, `example-${i + 1}.mjs`);
  writeFileSync(file, code);
  try {
    run("node", [file], work);
    console.log(`  example ${i + 1}: OK`);
  } catch (e) {
    failures++;
    console.error(`  example ${i + 1}: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
  }
});

run("npm", ["install", "--no-save", "--no-audit", "--no-fund", "typescript", "@types/node"], work);

function typeCheck(file, ...options) {
  run(
    "npx",
    ["tsc", "--noEmit", "--strict", "--module", "nodenext", "--moduleResolution", "nodenext", ...options, file],
    work,
  );
}

// 2. Type-check the usage sample against the generated declarations.
if (usageSample && existsSync(usageSample)) {
  console.log("Type-checking usage sample against generated index.d.ts");
  copyFileSync(usageSample, join(work, "usage.ts"));
  try {
    typeCheck("usage.ts");
    console.log("  type check: OK");
  } catch (e) {
    failures++;
    console.error(`  type check: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
  }
}

// 3. Run the examples of the docs, and type-check them.
if (snippets) {
  console.log("Running the examples of the docs");
  for (const file of readdirSync(examplesDir)) copyFileSync(join(examplesDir, file), join(work, file));
  copyFileSync(snippets, join(work, "snippets.mjs"));
  copyFileSync(snippets, join(work, "snippets.ts"));
  try {
    run("node", ["snippets.mjs"], work);
    typeCheck("snippets.ts", "--types", "node");
    console.log("  docs examples: OK");
  } catch (e) {
    failures++;
    console.error(`  docs examples: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
  }
}

// 4. Check real return values against the types generated from their LinkML models. The
// values are written out as object literals, so `tsc` also rejects keys the types don't have.
console.log("Type-checking real build info, validation reports, stats and import results against index.d.ts");
const collect = `
import { writeFileSync } from "node:fs";
import { LinkML } from "@neverblink/linkml";

const head = "id: https://example.org/s\\nname: s\\nimports:\\n  - linkml:types\\n";
const schemas = [
  // warnings only: no descriptions
  head + "classes:\\n  A:\\n    tree_root: true\\n    attributes:\\n      x:\\n        range: string\\n",
  // fatal: an unknown range
  head + "classes:\\n  A:\\n    attributes:\\n      x:\\n        range: Nope\\n",
  // warnings: no tree root, slot_usage of an unknown slot, a non-standard separator
  head + "classes:\\n  b-b:\\n    slot_usage:\\n      zz: {}\\n",
  // errors: two identifiers, an undefined prefix
  head + "classes:\\n  A:\\n    tree_root: true\\n    class_uri: nope:A\\n    attributes:\\n" +
    "      a:\\n        identifier: true\\n      b:\\n        identifier: true\\n",
  // not YAML at all
  "id: [",
];
const reports = [];
const stats = [];
for (const schema of schemas)
  for (const inferMessages of [true, false]) {
    const loaded = LinkML.loadFromString(schema, {}, inferMessages);
    reports.push(loaded.report);
    if (loaded.view) {
      reports.push(LinkML.lint(loaded.view, inferMessages));
      stats.push(loaded.view.stats(), loaded.view.stats({ pruning_mode: "skip" }));
    }
  }
const issueTypes = new Set(reports.flatMap((r) => r.issues.map((i) => i.issue_type)));
const ttl = "@prefix : <https://example.org/t/> .\\n" +
  "@prefix owl: <http://www.w3.org/2002/07/owl#> .\\n" +
  "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\\n" +
  "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\\n" +
  "<https://example.org/t> a owl:Ontology ; owl:imports <https://example.org/other> .\\n" +
  ":A a owl:Class ; rdfs:subClassOf [ a owl:Class ; owl:complementOf :B ] .\\n" +
  ":B a owl:Class .\\n" +
  ":p a owl:ObjectProperty ; rdfs:range [ a owl:Class ; owl:intersectionOf ( :A :B ) ] .\\n" +
  ":q a owl:ObjectProperty ; owl:propertyDisjointWith :p ; owl:propertyChainAxiom ( :p :p ) .\\n" +
  ":x owl:sameAs :y .\\n" +
  ":D a rdfs:Datatype ; owl:equivalentClass [ a rdfs:Datatype ; owl:onDatatype xsd:decimal ;\\n" +
  "    owl:withRestrictions ( [ xsd:minExclusive 0 ] [ xsd:length 2 ] ) ] .\\n";
const ossie = "version: 0.3.0.dev0\\nname: o\\nrequires: ['A.x >= 0']\\nontology:\\n  - concept: A\\n    type: EntityType\\n" +
  "    requires: ['NOT (A.x)']\\n    relationships:\\n      - name: x\\n" +
  "        roles: [{concept: Integer}, {concept: String}]\\n" +
  "        verbalizes: ['{A} has x {Integer}']\\n";
const imports = [LinkML.fromOwl(ttl), LinkML.fromOwl(""), LinkML.fromOssie(ossie)];
const warningTypes = new Set(imports.flatMap((r) => r.report.warnings.map((w) => w.warning_type)));
writeFileSync(
  "outputs.ts",
  'import type { BuildInfo, ImportResult, SchemaStats, SchemaValidationReport } from "@neverblink/linkml";\\n' +
    "export const build: BuildInfo = " + JSON.stringify(LinkML.buildInfo(), null, 2) + ";\\n" +
    "export const reports: SchemaValidationReport[] = " + JSON.stringify(reports, null, 2) + ";\\n" +
    "export const stats: SchemaStats[] = " + JSON.stringify(stats, null, 2) + ";\\n" +
    "export const imports: ImportResult[] = " + JSON.stringify(imports, null, 2) + ";\\n",
);
console.log([...issueTypes].sort().join(", "));
console.log([...warningTypes].sort().join(", "));
`;
writeFileSync(join(work, "collect.mjs"), collect);
try {
  const [issueTypes, warningTypes] = run("node", ["collect.mjs"], work).trim().split("\n");
  console.log(`  issue types covered: ${issueTypes}`);
  console.log(`  import warning types covered: ${warningTypes}`);
  typeCheck("outputs.ts");
  console.log("  real values fit the types: OK");
} catch (e) {
  failures++;
  console.error(`  real values fit the types: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
}

if (failures > 0) {
  console.error(`\n${failures} package check(s) failed.`);
  process.exit(1);
}
console.log("\nPackage verified: README examples run, types compile, and real values fit them.");
