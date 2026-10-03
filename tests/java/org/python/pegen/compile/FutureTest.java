package org.python.pegen.compile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.ImportFrom;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Pass;
import org.python.pegen.ast.alias;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.stmt;

/**
 * Future and the Compile driver, for what compare_ast.py can't see: the
 * features and location future.c records, and SyntaxError.text read from a
 * real file. Expected values are CPython 3.15's.
 */
public class FutureTest {

    /** "from __future__ import NAMES" on line lineno, laid out as CPython's AST would have it. */
    private static ImportFrom future(int lineno, String... names) {
        return importFrom("__future__", 0, lineno, names);
    }

    private static ImportFrom importFrom(String module, int level, int lineno, String... names) {
        int col = ("from " + module + " import ").length() + level;
        List<alias> aliases = new java.util.ArrayList<>();
        for (String name : names) {
            aliases.add(new alias(name, null, lineno, col, lineno, col + name.length()));
            col += name.length() + 2;
        }
        return new ImportFrom(module, aliases, level, 0, lineno, 0, lineno, col - 2);
    }

    private static Expr docstring(int lineno) {
        return new Expr(new Constant("doc", null, lineno, 0, lineno, 5), lineno, 0, lineno, 5);
    }

    private static Module module(stmt... body) {
        return new Module(Arrays.asList(body), Collections.emptyList());
    }

    private static Future.FutureFeatures features(mod m) {
        Future.FutureFeatures ff = new Future.FutureFeatures();
        Future._PyFuture_FromAST(m, "<unknown>", ff);
        return ff;
    }

    @Test
    public void featuresAndLocationOfLastFutureStatement() {
        Future.FutureFeatures ff = features(module(docstring(1),
                future(2, "annotations", "generator_stop"),
                future(3, "barry_as_FLUFL"),
                new Pass(4, 0, 4, 4),
                future(5, "braces")));
        assertEquals(Compile.CO_FUTURE_ANNOTATIONS | Compile.CO_FUTURE_BARRY_AS_BDFL,
                ff.ff_features);
        assertEquals("(3, 3, 0, 37)", ff.ff_location.toString());
    }

    @Test
    public void noFutureStatements() {
        Future.FutureFeatures ff = features(module(importFrom("__future__", 1, 1, "braces"),
                future(2, "annotations")));
        assertEquals(0, ff.ff_features);
        assertEquals(SourceLocation.NO_LOCATION, ff.ff_location);
        // Only Module and Interactive are searched.
        ff = features(new Expression(new Constant("x", null, 1, 0, 1, 3)));
        assertEquals(SourceLocation.NO_LOCATION, ff.ff_location);
    }

    @Test
    public void compilerSetupMergesFlags() {
        Compile.PyCompilerFlags flags = new Compile.PyCompilerFlags(Compile.CO_FUTURE_DIVISION);
        Compile c = Compile.new_compiler(module(future(1, "annotations")), "<unknown>", flags, -1);
        int merged = Compile.CO_FUTURE_DIVISION | Compile.CO_FUTURE_ANNOTATIONS;
        assertEquals(merged, c.c_future.ff_features);
        assertEquals(merged, flags.cf_flags);
        assertEquals(0, c.c_optimize);
    }

    @Test
    public void errorWithoutFileHasNoText() {
        try {
            features(module(future(1, "nested_scopes", "spam")));
            fail("expected a SyntaxError");
        } catch (PythonSyntaxError e) {
            assertEquals("future feature spam is not defined", e.msg);
            assertEquals(1, e.lineno);
            assertEquals(39, e.offset);
            assertEquals(1, e.end_lineno);
            assertEquals(43, e.end_offset);
            assertNull(e.text);
        }
    }

    /** CPython reads the line from the file, with universal newlines. */
    @Test
    public void errorTextFromFile() throws IOException {
        for (String nl : new String[] {"\n", "\r\n", "\r"}) {
            File f = File.createTempFile("future", ".py");
            try {
                Files.write(f.toPath(), ("\"\"\"doc\"\"\"" + nl + "from __future__ import braces" + nl)
                        .getBytes(StandardCharsets.UTF_8));
                try {
                    Compile._PyCompile_AstPreprocess(module(docstring(1), future(2, "braces")),
                            f.getPath(), new Compile.PyCompilerFlags(Compile.PyCF_ONLY_AST), -1,
                            true);
                    fail("expected a SyntaxError");
                } catch (PythonSyntaxError e) {
                    assertEquals("not a chance", e.msg);
                    assertEquals(2, e.lineno);
                    assertEquals(24, e.offset);
                    assertEquals(30, e.end_offset);
                    assertEquals("from __future__ import braces\n", e.text);
                }
            } finally {
                f.delete();
            }
        }
    }
}
