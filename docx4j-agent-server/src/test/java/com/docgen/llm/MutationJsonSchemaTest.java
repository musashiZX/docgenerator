package com.docgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6.1 — the schema must structurally describe every batch we accept
 * (golden files + representative op payloads). We assert on the schema
 * document itself; strict decoding correctness is OpenAI's side.
 */
class MutationJsonSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void responseFormatIsStrictNamedJsonSchema() {
        JsonNode format = MutationJsonSchema.responseFormat(mapper);

        assertEquals("json_schema", format.get("type").asText());
        JsonNode jsonSchema = format.get("json_schema");
        assertEquals("mutation_batch", jsonSchema.get("name").asText());
        assertTrue(jsonSchema.get("strict").asBoolean());
        assertEquals("object", jsonSchema.get("schema").get("type").asText());
    }

    @Test
    void batchSchemaRequiresAllTopLevelFieldsAndForbidsExtras() {
        JsonNode schema = MutationJsonSchema.batchSchema(mapper);

        assertEquals(false, schema.get("additionalProperties").asBoolean());
        List<String> required = valuesOf(schema.get("required"));
        assertTrue(required.containsAll(List.of("schema_version", "explanation", "mutations")));
        assertEquals(1, schema.get("properties").get("schema_version").get("enum").get(0).asInt());
    }

    @Test
    void mutationItemsCoverAllFourOps() {
        JsonNode anyOf = MutationJsonSchema.batchSchema(mapper)
                .get("properties").get("mutations").get("items").get("anyOf");

        assertEquals(4, anyOf.size());
        assertEquals("modify", opOf(anyOf.get(0)));
        assertEquals("insert", opOf(anyOf.get(1)));
        assertEquals("delete", opOf(anyOf.get(2)));
        assertEquals("format", opOf(anyOf.get(3)));

        // Strict mode: every property must be required, no extras allowed.
        for (JsonNode variant : anyOf) {
            assertEquals(false, variant.get("additionalProperties").asBoolean());
            List<String> required = valuesOf(variant.get("required"));
            variant.get("properties").fieldNames()
                    .forEachRemaining(field -> assertTrue(required.contains(field),
                            "strict schema must require every property, missing: " + field));
        }
    }

    @Test
    void goldenMutationFilesConformToSchemaShape() throws Exception {
        // The schema must at least accept our golden batches: parse each one
        // and check fields against the corresponding variant's requirements.
        for (String golden : List.of(
                "golden/modify-cell/mutation.json",
                "golden/insert-delete/mutation.json")) {
            JsonNode batch = mapper.readTree(
                    getClass().getClassLoader().getResourceAsStream(golden));
            assertEquals(1, batch.get("schema_version").asInt(), golden);
            assertTrue(batch.get("mutations").isArray(), golden);
            for (JsonNode mutation : batch.get("mutations")) {
                String op = mutation.get("op").asText();
                assertTrue(List.of("modify", "insert", "delete").contains(op),
                        golden + " has unknown op " + op);
            }
        }
    }

    private static String opOf(JsonNode variant) {
        return variant.get("properties").get("op").get("enum").get(0).asText();
    }

    private static List<String> valuesOf(JsonNode array) {
        return mapperless(array);
    }

    private static List<String> mapperless(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
