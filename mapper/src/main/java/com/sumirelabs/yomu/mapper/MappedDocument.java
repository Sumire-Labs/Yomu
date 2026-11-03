package com.sumirelabs.yomu.mapper;

import java.io.IOException;
import java.nio.file.Path;

/** Opt-in editable document and typed value. Retains source data; not thread-safe. */
public final class MappedDocument<T> {
    private final DocumentMapping state;
    private T value;
    MappedDocument(DocumentMapping state,T value){this.state=state;this.value=value;}
    public T getValue(){return value;}
    /** Replaces an immutable root such as a record. */
    public void setValue(T value){this.value=value;}
    public String getSource(){return state.source.getSource();}
    public String stringify(){return state.document(value).stringify(state.mapper.writer);}
    public void writeTo(Path output) throws IOException {state.document(value).writeTo(output,state.mapper.writer);}
    public void writeAtomicallyTo(Path output) throws IOException {state.document(value).writeAtomicallyTo(output,state.mapper.writer);}
}
