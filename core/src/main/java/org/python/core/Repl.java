// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An interactive read-eval-print loop for the Jython 3 runtime.
 * <p>
 * Each statement is compiled (see {@link ReplCompiler}: by default the
 * Java port of CPython's compiler, or a CPython subprocess) and
 * executed by {@link CPython315Frame} in a {@code globals} dictionary
 * that persists for the session. The value of an expression
 * statement is printed by the byte code itself (via
 * {@code CALL_INTRINSIC_1 INTRINSIC_PRINT}) to {@code System.out}.
 * <p>
 * Run it from the build with {@code ./gradlew -q --console=plain
 * core:repl}.
 */
public class Repl {

    /** Prompt for a new statement. */
    static final String PS1 = ">>> ";
    /** Prompt for a continuation line. */
    static final String PS2 = "... ";

    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final ReplCompiler compiler;

    private final Interpreter interp = new Interpreter();
    private final PyDict globals = new PyDict();

    /** Lines of the statement being entered. */
    private final List<String> buffer = new ArrayList<>();

    /**
     * Create a REPL on the given streams. The results of expression
     * statements go to {@code System.out}, so {@code out} should
     * normally be the same stream.
     *
     * @param in source of input lines
     * @param out destination of prompts
     * @param err destination of error reports
     * @param compiler to compile input
     */
    Repl(BufferedReader in, PrintStream out, PrintStream err, ReplCompiler compiler) {
        this.in = in;
        this.out = out;
        this.err = err;
        this.compiler = compiler;
        globals.put("__name__", "__main__");
    }

    /**
     * Run the loop until end of input.
     *
     * @throws IOException if reading input or talking to the compiler
     *     fails
     */
    void run() throws IOException {
        String prompt = PS1;
        while (true) {
            out.print(prompt);
            out.flush();
            String line = in.readLine();
            if (line == null) {
                // End of input: finish any statement in progress.
                if (!buffer.isEmpty()) { push(""); }
                out.println();
                return;
            }
            prompt = push(line) ? PS2 : PS1;
        }
    }

    /**
     * Add a line to the statement being entered and, if it is then
     * complete, execute it (or report the error).
     *
     * @param line to add
     * @return {@code true} if more input is needed
     * @throws IOException if talking to the compiler fails
     */
    // Compare CPython code.InteractiveConsole.push
    boolean push(String line) throws IOException {
        buffer.add(line);
        ReplCompiler.Result result = compiler.compile(String.join("\n", buffer));
        if (result instanceof ReplCompiler.Incomplete) { return true; }
        buffer.clear();
        if (result instanceof ReplCompiler.Code c) {
            warn(c.warnings());
            execute(c.code());
        } else if (result instanceof ReplCompiler.Error e) {
            warn(e.warnings());
            err.println(e.message());
        }
        return false;
    }

    /**
     * Report the compiler's warnings.
     *
     * @param warnings one line each
     */
    private void warn(List<String> warnings) {
        for (String w : warnings) { err.println(w); }
        err.flush();
    }

    /**
     * Execute compiled code in the session {@code globals}, reporting
     * (but surviving) any exception.
     *
     * @param code to execute
     */
    private void execute(CPython315Code code) {
        try {
            interp.eval(code, globals);
        } catch (PyException pye) {
            // No traceback yet: just "TypeName: message"
            err.println(pye);
        } catch (RuntimeException | StackOverflowError e) {
            // Includes InterpreterError (and MissingFeature)
            err.println("internal error: " + e);
        } finally {
            out.flush();
            err.flush();
        }
    }

    /**
     * Run an interactive session on the standard streams.
     *
     * @param args ignored
     * @throws IOException if reading input or talking to the compiler
     *     fails
     */
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        try (ReplCompiler compiler = ReplCompiler.create()) {
            System.out.printf("Jython 3 (prototype) using %s%n", compiler.description());
            System.out.println("Ctrl-D to exit.");
            new Repl(in, System.out, System.err, compiler).run();
        }
    }
}
