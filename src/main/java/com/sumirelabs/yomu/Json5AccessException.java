package com.sumirelabs.yomu;

/** A missing value, incompatible value kind or lossy numeric access, with a value-tree path. */
public final class Json5AccessException extends IllegalArgumentException {
    private static final long serialVersionUID=1L;
    private final String path;
    Json5AccessException(String path,String message) { super(path+": "+message);this.path=path; }
    Json5AccessException(String path,String message,Throwable cause) { super(path+": "+message,cause);this.path=path; }
    public String getPath() { return path; }
}
