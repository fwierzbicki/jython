// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import java.io.IOException;
import java.util.List;

/**
 * Compile interactive input to code objects for the {@link Repl}, as
 * CPython's {@code codeop.compile_command(source, "<stdin>", "single")}
 * does. There are two implementations: {@link JavaReplCompiler} (the
 * default), which uses the Java port of CPython's compiler in-process,
 * and {@link CPythonReplCompiler}, which delegates to a CPython 3.15
 * subprocess and is kept as the oracle. {@link #create()} chooses
 * between them.
 */
interface ReplCompiler extends AutoCloseable {

    /** System property choosing the compiler ({@code java} or {@code cpython}). */
    static final String COMPILER_PROPERTY = "jython.repl.compiler";
    /** Environment variable choosing the compiler, if the property is not set. */
    static final String COMPILER_ENV = "JYTHON_REPL_COMPILER";

    /** The result of compiling some source. */
    sealed interface Result {}

    /**
     * The source is a complete statement.
     *
     * @param code compiled from the source
     * @param warnings issued by the compiler, as {@code warnings} would
     *     show them, one line each
     */
    record Code(CPython315Code code, List<String> warnings) implements Result {}

    /** The source is incomplete: more lines are needed. */
    record Incomplete() implements Result {}

    /**
     * The source cannot be compiled.
     *
     * @param message describing the error (as CPython reports it)
     * @param warnings issued by the compiler before the error
     */
    record Error(String message, List<String> warnings) implements Result {}

    /**
     * Compile the source as interactive input.
     *
     * @param source to compile (possibly several lines)
     * @return the outcome
     * @throws IOException if communication with a compiler process fails
     */
    Result compile(String source) throws IOException;

    /**
     * Describe this compiler, for the REPL's banner.
     *
     * @return a description
     */
    String description();

    /** Release any resources (a subprocess) the compiler holds. */
    @Override
    void close();

    /**
     * Create the compiler chosen by the system property
     * {@value #COMPILER_PROPERTY}, else the environment variable
     * {@value #COMPILER_ENV}: {@code cpython} for
     * {@link CPythonReplCompiler}, otherwise (and by default)
     * {@link JavaReplCompiler}.
     *
     * @return the compiler
     * @throws IOException if a CPython subprocess cannot be started
     */
    static ReplCompiler create() throws IOException {
        String choice = System.getProperty(COMPILER_PROPERTY);
        if (choice == null || choice.isEmpty()) { choice = System.getenv(COMPILER_ENV); }
        if ("cpython".equals(choice)) { return new CPythonReplCompiler(); }
        return new JavaReplCompiler();
    }
}
