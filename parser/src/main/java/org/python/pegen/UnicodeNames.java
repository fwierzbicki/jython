package org.python.pegen;

import java.util.Locale;

/**
 * Character name lookup for {@code \N{...}} escapes: the part of CPython's
 * Modules/unicodedata.c that {@code _PyUnicode_Name_CAPI->getcode} runs.
 *
 * <p>Derived names (Hangul syllables and the "PREFIX-XXXX" ideographs) are
 * computed as in C, from the ranges in CPython 3.15's unicodename_db.h
 * (Unicode 17.0). Other names are looked up with the JDK's
 * {@link Character#codePointOf(String)}, restricted to the names Unicode
 * assigns.
 *
 * <p>Known gaps: the JDK's data is from an older Unicode version (13.0 in Java
 * 17), so names added since are missing. Name aliases are found only where the
 * JDK happens to use one as the name, as for most C0 control characters
 * ("NULL", "ESCAPE"); others ("LINE FEED", "BYTE ORDER MARK", ...) are missing.
 */
final class UnicodeNames {

    private UnicodeNames() {}

    // Hangul syllable constants (unicodedata.c).
    private static final int SBase = 0xAC00;
    private static final int LCount = 19;
    private static final int VCount = 21;
    private static final int TCount = 28;

    /** unicodedata.c hangul_syllables: the L, V and T jamo short names. */
    private static final String[][] hangul_syllables = {
        {"G", "A", ""},
        {"GG", "AE", "G"},
        {"N", "YA", "GG"},
        {"D", "YAE", "GS"},
        {"DD", "EO", "N"},
        {"R", "E", "NJ"},
        {"M", "YEO", "NH"},
        {"B", "YE", "D"},
        {"BB", "O", "L"},
        {"S", "WA", "LG"},
        {"SS", "WAE", "LM"},
        {"", "OE", "LB"},
        {"J", "YO", "LS"},
        {"JJ", "U", "LT"},
        {"C", "WEO", "LP"},
        {"K", "WE", "LH"},
        {"T", "WI", "M"},
        {"P", "YU", "B"},
        {"H", "EU", "BS"},
        {null, "YI", "S"},
        {null, "I", "SS"},
        {null, null, "NG"},
        {null, null, "J"},
        {null, null, "C"},
        {null, null, "K"},
        {null, null, "T"},
        {null, null, "P"},
        {null, null, "H"},
    };

    /** unicodename_db.h derived_name_ranges: first, last, prefix id. */
    private static final int[][] derived_name_ranges = {
        {0x3400, 0x4DBF, 1},
        {0x4E00, 0x9FFF, 1},
        {0xAC00, 0xD7A3, 0},
        {0xF900, 0xFA6D, 3},
        {0xFA70, 0xFAD9, 3},
        {0x13460, 0x143FA, 4},
        {0x17000, 0x187FF, 2},
        {0x18B00, 0x18CD5, 5},
        {0x18CFF, 0x18CFF, 5},
        {0x18D00, 0x18D1E, 2},
        {0x1B170, 0x1B2FB, 6},
        {0x20000, 0x2A6DF, 1},
        {0x2A700, 0x2B73F, 1},
        {0x2B740, 0x2B81D, 1},
        {0x2B820, 0x2CEAD, 1},
        {0x2CEB0, 0x2EBE0, 1},
        {0x2EBF0, 0x2EE5D, 1},
        {0x2F800, 0x2FA1D, 3},
        {0x30000, 0x3134A, 1},
        {0x31350, 0x323AF, 1},
        {0x323B0, 0x33479, 1},
    };

    /** unicodename_db.h derived_name_prefixes */
    private static final String[] derived_name_prefixes = {
        "HANGUL SYLLABLE ",
        "CJK UNIFIED IDEOGRAPH-",
        "TANGUT IDEOGRAPH-",
        "CJK COMPATIBILITY IDEOGRAPH-",
        "EGYPTIAN HIEROGLYPH-",
        "KHITAN SMALL SCRIPT CHARACTER-",
        "NUSHU CHARACTER-",
    };

    /**
     * _PyUnicode_GetNameCAPI: whether name lookup is available (C: a non-NULL
     * capsule). The JDK's name data is always present.
     */
    static boolean available() {
        return true;
    }

