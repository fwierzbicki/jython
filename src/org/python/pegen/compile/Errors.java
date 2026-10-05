package org.python.pegen.compile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

import org.python.pegen.Parser;
import org.python.pegen.PythonSyntaxError;
import org.python.pegen.lexer.Tokenizer;

/**
 * The parts of CPython's Python/errors.c the compiler stages use to locate a
 * SyntaxError, under their C names.
 *
 * <p>C sets the exception first (PyErr_SetString) and then adds the location
 * to it. Here the caller builds the exception without a location and
 * PyErr_RangedSyntaxLocationObject returns it with one, for the caller to
 * throw.
 */
public final class Errors {

    private Errors() {}

    /**
     * PyErr_RangedSyntaxLocationObject (via PyErr_SyntaxLocationObjectEx):
     * exc with lineno, offset, end_lineno and end_offset set as given (C's
     * offsets here are 1-based UTF-8 byte offsets, set unconverted), and
     * text read from the file filename names, if it can be.
     */
    public static PythonSyntaxError PyErr_RangedSyntaxLocationObject(PythonSyntaxError exc,
            String filename, int lineno, int col_offset, int end_lineno, int end_col_offset) {
        String text = filename != null ? PyErr_ProgramTextObject(filename, lineno) : null;
        return new PythonSyntaxError(exc.type, exc.msg, lineno, col_offset, text, end_lineno,
                end_col_offset);
    }

    /**
     * _PyErr_RaiseSyntaxError: the SyntaxError msg at the given location
     * (1-based columns), with text read from the file filename names.
     */
    public static PythonSyntaxError _PyErr_RaiseSyntaxError(String msg, String filename,
            int lineno, int col_offset, int end_lineno, int end_col_offset) {
        return PyErr_RangedSyntaxLocationObject(new PythonSyntaxError("SyntaxError", msg),
                filename, lineno, col_offset, end_lineno, end_col_offset);
    }

    /**
     * _PyErr_EmitSyntaxWarning: issues a SyntaxWarning to warnings (C:
     * PyErr_WarnExplicitObject). If the handler makes it an error, throws a
     * SyntaxError at the given location (1-based columns) instead.
     */
    public static void _PyErr_EmitSyntaxWarning(Parser.WarningHandler warnings, String msg,
            String filename, int lineno, int col_offset, int end_lineno, int end_col_offset,
            String module) {
        if (!warnings.warn(new Parser.ParserWarning("SyntaxWarning", msg, filename, lineno,
                module))) {
            /* Replace the SyntaxWarning exception with a SyntaxError
               to get a more accurate error report */
            throw _PyErr_RaiseSyntaxError(msg, filename, lineno, col_offset, end_lineno,
                    end_col_offset);
        }
    }

    /** C: the size of err_programtext's linebuf. */
    private static final int LINEBUF_SIZE = 1000;

    /**
     * PyErr_ProgramTextObject (_PyErr_ProgramDecodedTextObject with no
     * encoding): line lineno of the file filename, with its newline, or null
     * if there's no such file or line.
     *
     * <p>The line is decoded with the encoding a BOM or coding cookie
     * declares (_PyTokenizer_FindEncodingFilename), else UTF-8, with
     * "replace".
     */
    public static String PyErr_ProgramTextObject(String filename, int lineno) {
        if (filename == null || lineno <= 0) {
            return null;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(Paths.get(filename));
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
        String encoding = Tokenizer.findEncoding(data);
        if (encoding == null) {
            encoding = "utf-8";
        }
        return err_programtext(data, lineno, encoding);
    }

    /**
     * err_programtext, over the file's bytes. Like C, it reads with
     * _Py_UniversalNewlineFgetsWithSize into a 1000-byte buffer, so a line
     * longer than that comes back as its last piece.
     */
    private static String err_programtext(byte[] fp, int lineno, String encoding) {
        int[] pos = {0};
        ByteArrayOutputStream linebuf = null;
        for (int i = 0; i < lineno;) {
            linebuf = _Py_UniversalNewlineFgetsWithSize(fp, pos, LINEBUF_SIZE);
            if (linebuf == null) {
                /* Error or EOF. */
                return null;
            }
            /* fgets read *something*; if it didn't fill the
               whole buffer, it must have found a newline
               or hit the end of the file; if the last character is \n,
               it obviously found a newline; else we haven't
               yet seen a newline, so must continue */
            byte[] got = linebuf.toByteArray();
            if (i + 1 < lineno && got.length == LINEBUF_SIZE - 1
                    && got[LINEBUF_SIZE - 2] != '\n') {
                continue;
            }
            i++;
        }
        byte[] line = linebuf.toByteArray();
        int start = 0;
        /* Skip BOM. */
        if (lineno == 1 && line.length >= 3 && (line[0] & 0xff) == 0xef
                && (line[1] & 0xff) == 0xbb && (line[2] & 0xff) == 0xbf) {
            start = 3;
        }
        // PyUnicode_Decode(line, line_size, encoding, "replace")
        try {
            return new String(line, start, line.length - start, Charset.forName(encoding));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * _Py_UniversalNewlineFgetsWithSize: reads at most n - 1 bytes from
     * stream at pos[0], up to and including a newline; \r and \r\n read as
     * \n. Null at the end of the stream.
     */
    private static ByteArrayOutputStream _Py_UniversalNewlineFgetsWithSize(byte[] stream,
            int[] pos, int n) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (--n > 0 && pos[0] < stream.length) {
            int c = stream[pos[0]++];
            if (c == '\r') {
                // A \r is translated into a \n, and we skip an adjacent \n, if any.
                if (pos[0] < stream.length && stream[pos[0]] == '\n') {
                    pos[0]++;
                }
                c = '\n';
            }
            buf.write(c);
            if (c == '\n') {
                break;
            }
        }
        return buf.size() == 0 ? null : buf;
    }
}
