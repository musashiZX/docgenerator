package com.docgen.mutation;

/** old_text no longer matches the live block content — optimistic lock failure. */
public class StaleTargetException extends RuntimeException {

    public StaleTargetException(String message) {
        super(message);
    }
}
