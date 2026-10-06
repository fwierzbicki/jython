package org.python.pegen.compile;

import static org.python.pegen.compile.Compile.CO_FUTURE_ANNOTATIONS;
import static org.python.pegen.compile.Compile.CO_FUTURE_BARRY_AS_BDFL;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.ImportFrom;
import org.python.pegen.ast.Interactive;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.alias;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.stmt;

/**
 * A port of CPython's Python/future.c: finds the module's
 * {@code from __future__} imports and the features they turn on, and rejects
 * unknown features. C's names and order are kept.
 *
 * <p>Errors are thrown as PythonSyntaxError where C sets one and returns 0.
 */
public final class Future {

    private Future() {}

    /* Future feature support (Include/cpython/compile.h) */
    public static final String FUTURE_NESTED_SCOPES = "nested_scopes";
    public static final String FUTURE_GENERATORS = "generators";
    public static final String FUTURE_DIVISION = "division";
    public static final String FUTURE_ABSOLUTE_IMPORT = "absolute_import";
    public static final String FUTURE_WITH_STATEMENT = "with_statement";
    public static final String FUTURE_PRINT_FUNCTION = "print_function";
    public static final String FUTURE_UNICODE_LITERALS = "unicode_literals";
    public static final String FUTURE_BARRY_AS_BDFL = "barry_as_FLUFL";
    public static final String FUTURE_GENERATOR_STOP = "generator_stop";
    public static final String FUTURE_ANNOTATIONS = "annotations";

    /** C: _PyFutureFeatures (Include/internal/pycore_symtable.h). */
    public static final class FutureFeatures {
        /** flags set by future statements */
        public int ff_features;
        /** location of last future statement */
        public SourceLocation ff_location = SourceLocation.NO_LOCATION;
    }

    private static final String UNDEFINED_FUTURE_FEATURE = "future feature %.100s is not defined";

    private static void future_check_features(FutureFeatures ff, stmt s, String filename) {
        assert s.kind() == stmt.Kind.ImportFrom;

        List<alias> names = ((ImportFrom) s).names;
        for (int i = 0; i < names.size(); i++) {
            alias name = names.get(i);
            String feature = name.name;
            if (feature.equals(FUTURE_NESTED_SCOPES)) {
                continue;
            } else if (feature.equals(FUTURE_GENERATORS)) {
                continue;
            } else if (feature.equals(FUTURE_DIVISION)) {
                continue;
            } else if (feature.equals(FUTURE_ABSOLUTE_IMPORT)) {
                continue;
            } else if (feature.equals(FUTURE_WITH_STATEMENT)) {
                continue;
            } else if (feature.equals(FUTURE_PRINT_FUNCTION)) {
                continue;
            } else if (feature.equals(FUTURE_UNICODE_LITERALS)) {
                continue;
            } else if (feature.equals(FUTURE_BARRY_AS_BDFL)) {
                ff.ff_features |= CO_FUTURE_BARRY_AS_BDFL;
            } else if (feature.equals(FUTURE_GENERATOR_STOP)) {
                continue;
            } else if (feature.equals(FUTURE_ANNOTATIONS)) {
                ff.ff_features |= CO_FUTURE_ANNOTATIONS;
            } else if (feature.equals("braces")) {
                throw Errors.PyErr_RangedSyntaxLocationObject(
                        new PythonSyntaxError("SyntaxError", "not a chance"),
                        filename,
                        name.lineno,
                        name.col_offset + 1,
                        name.end_lineno,
                        name.end_col_offset + 1);
            } else {
                throw Errors.PyErr_RangedSyntaxLocationObject(
                        new PythonSyntaxError("SyntaxError",
                                UNDEFINED_FUTURE_FEATURE.replace("%.100s", precision100(feature))),
                        filename,
                        name.lineno,
                        name.col_offset + 1,
                        name.end_lineno,
                        name.end_col_offset + 1);
            }
        }
    }

    /**
     * PyUnicode_FromFormat's "%.100s" of PyUnicode_AsUTF8(s): at most 100
     * bytes of its UTF-8, decoded statefully, so a character cut in two is
     * dropped.
     */
    private static String precision100(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= 100) {
            return s;
        }
        int end = 100;
        while ((utf8[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }

    private static void future_parse(FutureFeatures ff, mod mod, String filename) {
        if (!(mod instanceof Module || mod instanceof Interactive)) {
            return;
        }

        // C reads mod->v.Module.body for both kinds: Interactive's body has the same place in the union.
        List<stmt> body = mod instanceof Module ? ((Module) mod).body : ((Interactive) mod).body;
        int n = body == null ? 0 : body.size();
        if (n == 0) {
            return;
        }

        int i = 0;
        if (Ast._PyAST_GetDocString(body) != null) {
            i++;
        }

        for (; i < n; i++) {
            stmt s = body.get(i);

            /* The only things that can precede a future statement
             *  are another future statement and a doc string.
             */

            if (s.kind() == stmt.Kind.ImportFrom && ((ImportFrom) s).level == 0) {
                String modname = ((ImportFrom) s).module;
                if (modname != null && modname.equals("__future__")) {
                    future_check_features(ff, s, filename);
                    ff.ff_location = SourceLocation.SRC_LOCATION_FROM_AST(s);
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    /**
     * _PyFuture_FromAST: fills in ff from mod's future statements. Throws
     * the SyntaxError for an unknown feature (or braces).
     */
    public static void _PyFuture_FromAST(mod mod, String filename, FutureFeatures ff) {
        ff.ff_features = 0;
        ff.ff_location = SourceLocation.NO_LOCATION;

        future_parse(ff, mod, filename);
    }
}
