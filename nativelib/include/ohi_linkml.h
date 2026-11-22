/* ohi LinkML: schema validation and multi-format code generation, as a C library.
 *
 * Two conventions run through the whole API:
 *
 *   - Options are one JSON string, and may be NULL for defaults.
 *   - Failure is NULL plus a message. A function returning a string returns NULL and writes the
 *     reason to *error; loading returns handle 0 instead. Every out-param is cleared first, so they
 *     can be reused across calls. A NULL return with no message is still a failure:
 *     it means even the message could not be allocated.
 *   - A schema that can't be written, or an ontology that can't be imported, for a reason the
 *     caller can fix, writes a validation report in JSON to *error instead of a message. It has
 *     one SchemaConversionError issue, whose "message" says why. Such a report starts with '{'.
 *
 * Every string the library returns belongs to the caller and must go back through linkml_free().
 * Strings in and out are NUL-terminated UTF-8.
 *
 * Threading: the library must be used from one thread. Scala Native cannot register a thread it
 * did not create (scala-native#4951), so a caller with several threads has to funnel calls through
 * one of its own, as the Python bindings do. Call linkml_init_threads() once from that thread
 * before anything else.
 */

#ifndef OHI_LINKML_H
#define OHI_LINKML_H

#ifdef __cplusplus
extern "C" {
#endif

/* Marks a function that is still exported, but will be removed. */
#if defined(__GNUC__) || defined(__clang__)
#define LINKML_DEPRECATED(reason) __attribute__((deprecated(reason)))
#elif defined(_MSC_VER)
#define LINKML_DEPRECATED(reason) __declspec(deprecated(reason))
#else
#define LINKML_DEPRECATED(reason)
#endif

/* Bumped whenever a change to the exported functions or to the options JSON breaks callers. */
#define LINKML_ABI_VERSION 5

/* The ABI version this library was built with. Compare against LINKML_ABI_VERSION. */
int linkml_abi_version(void);

/* Correct the stack bounds recorded when the library was loaded, and claim the calling thread.
 * Call once, from the thread that loaded the library and that will make every call. */
int linkml_init_threads(void);

/* Version and build metadata for this library, as a BuildInfo document in JSON. */
char *linkml_build_info(char **error);

/* Parse a schema from the file system, resolving imports from disk like the CLI does.
 * Returns a handle, or 0 with the reason in *report (the schema was refused) or *error. */
long long linkml_load_file(const char *path, const char *options, char **report, char **error);

/* Parse a schema from memory, resolving imports against a caller-supplied map given as two
 * parallel arrays. Pass path to read the root through the map too, which makes loading immune to
 * an import that references the root back; otherwise pass NULL and give the text in schema. */
long long linkml_load_string(const char *path, const char *schema, char **import_names,
                             char **import_bodies, int import_count, const char *options,
                             char **report, char **error);

/* Release a schema handle. Closing one that is already gone is not an error. */
void linkml_close(long long handle);

/* The schema as it was loaded, without its imports merged in, as YAML or JSON. */
char *linkml_serialize(long long handle, const char *options, char **error);

/* Re-run the validator over a loaded schema, as a SchemaValidationReport in JSON. */
char *linkml_lint(long long handle, const char *options, char **error);

/* Generators. Each takes a schema handle and returns one document. */
char *linkml_json_schema(long long handle, const char *options, char **error);
char *linkml_shacl(long long handle, const char *options, char **error);
char *linkml_rdfs(long long handle, const char *options, char **error);
char *linkml_linkml(long long handle, const char *options, char **error);
char *linkml_graphql(long long handle, const char *options, char **error);
char *linkml_typescript(long long handle, const char *options, char **error);
char *linkml_pydantic(long long handle, const char *options, char **error);
char *linkml_er_diagram(long long handle, const char *options, char **error);
char *linkml_ossie(long long handle, const char *options, char **error);
char *linkml_owl(long long handle, const char *options, char **error);
char *linkml_translation(long long handle, const char *options, char **error);

/* Statistics about the schema, as a SchemaStats in JSON. */
char *linkml_stats(long long handle, const char *options, char **error);

/* Generators producing several files, returned as a JSON object of filename to content. */
char *linkml_scala(long long handle, const char *options, char **error);
char *linkml_java(long long handle, const char *options, char **error);
char *linkml_frictionless(long long handle, const char *options, char **error);
char *linkml_docs(long long handle, const char *options, char **error);

/* Importers, deprecated: use the loaders below and linkml_serialize instead. Each takes a document
 * and returns an ImportResult in JSON: the LinkML schema it describes in "schema", and what could
 * not be imported, as lines in "warnings" and as an ImportReport (model/import-warnings.yaml) in
 * "report". */
LINKML_DEPRECATED("use linkml_load_ossie and linkml_serialize")
char *linkml_from_ossie(const char *ontology, const char *options, char **error);
LINKML_DEPRECATED("use linkml_load_owl and linkml_serialize")
char *linkml_from_owl(const char *ontology, const char *options, char **error);

/* Loaders. Each imports a document like the importer of the same name, but loads the schema instead
 * of writing it. Returns a handle, or 0 with the reason in *report (the schema was refused) or
 * *error (the document could not be imported). *not_imported gets what could not be imported, as
 * the "warnings" and "report" of an ImportResult. The options are the importer's, except "format"
 * and "full_uris": the text of a loaded schema comes from linkml_serialize. */
long long linkml_load_ossie(const char *ontology, const char *options, char **not_imported,
                            char **report, char **error);
long long linkml_load_owl(const char *ontology, const char *options, char **not_imported,
                          char **report, char **error);

/* Release anything the library returned. NULL-safe. */
void linkml_free(char *buffer);

#ifdef __cplusplus
}
#endif

#endif /* OHI_LINKML_H */
