// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.python.base.InterpreterError;
import org.python.modules.marshal;
import org.python.pegen.GeneratedParser;
import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Codegen;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.Marshal;
import org.python.pegen.compile.PyCodeObject;

/**
 * A {@link ReplCompiler} that uses the Java port of CPython's compiler
 * ({@code org.python.pegen}) in-process. It follows
 * {@code codeop.compile_command(source, "<stdin>", "single")} to tell
 * complete, incomplete and invalid input apart, and reports errors as
 * {@code traceback.format_exception_only} formats them. The code object
 * reaches the runtime as a {@link CPython315Code} through
 * {@link Marshal#dumps(PyCodeObject)} and {@link marshal}.
 * <p>
 * Differences from CPython: a SyntaxError "invalid syntax" gets no
 * keyword suggestion ({@code traceback}'s {@code _find_keyword_typos}),
 * and carets under wide (East Asian) characters are one column each.
 */
class JavaReplCompiler implements ReplCompiler {

    /** The file name compiled code and errors report. */
    static final String FILENAME = "<stdin>";

    /** The type the parser gives CPython's {@code _IncompleteInputError}. */
    private static final String INCOMPLETE_INPUT_ERROR = "IncompleteInputError";

    @Override
    public Result compile(String source) {
        List<Parser.ParserWarning> warnings = new ArrayList<>();
        try {
            PyCodeObject co = _maybe_compile(source, warnings);
            if (co == null) { return new Incomplete(); }
            return new Code(toCode(co), show(warnings));
        } catch (PythonSyntaxError e) {
            return new Error(format_exception_only(e), show(warnings));
        } catch (Codegen.Unsupported | Marshal.MarshalError e) {
            return new Error("internal error: " + e, show(warnings));
        }
    }

    @Override
    public String description() { return "Java compiler"; }

    @Override
    public void close() {}

    /**
     * Compile the source, or return {@code null} if it is incomplete
     * (C: {@code codeop._maybe_compile} with {@code codeop._compile}
     * and symbol {@code "single"}).
     *
     * @param source to compile
     * @param warnings to which to add those of the final compilation
     * @return the code object or {@code null}
     * @throws PythonSyntaxError if the source is invalid
     */
    private static PyCodeObject _maybe_compile(String source,
            List<Parser.ParserWarning> warnings) {
        // Check for source consisting of only blank lines and comments.
        boolean blank = true;
        for (String line : source.split("\n", -1)) {
            line = line.strip();
            if (!line.isEmpty() && line.charAt(0) != '#') {
                blank = false;  // Leave it alone.
                break;
            }
        }
        if (blank) {
            source = "pass";  // Replace it with a 'pass' statement
        }

        // Compiler warnings are ignored when checking for incomplete input.
        int incomplete = Compile.PyCF_ALLOW_INCOMPLETE_INPUT | Compile.PyCF_DONT_IMPLY_DEDENT;
        try {
            compile(source, FILENAME, Parser.SINGLE_INPUT, incomplete, null);
        } catch (PythonSyntaxError e) {
            if (!isSyntaxError(e)) { throw e; }  // Let other compile() errors propagate.
            try {
                compile(source + "\n", FILENAME, Parser.SINGLE_INPUT, incomplete, null);
                return null;
            } catch (PythonSyntaxError e2) {
                if (e2.type.equals(INCOMPLETE_INPUT_ERROR)) { return null; }
                if (!isSyntaxError(e2)) { throw e2; }
                // fallthrough
            }
        }
        return compile(source, FILENAME, Parser.SINGLE_INPUT, 0, warnings);
    }

    /**
     * Compile source as {@code compile(source, filename, mode, flags)}
     * does when {@code source} is a {@code str}.
     *
     * @param source to compile
     * @param filename the code object and errors report
     * @param startRule {@link Parser#SINGLE_INPUT},
     *     {@link Parser#FILE_INPUT} or {@link Parser#EVAL_INPUT}
     * @param flags {@code PyCF_*} flags
     * @param warnings to collect warnings, or {@code null} to ignore them
     * @return the code object
     * @throws PythonSyntaxError if the source is invalid
     */
    static PyCodeObject compile(String source, String filename, int startRule, int flags,
            List<Parser.ParserWarning> warnings) {
        // C: _Py_SourceAsString
        if (source.indexOf('\0') >= 0) {
            throw new PythonSyntaxError("SyntaxError",
                    "source code string cannot contain null bytes");
        }
        Compile.PyCompilerFlags cf = new Compile.PyCompilerFlags(
                flags | Compile.PyCF_SOURCE_IS_UTF8 | Compile.PyCF_IGNORE_COOKIE);
        Parser.WarningHandler handler = w -> {
            if (warnings != null) { warnings.add(w); }
            return true;
        };
        Parser p = Parser.fromString(source.getBytes(StandardCharsets.UTF_8),
                startRule, filename, cf, null);
        p.warning_handler = handler;
        Object tree = p.runParser(new GeneratedParser(p));
        if (tree == null) {
            PythonSyntaxError e = p.getError();
            throw e != null ? e : new PythonSyntaxError("SyntaxError", "invalid syntax");
        }
        return Compile._PyAST_Compile((mod)tree, filename, cf, -1, null, handler);
    }

