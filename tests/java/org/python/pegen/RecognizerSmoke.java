package org.python.pegen;

import java.io.IOException;

import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;

/**
 * Runs {@link GeneratedParser} over token dumps written by
 * tests/pegen/dump_tokens.py (read by {@link TokenDump}) and checks that every file is accepted (or, with
 * {@code --expect reject}, that every file is rejected). A parsed file is
 * also preprocessed, with constants folded (as by ast.parse(...,
 * optimize=1)), which must not fail either: like the parser, preprocess must
 * cope with the deepest nesting whatever the caller's stack. A stopgap until
 * the Python tokenizer is ported; driven by tests/pegen/smoke.sh.
 *
 * <p>Not a JUnit test: it needs python3 and a CPython checkout to produce its input.
 */
public class RecognizerSmoke {

    public static void main(String[] args) throws IOException {
        boolean expectAccept = true;
        int startRule = Parser.FILE_INPUT;
        String dump = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--expect")) {
                expectAccept = args[++i].equals("accept");
            } else if (args[i].equals("--mode")) {
                startRule = args[++i].equals("single") ? Parser.SINGLE_INPUT : Parser.FILE_INPUT;
            } else {
                dump = args[i];
            }
        }
        if (dump == null) {
            System.err.println(
                    "usage: RecognizerSmoke [--expect accept|reject] [--mode file|single] TOKEN_DUMP");
            System.exit(2);
        }

        int files = 0, unexpected = 0;
        long t0 = System.nanoTime();
        for (TokenDump.DumpFile file : TokenDump.read(dump)) {
            Parser p = new Parser(file.tokenSource(startRule == Parser.FILE_INPUT), startRule);
            Object result = p.runParser(new GeneratedParser(p));
            boolean accepted = result != null;
            PythonSyntaxError compileError = null;
            if (accepted) {
                try {
                    Compile._PyCompile_AstPreprocess((mod) result, "<unknown>",
                            new Compile.PyCompilerFlags(Compile.PyCF_OPTIMIZED_AST), 1, false);
                } catch (PythonSyntaxError e) {
                    accepted = false;
                    compileError = e;
                }
            }
            files++;
            if (accepted != expectAccept) {
                unexpected++;
                Token furthest = p.fill > 0 ? p.tokens[p.fill - 1] : null;
                System.out.println((accepted ? "ACCEPTED " : "REJECTED ") + file.path
                        + "  furthest token: " + furthest
                        + (p.getError() != null ? "  error: " + p.getError().getMessage() : "")
                        + (compileError != null ? "  preprocess: " + compileError.getMessage()
                                : ""));
            }
        }
        System.out.printf("%d files, %d not %s as expected, %.1fs%n", files, unexpected,
                expectAccept ? "accepted" : "rejected", (System.nanoTime() - t0) / 1e9);
        System.exit(unexpected == 0 && files > 0 ? 0 : 1);
    }
}
