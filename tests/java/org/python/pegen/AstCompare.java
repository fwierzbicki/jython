package org.python.pegen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.python.pegen.ast.AST;
import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;

/**
 * The Java half of tests/pegen/compare_ast.py: parses each file of a token
 * dump (tests/pegen/dump_tokens.py) with a parser generated with actions, and
 * runs the compiler stages compile(..., PyCF_ONLY_AST) runs on the tree
 * (Compile._PyCompile_AstPreprocess: future, for now), and writes the result
 * in a canonical text form that compare_ast.py also produces from CPython's
 * ast.parse(), so the two can be compared exactly.
 *
 * <p>Per file: "#FILE path", then the tree (one node, field or list item per
 * line, indented), or one "#ERROR" line with the exception's type, msg,
 * lineno, offset, end_lineno, end_offset and text; then a "#WARNING" line per
 * warning. Values are written so that nothing depends on repr(): strings as
 * UTF-16 code units with \\uXXXX escapes, floats as their IEEE bits, bytes in
 * hex.
 *
 * <p>Usage: AstCompare [--mode file|single|eval] TOKEN_DUMP OUT. Needs the
 * parser generated with --actions on the classpath ahead of build/classes.
 */
public class AstCompare {

    public static void main(String[] args) throws IOException {
        int startRule = Parser.FILE_INPUT;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--mode")) {
                String mode = args[++i];
                startRule = mode.equals("single") ? Parser.SINGLE_INPUT
                        : mode.equals("eval") ? Parser.EVAL_INPUT : Parser.FILE_INPUT;
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() != 2) {
            System.err.println("usage: AstCompare [--mode file|single|eval] TOKEN_DUMP OUT");
            System.exit(2);
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(files.get(1))), StandardCharsets.UTF_8))) {
            for (TokenDump.DumpFile file : TokenDump.read(files.get(0))) {
                StringBuilder b = new StringBuilder();
                b.append("#FILE ").append(file.path).append('\n');
                Parser p = new Parser(file.tokenSource(startRule == Parser.FILE_INPUT), startRule);
                Object result;
                PythonSyntaxError compileError = null;
                try {
                    result = p.runParser(new GeneratedParser(p));
                    if (result != null) {
                        // What compile(..., PyCF_ONLY_AST) runs after parsing (pythonrun.c).
                        Compile._PyCompile_AstPreprocess((mod) result, "<unknown>",
                                new Compile.PyCompilerFlags(Compile.PyCF_ONLY_AST), -1, true);
                    }
                } catch (PythonSyntaxError e) {
                    result = null;
                    compileError = e;
                } catch (RuntimeException | StackOverflowError e) {
                    result = null;
                    b.append("#CRASH ").append(str(e.toString())).append('\n');
                }
                if (result != null) {
                    dump(result, b, 0);
                } else if (compileError != null) {
                    error(compileError, b);
                } else if (p.getError() != null) {
                    error(p.getError(), b);
                } else if (b.indexOf("#CRASH") < 0) {
                    b.append("#CRASH no result and no error\n");
                }
                for (Parser.ParserWarning w : p.warnings) {
                    b.append("#WARNING ").append(w.category).append('\t').append(w.lineno)
                            .append('\t').append(str(w.message)).append('\n');
                }
                out.write(b.toString());
            }
        }
    }

    private static void error(PythonSyntaxError e, StringBuilder b) {
        b.append("#ERROR ").append(e.type).append('\t').append(str(e.msg));
        if (e.hasLocation()) {
            b.append('\t').append(e.lineno).append('\t').append(e.offset)
                    .append('\t').append(e.end_lineno).append('\t').append(e.end_offset)
                    .append('\t').append(e.text == null ? "None" : str(e.text));
        }
        b.append('\n');
    }

    private static final List<String> LOCATION =
            Arrays.asList("lineno", "col_offset", "end_lineno", "end_col_offset");

    /** A node: its class name, then its fields in ASDL order, then its location attributes. */
    static void dump(Object node, StringBuilder out, int indent) {
        Class<?> c = node.getClass();
        pad(out, indent).append(c.getSimpleName()).append('\n');
        List<Field> fields = new ArrayList<>();
        List<Field> locations = new ArrayList<>();
        for (Class<?> k = c; k != null && k != AST.class; k = k.getSuperclass()) {
            List<Field> own = new ArrayList<>();
            for (Field f : k.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isPublic(m) && !Modifier.isStatic(m)) {
                    (LOCATION.contains(f.getName()) ? locations : own).add(f);
                }
            }
            fields.addAll(0, own);
        }
        locations.sort(Comparator.comparingInt(f -> LOCATION.indexOf(f.getName())));
        fields.addAll(locations);
        try {
            for (Field f : fields) {
                Object v = f.get(node);
                pad(out, indent + 1).append(f.getName()).append('=');
                // A NULL sequence in C is an empty list in Python's AST.
                if (v == null && List.class.isAssignableFrom(f.getType())) {
                    v = Collections.emptyList();
                }
                value(v, out, indent + 1);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void value(Object v, StringBuilder out, int indent) {
        if (v == null) {
            out.append("None\n");
        } else if (v instanceof AST) {
            out.append('\n');
            dump(v, out, indent + 1);
        } else if (v instanceof List) {
            out.append("[\n");
            for (Object x : (List<?>) v) {
                pad(out, indent + 1);
                value(x, out, indent + 1);
            }
            pad(out, indent).append("]\n");
        } else if (v instanceof String) {
            out.append(str((String) v)).append('\n');
        } else if (v instanceof BigInteger || v instanceof Integer) {
            out.append(v).append('\n');
        } else if (v instanceof Double) {
            out.append('f').append(bits((Double) v)).append('\n');
        } else if (v instanceof Complex) {
            Complex z = (Complex) v;
            out.append('c').append(bits(z.real)).append(',').append(bits(z.imag)).append('\n');
        } else if (v instanceof Bytes) {
            out.append("b'");
            for (byte x : ((Bytes) v).toArray()) {
                out.append(String.format("%02x", x & 0xff));
            }
            out.append("'\n");
        } else if (v instanceof Enum) {
            // operator, context and singleton values (Load, Add, None, Ellipsis, ...)
            out.append(((Enum<?>) v).name()).append('\n');
        } else {
            out.append('?').append(v.getClass().getName()).append('\n');
        }
    }

    private static String bits(double d) {
        return Long.toHexString(Double.doubleToLongBits(d));
    }

    /** A string as printable ASCII, other UTF-16 code units (and \\ and ") as \\uXXXX. */
    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '\\' && c != '"') {
                b.append(c);
            } else {
                b.append(String.format("\\u%04x", (int) c));
            }
        }
        return b.append('"').toString();
    }

    private static StringBuilder pad(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) {
            b.append(' ');
        }
        return b;
    }
}
