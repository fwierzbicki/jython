package org.python.pegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.python.pegen.lexer.Tokenizer;

/**
 * The Java side of tests/pegen/compare_tokens.py: tokenizes each file with
 * the Java tokenizer (org.python.pegen.lexer) and writes its tokens in
 * dump_tokens.py's format, without the #SOURCE line:
 *
 * <pre>
 * #FILE path
 * TYPE LINENO COL END_LINENO END_COL STRING
 * #META text          (after a token that carries metadata)
 * #ERROR done message (the tokenizer failed: tok->done and the exception)
 * </pre>
 *
 * Usage: TokenCompare LIST OUT, where LIST names the files, one per line.
 */
public final class TokenCompare {

    private TokenCompare() {}

    public static void main(String[] args) throws IOException {
        List<String> paths = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1]),
                StandardCharsets.UTF_8))) {
            for (String path : paths) {
                out.print("#FILE " + path + "\n");
                try {
                    dump(Files.readAllBytes(Paths.get(path)), out);
                } catch (RuntimeException | StackOverflowError e) {
                    out.print("#CRASH " + escape(e.toString()) + "\n");
                }
            }
        }
    }

    private static void dump(byte[] source, PrintWriter out) {
        Tokenizer tok;
        try {
            tok = Tokenizer.fromString(source, true, "<unknown>");
        } catch (PythonSyntaxError e) {
            out.print("#ERROR init " + escape(e.msg) + "\n");
            return;
        }
        for (;;) {
            Token t = tok.next();
            if (t.type == TokenTypes.ERRORTOKEN) {
                PythonSyntaxError e = tok.error();
                out.print("#ERROR " + tok.done() + " "
                        + (e == null ? "" : escape(e.msg + " " + e.lineno + ":" + e.offset))
                        + "\n");
                return;
            }
            out.print(TokenTypes.NAMES[t.type] + " " + t.lineno + " " + t.col_offset + " "
                    + t.end_lineno + " " + t.end_col_offset + " " + escape(t.string) + "\n");
            if (t.metadata != null) {
                out.print("#META " + escape((String) t.metadata) + "\n");
            }
            if (t.type == TokenTypes.ENDMARKER) {
                return;
            }
        }
    }

    /** As dump_tokens.py's escape(): backslash, newline and carriage return. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
