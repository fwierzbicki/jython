package org.python.pegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the token dumps written by parser/src/test/pegen/dump_tokens.py, and replays
 * them to the parser as a {@link TokenSource} that also answers for the
 * tokenizer's state. A stopgap until the Python tokenizer is ported; used by
 * RecognizerSmoke and AstCompare.
 */
public final class TokenDump {

    private TokenDump() {}

    /** One dumped file: its path, source (universal newlines) and tokens. */
    public static final class DumpFile {
        public final String path;
        public final String source;
        public final List<Token> tokens;

        DumpFile(String path, String source, List<Token> tokens) {
            this.path = path;
            this.source = source;
            this.tokens = tokens;
        }

        /**
         * A fresh token source over this file (the parser changes the tokens
         * it reads). execInput is C's exec_input: true for file input, whose
         * last line gets an implicit newline.
         */
        public DumpTokenSource tokenSource(boolean execInput) {
            List<Token> copy = new ArrayList<>(tokens.size());
            for (Token t : tokens) {
                Token c = new Token(t.type, t.string, t.lineno, t.col_offset, t.end_lineno,
                        t.end_col_offset);
                c.metadata = t.metadata;
                copy.add(c);
            }
            return new DumpTokenSource(copy, source, execInput);
        }
    }

    /** Reads a dump: "#FILE path", "#SOURCE text", then token lines and "#META text" lines. */
    public static List<DumpFile> read(String dump) throws IOException {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < TokenTypes.NAMES.length; i++) {
            byName.put(TokenTypes.NAMES[i], i);
        }
        List<String> lines = Files.readAllLines(Paths.get(dump), StandardCharsets.UTF_8);
        List<DumpFile> files = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            String file = lines.get(i++).substring("#FILE ".length());
            String source = unescape(lines.get(i++).substring("#SOURCE ".length()));
            List<Token> toks = new ArrayList<>();
            for (; i < lines.size() && !lines.get(i).startsWith("#FILE "); i++) {
                String line = lines.get(i);
                if (line.startsWith("#META ")) {
                    toks.get(toks.size() - 1).metadata = unescape(line.substring(6));
                    continue;
                }
                // TYPE LINENO COL END_LINENO END_COL STRING
                String[] f = line.split(" ", 6);
                Integer type = byName.get(f[0]);
                if (type == null) {
                    throw new IllegalArgumentException(
                            file + ": unknown token type " + f[0] + " '" + f[5] + "'");
                }
                toks.add(new Token(type, unescape(f[5]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])));
            }
            files.add(new DumpFile(file, source, toks));
        }
        return files;
    }

    /** Reverses dump_tokens.py's escaping of backslash, newline and carriage return. */
    static String unescape(String s) {
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

    /**
     * Replays dumped tokens, and derives from them and the source the
     * tokenizer state C's parser reads: the f/t-string mode stack, the
     * bracket stack (and each token's level), the line and cursor after the
     * last token, and done.
     * After the last token it keeps returning ENDMARKER, as C's tokenizer
     * does.
     */
    public static final class DumpTokenSource implements TokenSource {
        private final List<Token> tokens;
        private final String source;
        private final String[] lines;
        private final boolean execInput;
        private int next;
        private Token last;

        /** Innermost last: 't' or 'f', upper case if raw. */
        private final StringBuilder modes = new StringBuilder();

        /** The open brackets: their characters, lines and byte columns. */
        private final StringBuilder parens = new StringBuilder();
        private final List<int[]> parenPositions = new ArrayList<>();

        DumpTokenSource(List<Token> tokens, String source, boolean execInput) {
            this.tokens = tokens;
            this.source = source;
            this.lines = source.split("\n", -1);
            this.execInput = execInput;
        }

        @Override
        public Token next() {
            if (next == tokens.size()) {
                Token end = tokens.get(tokens.size() - 1);
                return new Token(end.type, end.string, end.lineno, end.col_offset, end.end_lineno,
                        end.end_col_offset);
            }
            Token t = tokens.get(next++);
            last = t;
            switch (t.type) {
                case TokenTypes.FSTRING_START:
                case TokenTypes.TSTRING_START: {
                    char kind = t.type == TokenTypes.TSTRING_START ? 't' : 'f';
                    boolean raw = t.string.indexOf('r') >= 0 || t.string.indexOf('R') >= 0;
                    modes.append(raw ? Character.toUpperCase(kind) : kind);
                    break;
                }
                case TokenTypes.FSTRING_END:
                case TokenTypes.TSTRING_END:
                    if (modes.length() > 0) {
                        modes.setLength(modes.length() - 1);
                    }
                    break;
                case TokenTypes.LPAR:
                case TokenTypes.LSQB:
                case TokenTypes.LBRACE:
                    parens.append(t.string.charAt(0));
                    parenPositions.add(new int[] {t.lineno, t.col_offset});
                    break;
                case TokenTypes.RPAR:
                case TokenTypes.RSQB:
                case TokenTypes.RBRACE:
                    if (parens.length() > 0) {
                        parens.setLength(parens.length() - 1);
                        parenPositions.remove(parenPositions.size() - 1);
                    }
                    break;
                default:
            }
            // C: token->level = tok->level, the brackets open after this token.
            t.level = parens.length();
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

        @Override
        public int done() {
            return last != null && last.type == TokenTypes.ENDMARKER ? ActionHelpers.E_EOF
                    : ActionHelpers.E_OK;
        }

        /** A token's end_lineno is tok->lineno when it was made. */
        @Override
        public int lineno() {
            return last == null ? 0 : last.end_lineno;
        }

        /**
         * tok->cur after the last token: its end (for NEWLINE, after the
         * newline). INDENT and DEDENT have no columns; the cursor is then at
         * the next token on the line, or at the end of the input.
         */
        @Override
        public int cursorColumn() {
            if (last == null) {
                return 0;
            }
            if (last.end_col_offset >= 0) {
                return last.end_col_offset;
            }
            if (atEnd()) {
                // At the end of the input: the end of the last line.
                String line = currentLine();
                return line == null ? 0 : line.getBytes(StandardCharsets.UTF_8).length;
            }
            for (int i = next; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.lineno != last.lineno) {
                    break;
                }
                if (t.col_offset >= 0) {
                    return t.col_offset;
                }
            }
            return 0;
        }

        /** Whether only DEDENTs and ENDMARKER remain (or were last): the input is all read. */
        private boolean atEnd() {
            if (last == null) {
                return false;
            }
            for (int i = next - 1; i < tokens.size(); i++) {
                int type = tokens.get(i).type;
                if (type != TokenTypes.DEDENT && type != TokenTypes.ENDMARKER) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The line the tokenizer is on, with its newline. The last line has
         * one only if the source ends with one, or for exec input, where the
         * tokenizer adds it.
         */
        @Override
        public String currentLine() {
            int lineno = lineno();
            if (lineno < 1) {
                return null;
            }
            if (lineno > lines.length) {
                return "";
            }
            boolean newline = lineno < lines.length || execInput;
            return lines[lineno - 1] + (newline ? "\n" : "");
        }

        /** As C walks tok->str: a line past the end gives the last one. */
        @Override
        public String getLine(int lineno) {
            return lines[Math.max(1, Math.min(lineno, lines.length)) - 1];
        }

        @Override
        public String rest() {
            int lineno = lineno();
            if (lineno < 1) {
                return source;
            }
            if (lineno > lines.length || atEnd()) {
                return "";
            }
            int offset = 0;
            for (int i = 0; i < lineno - 1; i++) {
                offset += lines[i].length() + 1;
            }
            // cursorColumn() is in bytes; count the characters they encode.
            String line = lines[lineno - 1];
            int bytes = cursorColumn();
            int chars = 0;
            while (chars < line.length() && bytes > 0) {
                int cp = line.codePointAt(chars);
                bytes -= cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                chars += Character.charCount(cp);
            }
            offset += chars + Math.max(0, bytes);
            return offset >= source.length() ? "" : source.substring(offset);
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public int level() {
            return parens.length();
        }

        @Override
        public char parenstack(int i) {
            return parens.charAt(i);
        }

        @Override
        public int parenlinenostack(int i) {
            return parenPositions.get(i)[0];
        }

        @Override
        public int parencolstack(int i) {
            return parenPositions.get(i)[1];
        }
    }
}
