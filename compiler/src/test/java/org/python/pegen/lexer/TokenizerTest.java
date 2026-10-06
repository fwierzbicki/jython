package org.python.pegen.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.python.pegen.GeneratedParser;
import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.Assign;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.Symtable;

/**
 * What the comparisons with CPython (compare_tokens.py, compare_ast.py) don't
 * reach: the encoding found for a file's program text
 * (_PyTokenizer_FindEncodingFilename), SyntaxError.text read through it, and
 * PyCF_IGNORE_COOKIE. Expected values are CPython 3.15's.
 */
public class TokenizerTest {

    private static String findEncoding(String source, String charset) {
        return Tokenizer.findEncoding(source.getBytes(Charset.forName(charset)));
    }

    @Test
    public void findEncoding() {
        assertNull(findEncoding("x = 1\n", "UTF-8"));
        assertEquals("utf-8", findEncoding("\ufeffx = 1\n", "UTF-8"));
        assertEquals("iso-8859-1", findEncoding("# -*- coding: latin-1 -*-\nx = 1\n", "UTF-8"));
        assertEquals("cp1252", findEncoding("#!/usr/bin/python\n# coding=cp1252\n", "UTF-8"));
        // Only a comment line can come before a cookie.
        assertNull(findEncoding("x = 1\n# coding: latin-1\n", "UTF-8"));
        // Nor is a third line looked at.
        assertNull(findEncoding("#\n#\n# coding: latin-1\n", "UTF-8"));
        // A cookie on an unterminated last line counts.
        assertEquals("iso-8859-1", findEncoding("# coding: latin-1", "UTF-8"));
        // An encoding that can't be opened gives none.
        assertNull(findEncoding("# coding: no-such-encoding\n", "UTF-8"));
    }

    private static Object parse(byte[] source, String filename, int flags) {
        Parser p = Parser.fromString(source, Parser.FILE_INPUT, filename,
                new Compile.PyCompilerFlags(flags), null);
        Object result = p.runParser(new GeneratedParser(p));
        if (result == null) {
            throw p.getError();
        }
        return result;
    }

    /** The error's text is the file's line, decoded as its cookie says. */
    @Test
    public void errorTextUsesCookie() throws IOException {
        byte[] source = ("# -*- coding: latin-1 -*-\nglobal x\nx = 1\ndef f():\n"
                + "    x = 2\n    global x  # \u00e9\n").getBytes(StandardCharsets.ISO_8859_1);
        File f = File.createTempFile("cookie", ".py");
        try {
            Files.write(f.toPath(), source);
            Object m = parse(source, f.getPath(), 0);
            try {
                Symtable._Py_SymtableStringObjectFlags((mod) m, f.getPath(),
                        new Compile.PyCompilerFlags());
                fail("expected a SyntaxError");
            } catch (PythonSyntaxError e) {
                assertEquals("name 'x' is assigned to before global declaration", e.msg);
                assertEquals(6, e.lineno);
                assertEquals(5, e.offset);
                assertEquals("    global x  # \u00e9\n", e.text);
            }
        } finally {
            f.delete();
        }
    }

    private static Object firstValue(Object module) {
        Assign assign = (Assign) ((Module) module).body.get(0);
        return ((Constant) assign.value).value;
    }

    /** Source bytes follow their cookie; PyCF_IGNORE_COOKIE reads them as UTF-8. */
    @Test
    public void ignoreCookie() {
        byte[] source = "# coding: latin-1\ns = '\u00e9'\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("\u00c3\u00a9", firstValue(parse(source, "<unknown>", 0)));
        assertEquals("\u00e9",
                firstValue(parse(source, "<unknown>", Compile.PyCF_IGNORE_COOKIE)));
    }
}
