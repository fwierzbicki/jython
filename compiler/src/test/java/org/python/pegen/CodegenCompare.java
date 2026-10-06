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
import org.python.pegen.compile.Codegen;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.InstructionSequence;
import org.python.pegen.compile.Opcode;
import org.python.pegen.compile.PyCodeObject;
import org.python.pegen.compile.PyFrozenSet;
import org.python.pegen.compile.PySlice;
import org.python.pegen.compile.PyTuple;

/**
 * The Java half of tests/pegen/compare_codegen.py: parses each file as
 * ast.parse() does, runs codegen on the tree as
 * _testinternalcapi.compiler_codegen does (Compile._PyCompile_CodeGen:
 * preprocess at the optimize level, symtable, codegen) and writes the
 * instruction sequences in the canonical form compare_codegen.py also
 * writes for CPython:
 *
 * <pre>
 * #FILE path
 * #ARGS argcount posonlyargcount kwonlyargcount     (the module's unit)
 * #CONST value                                      (its constants, in index order)
 * #UNIT                                             (a unit, depth first)
 * OPNAME oparg lineno end_lineno col end_col        (oparg None without an argument)
 * #END
 * #WARNING category lineno message                  (warnings codegen's compile issued)
 * </pre>
 *
 * or one #ERROR line (as AstCompare writes it), #UNSUPPORTED with the
 * construct whose port is still to come, or #CRASH.
 *
 * <p>Usage: CodegenCompare [--mode file|single|eval] [--optimize N] LIST OUT,
 * where LIST names the files, one per line.
 */
public class CodegenCompare {

    public static void main(String[] args) throws IOException {
        int startRule = Parser.FILE_INPUT;
        int optimize = 0;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--optimize")) {
                optimize = Integer.parseInt(args[++i]);
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
                    "usage: CodegenCompare [--mode file|single|eval] [--optimize N] LIST OUT");
            System.exit(2);
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(files.get(1))), StandardCharsets.UTF_8))) {
            for (String path : Files.readAllLines(Paths.get(files.get(0)),
                    StandardCharsets.UTF_8)) {
                StringBuilder b = new StringBuilder();
                b.append("#FILE ").append(path).append('\n');
                b.append(compile(Files.readAllBytes(Paths.get(path)), startRule, optimize));
                out.write(b.toString());
            }
        }
    }

    private static String compile(byte[] source, int startRule, int optimize) {
        StringBuilder b = new StringBuilder();
        // ast.parse(source, mode=...)
        Object tree;
        try {
            Parser p = AstCompare.parser(source, startRule, Compile.PyCF_ONLY_AST);
            tree = p.runParser(new GeneratedParser(p));
            if (tree == null) {
                AstCompare.error(p.getError(), b);
                return b.toString();
            }
            Compile._PyCompile_AstPreprocess((mod) tree, "<unknown>",
                    new Compile.PyCompilerFlags(Compile.PyCF_ONLY_AST), 0, true);
        } catch (PythonSyntaxError e) {
            AstCompare.error(e, b);
            return b.toString();
        }
        // _testinternalcapi.compiler_codegen(tree, "<unknown>", optimize, mode)
        List<Parser.ParserWarning> warnings = new ArrayList<>();
        try {
            Compile.CodeGenResult result = Compile._PyCompile_CodeGen((mod) tree, "<unknown>",
                    null, optimize, w -> warnings.add(w));
            b.append("#ARGS ").append(result.argcount).append(' ')
                    .append(result.posonlyargcount).append(' ')
                    .append(result.kwonlyargcount).append('\n');
            for (Object v : result.consts) {
                b.append("#CONST ").append(constant(v)).append('\n');
            }
            unit(result.seq, b);
        } catch (PythonSyntaxError e) {
            b.setLength(0);
            AstCompare.error(e, b);
        } catch (Codegen.Unsupported e) {
            b.setLength(0);
            b.append("#UNSUPPORTED ").append(e.getMessage()).append('\n');
            return b.toString();
        } catch (RuntimeException | StackOverflowError | AssertionError e) {
            b.setLength(0);
            b.append("#CRASH ").append(AstCompare.str(e.toString())).append('\n');
            return b.toString();
        }
        for (Parser.ParserWarning w : warnings) {
            b.append("#WARNING ").append(w.category).append('\t').append(w.lineno)
                    .append('\t').append(AstCompare.str(w.message)).append('\n');
        }
        return b.toString();
    }

    /** A unit's instructions, then its nested units (C: get_instructions, get_nested). */
    private static void unit(InstructionSequence seq, StringBuilder b) {
        seq._PyInstructionSequence_ApplyLabelMap();
        b.append("#UNIT\n");
        for (InstructionSequence._PyInstruction instr : seq.s_instrs) {
            b.append(Opcode.OPNAME[instr.i_opcode]).append(' ');
            if (Opcode.OPCODE_HAS_ARG(instr.i_opcode)) {
                b.append(instr.i_oparg);
            } else {
                b.append("None");
            }
            b.append(' ').append(instr.i_loc.lineno).append(' ').append(instr.i_loc.end_lineno)
                    .append(' ').append(instr.i_loc.col_offset).append(' ')
                    .append(instr.i_loc.end_col_offset).append('\n');
        }
        if (seq.s_nested != null) {
            for (InstructionSequence nested : seq.s_nested) {
                unit(nested, b);
            }
        }
        b.append("#END\n");
    }

    /** A constant's canonical form (as compare_codegen.py's constant()). */
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
            StringBuilder h = new StringBuilder("bytes:");
            for (byte x : ((Bytes) v).toArray()) {
                h.append(String.format("%02x", x & 0xff));
            }
            return h.toString();
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
