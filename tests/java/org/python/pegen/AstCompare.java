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
 * The Java half of tests/pegen/compare_ast.py: parses each file (tokenized by
 * the Java tokenizer, as compile() does with source bytes) with a parser
 * generated with actions, and
 * runs the compiler stages compile(..., PyCF_ONLY_AST) runs on the tree
 * (Compile._PyCompile_AstPreprocess: future and preprocess), and writes the result
 * in a canonical text form that compare_ast.py also produces from CPython's
 * ast.parse(), so the two can be compared exactly.
 *
 * <p>Per file: "#FILE path", then the tree (one node, field or list item per
 * line, indented), or one "#ERROR" line with the exception's type, msg,
 * lineno, offset, end_lineno, end_offset and text; then a "#WARNING" line per
 * warning. For a file that's accepted, a "#COMPILE-WARNING" line follows for
 * each warning preprocess issues when compiling to code (Compile.new_compiler
 * on a second parse), which PyCF_ONLY_AST doesn't enable. Values are written
 * so that nothing depends on repr(): strings as
 * UTF-16 code units with \\uXXXX escapes, floats as their IEEE bits, bytes in
 * hex.
 *
 * <p>Usage: AstCompare [--mode file|single|eval] [--optimize N] LIST OUT,
 * where LIST names the files, one per line. With N > 0, PyCF_OPTIMIZED_AST is set too, as ast.parse(...,
 * optimize=N) sets it. Needs the
 * parser generated with --actions on the classpath ahead of build/classes.
 */
public class AstCompare {

    public static void main(String[] args) throws IOException {
        int startRule = Parser.FILE_INPUT;
        int optimize = 0;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--optimize")) {
                optimize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--mode")) {
                String mode = args[++i];
                startRule = mode.equals("single") ? Parser.SINGLE_INPUT
                        : mode.equals("eval") ? Parser.EVAL_INPUT : Parser.FILE_INPUT;
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() != 2) {
            System.err.println(
                    "usage: AstCompare [--mode file|single|eval] [--optimize N] LIST OUT");
            System.exit(2);
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(files.get(1))), StandardCharsets.UTF_8))) {
            for (String path : Files.readAllLines(Paths.get(files.get(0)),
                    StandardCharsets.UTF_8)) {
                byte[] source = Files.readAllBytes(Paths.get(path));
                StringBuilder b = new StringBuilder();
                b.append("#FILE ").append(path).append('\n');
                int flags = optimize > 0 ? Compile.PyCF_OPTIMIZED_AST : Compile.PyCF_ONLY_AST;
                Parser p = null;
                Object result;
                PythonSyntaxError compileError = null;
                try {
                    p = parser(source, startRule, flags);
                    result = p.runParser(new GeneratedParser(p));
                    if (result != null) {
                        // What compile(..., PyCF_ONLY_AST) runs after parsing (pythonrun.c).
                        boolean syntax_check_only = (flags & Compile.PyCF_OPTIMIZED_AST)
                                == Compile.PyCF_ONLY_AST;
                        Compile._PyCompile_AstPreprocess((mod) result, "<unknown>",
                                new Compile.PyCompilerFlags(flags), optimize, syntax_check_only);
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
                } else if (p != null && p.getError() != null) {
                    error(p.getError(), b);
                } else if (b.indexOf("#CRASH") < 0) {
                    b.append("#CRASH no result and no error\n");
                }
                if (p != null) {
                    for (Parser.ParserWarning w : p.warnings) {
                        warning("#WARNING ", w, b);
                    }
                }
                if (result != null) {
                    for (Parser.ParserWarning w : compileWarnings(source, startRule, optimize)) {
                        warning("#COMPILE-WARNING ", w, b);
                    }
                }
                out.write(b.toString());
            }
        }
    }

    /**
     * The warnings compiling the file to code issues after parsing: what
     * Compile.new_compiler issues for a fresh parse of it.
     */
    private static List<Parser.ParserWarning> compileWarnings(byte[] source,
            int startRule, int optimize) {
        List<Parser.ParserWarning> warnings = new ArrayList<>();
        Parser p = parser(source, startRule, 0);
        Object result = p.runParser(new GeneratedParser(p));
        try {
            Compile.new_compiler((mod) result, "<unknown>", new Compile.PyCompilerFlags(),
                    optimize, null, w -> warnings.add(w));
        } catch (PythonSyntaxError e) {
            // Its warnings so far are kept, as compile() issues them before raising.
        }
        return warnings;
    }

    private static void warning(String prefix, Parser.ParserWarning w, StringBuilder b) {
        b.append(prefix).append(w.category).append('\t').append(w.lineno)
                .append('\t').append(str(w.message)).append('\n');
    }

    /**
     * A parser over source as compile(source, "<unknown>", mode, flags) sets
     * one up for bytes. First what the builtin checks (_Py_SourceAsString):
     * no null bytes.
     */
    static Parser parser(byte[] source, int startRule, int flags) {
        for (byte c : source) {
            if (c == 0) {
                throw new PythonSyntaxError("SyntaxError",
                        "source code string cannot contain null bytes");
            }
        }
        return Parser.fromString(source, startRule, "<unknown>",
                new Compile.PyCompilerFlags(flags), null);
    }

    static void error(PythonSyntaxError e, StringBuilder b) {
        b.append("#ERROR ").append(e.type).append('\t').append(str(e.msg));
        if (e.hasLocation()) {
            b.append('\t').append(e.lineno).append('\t').append(e.offset).append('\t')
                    .append(e.noEnd ? "None" : String.valueOf(e.end_lineno)).append('\t')
                    .append(e.noEnd ? "None" : String.valueOf(e.end_offset))
                    .append('\t').append(e.text == null ? "None" : str(e.text));
        } else if (e.type.equals("SyntaxError") || e.type.equals("IndentationError")
                || e.type.equals("TabError")) {
            // A SyntaxError raised with just a message.
            b.append("\tNone\tNone\tNone\tNone\tNone");
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

    static String bits(double d) {
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
