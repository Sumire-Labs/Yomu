package com.sumirelabs.yomu;

/** A JSON5 syntax or numeric representation error with a UTF-16 offset and one-based line and column. */
public final class Json5Exception extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final int offset;
    private final int line;
    private final int column;
    private final String reason;

    Json5Exception(String message, String source, int offset) {
        this(message, offset, position(source, offset));
    }

    private Json5Exception(String message, int offset, int[] location) {
        this(message,offset,location[0],location[1]);
    }

    Json5Exception(String message,int offset,int line,int column) {
        super(message + " at line " + line + ", column " + column + " (offset " + offset + ")");
        this.offset = offset;
        this.line = line;
        this.column = column;
        this.reason = message;
    }
    String reason() { return reason; }

    private static int[] position(String source, int offset) {
        int row = 1;
        int col = 1;
        for (int i = 0; i < offset; i++) {
            char c = source.charAt(i);
            if (c == '\r') {
                row++;
                col = 1;
                if (i + 1 < offset && source.charAt(i + 1) == '\n') i++;
            } else if (c == '\n' || c == '\u2028' || c == '\u2029') {
                row++;
                col = 1;
            } else col++;
        }
        return new int[] {row, col};
    }

    public int getOffset() { return offset; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
}
