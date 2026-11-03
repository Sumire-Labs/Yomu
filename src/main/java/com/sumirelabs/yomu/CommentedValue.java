package com.sumirelabs.yomu;

import java.util.Objects;

/** An output value with a leading comment, useful for generating mod configuration files. */
public final class CommentedValue {
    private final String comment;
    private final Object value;

    public CommentedValue(String comment, Object value) {
        this.comment = Objects.requireNonNull(comment, "comment");
        this.value = value;
    }

    public String getComment() { return comment; }
    public Object getValue() { return value; }
}
