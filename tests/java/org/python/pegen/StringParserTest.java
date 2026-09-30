package org.python.pegen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.python.pegen.ast.Bytes;

/**
 * String literal decoding (StringParser, the port of string_parser.c). Each
 * literal is given as its source text, a STRING token at line 1, column 0.
 * Expected values, messages and positions are CPython 3.15's (compile() of the
 * literal), including cases from test_string_literals.
 */
public class StringParserTest {

    private static final String INVALID_ESCAPE =
            "\"\\%1$s\" is an invalid escape sequence. Such sequences will not work in the "
            + "future. Did you mean \"\\\\%1$s\"? A raw string is also an option.";
    private static final String INVALID_OCTAL =
            "\"\\%1$s\" is an invalid octal escape sequence. Such sequences will not work in "
            + "the future. Did you mean \"\\\\%1$s\"? A raw string is also an option.";
    private static final String INVALID_ESCAPE_ERROR =
            "\"\\%1$s\" is an invalid escape sequence. Did you mean \"\\\\%1$s\"? "
            + "A raw string is also an option.";
    private static final String INVALID_OCTAL_ERROR =
            "\"\\%1$s\" is an invalid octal escape sequence. Did you mean \"\\\\%1$s\"? "
            + "A raw string is also an option.";

    private Parser p;

    private Object parse(String source, boolean warningsAsErrors) {
        p = new Parser(() -> null, Parser.FILE_INPUT);
        p.warnings_as_errors = warningsAsErrors;
        Token t = new Token(TokenTypes.STRING, source, 1, 0, 1, source.length());
        return StringParser._PyPegen_parse_string(p, t);
    }

    /** Asserts the literal decodes to the str expected, with no warnings. */
    private void assertStr(String expected, String source) {
        assertEquals(source, expected, parse(source, false));
        assertEquals(source, 0, p.warnings.size());
    }

    /** Asserts the literal decodes to the bytes expected (given as ISO-8859-1), with no warnings. */
    private void assertBytes(String expected, String source) {
        Object v = parse(source, false);
        assertTrue(source, v instanceof Bytes);
        assertArrayEquals(source, expected.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                ((Bytes) v).toArray());
        assertEquals(source, 0, p.warnings.size());
    }

    /**
     * Asserts the literal fails to decode, and that _Pypegen_raise_decode_error
     * reports the SyntaxError message expected.
     */
    private void assertDecodeError(String expected, String source) {
        assertNull(source, parse(source, false));
        assertTrue(source, p.errorOccurred());
        ActionHelpers._Pypegen_raise_decode_error(p);
        assertTrue(source, p.getError().startsWith("SyntaxError: "));
        assertEquals(source, expected, p.errorMessage());
    }

    /**
     * Asserts the literal decodes to value with one SyntaxWarning, message, on
     * lineno; and that as an error it is a SyntaxError, errorMessage at
     * lineno:offset (1-based).
     */
    private void assertWarns(Object value, String message, String errorMessage, int lineno,
            int offset, String source) {
        Object v = parse(source, false);
        if (value instanceof byte[]) {
            assertArrayEquals(source, (byte[]) value, ((Bytes) v).toArray());
        } else {
            assertEquals(source, value, v);
        }
        List<Parser.ParserWarning> warnings = p.warnings;
        assertEquals(source, 1, warnings.size());
        assertEquals(source, "SyntaxWarning", warnings.get(0).category);
        assertEquals(source, message, warnings.get(0).message);
        assertEquals(source, lineno, warnings.get(0).lineno);

        assertNull(source, parse(source, true));
        assertEquals(source, "SyntaxError: " + errorMessage + " at " + lineno + ":" + offset,
                p.getError());
        assertTrue(source, p.error_indicator);
    }

    private void assertInvalidEscape(Object value, String escape, int lineno, int offset,
            String source) {
        assertWarns(value, String.format(INVALID_ESCAPE, escape),
                String.format(INVALID_ESCAPE_ERROR, escape), lineno, offset, source);
    }

    private void assertInvalidOctal(Object value, String escape, int lineno, int offset,
            String source) {
        assertWarns(value, String.format(INVALID_OCTAL, escape),
                String.format(INVALID_OCTAL_ERROR, escape), lineno, offset, source);
    }

