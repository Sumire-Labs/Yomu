package com.sumirelabs.yomu.mapper;

/** Built-in, locale-independent field naming strategies. Map keys and aliases are unchanged. */
public enum NamingPolicy implements NamingStrategy {
    IDENTITY, SNAKE_CASE, KEBAB_CASE, LOWER_CAMEL_CASE, UPPER_CAMEL_CASE;

    public String translate(String name) {
        if (this == IDENTITY) return name;
        StringBuilder out = new StringBuilder(name.length() + 8);
        boolean boundary = false;
        int previous = -1;
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '_' || cp == '-' || Character.isWhitespace(cp)) {
                boundary = out.length() > 0;
                previous = -1;
                continue;
            }
            int next = offset < name.length() ? name.codePointAt(offset) : -1;
            boolean word = boundary || previous != -1 && Character.isUpperCase(cp)
                    && (Character.isLowerCase(previous) || Character.isDigit(previous)
                    || Character.isUpperCase(previous) && next != -1 && Character.isLowerCase(next));
            if (this == SNAKE_CASE || this == KEBAB_CASE) {
                if (word && out.length() > 0) out.append(this == SNAKE_CASE ? '_' : '-');
                out.appendCodePoint(Character.toLowerCase(cp));
            } else {
                boolean upper = out.length() == 0 ? this == UPPER_CAMEL_CASE : word;
                out.appendCodePoint(upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
            }
            previous = cp;
            boundary = false;
        }
        return out.toString();
    }
}
