package com.sumirelabs.yomu.mapper;

/** A type, construction or field-access failure with the JSON value's path. */
public final class MappingException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String path;

    MappingException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    MappingException(String path, String message, Throwable cause) {
        super(path + ": " + message, cause);
        this.path = path;
    }

    public String getPath() { return path; }
}
