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
import java.util.List;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Complex;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.Flowgraph;
import org.python.pegen.compile.InstructionSequence;
import org.python.pegen.compile.Opcode;
import org.python.pegen.compile.PyCodeObject;
import org.python.pegen.compile.PyFrozenSet;
import org.python.pegen.compile.PySlice;
import org.python.pegen.compile.PyTuple;

/**
 * The Java half of tests/pegen/compare_flowgraph.py: compiles each file to
 * instruction sequences as CodegenCompare does, then optimizes every unit
 * as _testinternalcapi.optimize_cfg does (Flowgraph._PyCompile_OptimizeCfg:
 * a fresh const cache, nparams 0, firstlineno 1), and writes:
 *
 * <pre>
 * #FILE path
 * #IN nlocals                     (a unit's inputs, depth first: its number of locals,
 * #INCONST expr                    and its constants as Python expressions)
 * #UNIT                           (a unit's result, depth first)
 * #CONST value                    (its constants after flowgraph, in index order)
 * OPNAME oparg lineno end_lineno col end_col
 * </pre>
 *
 * A unit whose optimization fails one of the two C assertions CPython's
 * optimize_cfg is known to fail (see compare_flowgraph.py) is written as
 * #ABORT and the function's name instead.
 * Or the file is one #ERROR line (as AstCompare writes it), or #CRASH. The #IN lines are
 * what compare_flowgraph.py gives CPython's optimize_cfg (compiler_codegen
 * exposes neither a nested unit's constants nor any unit's number of
 * locals); they aren't compared.
 *
 * <p>Usage: FlowgraphCompare [--mode file|single|eval] [--optimize N] LIST OUT,
 * where LIST names the files, one per line.
 */
public class FlowgraphCompare {

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
                    "usage: FlowgraphCompare [--mode file|single|eval] [--optimize N] LIST OUT");
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
        Compile.CodeGenResult result;
        try {
            result = Compile._PyCompile_CodeGen((mod) tree, "<unknown>", null, optimize,
                    w -> true);
        } catch (PythonSyntaxError e) {
            AstCompare.error(e, b);
            return b.toString();
        } catch (RuntimeException | StackOverflowError | AssertionError e) {
            b.append("#CRASH ").append(AstCompare.str(e.toString())).append('\n');
            return b.toString();
        }
        // Each unit's inputs, then optimize_cfg(seq, consts, nlocals) on each.
        List<InstructionSequence> seqs = new ArrayList<>();
        List<Compile.UnitInputs> inputs = new ArrayList<>();
        units(result.seq, result, seqs, inputs);
        StringBuilder r = new StringBuilder();
        try {
            for (int i = 0; i < seqs.size(); i++) {
                List<Object> consts = new ArrayList<>(inputs.get(i).consts);
                r.append("#UNIT\n");
                InstructionSequence optimized;
                try {
                    optimized = Flowgraph._PyCompile_OptimizeCfg(seqs.get(i), consts,
                            inputs.get(i).nlocals);
                } catch (AssertionError e) {
                    String where = e.getStackTrace()[0].getMethodName();
                    if (!where.equals("load_fast_push_block")
                            && !where.equals("_PyCfg_FromInstructionSequence")) {
                        throw e;
                    }
                    // CPython's debug build aborts here too (see compare_flowgraph.py).
                    r.append("#ABORT ").append(where).append('\n');
                    continue;
                }
                for (Object v : consts) {
                    r.append("#CONST ").append(CodegenCompare.constant(v)).append('\n');
                }
                instructions(optimized, r);
            }
        } catch (RuntimeException | StackOverflowError | AssertionError e) {
            r.setLength(0);
            r.append("#CRASH ").append(AstCompare.str(e.toString())).append('\n');
            StackTraceElement[] trace = e.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 3); i++) {
                r.append("#CRASH at ").append(trace[i]).append('\n');
            }
        }
        for (Compile.UnitInputs in : inputs) {
            b.append("#IN ").append(in.nlocals).append('\n');
            for (Object v : in.consts) {
                b.append("#INCONST ").append(expr(v)).append('\n');
            }
        }
        b.append(r);
        return b.toString();
    }

    /** The units depth first, as compiler_codegen's get_nested() gives them. */
    private static void units(InstructionSequence seq, Compile.CodeGenResult result,
            List<InstructionSequence> seqs, List<Compile.UnitInputs> inputs) {
        seqs.add(seq);
        inputs.add(seq == result.seq ? new Compile.UnitInputs(result.consts, result.nlocals)
                : result.nested.get(seq));
        if (seq.s_nested != null) {
            for (InstructionSequence nested : seq.s_nested) {
                units(nested, result, seqs, inputs);
            }
        }
    }

    private static void instructions(InstructionSequence seq, StringBuilder b) {
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
    }

    private static String hex(byte[] data) {
        StringBuilder h = new StringBuilder();
        for (byte x : data) {
            h.append(String.format("%02x", x & 0xff));
        }
        return h.toString();
    }

    private static String rawbits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    /** A constant as a Python expression compare_flowgraph.py evaluates. */
    static String expr(Object v) {
        if (v == Singleton.None) {
            return "None";
        } else if (v == Singleton.True) {
            return "True";
        } else if (v == Singleton.False) {
            return "False";
        } else if (v == Singleton.Ellipsis) {
            return "...";
        } else if (v instanceof BigInteger) {
            return "(" + v + ")";
        } else if (v instanceof Double) {
            return "F('" + rawbits((Double) v) + "')";
        } else if (v instanceof Complex) {
            Complex z = (Complex) v;
            return "C('" + rawbits(z.real) + "','" + rawbits(z.imag) + "')";
        } else if (v instanceof String) {
            // UTF-16 code units, lone surrogates included
            StringBuilder h = new StringBuilder("S('");
            for (char c : ((String) v).toCharArray()) {
                h.append(String.format("%04x", (int) c));
            }
            return h.append("')").toString();
        } else if (v instanceof Bytes) {
            return "B('" + hex(((Bytes) v).toArray()) + "')";
        } else if (v instanceof PyTuple) {
            StringBuilder t = new StringBuilder("T(");
            for (Object x : ((PyTuple) v).items) {
                t.append(expr(x)).append(',');
            }
            return t.append(')').toString();
        } else if (v instanceof PyFrozenSet) {
            StringBuilder t = new StringBuilder("FS(");
            for (Object x : ((PyFrozenSet) v).items) {
                t.append(expr(x)).append(',');
            }
            return t.append(')').toString();
        } else if (v instanceof PySlice) {
            PySlice s = (PySlice) v;
            return "SL(" + expr(s.start) + "," + expr(s.stop) + "," + expr(s.step) + ")";
        } else if (v instanceof PyCodeObject) {
            PyCodeObject co = (PyCodeObject) v;
            return "CODE(" + expr(co.co_name) + "," + expr(co.co_qualname) + ","
                    + co.co_firstlineno + ")";
        }
        throw new IllegalArgumentException("constant " + v.getClass().getSimpleName());
    }
}
