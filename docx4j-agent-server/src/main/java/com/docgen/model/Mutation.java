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
        @JsonSubTypes.Type(value = ModifyMutation.class, name = "modify")
})
public sealed interface Mutation permits ModifyMutation {

    String op();
}
