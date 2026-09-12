package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** One LLM-proposed document operation. Discriminated by the "op" field. */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "op",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ModifyMutation.class, name = "modify"),
        @JsonSubTypes.Type(value = InsertMutation.class, name = "insert"),
        @JsonSubTypes.Type(value = DeleteMutation.class, name = "delete"),
        @JsonSubTypes.Type(value = FormatMutation.class, name = "format")
})
public sealed interface Mutation permits ModifyMutation, InsertMutation, DeleteMutation, FormatMutation {

    String op();
}
