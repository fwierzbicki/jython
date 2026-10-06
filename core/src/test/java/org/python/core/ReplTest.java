// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests of the interactive {@link Repl}, with each {@link ReplCompiler}:
 * the Java compiler ({@link JavaReplCompiler}) and a CPython subprocess
 * ({@link CPythonReplCompiler}), and of the Java compiler against
 * CPython's. The Gradle build tells us which CPython to use through the
 * system property {@value CPythonReplCompiler#CPYTHON_PROPERTY}; the
 * tests that need it are skipped if it cannot compile.
 */
@DisplayName("The REPL ...")
class ReplTest extends UnitTestSupport {

    /** One compiler subprocess shared by all the tests that need it. */
    private static CPythonReplCompiler cpython;

    @BeforeAll
    static void startCPython() throws IOException { cpython = new CPythonReplCompiler(); }

    @AfterAll
    static void stopCPython() { if (cpython != null) { cpython.close(); } }

    /** Skip the test unless the CPython compiler works. */
    private static void assumeCPython() {
        boolean ok;
        try {
            ok = cpython.compile("1") instanceof ReplCompiler.Code;
        } catch (IOException e) {
            ok = false;
        }
        assumeTrue(ok, "CPython 3.15 compiler not available");
    }

    /** What a session wrote to its output and error streams. */
    private record Session(String out, String err) {}

    /**
     * Run a REPL session on the given input lines, capturing
     * {@code System.out} (where expression values go) and the error
     * stream.
     *
     * @param compiler to compile the input
     * @param lines input to the session
     * @return captured output and error text
     * @throws IOException from the REPL
     */
    private static Session session(ReplCompiler compiler, String... lines) throws IOException {
        BufferedReader in = new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            new Repl(in, out, err, compiler).run();
        } finally {
            System.setOut(saved);
        }
        return new Session(outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    /** Sessions that should go the same way whichever compiler is used. */
    abstract static class Sessions {

        /** @return the compiler under test */
        abstract ReplCompiler compiler();

        private Session session(String... lines) throws IOException {
            return ReplTest.session(compiler(), lines);
        }

        @Test
        @DisplayName("echoes the value of an expression")
        void echoesExpression() throws IOException {
            Session s = session("x = 6 * 7", "x");
            assertEquals(">>> >>> 42\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("does not echo None")
        void doesNotEchoNone() throws IOException {
            Session s = session("None", "x = None", "x", "'abc'.upper()");
            assertEquals(">>> >>> >>> >>> 'ABC'\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("prompts for continuation lines")
        void continuesCompoundStatement() throws IOException {
            Session s = session("x = 1", "if x:", "    y = 2", "", "y");
            assertEquals(">>> >>> ... ... >>> 2\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("finishes a compound statement at end of input")
        void finishesAtEof() throws IOException {
            Session s = session("if True:", "    z = 3");
            assertEquals("", s.err());
            assertTrue(s.out().startsWith(">>> ... ... "), s.out());
        }

        @Test
        @DisplayName("ignores blank and comment lines")
        void ignoresBlankAndComment() throws IOException {
            Session s = session("", "# just a comment", "   ", "7");
            assertEquals(">>> >>> >>> >>> 7\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("prompts inside a triple-quoted string")
        void continuesString() throws IOException {
            Session s = session("s = '''a", "b'''", "len(s)");
            assertEquals(">>> ... >>> 3\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("reports a syntax error and carries on")
        void reportsSyntaxError() throws IOException {
            Session s = session("1 +", "2");
            assertEquals("  File \"<stdin>\", line 1\n    1 +\nSyntaxError: invalid syntax\n",
                    s.err());
            assertTrue(s.out().contains("2\n"), s.out());
        }

        @Test
        @DisplayName("reports an indentation error and carries on")
        void reportsIndentationError() throws IOException {
            Session s = session("if 1:", "  x = 1", " y = 2", "3");
            assertTrue(s.err().endsWith(
                    "IndentationError: unindent does not match any outer indentation level\n"),
                    s.err());
            assertTrue(s.out().contains("3\n"), s.out());
        }

        @Test
        @DisplayName("reports a Python exception and carries on")
        void reportsNameError() throws IOException {
            Session s = session("undefined_name", "5");
            assertEquals("NameError: name 'undefined_name' is not defined\n", s.err());
            assertTrue(s.out().contains("5\n"), s.out());
        }

        @Test
        @DisplayName("reports an unimplemented feature and carries on")
        void reportsMissingFeature() throws IOException {
            // True division is not yet implemented in BINARY_OP. (Use a
            // variable, or the compiler will fold 1 / 2 to a constant.)
            Session s = session("n = 1", "n / 2", "6");
            assertTrue(s.err().contains("internal error"), s.err());
            assertTrue(s.out().contains("6\n"), s.out());
        }

        @Test
        @DisplayName("keeps globals between statements")
        void keepsGlobals() throws IOException {
            // (list has no __repr__ yet, so we look at the elements)
            Session s = session("a = [1, 2, 3]", "b = a[1:]", "len(b)", "b[0]", "__name__");
            assertEquals(">>> >>> >>> 2\n>>> 2\n>>> '__main__'\n>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("provides print()")
        void printBuiltin() throws IOException {
            Session s = session("print('a', 1, sep='-')", "print()", "print(2, end='!')");
            assertEquals(">>> a-1\n>>> \n>>> 2!>>> \n", s.out());
            assertEquals("", s.err());
        }

        @Test
        @DisplayName("compiler distinguishes complete, incomplete and invalid input")
        void compilerResults() throws IOException {
            assertInstanceOf(ReplCompiler.Incomplete.class, compiler().compile("if x:"));
            assertInstanceOf(ReplCompiler.Code.class, compiler().compile("1"));
            assertInstanceOf(ReplCompiler.Error.class, compiler().compile("1 +"));
        }
    }

    @Nested
    @DisplayName("compiling with Java ...")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WithJava extends Sessions {

        private final ReplCompiler compiler = new JavaReplCompiler();

        @Override
        ReplCompiler compiler() { return compiler; }

        @Test
        @DisplayName("reports a SyntaxWarning")
        void reportsSyntaxWarning() throws IOException {
            Session s = session(compiler, "1 is 1");
            assertEquals("<stdin>:1: SyntaxWarning: \"is\" with 'int' literal."
                    + " Did you mean \"==\"?\n", s.err());
            assertEquals(">>> True\n>>> \n", s.out());
        }

        @Test
        @DisplayName("does not warn about incomplete input")
        void noWarningWhileIncomplete() throws IOException {
            ReplCompiler.Result r = compiler.compile("if 1 is 1:");
            assertInstanceOf(ReplCompiler.Incomplete.class, r);
            r = compiler.compile("if 1 is 1:\n    pass\n");
            assertEquals(List.of("<stdin>:1: SyntaxWarning: \"is\" with 'int' literal."
                    + " Did you mean \"==\"?"), ((ReplCompiler.Code)r).warnings());
        }
    }

    @Nested
    @DisplayName("compiling with CPython ...")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WithCPython extends Sessions {

        @BeforeAll
        void needCPython() { assumeCPython(); }

        @Override
        ReplCompiler compiler() { return cpython; }
    }

    /**
     * The Java compiler gives the same kind of result as CPython's for
     * each input, and the same error text.
     *
     * @param source input to compile ({@code \n}-separated lines)
     * @throws IOException talking to CPython
     */
    @DisplayName("Java compiler matches CPython on")
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            // Complete
            "1", "x = 6 * 7", "", "# comment", "  \n# comment\n", "if x:\n    y\n",
            "def f(a, *b, **c):\n    return a\n\n", "@d\ndef f(): pass\n", "[\n1,\n2]",
            "x = '''a\nb'''", "1 is 1",
            // Incomplete
            "if x:", "if x:\n    y", "def f(", "@d", "x = '''a", "[\n1,", "x = \\",
            "class C:\n    def f(self):", "try:\n    pass",
            // Invalid
            "1 +", "  x", "0_", "'\\N{nope}'", "def f(:", "x = (1,\n2]", "a b c",
            "if x:\n  y\n z", "x = 'abc", "f(**)", "import", "1 = x", "del f()",
            "return 1", "nonlocal x", "x = 1\ny = 2", "'\\N{nope}' + 1", "def f():\n  yield = 1",
            "\u00e9t\u00e9 +", "x = \u00a0 1"})
    void matchesCPython(String source) throws IOException {
        assumeCPython();
        ReplCompiler.Result expected = cpython.compile(source);
        ReplCompiler.Result actual = new JavaReplCompiler().compile(source);
        assertEquals(expected.getClass(), actual.getClass(), () -> describe(actual));
        if (expected instanceof ReplCompiler.Error e) {
            assertEquals(e.message(), ((ReplCompiler.Error)actual).message());
        }
    }

    private static String describe(ReplCompiler.Result r) {
        return r instanceof ReplCompiler.Error e ? e.message() : r.toString();
    }
}
