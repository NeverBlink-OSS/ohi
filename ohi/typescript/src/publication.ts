import type { Artifact, ArtifactOrigin, Dependency, Format, Publication } from "./generated.js";
import { declared, type Fields } from "./fields.js";

// The compiler checks that these have every value of the generated enums.
export const formats: Record<Format, true> = {
  "owl-turtle": true, "owl-rdfxml": true, "rdfs-turtle": true, "linkml-yaml": true, "ossie-yaml": true,
  "shacl-turtle": true,
};
const origins: Record<ArtifactOrigin, true> = { UPSTREAM: true, GENERATED: true };

export const publicationFields: Fields<Publication> = {
  schema_version: true, base_url: true, version: true, namespace: true, name: true, title: true, published_at: true,
  publisher: true, licenses: true, namespace_iri: true, preferred_prefix: true, description: true, keywords: true,
  element_count: true, native_element_count: true, artifacts: true, dependencies: true,
};
export const artifactFields: Fields<Artifact> = {
  format: true, url: true, origin: true, roots: true, size_bytes: true, sha256: true,
};
export const dependencyFields: Fields<Dependency> = { base_url: true };

/** Whether a value is text outside an enum, so added to it after this SDK version. */
export function isNew(value: unknown, known: object): boolean {
  return typeof value === "string" && !Object.hasOwn(known, value);
}

/**
 * Decode publication metadata, discarding extras and artifacts with a format or origin added
 * after this SDK version. Field values are not validated.
 */
export function decodePublication(text: string): Publication {
  const data: Publication = JSON.parse(text);
  if (data.preferred_prefix !== undefined && data.namespace_iri === undefined) {
    throw new Error("preferred_prefix requires namespace_iri");
  }
  const artifacts = data.artifacts.filter(({ format, origin }) => !isNew(format, formats) && !isNew(origin, origins));
  if (data.artifacts.length > 0 && artifacts.length === 0) {
    throw new Error("publication has no artifacts that this SDK version supports");
  }
  const artifactUrls = artifacts.map(({ url }) => url);
  if (new Set(artifactUrls).size !== artifactUrls.length) {
    throw new Error("artifacts must have unique URLs");
  }
  const dependencyUrls = data.dependencies.map(({ base_url }) => base_url);
  if (new Set(dependencyUrls).size !== dependencyUrls.length) {
    throw new Error("dependencies must have unique base_url values");
  }
  if (dependencyUrls.includes(data.base_url)) {
    throw new Error("dependencies must not contain the publication itself");
  }

  return {
    ...declared(data, publicationFields),
    artifacts: artifacts.map((artifact) => declared(artifact, artifactFields)),
    dependencies: data.dependencies.map((dependency) => declared(dependency, dependencyFields)),
  };
}