    @Test
    public void quotes() {
        assertStr("abc", "'abc'");
        assertStr("abc", "\"abc\"");
        assertStr("", "''");
        assertStr("", "\"\"");
        assertStr("", "''''''");
        assertStr("", "\"\"\"\"\"\"");
        assertStr("a'b", "'''a'b'''");
        assertStr("a\"\"b", "\"\"\"a\"\"b\"\"\"");
        assertStr("'", "\"\\'\"");
    }

    @Test
    public void prefixes() {
        assertStr("abc", "u'abc'");
        assertStr("\n", "U'\\n'");
        assertStr("\\n\\q\\x", "r'\\n\\q\\x'");
        assertStr("\\N{x}", "R'\\N{x}'");
        assertBytes("abc", "b'abc'");
        assertBytes("a", "Br'a'");
        assertBytes("a", "bR'a'");
        assertBytes("a", "RB'a'");
        assertBytes("\\x", "br'\\x'");
        assertBytes("\\q", "rb'\\q'");
    }

    @Test
    public void strEscapes() {
        assertStr("\n\t\r\\'\"\u0007\b\f\u000b", "'\\n\\t\\r\\\\\\'\\\"\\a\\b\\f\\v'");
        assertStr("x", "'\\\nx'");
        assertStr("\u0000\u0001\n\u0053\u00ff", "'\\0\\01\\012\\123\\377'");
        assertStr("S4", "'\\1234'");
        assertStr("A\u007f\u00ff", "'\\x41\\x7f\\xff'");
        assertStr("A\u00e9\uffff", "'\\u0041\\u00e9\\uffff'");
        assertStr("\ud83d\ude00", "'\\U0001F600'");
        assertStr("\udbff\udfff", "'\\U0010ffff'");
        assertStr("\ud800", "'\\ud800'");
        assertStr("\\\\x", "'\\\\\\\\x'");
        assertStr("\\q", "'\\\\q'");
    }

    @Test
    public void nonAscii() {
        assertStr("\u00e9", "'\u00e9'");
        assertStr("\\\u00e9", "'\\\u00e9'");
        assertStr("a\u00e9A", "'a\u00e9\\x41'");
        assertStr("\ud83d\ude00\t", "'\ud83d\ude00\\t'");
    }

    @Test
    public void strDecodeErrors() {
        String codec = "(unicode error) 'unicodeescape' codec can't decode bytes in position ";
        assertDecodeError(codec + "0-2: truncated \\xXX escape", "'\\x4'");
        assertDecodeError(codec + "0-1: truncated \\xXX escape", "'\\x'");
        assertDecodeError(codec + "0-1: truncated \\xXX escape", "'\\xg0'");
        assertDecodeError(codec + "0-4: truncated \\uXXXX escape", "'\\u004'");
        assertDecodeError(codec + "0-9: illegal Unicode character", "'\\U00110000'");
        assertDecodeError(codec + "0-9: illegal Unicode character", "'\\Uffffffff'");
        // Positions are in the buffer C decodes, where a non-ASCII character
        // has become a 10-byte \U escape.
        assertDecodeError(codec + "10-12: truncated \\xXX escape", "'\u00e9\\x4'");
    }

    @Test
    public void namedEscapes() {
        assertStr("a", "'\\N{LATIN SMALL LETTER A}'");
        assertStr("\u00e9", "'\\N{latin small letter e with acute}'");
        assertStr("\u2014", "'\\N{EM DASH}'");
        assertStr("\ud83d\ude00", "'\\N{GRINNING FACE}'");
        assertStr("\ud80c\udc00", "'\\N{EGYPTIAN HIEROGLYPH A001}'");
        // Derived names
        assertStr("\uac01", "'\\N{HANGUL SYLLABLE GAG}'");
        assertStr("\uac01", "'\\N{hangul syllable gag}'");
        assertStr("\u4e00", "'\\N{CJK UNIFIED IDEOGRAPH-4E00}'");
        assertStr("\ud840\udc00", "'\\N{CJK UNIFIED IDEOGRAPH-20000}'");
        assertStr("\uf900", "'\\N{CJK COMPATIBILITY IDEOGRAPH-F900}'");
        assertStr("\ud81c\udc00", "'\\N{TANGUT IDEOGRAPH-17000}'");
        assertStr("\ud82c\udd70", "'\\N{NUSHU CHARACTER-1B170}'");

        String codec = "(unicode error) 'unicodeescape' codec can't decode bytes in position ";
        assertDecodeError(codec + "0-23: unknown Unicode character name",
                "'\\N{HANGUL SYLLABLE GAGX}'");
        assertDecodeError(codec + "0-30: unknown Unicode character name",
                "'\\N{CJK UNIFIED IDEOGRAPH-04E00}'");
        assertDecodeError(codec + "0-29: unknown Unicode character name",
                "'\\N{CJK UNIFIED IDEOGRAPH-A000}'");
        assertDecodeError(codec + "0-15: unknown Unicode character name", "'\\N{NO SUCH NAME}'");
        assertDecodeError(codec + "0-2: malformed \\N character escape", "'\\N{}'");
        assertDecodeError(codec + "0-1: malformed \\N character escape", "'\\N'");
        assertDecodeError(codec + "0-1: malformed \\N character escape", "'\\Nx'");
        assertDecodeError(codec + "0-5: malformed \\N character escape", "'\\N{abc'");
    }

