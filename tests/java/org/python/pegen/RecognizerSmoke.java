package org.python.pegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link GeneratedParser} over token dumps written by
 * tests/pegen/dump_tokens.py and checks that every file is accepted (or, with
 * {@code --expect reject}, that every file is rejected). A stopgap until the
 * Python tokenizer is ported; driven by tests/pegen/smoke.sh.
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

        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < TokenTypes.NAMES.length; i++) {
            byName.put(TokenTypes.NAMES[i], i);
        }
        List<String> lines = Files.readAllLines(Paths.get(dump), StandardCharsets.UTF_8);
        int files = 0, unexpected = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < lines.size();) {
            String file = lines.get(i++).substring("#FILE ".length());
            List<Token> toks = new ArrayList<>();
            for (; i < lines.size() && !lines.get(i).startsWith("#FILE "); i++) {
                // TYPE LINENO COL END_LINENO END_COL STRING
                String[] f = lines.get(i).split(" ", 6);
                Integer type = byName.get(f[0]);
                if (type == null) {
                    throw new IllegalArgumentException(
                            file + ": unknown token type " + f[0] + " '" + f[5] + "'");
                }
                toks.add(new Token(type, unescape(f[5]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                        Integer.parseInt(f[3]), Integer.parseInt(f[4])));
            }
            Parser p = new Parser(new DumpTokenSource(toks.iterator()), startRule);
            boolean accepted = p.runParser(new GeneratedParser(p)) != null;
            files++;
            if (accepted != expectAccept) {
                unexpected++;
                Token furthest = p.fill > 0 ? p.tokens[p.fill - 1] : null;
                System.out.println((accepted ? "ACCEPTED " : "REJECTED ") + file
                        + "  furthest token: " + furthest
                        + (p.getError() != null ? "  error: " + p.getError() : ""));
            }
        }
        System.out.printf("%d files, %d not %s as expected, %.1fs%n", files, unexpected,
                expectAccept ? "accepted" : "rejected", (System.nanoTime() - t0) / 1e9);
        System.exit(unexpected == 0 && files > 0 ? 0 : 1);
    }

    /**
     * Replays dumped tokens, keeping the f/t-string mode stack that C's
     * tokenizer keeps (TokenSource's tokenizer-state methods): a mode is
     * pushed by FSTRING_START or TSTRING_START and popped by the matching END.
     */
    static final class DumpTokenSource implements TokenSource {
        private final Iterator<Token> it;
        /** Innermost last: 't' or 'f', upper case if raw. */
        private final StringBuilder modes = new StringBuilder();

        DumpTokenSource(Iterator<Token> it) {
            this.it = it;
        }

        @Override
        public Token next() {
            if (!it.hasNext()) {
                return null;
            }
            Token t = it.next();
            if (t.type == TokenTypes.FSTRING_START || t.type == TokenTypes.TSTRING_START) {
                char kind = t.type == TokenTypes.TSTRING_START ? 't' : 'f';
                boolean raw = t.string.indexOf('r') >= 0 || t.string.indexOf('R') >= 0;
                modes.append(raw ? Character.toUpperCase(kind) : kind);
            } else if ((t.type == TokenTypes.FSTRING_END || t.type == TokenTypes.TSTRING_END)
                    && modes.length() > 0) {
                modes.setLength(modes.length() - 1);
            }
            return t;
        }

        private char mode() {
            return modes.charAt(modes.length() - 1);
        }

        @Override
        public boolean insideFstring() {
            return modes.length() > 0;
        }

        @Override
        public boolean insideTstring() {
            return insideFstring() && Character.toLowerCase(mode()) == 't';
        }

        @Override
        public boolean fstringRaw() {
            return insideFstring() && Character.isUpperCase(mode());
        }
    }

    /** Reverses dump_tokens.py's escaping of backslash, newline and carriage return. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                out.append(d == 'n' ? '\n' : d == 'r' ? '\r' : d);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
