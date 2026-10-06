// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.python.pegen.Parser;
import org.python.pegen.compile.PyCodeObject;

/**
 * The run-and-compare oracle: each Python example in
 * {@code core/src/test/pythonExample} is compiled by the Java compiler,
 * run by {@link CPython315Frame}, and its globals and what it prints
 * compared with what CPython left when the build ran it
 * ({@code compile_examples.py}: the {@code .var} and {@code .out}
 * files). See {@link CPython315CodeTest} for the same examples compiled
 * by CPython.
 */
@DisplayName("Given programs compiled by the Java compiler ...")
class JavaCompiledExampleTest extends UnitTestSupport {

    /**
     * Examples the runtime cannot run yet. One of these that starts to
     * pass fails the test, so it can be taken off the list.
     */
    private static final Set<String> EXPECTED_FAILURES = Set.of(
            // print(None) shows "<None object at ...>": NoneType has no __repr__
            "print_builtin");

    /** @return the base names of the examples, sorted */
    static Stream<String> examples() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(CPython315CodeTest.PYTHON_DIR)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".py"))
                    .map(n -> n.substring(0, n.length() - 3)).sorted().forEach(names::add);
        }
        return names.stream();
    }

    @DisplayName("we get the results CPython does from")
    @ParameterizedTest(name = "{0}.py")
    @MethodSource("examples")
    void runsAsCPython(String name) throws IOException {
        try {
            runAndCompare(name);
        } catch (AssertionError | RuntimeException e) {
            if (EXPECTED_FAILURES.contains(name)) { return; }
            throw e;
        }
        assertFalse(EXPECTED_FAILURES.contains(name),
                () -> name + " now passes: take it off EXPECTED_FAILURES");
    }

    /**
     * Compile an example with the Java compiler, run it, and compare the
     * globals and output with CPython's.
     *
     * @param name of the example
     * @throws IOException reading the files
     */
    private static void runAndCompare(String name) throws IOException {
        Path source = CPython315CodeTest.PYTHON_DIR.resolve(name + ".py");
        PyCodeObject co = JavaReplCompiler.compile(Files.readString(source), source.toString(),
                Parser.FILE_INPUT, 0, null);
        CPython315Code code = JavaReplCompiler.toCode(co);

        PyDict globals = new PyDict();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        Object r;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            r = new Interpreter().eval(code, globals);
        } finally {
            System.setOut(saved);
        }
        assertEquals(Py.None, r);

        PyDict expected = CPython315CodeTest.readResultDict(name);
        assertTrue(expected.size() > 0 || globals.size() == 0, "no CPython results");
        CPython315CodeTest.assertExpectedVariables(expected, globals);

        Path outFile = CPython315CodeTest.PYC_DIR.resolve(name + ".cpython-315.out");
        if (!Files.exists(outFile)) { fail("no CPython output " + outFile); }
        assertEquals(Files.readString(outFile), outBytes.toString(StandardCharsets.UTF_8));
    }
}
