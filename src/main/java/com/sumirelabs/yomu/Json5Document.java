package com.sumirelabs.yomu;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.Objects;

/** Opt-in source-preserving document. Mutable values, not thread-safe; retains the complete source. */
public final class Json5Document {
    private final DocumentSyntax syntax;
    private final Json5ReadOptions options;
    private final Object value;

    private Json5Document(DocumentSyntax syntax,Json5ReadOptions options,Object value) {
        this.syntax=syntax;this.options=options;this.value=value;
    }
    public static Json5Document read(String source) {return read(source,Json5ReadOptions.DEFAULT);}
    public static Json5Document read(String source,Json5ReadOptions options) {
        Objects.requireNonNull(source,"source");Objects.requireNonNull(options,"options");
        Object original=Yomu.parse(source,options);
        return new Json5Document(new DocumentSyntax(source,original,options),options,original);
    }
    public static Json5Document read(Reader input) throws IOException {return read(input,Json5ReadOptions.DEFAULT);}
    /** Borrows the reader, but materializes all source text and values. */
    public static Json5Document read(Reader input,Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(input,"input");Objects.requireNonNull(options,"options");
        StringBuilder text=new StringBuilder();char[] buffer=new char[8192];int count;
        while((count=input.read(buffer))!=-1) {
            if(count==0){int c=input.read();if(c<0)break;text.append((char)c);}
            else text.append(buffer,0,count);
        }
        return read(text.toString(),options);
    }
    public static Json5Document read(InputStream input) throws IOException {return read(input,Json5ReadOptions.DEFAULT);}
    /** Borrows and strictly decodes UTF-8 input. */
    public static Json5Document read(InputStream input,Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(input,"input");
        return read(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),options);
    }
    public static Json5Document read(Path input) throws IOException {return read(input,Json5ReadOptions.DEFAULT);}
    public static Json5Document read(Path input,Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(options,"options");
        try(InputStream stream=Files.newInputStream(input)){return read(stream,options);}
    }
    /** Live Map/List values. Mutations are applied to the original source when rendering. */
    public Object getValue(){return value;}
    public Json5Object getObject(){return Json5Values.object(value,"$");}
    public Json5Array getArray(){return Json5Values.array(value,"$");}
    public Json5ReadOptions getReadOptions(){return options;}
    public String getSource(){return syntax.source;}
    /** Shares the source snapshot, replacing the working value. Does not mutate this document. */
    public Json5Document withValue(Object value){return new Json5Document(syntax,options,value);}
    public String stringify(){return stringify(new Json5Writer().withStrictJson(options.isStrictJson()));}
    /** JSON5 retains source comments. Strict JSON normalizes output and omits comments. */
    public String stringify(Json5Writer writer) {
        Objects.requireNonNull(writer,"writer");
        String canonical=writer.stringify(value); // Validate types, cycles, depth and non-finite values first.
        String result=writer.isStrictJson()?canonical:syntax.render(value,writer);
        Object checked=Yomu.parse(result,options.withStrictJson(writer.isStrictJson()));
        if(!DocumentSyntax.equal(checked,value)&&!DocumentSyntax.equal(checked,Yomu.parse(canonical,options.withStrictJson(writer.isStrictJson()))))
            throw new IllegalStateException("Preserved document changed the serialized value");
        return result;
    }
    public void writeTo(Path output) throws IOException {writeTo(output,new Json5Writer().withStrictJson(options.isStrictJson()));}
    public void writeTo(Path output,Json5Writer writer) throws IOException {
        Objects.requireNonNull(output,"output");String text=stringify(writer);utf8(text);Files.write(output,text.getBytes(StandardCharsets.UTF_8));
    }
    public void writeAtomicallyTo(Path output) throws IOException {writeAtomicallyTo(output,new Json5Writer().withStrictJson(options.isStrictJson()));}
    public void writeAtomicallyTo(Path output,Json5Writer writer) throws IOException {
        Objects.requireNonNull(output,"output");String text=stringify(writer);utf8(text);AtomicFiles.writeUtf8(output,text);
    }
    private static void utf8(String text) throws IOException {
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(i+1==text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IOException("Preserved source contains an unpaired UTF-16 surrogate");
            } else if(Character.isLowSurrogate(c))throw new IOException("Preserved source contains an unpaired UTF-16 surrogate");
        }
    }
}
