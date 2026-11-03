package com.sumirelabs.yomu;

/** Immutable parsing options, reusable across threads. Defaults preserve Double decimal values. */
public final class Json5ReadOptions {
    public static final Json5ReadOptions DEFAULT = new Json5ReadOptions();
    private final boolean bigDecimals;
    private final int maxDepth;
    private final boolean strictJson;

    public Json5ReadOptions() { this(false, Yomu.DEFAULT_MAX_DEPTH, false); }

    public static Builder builder() { return new Builder(DEFAULT); }
    public Builder toBuilder() { return new Builder(this); }

    /** Mutable, non-thread-safe configuration. build returns an immutable snapshot. */
    public static final class Builder {
        private Json5ReadOptions value;
        private Builder(Json5ReadOptions value) { this.value = value; }
        public Builder bigDecimals(boolean enabled) { value = value.withBigDecimals(enabled); return this; }
        public Builder maxDepth(int depth) { value = value.withMaxDepth(depth); return this; }
        public Builder strictJson(boolean enabled) { value = value.withStrictJson(enabled); return this; }
        public Json5ReadOptions build() { return value; }
    }

    private Json5ReadOptions(boolean bigDecimals, int maxDepth, boolean strictJson) {
        if (maxDepth < 1 || maxDepth > 512) throw new IllegalArgumentException("maxDepth must be between 1 and 512");
        this.bigDecimals = bigDecimals;
        this.maxDepth = maxDepth;
        this.strictJson = strictJson;
    }

    /** Reads decimal/exponent literals directly as BigDecimal. Non-finite values and negative zero remain Double. */
    public Json5ReadOptions withBigDecimals(boolean value) { return new Json5ReadOptions(value, maxDepth, strictJson); }
    public Json5ReadOptions withMaxDepth(int value) { return new Json5ReadOptions(bigDecimals, value, strictJson); }
    /** Restricts the input grammar to JSON. Numeric representation options remain independent. */
    public Json5ReadOptions withStrictJson(boolean value) { return new Json5ReadOptions(bigDecimals, maxDepth, value); }
    public boolean isBigDecimals() { return bigDecimals; }
    public int getMaxDepth() { return maxDepth; }
    public boolean isStrictJson() { return strictJson; }
}
