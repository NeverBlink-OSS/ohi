import copy
import unittest

from check import breaks

# A cut-down contract with the classes and fields that the tests change or the check opens.
RELEASED = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$ref": "#/$defs/Publication",
    "$defs": {
        "Publication": {
            "type": "object",
            "required": ["published_at", "artifacts"],
            "properties": {
                "published_at": {"type": "string", "format": "date-time"},
                "artifacts": {"type": "array", "items": {"$ref": "#/$defs/Artifact"}},
            },
        },
        "Artifact": {
            "type": "object",
            "required": ["format", "origin"],
            "properties": {"format": {"$ref": "#/$defs/Format"}, "origin": {"$ref": "#/$defs/ArtifactOrigin"}},
        },
        "SearchHit": {
            "type": "object",
            "required": ["formats"],
            "properties": {"formats": {"type": "array", "items": {"$ref": "#/$defs/Format"}}},
        },
        "Format": {"type": "string", "enum": ["owl-turtle", "owl-rdfxml"]},
        "ArtifactOrigin": {"type": "string", "enum": ["UPSTREAM"]},
    },
}
SEARCH_HIT = {**RELEASED, "$ref": "#/$defs/SearchHit"}


def changed(schema):
    """A copy of the schema and its classes, for a test to edit."""
    current = copy.deepcopy(schema)
    return current, current["$defs"]


class BreaksTest(unittest.TestCase):
    def assert_breaks(self, messages, clients):
        self.assertEqual(len(messages), 1, messages)
        self.assertTrue(messages[0].startswith(clients), messages[0])

    def test_new_optional_field_breaks_nothing(self):
        current, classes = changed(RELEASED)
        classes["Publication"]["properties"]["revision"] = {"type": "integer"}

        self.assertEqual(breaks("package", RELEASED, current), [])

    def test_new_required_field_breaks_clients_built_from_the_new_schema(self):
        current, classes = changed(RELEASED)
        classes["Publication"]["properties"]["revision"] = {"type": "integer"}
        classes["Publication"]["required"].append("revision")

        self.assert_breaks(breaks("package", RELEASED, current), "Clients built from this schema")

    def test_field_that_is_no_longer_required_breaks_released_clients(self):
        current, classes = changed(RELEASED)
        classes["Publication"]["required"].remove("published_at")

        self.assert_breaks(breaks("package", RELEASED, current), "Released clients")

    def test_date_that_becomes_plain_text_breaks_released_clients(self):
        current, classes = changed(RELEASED)
        del classes["Publication"]["properties"]["published_at"]["format"]

        self.assert_breaks(breaks("package", RELEASED, current), "Released clients")

    def test_new_format_of_a_file_breaks_nothing(self):
        current, classes = changed(RELEASED)
        classes["Format"]["enum"].append("shacl-turtle")

        self.assertEqual(breaks("package", RELEASED, current), [])

    def test_new_format_of_a_search_hit_breaks_nothing(self):
        current, classes = changed(SEARCH_HIT)
        classes["Format"]["enum"].append("shacl-turtle")

        self.assertEqual(breaks("search", SEARCH_HIT, current), [])

    def test_new_format_breaks_released_clients_in_a_field_where_they_do_not_skip_it(self):
        released, classes = changed(RELEASED)
        classes["Publication"]["properties"]["source_format"] = {"$ref": "#/$defs/Format"}
        current, classes = changed(released)
        classes["Format"]["enum"].append("shacl-turtle")

        self.assert_breaks(breaks("package", released, current), "Released clients")

    def test_removed_format_breaks_clients_built_from_the_new_schema(self):
        current, classes = changed(RELEASED)
        classes["Format"]["enum"].remove("owl-rdfxml")

        self.assert_breaks(breaks("package", RELEASED, current), "Clients built from this schema")