    @Test
    public void strInvalidEscapes() {
        assertInvalidEscape("\\q", "q", 1, 2, "'\\q'");
        assertInvalidEscape("\\d", "d", 1, 2, "'\\d'");
        assertInvalidEscape("\\%", "%", 1, 2, "'\\%'");
        assertInvalidEscape("\\8", "8", 1, 2, "'\\8'");
        // Only the first invalid escape is reported.
        assertInvalidEscape("a\\qb\\wc", "q", 1, 3, "'a\\qb\\wc'");
        assertInvalidOctal("\u0100", "400", 1, 2, "'\\400'");
        assertInvalidOctal("\u01ff", "777", 1, 2, "'\\777'");
        assertInvalidEscape("x\\q", "q", 2, 1, "'x\\\n\\q'");
        assertInvalidEscape("line1\n\\q", "q", 2, 1, "\"\"\"line1\n\\q\"\"\"");
        assertInvalidEscape("line1\nline2 \\d", "d", 2, 7, "\"\"\"line1\nline2 \\d\"\"\"");
    }

    @Test
    public void bytesEscapes() {
        assertBytes("\n\u0000\u00ff", "b'\\n\\x00\\xff'");
        assertBytes("\n\t\r\\'\"\u0007\b\f\u000b", "b'\\n\\t\\r\\\\\\'\\\"\\a\\b\\f\\v'");
        assertBytes("x", "b'\\\nx'");
        assertBytes("\u0000\u0001\n\u0053\u00ff", "b'\\0\\01\\012\\123\\377'");
    }

    @Test
    public void bytesErrors() {
        assertDecodeError("(value error) invalid \\x escape at position 0", "b'\\x4'");
        assertDecodeError("(value error) invalid \\x escape at position 0", "b'\\x'");
        assertDecodeError("(value error) invalid \\x escape at position 0", "b'\\xzz'");
        assertDecodeError("(value error) invalid \\x escape at position 2", "b'ab\\x'");

        // Not a decode error: raised directly, and _Pypegen_raise_decode_error leaves it.
        assertDecodeError("bytes can only contain ASCII literal characters", "b'\u00e9'");
    }

    @Test
    public void bytesInvalidEscapes() {
        assertInvalidEscape(new byte[] {'\\', 'q'}, "q", 1, 1, "b'\\q'");
        assertInvalidEscape("\\N{EM DASH}".getBytes(), "N", 1, 1, "b'\\N{EM DASH}'");
        assertInvalidEscape("\\u0041".getBytes(), "u", 1, 1, "b'\\u0041'");
        assertInvalidOctal(new byte[] {0}, "400", 1, 1, "b'\\400'");
        assertInvalidOctal(new byte[] {'\n', 0, (byte) 0xff, (byte) 0xff}, "777", 1, 11,
                "b'\\n\\x00\\xff\\777'");
    }

    @Test
    public void noWarningsInSecondPass() {
        p = new Parser(() -> null, Parser.FILE_INPUT);
        p.call_invalid_rules = true;
        Token t = new Token(TokenTypes.STRING, "'\\q'", 1, 0, 1, 4);
        assertEquals("\\q", StringParser._PyPegen_parse_string(p, t));
        assertEquals(0, p.warnings.size());
    }

    @Test
    public void decodeString() {
        byte[] s = "a\\tb".getBytes();
        p = new Parser(() -> null, Parser.FILE_INPUT);
        assertEquals("a\tb", StringParser._PyPegen_decode_string(p, 0, s, 0, s.length, null));
        assertEquals("a\\tb", StringParser._PyPegen_decode_string(p, 1, s, 0, s.length, null));
        assertEquals("\\t", StringParser._PyPegen_decode_string(p, 1, s, 1, 2, null));
    }
}