    /** find_prefix_id: the derived-name prefix of code, or -1. */
    private static int find_prefix_id(int code) {
        for (int[] range : derived_name_ranges) {
            if (code < range[0]) {
                return -1;
            }
            if (code <= range[1]) {
                return range[2];
            }
        }
        return -1;
    }

    /** PyOS_strnicmp(str + pos, s, s.length()) == 0 */
    private static boolean startsWithIgnoreCase(String str, int pos, String s) {
        return str.regionMatches(true, pos, s, 0, s.length());
    }

    /**
     * find_syllable: the longest jamo name in the given column that str
     * starts with at pos. Returns {len, index}; index is -1 if none matched.
     */
    private static int[] find_syllable(String str, int pos, int count, int column) {
        int len = -1;
        int found = -1;
        for (int i = 0; i < count; i++) {
            String s = hangul_syllables[i][column];
            int len1 = s.length();
            if (len1 <= len) {
                continue;
            }
            if (startsWithIgnoreCase(str, pos, s)) {
                len = len1;
                found = i;
            }
        }
        if (len == -1) {
            len = 0;
        }
        return new int[] {len, found};
    }

    /** parse_hex_code: 4 to 6 hex digits without a leading zero, or -1. */
    private static int parse_hex_code(String name, int start) {
        int namelen = name.length() - start;
        if (namelen < 4 || namelen > 6) {
            return -1;
        }
        if (name.charAt(start) == '0') {
            return -1;
        }
        int v = 0;
        for (int i = start; i < name.length(); i++) {
            v *= 16;
            char c = Character.toUpperCase(name.charAt(i));
            if (c >= '0' && c <= '9') {
                v += c - '0';
            } else if (c >= 'A' && c <= 'F') {
                v += c - 'A' + 10;
            } else {
                return -1;
            }
        }
        if (v > 0x10ffff) {
            return -1;
        }
        return v;
    }

    /**
     * capi_getcode(name, namelen, &code, 0) (via _getcode): the code point
     * with the given name, ignoring case, or -1 if there is none.
     */
    static int getcode(String name) {
        int i = 0;
        for (; i < derived_name_prefixes.length; i++) {
            if (startsWithIgnoreCase(name, 0, derived_name_prefixes[i])) {
                break;
            }
        }

        if (i == 0) {
            /* Hangul syllables. */
            int pos = 16;
            int[] l = find_syllable(name, pos, LCount, 0);
            pos += l[0];
            int[] v = find_syllable(name, pos, VCount, 1);
            pos += v[0];
            int[] t = find_syllable(name, pos, TCount, 2);
            pos += t[0];
            if (l[1] != -1 && v[1] != -1 && t[1] != -1 && pos == name.length()) {
                return SBase + (l[1] * VCount + v[1]) * TCount + t[1];
            }
            /* Otherwise, it's an illegal syllable name. */
            return -1;
        }

        if (i < derived_name_prefixes.length) {
            int v = parse_hex_code(name, derived_name_prefixes[i].length());
            if (find_prefix_id(v) != i) {
                return -1;
            }
            return v;
        }

        // C: _lookup_dawg_packed, here the JDK's table.
        return jdk_lookup(name);
    }

    /**
     * Character.codePointOf(name), but only for a name Unicode assigns. The JDK
     * also accepts, and CPython doesn't: a name with leading or trailing
     * whitespace; the name it makes up for a code point Unicode leaves unnamed
     * (block name and hex, e.g. "PRIVATE USE AREA E000"); and the Unicode 1.0
     * names it gives some control characters, which contain parentheses
     * ("LINE FEED (LF)").
     */
    private static int jdk_lookup(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Unicode names use only letters, digits, space and hyphen.
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == ' ' || c == '-')) {
                return -1;
            }
        }
        int code;
        try {
            code = Character.codePointOf(name);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        String real = Character.getName(code);
        if (real == null || !real.equalsIgnoreCase(name)) {
            return -1; // trimmed whitespace
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(code);
        if (block != null && real.equals(block.toString().replace('_', ' ') + " "
                + Integer.toHexString(code).toUpperCase(Locale.ROOT))) {
            return -1; // made-up name
        }
        return code;
    }
}
