package com.docgen.agent;

import java.util.List;
import java.util.Map;

public final class ToolDefinitions {

    public static final List<Map<String, Object>> TOOLS = List.of(
            tool("read_document",
                    "Read the full document structure. Paragraphs are [index] (style) text. Tables are [TABLE t_index].",
                    Map.of("type", "object", "properties", Map.of())),
            tool("append_paragraph",
                    "Append a new paragraph at the end of the document body.",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "text", Map.of("type", "string"),
                                    "style", Map.of("type", "string")),
                            "required", List.of("text"))),
            tool("insert_paragraph",
                    "Insert a new paragraph before the paragraph at index (0-based, outside tables).",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "index", Map.of("type", "integer"),
                                    "text", Map.of("type", "string"),
                                    "style", Map.of("type", "string")),
                            "required", List.of("index", "text"))),
            tool("set_paragraph",
                    "Replace the full text (and optionally style) of the paragraph at index.",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "index", Map.of("type", "integer"),
                                    "text", Map.of("type", "string"),
                                    "style", Map.of("type", "string")),
                            "required", List.of("index", "text"))),
            tool("delete_paragraph",
                    "Delete the paragraph at index. Later paragraphs shift up by one.",
                    Map.of("type", "object",
                            "properties", Map.of("index", Map.of("type", "integer")),
                            "required", List.of("index"))),
            tool("replace_text",
                    "Find-and-replace a string across the entire document including table cells.",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "find", Map.of("type", "string"),
                                    "replace", Map.of("type", "string")),
                            "required", List.of("find", "replace"))),
            tool("read_table",
                    "Return full contents of a table with row and column indices.",
                    Map.of("type", "object",
                            "properties", Map.of("table_index", Map.of("type", "integer")),
                            "required", List.of("table_index"))),
            tool("set_table_cell",
                    "Replace the text of one table cell (all indices 0-based).",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "table_index", Map.of("type", "integer"),
                                    "row", Map.of("type", "integer"),
                                    "col", Map.of("type", "integer"),
                                    "text", Map.of("type", "string")),
                            "required", List.of("table_index", "row", "col", "text")))
    );

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters));
    }

    private ToolDefinitions() {}
}