    /**
     * Whether the exception is a {@code SyntaxError} (or subclass).
     *
     * @param e exception from the compiler
     * @return whether a {@code SyntaxError}
     */
    private static boolean isSyntaxError(PythonSyntaxError e) {
        return switch (e.type) {
            case "SyntaxError", "IndentationError", "TabError", INCOMPLETE_INPUT_ERROR -> true;
            default -> false;
        };
    }

    /**
     * Convert a code object from the compiler to one the runtime can
     * execute, by way of {@code marshal}.
     *
     * @param co from the compiler
     * @return the runtime's code object
     */
    static CPython315Code toCode(PyCodeObject co) {
        Object o = new marshal.BytesReader(Marshal.dumps(co)).readObject();
        if (o instanceof CPython315Code code) { return code; }
        throw new InterpreterError("marshal did not make a code object");
    }

    /**
     * Warnings as {@code warnings.showwarning} writes them (no source
     * line: {@code linecache} has none for {@code <stdin>}).
     *
     * @param warnings issued
     * @return a line for each
     */
    private static List<String> show(List<Parser.ParserWarning> warnings) {
        List<String> lines = new ArrayList<>(warnings.size());
        for (Parser.ParserWarning w : warnings) {
            lines.add(w.filename + ":" + w.lineno + ": " + w.category + ": " + w.message);
        }
        return lines;
    }

    /**
     * Format a compiler exception as
     * {@code traceback.format_exception_only} does (without colour),
     * less the final newline: for a {@code SyntaxError}, its location,
     * the source line and carets under the error, then the type and
     * message ({@code TracebackException._format_syntax_error}).
     *
     * @param e exception from the compiler
     * @return text of the report
     */
    static String format_exception_only(PythonSyntaxError e) {
        String stype = e.type;
        if (!isSyntaxError(e)) { return stype + ": " + e.msg; }

        // A SyntaxError without a location has no file name either.
        StringBuilder b = new StringBuilder();
        if (e.hasLocation()) {
            b.append("  File \"").append(FILENAME).append("\", line ").append(e.lineno)
                    .append('\n');
        }

        String text = e.text;
        if (e.hasLocation() && text != null) {
            // text = " foo\n", rtext = " foo", ltext = "foo"
            String rtext = rstrip(text, "\n");
            String ltext = lstrip(rtext, " \n\f");
            int spaces = len(rtext) - len(ltext);
            int offset = e.offset;
            int end_offset;
            if (!e.noEnd && e.lineno == e.end_lineno) {
                end_offset = e.end_offset != 0 ? e.end_offset : offset;
            } else {
                end_offset = len(rtext) + 1;
            }
            if (!text.isEmpty() && offset > len(text)) { offset = len(rtext) + 1; }
            if (!text.isEmpty() && end_offset > len(text)) { end_offset = len(rtext) + 1; }
            if (offset >= end_offset || end_offset < 0) { end_offset = offset + 1; }

            // Convert 1-based column offset to 0-based index into stripped text
            int colno = offset - 1 - spaces;
            int end_colno = end_offset - 1 - spaces;
            b.append("    ").append(ltext).append('\n');
            if (colno >= 0) {
                int n = len(ltext);
                int from = Math.min(colno, n), to = Math.max(from, Math.min(end_colno, n));
                int caret_count = to > from ? to - from : end_colno - colno;
                b.append("    ").append(" ".repeat(from)).append("^".repeat(caret_count))
                        .append('\n');
            }
        }
        String msg = e.msg == null || e.msg.isEmpty() ? "<no detail available>" : e.msg;
        b.append(stype).append(": ").append(msg);
        return b.toString();
    }

    /** Length in code points, as Python's {@code len(str)}. */
    private static int len(String s) { return s.codePointCount(0, s.length()); }

    /** Python's {@code s.rstrip(chars)}. */
    private static String rstrip(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) { end--; }
        return s.substring(0, end);
    }

    /** Python's {@code s.lstrip(chars)}. */
    private static String lstrip(String s, String chars) {
        int start = 0;
        while (start < s.length() && chars.indexOf(s.charAt(start)) >= 0) { start++; }
        return s.substring(start);
    }
}
