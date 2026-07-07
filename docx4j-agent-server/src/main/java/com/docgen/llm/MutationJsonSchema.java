package com.docgen.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strict JSON schema for {@link com.docgen.model.MutationBatch}, used as the
 * OpenAI structured-output {@code response_format}. Constrained decoding
 * guarantees shape; semantic checks (ids, old_text) stay in MutationValidator.
 */
public final class MutationJsonSchema {

    private MutationJsonSchema() {
    }

    /** The full response_format node: {"type":"json_schema","json_schema":{...}}. */
    public static ObjectNode responseFormat(ObjectMapper mapper) {
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("type", "json_schema");
        ObjectNode jsonSchema = wrapper.putObject("json_schema");
        jsonSchema.put("name", "mutation_batch");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", batchSchema(mapper));
        return wrapper;
    }

    static ObjectNode batchSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");

        ObjectNode version = props.putObject("schema_version");
        version.put("type", "integer");
        version.putArray("enum").add(1);

        ObjectNode explanation = props.putObject("explanation");
        explanation.put("type", "string");
        explanation.put("description", "One short sentence describing the batch.");

        ObjectNode mutations = props.putObject("mutations");
        mutations.put("type", "array");
        ObjectNode items = mutations.putObject("items");
        ArrayNode anyOf = items.putArray("anyOf");
        anyOf.add(modifySchema(mapper));
        anyOf.add(insertSchema(mapper));
        anyOf.add(deleteSchema(mapper));

        required(schema, "schema_version", "explanation", "mutations");
        return schema;
    }

    private static ObjectNode modifySchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        ObjectNode props = (ObjectNode) schema.get("properties");
        enumString(props, "op", "modify");
        string(props, "target_id", "A target_id copied EXACTLY from the provided block list.");
        string(props, "old_text", "Exact substring of the block's current text to replace. Copy it verbatim.");
        ObjectNode occurrence = props.putObject("occurrence");
        occurrence.put("type", "integer");
        occurrence.put("description", "0-based index if old_text appears multiple times in the block. Usually 0.");
        string(props, "new_text", "Replacement text. Must differ from old_text.");
        required(schema, "op", "target_id", "old_text", "occurrence", "new_text");
        return schema;
    }

    private static ObjectNode insertSchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        ObjectNode props = (ObjectNode) schema.get("properties");
        enumString(props, "op", "insert");
        string(props, "anchor_id", "target_id of an existing body paragraph (type=paragraph) to insert next to.");
        ObjectNode position = props.putObject("position");
        position.put("type", "string");
        position.putArray("enum").add("before").add("after");
        enumString(props, "node_type", "paragraph");
        string(props, "text", "Full text of the new paragraph.");
        ObjectNode style = props.putObject("style");
        style.putArray("type").add("string").add("null");
        style.put("description", "Optional paragraph style name; null copies the anchor's style.");
        required(schema, "op", "anchor_id", "position", "node_type", "text", "style");
        return schema;
    }

    private static ObjectNode deleteSchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        ObjectNode props = (ObjectNode) schema.get("properties");
        enumString(props, "op", "delete");
        string(props, "target_id", "target_id of the body paragraph (type=paragraph) to remove entirely.");
        required(schema, "op", "target_id");
        return schema;
    }

    private static ObjectNode objectSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }

    private static void enumString(ObjectNode props, String name, String onlyValue) {
        ObjectNode node = props.putObject(name);
        node.put("type", "string");
        node.putArray("enum").add(onlyValue);
    }

    private static void string(ObjectNode props, String name, String description) {
        ObjectNode node = props.putObject(name);
        node.put("type", "string");
        node.put("description", description);
    }

    private static void required(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) {
            required.add(name);
        }
    }
}
