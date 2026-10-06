// Copyright (c)2026 Jython Developers.
// Licensed to PSF under a contributor agreement.
package org.python.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.python.base.InterpreterError;
import org.python.modules.marshal;

/**
 * A {@link ReplCompiler} that delegates compilation to a CPython
 * subprocess of the version whose byte code {@link CPython315Frame}
 * executes: the oracle for {@link JavaReplCompiler}. The subprocess runs
 * the script {@code repl_compiler.py} (a resource alongside this class),
 * which uses {@code codeop.compile_command(source, "<stdin>", "single")}
 * and returns the code object in {@code marshal} format. Warnings go to
 * the subprocess's error stream, which is this process's.
 */
class CPythonReplCompiler implements ReplCompiler {

    /** System property naming the CPython executable. */
    static final String CPYTHON_PROPERTY = "jython.cpython";
    /** Environment variable naming the CPython executable. */
    static final String CPYTHON_ENV = "JYTHON_CPYTHON";
    /** Executable used when neither property nor variable is set. */
    static final String CPYTHON_DEFAULT = "python3.15";

    private static final String SCRIPT = "repl_compiler.py";

    /** The CPython executable in use. */
    final String executable;

    private final Process process;
    private final DataOutputStream toCompiler;
    private final DataInputStream fromCompiler;

    /**
     * Start a compiler subprocess using the executable named by
     * {@link #findExecutable()}.
     *
     * @throws IOException if the subprocess cannot be started
     */
    CPythonReplCompiler() throws IOException { this(findExecutable()); }

    /**
     * Start a compiler subprocess using the given CPython executable.
     *
     * @param executable CPython to run
     * @throws IOException if the subprocess cannot be started
     */
    CPythonReplCompiler(String executable) throws IOException {
        this.executable = executable;
        String script = readScript();
        // -I: ignore PYTHON* variables and user site; -u: unbuffered
        ProcessBuilder pb = new ProcessBuilder(List.of(executable, "-I", "-u", "-c", script));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            this.process = pb.start();
        } catch (IOException ioe) {
            throw new IOException(String.format(
                    "cannot run CPython '%s' to compile (set -D%s or %s): %s", executable,
                    CPYTHON_PROPERTY, CPYTHON_ENV, ioe.getMessage()), ioe);
        }
        this.toCompiler = new DataOutputStream(process.getOutputStream());
        this.fromCompiler = new DataInputStream(process.getInputStream());
    }

    /**
     * The CPython executable to use: the system property
     * {@value #CPYTHON_PROPERTY}, else the environment variable
     * {@value #CPYTHON_ENV}, else {@value #CPYTHON_DEFAULT}.
     *
     * @return name or path of the executable
     */
    static String findExecutable() {
        String exe = System.getProperty(CPYTHON_PROPERTY);
        if (exe == null || exe.isEmpty()) { exe = System.getenv(CPYTHON_ENV); }
        if (exe == null || exe.isEmpty()) { exe = CPYTHON_DEFAULT; }
        return exe;
    }

    @Override
    public Result compile(String source) throws IOException {
        byte[] request = source.getBytes(StandardCharsets.UTF_8);
        try {
            toCompiler.writeInt(request.length);
            toCompiler.write(request);
            toCompiler.flush();

            int n = fromCompiler.readInt();
            byte[] reply = fromCompiler.readNBytes(n);
            if (reply.length != n || n < 1) {
                throw new IOException("short reply from CPython compiler");
            }
            return switch (reply[0]) {
                case 'C' -> new Code(readCode(reply), List.of());
                case 'I' -> new Incomplete();
                case 'E' -> new Error(
                        new String(reply, 1, n - 1, StandardCharsets.UTF_8).stripTrailing(),
                        List.of());
                default -> throw new IOException("bad reply from CPython compiler");
            };
        } catch (IOException ioe) {
            if (!process.isAlive()) {
                throw new IOException(String.format("CPython compiler '%s' exited (status %d)",
                        executable, process.exitValue()), ioe);
            }
            throw ioe;
        }
    }

    @Override
    public String description() { return "CPython compiler " + executable; }

    /** Close the subprocess input, and wait (briefly) for it to exit. */
    @Override
    public void close() {
        try {
            toCompiler.close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (IOException | InterruptedException e) {
            process.destroy();
        }
    }

    /** Decode the marshalled code object after the status byte. */
    private static CPython315Code readCode(byte[] reply) {
        byte[] data = Arrays.copyOfRange(reply, 1, reply.length);
        Object o = new marshal.BytesReader(data).readObject();
        if (o instanceof CPython315Code code) { return code; }
        throw new InterpreterError("CPython compiler did not return a code object");
    }

    /** Read the compiler script from the class path. */
    private static String readScript() throws IOException {
        try (InputStream s = CPythonReplCompiler.class.getResourceAsStream(SCRIPT)) {
            if (s == null) { throw new IOException("missing resource " + SCRIPT); }
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
