package org.python.pegen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.Marshal;
import org.python.pegen.compile.Opcode;
import org.python.pegen.compile.PyCodeObject;
import org.python.pegen.compile.PyFrozenSet;
import org.python.pegen.compile.PySlice;
import org.python.pegen.compile.PyTuple;

/**
 * The Java side of tests/pegen/compare_code.py: compiles each file as
 * compile(source, "<unknown>", mode, dont_inherit=True, optimize=N) does
 * (Parser.fromString, then Compile._PyAST_Compile) and writes its code
 * objects in the canonical form compare_code.py writes for CPython's.
 *
 * <p>Usage: CodeCompare [--mode file|single|eval] [--optimize N] [--marshal]
 * LIST OUT, LIST holding one path per line. With --marshal, a code object
 * is written as Marshal.dumps' bytes, in hex, for compare_code.py to load.
 */
public final class CodeCompare {

    private CodeCompare() {}

    public static void main(String[] args) throws IOException {
        int startRule = Parser.FILE_INPUT;
        int optimize = 0;
        boolean marshal = false;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--optimize")) {
                optimize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--marshal")) {
                marshal = true;
            } else if (args[i].equals("--mode")) {
                String mode = args[++i];
                startRule = mode.equals("single") ? Parser.SINGLE_INPUT
                        : mode.equals("eval") ? Parser.EVAL_INPUT : Parser.FILE_INPUT;
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() != 2) {
            System.err.println(
                    "usage: CodeCompare [--mode file|single|eval] [--optimize N] [--marshal]"
                    + " LIST OUT");
            System.exit(2);
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(files.get(1))), StandardCharsets.UTF_8))) {
            for (String path : Files.readAllLines(Paths.get(files.get(0)),
                    StandardCharsets.UTF_8)) {
                StringBuilder b = new StringBuilder();
                b.append("#FILE ").append(path).append('\n');
                b.append(compile(Files.readAllBytes(Paths.get(path)), startRule, optimize,
                        marshal));
                out.write(b.toString());
            }
        }
    }

    private static String compile(byte[] source, int startRule, int optimize,
            boolean marshal) {
        StringBuilder b = new StringBuilder();
        Parser p = null;
        List<Parser.ParserWarning> warnings = new ArrayList<>();
        try {
            p = AstCompare.parser(source, startRule, 0);
            Object tree = p.runParser(new GeneratedParser(p));
            if (tree == null) {
                AstCompare.error(p.getError(), b);
            } else {
                PyCodeObject co = Compile._PyAST_Compile((mod) tree, "<unknown>",
                        new Compile.PyCompilerFlags(), optimize, null, w -> warnings.add(w));
                if (!marshal) {
                    code(co, b);
                } else {
                    try {
                        byte[] data = Marshal.dumps(co);
                        b.append("#MARSHAL ").append(hex(data)).append('\n');
                    } catch (Marshal.MarshalError e) {
                        b.append("#MARSHAL-ERROR\n");
                    }
                }
            }
        } catch (PythonSyntaxError e) {
            b.setLength(0);
            AstCompare.error(e, b);
        } catch (RuntimeException | StackOverflowError | AssertionError e) {
            b.setLength(0);
            b.append("#CRASH ").append(AstCompare.str(e.toString())).append('\n');
            return b.toString();
        }
        List<Parser.ParserWarning> all = new ArrayList<>();
        if (p != null) {
            all.addAll(p.warnings);
        }
        all.addAll(warnings);
        for (Parser.ParserWarning w : all) {
            b.append("#WARNING ").append(w.category).append('\t').append(w.lineno)
                    .append('\t').append(AstCompare.str(w.message)).append('\n');
        }
        return b.toString();
    }

    /** co and the code objects in its constants, depth first (as compare_code.py's code()). */
    private static void code(PyCodeObject top, StringBuilder b) {
        final PyCodeObject end = null;
        List<PyCodeObject> work = new ArrayList<>();
        work.add(top);
        while (!work.isEmpty()) {
            PyCodeObject co = work.remove(work.size() - 1);
            if (co == end) {
                b.append("#END\n");
                continue;
            }
            b.append("#CODE ").append(AstCompare.str(co.co_name)).append(' ')
                    .append(AstCompare.str(co.co_qualname)).append(' ')
                    .append(co.co_firstlineno).append('\n');
            b.append("#ARGS ").append(co.co_argcount).append(' ').append(co.co_posonlyargcount)
                    .append(' ').append(co.co_kwonlyargcount).append('\n');
            b.append("#STACKSIZE ").append(co.co_stacksize).append('\n');
            b.append("#FLAGS ").append(Integer.toHexString(co.co_flags)).append('\n');
            b.append("#FILENAME ").append(AstCompare.str(co.co_filename)).append('\n');
            for (Object name : co.co_names.items) {
                b.append("#NAME ").append(AstCompare.str((String) name)).append('\n');
            }
            for (int i = 0; i < co.co_nlocalsplus; i++) {
                b.append("#LOCAL ").append(AstCompare.str((String) co.co_localsplusnames.items[i]))
                        .append(' ').append(String.format("%02x",
                                PyCodeObject._PyLocals_GetKind(co.co_localspluskinds, i)))
                        .append('\n');
            }
            for (Object v : co.co_consts.items) {
                b.append("#CONST ").append(constant(v)).append('\n');
            }
            List<String> positions = positions(co);
            byte[] raw = co.co_code;
            for (int i = 0; i < raw.length; i += 2) {
                int op = raw[i] & 0xff;
                String name = op < Opcode.OPNAME.length && Opcode.OPNAME[op] != null
                        ? Opcode.OPNAME[op] : "<" + op + ">";
                b.append(name).append(' ').append(raw[i + 1] & 0xff).append(' ')
                        .append(i / 2 < positions.size() ? positions.get(i / 2) : "? ? ? ?")
                        .append('\n');
            }
            b.append("#LINETABLE ").append(hex(co.co_linetable)).append('\n');
            b.append("#EXCEPTIONTABLE ").append(hex(co.co_exceptiontable)).append('\n');
            handlers(co.co_exceptiontable, b);
            work.add(end);
            List<PyCodeObject> nested = new ArrayList<>();
            for (Object v : co.co_consts.items) {
                if (v instanceof PyCodeObject) {
                    nested.add((PyCodeObject) v);
                }
            }
            Collections.reverse(nested);
            work.addAll(nested);
        }
    }

    private static String hex(byte[] a) {
        StringBuilder h = new StringBuilder();
        for (byte x : a) {
            h.append(String.format("%02x", x & 0xff));
        }
        return h.toString();
    }

    /**
     * co_positions(): each code unit's (lineno, end_lineno, col, end_col),
     * decoded from co_linetable as codeobject.c's advance_with_locations
     * does; -1 is None.
     */
    private static List<String> positions(PyCodeObject co) {
        List<String> out = new ArrayList<>();
        byte[] t = co.co_linetable;
        int[] pos = {0};
        int computed_line = co.co_firstlineno;
        while (pos[0] < t.length) {
            int first_byte = t[pos[0]++] & 0xff;
            int code = (first_byte >> 3) & 15;
            int length = (first_byte & 7) + 1;
            int line, endline, column, endcolumn;
            switch (code) {
                case 15: // PY_CODE_LOCATION_INFO_NONE
                    line = endline = -1;
                    column = endcolumn = -1;
                    break;
                case 14: // PY_CODE_LOCATION_INFO_LONG
                    computed_line += read_signed_varint(t, pos);
                    line = computed_line;
                    endline = line + read_varint(t, pos);
                    column = read_varint(t, pos) - 1;
                    endcolumn = read_varint(t, pos) - 1;
                    break;
                case 13: // PY_CODE_LOCATION_INFO_NO_COLUMNS
                    computed_line += read_signed_varint(t, pos);
                    endline = line = computed_line;
                    column = endcolumn = -1;
                    break;
                case 10:
                case 11:
                case 12: // one line form
                    computed_line += code - 10;
                    endline = line = computed_line;
                    column = t[pos[0]++] & 0xff;
                    endcolumn = t[pos[0]++] & 0xff;
                    break;
                default: { // short forms
                    int second_byte = t[pos[0]++] & 0xff;
                    endline = line = computed_line;
                    column = code << 3 | (second_byte >> 4);
                    endcolumn = column + (second_byte & 15);
                }
            }
            String p = opt(line) + " " + opt(endline) + " " + opt(column) + " " + opt(endcolumn);
            for (int i = 0; i < length; i++) {
                out.add(p);
            }
        }
        return out;
    }

    private static String opt(int v) {
        return v == -1 ? "None" : String.valueOf(v);
    }

    private static int read_varint(byte[] t, int[] pos) {
        int read = t[pos[0]++] & 0xff;
        int val = read & 63;
        int shift = 0;
        while ((read & 64) != 0) {
            read = t[pos[0]++] & 0xff;
            shift += 6;
            val |= (read & 63) << shift;
        }
        return val;
    }

    private static int read_signed_varint(byte[] t, int[] pos) {
        int uval = read_varint(t, pos);
        if ((uval & 1) != 0) {
            return -(uval >>> 1);
        } else {
            return uval >>> 1;
        }
    }

    /** dis._parse_exception_table's entries (offsets in bytes). */
    private static void handlers(byte[] t, StringBuilder b) {
        int[] pos = {0};
        while (pos[0] < t.length) {
            int start = parse_varint(t, pos) * 2;
            if (pos[0] >= t.length) {
                return;
            }
            int length = parse_varint(t, pos) * 2;
            int end = start + length;
            int target = parse_varint(t, pos) * 2;
            int dl = parse_varint(t, pos);
            int depth = dl >> 1;
            int lasti = dl & 1;
            b.append("#HANDLER ").append(start).append(' ').append(end).append(' ')
                    .append(target).append(' ').append(depth).append(' ').append(lasti)
                    .append('\n');
        }
    }

    /** dis._parse_varint: big-endian groups of 6 bits. */
    private static int parse_varint(byte[] t, int[] pos) {
        int b = t[pos[0]++] & 0xff;
        int val = b & 63;
        while ((b & 64) != 0) {
            val <<= 6;
            b = t[pos[0]++] & 0xff;
            val |= b & 63;
        }
        return val;
    }

    /** A constant's canonical form (as compare_code.py's constant()). */
    static String constant(Object v) {
        if (v instanceof Singleton) {
            return v.toString();
        } else if (v instanceof BigInteger) {
            return "int:" + v;
        } else if (v instanceof Double) {
            return "float:" + AstCompare.bits((Double) v);
        } else if (v instanceof Complex) {
            Complex z = (Complex) v;
            return "complex:" + AstCompare.bits(z.real) + "," + AstCompare.bits(z.imag);
        } else if (v instanceof String) {
            return "str:" + AstCompare.str((String) v);
        } else if (v instanceof Bytes) {
            return "bytes:" + hex(((Bytes) v).toArray());
        } else if (v instanceof PyTuple) {
            List<String> items = new ArrayList<>();
            for (Object x : ((PyTuple) v).items) {
                items.add(constant(x));
            }
            return "tuple(" + String.join(",", items) + ")";
        } else if (v instanceof PyFrozenSet) {
            List<String> items = new ArrayList<>();
            for (Object x : ((PyFrozenSet) v).items) {
                items.add(constant(x));
            }
            Collections.sort(items);
            return "frozenset(" + String.join(",", items) + ")";
        } else if (v instanceof PySlice) {
            PySlice s = (PySlice) v;
            return "slice(" + constant(s.start) + "," + constant(s.stop) + ","
                    + constant(s.step) + ")";
        } else if (v instanceof PyCodeObject) {
            PyCodeObject co = (PyCodeObject) v;
            return "code:" + AstCompare.str(co.co_name) + "," + AstCompare.str(co.co_qualname)
                    + "," + co.co_firstlineno;
        }
        return "?" + v.getClass().getSimpleName();
    }
}
