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
        anyOf.add(formatSchema(mapper));

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
        string(props, "anchor_id",
                "For paragraph: a type=paragraph id. For table_row/table_column: a type=table_cell id "
                        + "in the template row/column to clone.");
        ObjectNode position = props.putObject("position");
        position.put("type", "string");
        position.putArray("enum").add("before").add("after");
        ObjectNode nodeType = props.putObject("node_type");
        nodeType.put("type", "string");
        nodeType.putArray("enum").add("paragraph").add("table_row").add("table_column");
        nodeType.put("description",
                "paragraph = body paragraph; table_row/table_column = clone a dataframe table row/column.");

        ObjectNode text = props.putObject("text");
        text.putArray("type").add("string").add("null");
        text.put("description", "Required for paragraph inserts. Null for table_row/table_column.");

        ObjectNode style = props.putObject("style");
        style.putArray("type").add("string").add("null");
        style.put("description", "Optional paragraph style name; null copies the anchor's style.");

        ObjectNode cells = props.putObject("cells");
        cells.putArray("type").add("array").add("null");
        cells.put("description",
                "For table_row: one string per column. For table_column: one string per row. "
                        + "Null clones the template texts. Null for paragraph inserts.");
        ObjectNode cellItems = cells.putObject("items");
        cellItems.put("type", "string");

        required(schema, "op", "anchor_id", "position", "node_type", "text", "style", "cells");
        return schema;
    }

    private static ObjectNode deleteSchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        ObjectNode props = (ObjectNode) schema.get("properties");
        enumString(props, "op", "delete");
        string(props, "target_id",
                "For paragraph: body paragraph id. For table_row/table_column: any cell id in that row/column.");
        ObjectNode nodeType = props.putObject("node_type");
        nodeType.put("type", "string");
        nodeType.putArray("enum").add("paragraph").add("table_row").add("table_column");
        nodeType.put("description",
                "paragraph removes one body paragraph; table_row/table_column remove a whole row/column.");
        string(props, "evidence_text",
                "Exact substring COPIED VERBATIM from the target block's (or, for table_row/table_column, "
                        + "any cell in that row/column's) current text, proving this block is genuinely the one "
                        + "the user meant. Never paraphrase or invent this — if you cannot quote real existing "
                        + "text that matches the user's request, do not delete this block.");
        required(schema, "op", "target_id", "node_type", "evidence_text");
        return schema;
    }

    private static ObjectNode formatSchema(ObjectMapper mapper) {
        ObjectNode schema = objectSchema(mapper);
        ObjectNode props = (ObjectNode) schema.get("properties");
        enumString(props, "op", "format");
        string(props, "target_id", "A target_id copied EXACTLY from the provided block list.");

        ObjectNode text = props.putObject("text");
        text.putArray("type").add("string").add("null");
        text.put("description",
                "Exact substring COPIED VERBATIM from the block's current text to format. "
                        + "Null formats the WHOLE block.");

        ObjectNode occurrence = props.putObject("occurrence");
        occurrence.putArray("type").add("integer").add("null");
        occurrence.put("description", "0-based index if text appears multiple times. Usually 0 or null.");

        ObjectNode bold = props.putObject("bold");
        bold.putArray("type").add("boolean").add("null");
        bold.put("description", "true = bold, false = remove bold, null = leave unchanged.");

        ObjectNode italic = props.putObject("italic");
        italic.putArray("type").add("boolean").add("null");
        italic.put("description", "true = italic, false = remove italic, null = leave unchanged.");

        ObjectNode underline = props.putObject("underline");
        underline.putArray("type").add("boolean").add("null");
        underline.put("description", "true = underline, false = remove underline, null = leave unchanged.");

        ObjectNode fontSize = props.putObject("font_size");
        fontSize.putArray("type").add("integer").add("null");
        fontSize.put("description", "Font size in POINTS (e.g. 14). Null = leave unchanged.");

        ObjectNode align = props.putObject("align");
        align.putArray("type").add("string").add("null");
        align.put("description",
                "Paragraph alignment: left|center|right|justify — applies to the WHOLE paragraph "
                        + "regardless of text/occurrence. Null = leave unchanged.");

        required(schema, "op", "target_id", "text", "occurrence", "bold", "italic", "underline",
                "font_size", "align");
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
