package org.python.pegen.compile;

import static org.python.pegen.compile.Compile.*;
import static org.python.pegen.compile.InstructionSequence.IS_JUMP_TARGET_LABEL;
import static org.python.pegen.compile.InstructionSequence.NO_LABEL;
import static org.python.pegen.compile.InstructionSequence.SAME_JUMP_TARGET_LABEL;
import static org.python.pegen.compile.Opcode.*;
import static org.python.pegen.compile.OpcodeUtils.*;
import static org.python.pegen.compile.SourceLocation.NEXT_LOCATION;
import static org.python.pegen.compile.SourceLocation.NO_LOCATION;
import static org.python.pegen.compile.Symtable.CELL;
import static org.python.pegen.compile.Symtable.DEF_IMPORT;
import static org.python.pegen.compile.Symtable.DEF_LOCAL;
import static org.python.pegen.compile.Symtable.DEF_NONLOCAL;
import static org.python.pegen.compile.Symtable.FREE;
import static org.python.pegen.compile.Symtable.GLOBAL_IMPLICIT;
import static org.python.pegen.compile.Symtable.SYMBOL_TO_SCOPE;
import static org.python.pegen.compile.Symtable._PyST_GetScope;
import static org.python.pegen.compile.Symtable._PyST_GetSymbol;
import static org.python.pegen.compile.Symtable._PyST_IsFunctionLike;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.python.pegen.ast.AnnAssign;
import org.python.pegen.ast.Assert;
import org.python.pegen.ast.Assign;
import org.python.pegen.ast.AsyncFor;
import org.python.pegen.ast.AsyncFunctionDef;
import org.python.pegen.ast.AsyncWith;
import org.python.pegen.ast.Attribute;
import org.python.pegen.ast.AugAssign;
import org.python.pegen.ast.Await;
import org.python.pegen.ast.BinOp;
import org.python.pegen.ast.BoolOp;
import org.python.pegen.ast.Call;
import org.python.pegen.ast.ClassDef;
import org.python.pegen.ast.Compare;
import org.python.pegen.ast.Constant;
import org.python.pegen.ast.Delete;
import org.python.pegen.ast.Dict;
import org.python.pegen.ast.DictComp;
import org.python.pegen.ast.ExceptHandler;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.For;
import org.python.pegen.ast.FormattedValue;
import org.python.pegen.ast.FunctionDef;
import org.python.pegen.ast.GeneratorExp;
import org.python.pegen.ast.If;
import org.python.pegen.ast.IfExp;
import org.python.pegen.ast.Import;
import org.python.pegen.ast.ImportFrom;
import org.python.pegen.ast.Interpolation;
import org.python.pegen.ast.JoinedStr;
import org.python.pegen.ast.Lambda;
import org.python.pegen.ast.ListComp;
import org.python.pegen.ast.Match;
import org.python.pegen.ast.MatchAs;
import org.python.pegen.ast.MatchClass;
import org.python.pegen.ast.MatchMapping;
import org.python.pegen.ast.MatchOr;
import org.python.pegen.ast.MatchSequence;
import org.python.pegen.ast.MatchSingleton;
import org.python.pegen.ast.MatchStar;
import org.python.pegen.ast.MatchValue;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.NamedExpr;
import org.python.pegen.ast.ParamSpec;
import org.python.pegen.ast.Raise;
import org.python.pegen.ast.Return;
import org.python.pegen.ast.SetComp;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.Slice;
import org.python.pegen.ast.Starred;
import org.python.pegen.ast.Subscript;
import org.python.pegen.ast.TemplateStr;
import org.python.pegen.ast.Try;
import org.python.pegen.ast.TryStar;
import org.python.pegen.ast.Tuple;
import org.python.pegen.ast.TypeAlias;
import org.python.pegen.ast.TypeVar;
import org.python.pegen.ast.TypeVarTuple;
import org.python.pegen.ast.UnaryOp;
import org.python.pegen.ast.While;
import org.python.pegen.ast.With;
import org.python.pegen.ast.Yield;
import org.python.pegen.ast.YieldFrom;
import org.python.pegen.ast.alias;
import org.python.pegen.ast.arg;
import org.python.pegen.ast.arguments;
import org.python.pegen.ast.cmpopType;
import org.python.pegen.ast.comprehension;
import org.python.pegen.ast.expr_contextType;
import org.python.pegen.ast.keyword;
import org.python.pegen.ast.match_case;
import org.python.pegen.ast.operatorType;
import org.python.pegen.ast.unaryopType;
import org.python.pegen.ast.withitem;
import org.python.pegen.ast.base.excepthandler;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.pattern;
import org.python.pegen.ast.base.stmt;
import org.python.pegen.ast.base.type_param;
import org.python.pegen.compile.Compile._PyCompile_CodeUnitMetadata;
import org.python.pegen.compile.Compile._PyCompile_FBlockInfo;
import org.python.pegen.compile.Compile._PyCompile_InlinedComprehensionState;
import org.python.pegen.compile.InstructionSequence._PyJumpTargetLabel;
import org.python.pegen.compile.Symtable.BlockKey;
import org.python.pegen.compile.Symtable.PySTEntryObject;
import org.python.pegen.compile.Symtable._Py_block_ty;

/**
 * A port of Python/codegen.c, the compiler's code generation stage, which
 * produces a sequence of pseudo-instructions from an AST. The primary entry
 * points are _PyCodegen_Module() for modules, and _PyCodegen_Expression()
 * for expressions.
 *
 * <p>C's names and order are kept. C's macros are methods of the same
 * names. A function whose only result is SUCCESS or ERROR returns void and
 * throws the SyntaxError C raises; the RETURN_IF_ERROR_IN_SCOPE variants
 * exit the scope before returning ERROR, which matters only for a compile
 * that goes on, so here they are the plain ones. A {@code location *} is a
 * one-element array.
 */
public final class Codegen {

    private Codegen() {}

    private static final int COMP_GENEXP   = 0;
    private static final int COMP_LISTCOMP = 1;
    private static final int COMP_SETCOMP  = 2;
    private static final int COMP_DICTCOMP = 3;

    /** A construct whose port is still to come; the comparison counts it apart. */
    public static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unsupported(String what) {
            super(what);
        }
    }

    private static InstructionSequence INSTR_SEQUENCE(Compile c) {
        return c._PyCompile_InstrSequence();
    }

    private static int FUTURE_FEATURES(Compile c) {
        return c._PyCompile_FutureFeatures();
    }

    private static Symtable SYMTABLE(Compile c) {
        return c._PyCompile_Symtable();
    }

    private static PySTEntryObject SYMTABLE_ENTRY(Compile c) {
        return c._PyCompile_SymtableEntry();
    }

    private static int OPTIMIZATION_LEVEL(Compile c) {
        return c._PyCompile_OptimizationLevel();
    }

    private static boolean IS_INTERACTIVE_TOP_LEVEL(Compile c) {
        return c._PyCompile_IsInteractiveTopLevel();
    }

    private static int SCOPE_TYPE(Compile c) {
        return c._PyCompile_ScopeType();
    }

    private static String QUALNAME(Compile c) {
        return c._PyCompile_Qualname();
    }

    private static _PyCompile_CodeUnitMetadata METADATA(Compile c) {
        return c._PyCompile_Metadata();
    }

    private static SourceLocation LOCATION(int lno, int end_lno, int col, int end_col) {
        return new SourceLocation(lno, end_lno, col, end_col);
    }

    private static SourceLocation LOC(org.python.pegen.ast.Located x) {
        return SourceLocation.SRC_LOCATION_FROM_AST(x);
    }

    /** C: asdl_seq_LEN, which is 0 for NULL. */
    private static int LEN(List<?> seq) {
        return seq == null ? 0 : seq.size();
    }

    private static _PyJumpTargetLabel NEW_JUMP_TARGET_LABEL(Compile c) {
        return INSTR_SEQUENCE(c)._PyInstructionSequence_NewLabel();
    }

    private static void USE_LABEL(Compile c, _PyJumpTargetLabel lbl) {
        INSTR_SEQUENCE(c)._PyInstructionSequence_UseLabel(lbl.id);
    }

    private static int compare_masks(int op) {
        switch (op) {
            case Py_LT: return COMPARISON_LESS_THAN;
            case Py_LE: return COMPARISON_LESS_THAN | COMPARISON_EQUALS;
            case Py_EQ: return COMPARISON_EQUALS;
            case Py_NE: return COMPARISON_NOT_EQUALS;
            case Py_GT: return COMPARISON_GREATER_THAN;
            case Py_GE: return COMPARISON_GREATER_THAN | COMPARISON_EQUALS;
            default: throw new IllegalArgumentException();
        }
    }

    /** pattern_context */
    private static final class pattern_context {
        // A list of strings corresponding to name captures. It is used to track:
        // - Repeated name assignments in the same pattern.
        // - Different name assignments in alternatives.
        // - The order of name assignments in alternatives.
        List<String> stores;
        // If 0, any name captures against our subject will raise.
        boolean allow_irrefutable;
        // An array of blocks to jump to on failure. Jumping to fail_pop[i] will pop
        // i items off of the stack. The end result looks like this (with each block
        // falling through to the next):
        // fail_pop[4]: POP_TOP
        // fail_pop[3]: POP_TOP
        // fail_pop[2]: POP_TOP
        // fail_pop[1]: POP_TOP
        // fail_pop[0]: NOP
        _PyJumpTargetLabel[] fail_pop;
        // The current length of fail_pop.
        int fail_pop_size;
        // The number of items on top of the stack that need to *stay* on top of the
        // stack. Variable captures go beneath these. All of them will be popped on
        // failure.
        int on_top;

        pattern_context copy() {
            pattern_context pc = new pattern_context();
            pc.stores = stores;
            pc.allow_irrefutable = allow_irrefutable;
            pc.fail_pop = fail_pop;
            pc.fail_pop_size = fail_pop_size;
            pc.on_top = on_top;
            return pc;
        }

        void set(pattern_context o) {
            stores = o.stores;
            allow_irrefutable = o.allow_irrefutable;
            fail_pop = o.fail_pop;
            fail_pop_size = o.fail_pop_size;
            on_top = o.on_top;
        }
    }

    /* IterStackPosition */
    private static final int ITERABLE_IN_LOCAL = 0;
    private static final int ITERABLE_ON_STACK = 1;
    private static final int ITERATOR_ON_STACK = 2;

    /* Add an opcode with an integer argument */
    private static void codegen_addop_i(InstructionSequence seq, int opcode, int oparg,
            SourceLocation loc) {
        /* oparg value is unsigned, but a signed C int is usually used to store
           it in the C code (like Python/ceval.c).

           Limit to 32-bit signed C int (rather than INT_MAX) for portability.

           The argument of a concrete bytecode instruction is limited to 8-bit.
           EXTENDED_ARG is used for 16, 24, and 32-bit arguments. */

        assert !IS_ASSEMBLER_OPCODE(opcode);
        seq._PyInstructionSequence_Addop(opcode, oparg, loc);
    }

    private static void ADDOP_I(Compile c, SourceLocation loc, int op, int o) {
        codegen_addop_i(INSTR_SEQUENCE(c), op, o, loc);
    }

    private static void codegen_addop_noarg(InstructionSequence seq, int opcode,
            SourceLocation loc) {
        assert !OPCODE_HAS_ARG(opcode);
        assert !IS_ASSEMBLER_OPCODE(opcode);
        seq._PyInstructionSequence_Addop(opcode, 0, loc);
    }

    private static void ADDOP(Compile c, SourceLocation loc, int op) {
        codegen_addop_noarg(INSTR_SEQUENCE(c), op, loc);
    }

    private static void codegen_addop_load_const(Compile c, SourceLocation loc, Object o) {
        int arg = c._PyCompile_AddConst(o);
        ADDOP_I(c, loc, LOAD_CONST, arg);
    }

    private static void ADDOP_LOAD_CONST(Compile c, SourceLocation loc, Object o) {
        codegen_addop_load_const(c, loc, o);
    }

    private static void codegen_addop_o(Compile c, SourceLocation loc, int opcode,
            Map<Object, Integer> dict, Object o) {
        int arg = _PyCompile_DictAddObj(dict, o);
        ADDOP_I(c, loc, opcode, arg);
    }

    /** C: ADDOP_N, with the dict (C: METADATA(C)->u_ ## TYPE) passed in. */
    private static void ADDOP_N(Compile c, SourceLocation loc, int op, Object o,
            Map<Object, Integer> dict) {
        assert !OPCODE_HAS_CONST(op); /* use ADDOP_LOAD_CONST_NEW */
        codegen_addop_o(c, loc, op, dict, o);
    }

    private static final int LOAD_METHOD = -1;
    private static final int LOAD_SUPER_METHOD = -2;
    private static final int LOAD_ZERO_SUPER_ATTR = -3;
    private static final int LOAD_ZERO_SUPER_METHOD = -4;

    private static void codegen_addop_name_custom(Compile c, SourceLocation loc, int opcode,
            Map<Object, Integer> dict, String o, int shift, int low) {
        String mangled = c._PyCompile_MaybeMangle(o);
        int arg = _PyCompile_DictAddObj(dict, mangled);
        ADDOP_I(c, loc, opcode, (arg << shift) | low);
    }

    private static void codegen_addop_name(Compile c, SourceLocation loc, int opcode,
            Map<Object, Integer> dict, String o) {
        int shift = 0, low = 0;
        if (opcode == LOAD_ATTR) {
            shift = 1;
        }
        if (opcode == LOAD_METHOD) {
            opcode = LOAD_ATTR;
            shift = 1;
            low = 1;
        }
        if (opcode == LOAD_SUPER_ATTR) {
            shift = 2;
            low = 2;
        }
        if (opcode == LOAD_SUPER_METHOD) {
            opcode = LOAD_SUPER_ATTR;
            shift = 2;
            low = 3;
        }
        if (opcode == LOAD_ZERO_SUPER_ATTR) {
            opcode = LOAD_SUPER_ATTR;
            shift = 2;
        }
        if (opcode == LOAD_ZERO_SUPER_METHOD) {
            opcode = LOAD_SUPER_ATTR;
            shift = 2;
            low = 1;
        }
        codegen_addop_name_custom(c, loc, opcode, dict, o, shift, low);
    }

    private static void ADDOP_NAME(Compile c, SourceLocation loc, int op, String o,
            Map<Object, Integer> dict) {
        codegen_addop_name(c, loc, op, dict, o);
    }

    private static void ADDOP_NAME_CUSTOM(Compile c, SourceLocation loc, int op, String o,
            Map<Object, Integer> dict, int shift, int low) {
        codegen_addop_name_custom(c, loc, op, dict, o, shift, low);
    }

    private static void codegen_addop_j(InstructionSequence seq, SourceLocation loc,
            int opcode, _PyJumpTargetLabel target) {
        assert IS_JUMP_TARGET_LABEL(target);
        assert HAS_TARGET(opcode);
        assert !IS_ASSEMBLER_OPCODE(opcode);
        seq._PyInstructionSequence_Addop(opcode, target.id, loc);
    }

    private static void ADDOP_JUMP(Compile c, SourceLocation loc, int op,
            _PyJumpTargetLabel o) {
        codegen_addop_j(INSTR_SEQUENCE(c), loc, op, o);
    }

    private static void ADDOP_COMPARE(Compile c, SourceLocation loc, cmpopType cmp) {
        codegen_addcompare(c, loc, cmp);
    }

    private static void ADDOP_BINARY(Compile c, SourceLocation loc, operatorType binop) {
        addop_binary(c, loc, binop, false);
    }

    private static void ADDOP_INPLACE(Compile c, SourceLocation loc, operatorType binop) {
        addop_binary(c, loc, binop, true);
    }

    private static void ADD_YIELD_FROM(Compile c, SourceLocation loc, boolean await) {
        codegen_add_yield_from(c, loc, await);
    }

    private static void POP_EXCEPT_AND_RERAISE(Compile c, SourceLocation loc) {
        codegen_pop_except_and_reraise(c, loc);
    }

    private static void ADDOP_YIELD(Compile c, SourceLocation loc) {
        codegen_addop_yield(c, loc);
    }

    /* VISIT_SEQ for statements and expressions */

    private static void VISIT_SEQ_stmt(Compile c, List<stmt> seq) {
        for (int _i = 0; _i < LEN(seq); _i++) {
            codegen_visit_stmt(c, seq.get(_i));
        }
    }

    private static void VISIT_SEQ_expr(Compile c, List<expr> seq) {
        for (int _i = 0; _i < LEN(seq); _i++) {
            codegen_visit_expr(c, seq.get(_i));
        }
    }

    private static void VISIT_SEQ_keyword(Compile c, List<keyword> seq) {
        for (int _i = 0; _i < LEN(seq); _i++) {
            codegen_visit_keyword(c, seq.get(_i));
        }
    }

    private static void codegen_call_exit_with_nones(Compile c, SourceLocation loc) {
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADDOP_I(c, loc, CALL, 3);
    }

    private static void codegen_add_yield_from(Compile c, SourceLocation loc, boolean await) {
        _PyJumpTargetLabel send = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel fail = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);

        USE_LABEL(c, send);
        ADDOP_JUMP(c, loc, SEND, exit);
        // Set up a virtual try/except to handle when StopIteration is raised during
        // a close or throw call. The only way YIELD_VALUE raises if they do!
        ADDOP_JUMP(c, loc, SETUP_FINALLY, fail);
        ADDOP_I(c, loc, YIELD_VALUE, 1);
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        ADDOP_I(c, loc, RESUME, await ? RESUME_AFTER_AWAIT : RESUME_AFTER_YIELD_FROM);
        ADDOP_JUMP(c, loc, JUMP_NO_INTERRUPT, send);

        USE_LABEL(c, fail);
        ADDOP(c, loc, CLEANUP_THROW);

        USE_LABEL(c, exit);
        ADDOP(c, loc, END_SEND);
    }

    private static void codegen_pop_except_and_reraise(Compile c, SourceLocation loc) {
        /* Stack contents
         * [exc_info, lasti, exc]            COPY        3
         * [exc_info, lasti, exc, exc_info]  POP_EXCEPT
         * [exc_info, lasti, exc]            RERAISE      1
         * (exception_unwind clears the stack)
         */

        ADDOP_I(c, loc, COPY, 3);
        ADDOP(c, loc, POP_EXCEPT);
        ADDOP_I(c, loc, RERAISE, 1);
    }

    /* Unwind a frame block.  If preserve_tos is true, the TOS before
     * popping the blocks will be restored afterwards, unless another
     * return, break or continue is found. In which case, the TOS will
     * be popped.
     */
    @SuppressWarnings("unchecked")
    private static void codegen_unwind_fblock(Compile c, SourceLocation[] ploc,
            _PyCompile_FBlockInfo info, boolean preserve_tos) {
        switch (info.fb_type) {
            case COMPILE_FBLOCK_WHILE_LOOP:
            case COMPILE_FBLOCK_EXCEPTION_HANDLER:
            case COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER:
            case COMPILE_FBLOCK_ASYNC_COMPREHENSION_GENERATOR:
            case COMPILE_FBLOCK_STOP_ITERATION:
                return;

            case COMPILE_FBLOCK_FOR_LOOP:
                /* Pop the iterator */
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 3);
                }
                ADDOP(c, ploc[0], POP_TOP);
                ADDOP(c, ploc[0], POP_TOP);
                return;

            case COMPILE_FBLOCK_ASYNC_FOR_LOOP:
                /* Pop the iterator */
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                ADDOP(c, ploc[0], POP_TOP);
                return;

            case COMPILE_FBLOCK_TRY_EXCEPT:
                ADDOP(c, ploc[0], POP_BLOCK);
                return;

            case COMPILE_FBLOCK_FINALLY_TRY:
                /* This POP_BLOCK gets the line number of the unwinding statement */
                ADDOP(c, ploc[0], POP_BLOCK);
                if (preserve_tos) {
                    c._PyCompile_PushFBlock(ploc[0], COMPILE_FBLOCK_POP_VALUE,
                                            NO_LABEL, NO_LABEL, null);
                }
                /* Emit the finally block */
                VISIT_SEQ_stmt(c, (List<stmt>) info.fb_datum);
                if (preserve_tos) {
                    c._PyCompile_PopFBlock(COMPILE_FBLOCK_POP_VALUE, NO_LABEL);
                }
                /* The finally block should appear to execute after the
                 * statement causing the unwinding, so make the unwinding
                 * instruction artificial */
                ploc[0] = NO_LOCATION;
                return;

            case COMPILE_FBLOCK_FINALLY_END:
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                ADDOP(c, ploc[0], POP_TOP); /* exc_value */
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                ADDOP(c, ploc[0], POP_BLOCK);
                ADDOP(c, ploc[0], POP_EXCEPT);
                return;

            case COMPILE_FBLOCK_WITH:
            case COMPILE_FBLOCK_ASYNC_WITH:
                ploc[0] = info.fb_loc;
                ADDOP(c, ploc[0], POP_BLOCK);
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 3);
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                codegen_call_exit_with_nones(c, ploc[0]);
                if (info.fb_type == COMPILE_FBLOCK_ASYNC_WITH) {
                    ADDOP_I(c, ploc[0], GET_AWAITABLE, 2);
                    ADDOP(c, ploc[0], PUSH_NULL);
                    ADDOP_LOAD_CONST(c, ploc[0], Singleton.None);
                    ADD_YIELD_FROM(c, ploc[0], true);
                }
                ADDOP(c, ploc[0], POP_TOP);
                /* The exit block should appear to execute after the
                 * statement causing the unwinding, so make the unwinding
                 * instruction artificial */
                ploc[0] = NO_LOCATION;
                return;

            case COMPILE_FBLOCK_HANDLER_CLEANUP: {
                if (info.fb_datum != null) {
                    ADDOP(c, ploc[0], POP_BLOCK);
                }
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                ADDOP(c, ploc[0], POP_BLOCK);
                ADDOP(c, ploc[0], POP_EXCEPT);
                if (info.fb_datum != null) {
                    ADDOP_LOAD_CONST(c, ploc[0], Singleton.None);
                    codegen_nameop(c, ploc[0], (String) info.fb_datum, expr_contextType.Store);
                    codegen_nameop(c, ploc[0], (String) info.fb_datum, expr_contextType.Del);
                }
                return;
            }
            case COMPILE_FBLOCK_POP_VALUE: {
                if (preserve_tos) {
                    ADDOP_I(c, ploc[0], SWAP, 2);
                }
                ADDOP(c, ploc[0], POP_TOP);
                return;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Unwind block stack. If loop is not null, then stop when the first loop
     * is encountered, and put it in loop[0].
     */
    private static void codegen_unwind_fblock_stack(Compile c, SourceLocation[] ploc,
            boolean preserve_tos, _PyCompile_FBlockInfo[] loop) {
        _PyCompile_FBlockInfo top = c._PyCompile_TopFBlock();
        if (top == null) {
            return;
        }
        if (top.fb_type == COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER) {
            throw c._PyCompile_Error(
                ploc[0], "'break', 'continue' and 'return' cannot appear in an except* block");
        }
        if (loop != null && (top.fb_type == COMPILE_FBLOCK_WHILE_LOOP ||
                             top.fb_type == COMPILE_FBLOCK_FOR_LOOP ||
                             top.fb_type == COMPILE_FBLOCK_ASYNC_FOR_LOOP)) {
            loop[0] = top;
            return;
        }
        _PyCompile_FBlockInfo copy = top.copy();
        c._PyCompile_PopFBlock(top.fb_type, top.fb_block);
        codegen_unwind_fblock(c, ploc, copy, preserve_tos);
        codegen_unwind_fblock_stack(c, ploc, preserve_tos, loop);
        c._PyCompile_PushFBlock(copy.fb_loc, copy.fb_type, copy.fb_block,
                                copy.fb_exit, copy.fb_datum);
    }

    private static void codegen_enter_scope(Compile c, String name, int scope_type,
            BlockKey key, int lineno, String privateobj, _PyCompile_CodeUnitMetadata umd) {
        c._PyCompile_EnterScope(name, scope_type, key, lineno, privateobj, umd);
        SourceLocation loc = LOCATION(lineno, lineno, 0, 0);
        if (scope_type == COMPILE_SCOPE_MODULE) {
            loc = LOCATION(0, loc.end_lineno, loc.col_offset, loc.end_col_offset);
        }
        /* Add the generator prefix instructions. */

        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        if (ste.ste_coroutine || ste.ste_generator) {
            /* Note that RETURN_GENERATOR + POP_TOP have a net stack effect
             * of 0. This is because RETURN_GENERATOR pushes the generator
             before returning. */
            SourceLocation gloc = LOCATION(lineno, lineno, -1, -1);
            ADDOP(c, gloc, RETURN_GENERATOR);
            ADDOP(c, gloc, POP_TOP);
        }

        ADDOP_I(c, loc, RESUME, RESUME_AT_FUNC_START);
        if (scope_type == COMPILE_SCOPE_MODULE) {
            ADDOP(c, loc, ANNOTATIONS_PLACEHOLDER);
        }
    }

    /** C: _Py_ANNOTATE_FORMAT_VALUE_WITH_FAKE_GLOBALS (pycore_object.h) */
    private static final int _Py_ANNOTATE_FORMAT_VALUE_WITH_FAKE_GLOBALS = 2;

    private static void codegen_setup_annotations_scope(Compile c, SourceLocation loc,
            BlockKey key, String name) {
        _PyCompile_CodeUnitMetadata umd = new _PyCompile_CodeUnitMetadata();
        umd.u_posonlyargcount = 1;
        codegen_enter_scope(c, name, COMPILE_SCOPE_ANNOTATIONS,
                            key, loc.lineno, null, umd);

        // if .format > VALUE_WITH_FAKE_GLOBALS: raise NotImplementedError
        Object value_with_fake_globals =
                BigInteger.valueOf(_Py_ANNOTATE_FORMAT_VALUE_WITH_FAKE_GLOBALS);

        assert !SYMTABLE_ENTRY(c).ste_has_docstring;
        ADDOP_I(c, loc, LOAD_FAST, 0);
        ADDOP_LOAD_CONST(c, loc, value_with_fake_globals);
        ADDOP_I(c, loc, COMPARE_OP, (Py_GT << 5) | compare_masks(Py_GT));
        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        ADDOP_JUMP(c, loc, POP_JUMP_IF_FALSE, body);
        ADDOP_I(c, loc, LOAD_COMMON_CONSTANT, CONSTANT_NOTIMPLEMENTEDERROR);
        ADDOP_I(c, loc, RAISE_VARARGS, 1);
        USE_LABEL(c, body);
    }

    private static void codegen_leave_annotations_scope(Compile c, SourceLocation loc) {
        ADDOP(c, loc, RETURN_VALUE);
        PyCodeObject co = c._PyCompile_OptimizeAndAssemble(true);

        // We want the parameter to __annotate__ to be named "format" in the
        // signature  shown by inspect.signature(), but we need to use a
        // different name (.format) in the symtable; if the name
        // "format" appears in the annotations, it doesn't get clobbered
        // by this name.  This code is essentially:
        // co->co_localsplusnames = ("format", *co->co_localsplusnames[1:])
        // (Phase D: the placeholder code object has no co_localsplusnames.)

        c._PyCompile_ExitScope();
        codegen_make_closure(c, loc, co, 0);
    }

    private static void codegen_deferred_annotations_body(Compile c, SourceLocation loc,
            List<stmt> deferred_anno, List<Integer> conditional_annotation_indices,
            int scope_type) {
        int annotations_len = deferred_anno.size();

        assert annotations_len == conditional_annotation_indices.size();

        ADDOP_I(c, loc, BUILD_MAP, 0); // stack now contains <annos>

        for (int i = 0; i < annotations_len; i++) {
            AnnAssign st = (AnnAssign) deferred_anno.get(i);
            String mangled = c._PyCompile_Mangle(((Name) st.target).id);
            // NOTE: ref of mangled can be leaked on ADDOP* and VISIT macros due to early returns
            // fixing would require an overhaul of these macros

            int idx = conditional_annotation_indices.get(i);
            _PyJumpTargetLabel not_set = NEW_JUMP_TARGET_LABEL(c);

            if (idx != -1) {
                ADDOP_LOAD_CONST(c, LOC(st), BigInteger.valueOf(idx));
                if (scope_type == COMPILE_SCOPE_CLASS) {
                    ADDOP_NAME(
                        c, LOC(st), LOAD_DEREF, "__conditional_annotations__",
                        METADATA(c).u_freevars);
                }
                else {
                    ADDOP_NAME(
                        c, LOC(st), LOAD_GLOBAL, "__conditional_annotations__",
                        METADATA(c).u_names);
                }

                ADDOP_I(c, LOC(st), CONTAINS_OP, 0);
                ADDOP_JUMP(c, LOC(st), POP_JUMP_IF_FALSE, not_set);
            }

            codegen_visit_expr(c, st.annotation);
            ADDOP_I(c, LOC(st), COPY, 2);
            ADDOP_LOAD_CONST(c, LOC(st), mangled);
            // stack now contains <annos> <name> <annos> <value>
            ADDOP(c, loc, STORE_SUBSCR);
            // stack now contains <annos>

            USE_LABEL(c, not_set);
        }
    }

    private static void codegen_process_deferred_annotations(Compile c, SourceLocation loc) {
        List<stmt> deferred_anno = c._PyCompile_DeferredAnnotations();
        List<Integer> conditional_annotation_indices =
                c._PyCompile_ConditionalAnnotationIndices();
        if (deferred_anno == null) {
            assert conditional_annotation_indices == null;
            return;
        }

        int scope_type = SCOPE_TYPE(c);
        boolean need_separate_block = scope_type == COMPILE_SCOPE_MODULE;
        if (need_separate_block) {
            c._PyCompile_StartAnnotationSetup();
        }

        // It's possible that ste_annotations_block is set but
        // u_deferred_annotations is not, because the former is still
        // set if there are only non-simple annotations (i.e., annotations
        // for attributes, subscripts, or parenthesized names). However, the
        // reverse should not be possible.
        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        assert ste.ste_annotation_block != null;
        BlockKey key = new BlockKey(ste.ste_id, 1);
        codegen_setup_annotations_scope(c, loc, key, ste.ste_annotation_block.ste_name);
        codegen_deferred_annotations_body(c, loc, deferred_anno,
                                          conditional_annotation_indices, scope_type);

        codegen_leave_annotations_scope(c, loc);
        codegen_nameop(
            c, loc,
            ste.ste_type == _Py_block_ty.ClassBlock ? "__annotate_func__" : "__annotate__",
            expr_contextType.Store);

        if (need_separate_block) {
            c._PyCompile_EndAnnotationSetup();
        }
    }

    /* Compile an expression */
    static void _PyCodegen_Expression(Compile c, expr e) {
        codegen_visit_expr(c, e);
    }

    /* Compile a sequence of statements, checking for a docstring
       and for annotations. */

    static void _PyCodegen_Module(Compile c, SourceLocation loc, List<stmt> stmts,
            boolean is_interactive) {
        if (SYMTABLE_ENTRY(c).ste_has_conditional_annotations) {
            ADDOP_I(c, loc, BUILD_SET, 0);
            ADDOP_N(c, loc, STORE_NAME, "__conditional_annotations__", METADATA(c).u_names);
        }
        codegen_body(c, loc, stmts, is_interactive);
    }

    static void codegen_body(Compile c, SourceLocation loc, List<stmt> stmts,
            boolean is_interactive) {
        /* If from __future__ import annotations is active,
         * every annotated class and module should have __annotations__.
         * Else __annotate__ is created when necessary. */
        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        if ((FUTURE_FEATURES(c) & CO_FUTURE_ANNOTATIONS) != 0 && ste.ste_annotations_used) {
            ADDOP(c, loc, SETUP_ANNOTATIONS);
        }
        if (LEN(stmts) == 0) {
            return;
        }
        int first_instr = 0;
        if (!is_interactive) { /* A string literal on REPL prompt is not a docstring */
            if (ste.ste_has_docstring) {
                String docstring = Ast._PyAST_GetDocString(stmts);
                assert docstring != null;
                first_instr = 1;
                /* set docstring */
                assert OPTIMIZATION_LEVEL(c) < 2;
                String cleandoc = Compile._PyCompile_CleanDoc(docstring);
                stmt st = stmts.get(0);
                assert st instanceof Expr;
                SourceLocation dloc = LOC(((Expr) st).value);
                ADDOP_LOAD_CONST(c, dloc, cleandoc);
                codegen_nameop(c, NO_LOCATION, "__doc__", expr_contextType.Store);
            }
        }
        for (int i = first_instr; i < LEN(stmts); i++) {
            codegen_visit_stmt(c, stmts.get(i));
        }
        // If there are annotations and the future import is not on, we
        // collect the annotations in a separate pass and generate an
        // __annotate__ function. See PEP 649.
        if ((FUTURE_FEATURES(c) & CO_FUTURE_ANNOTATIONS) == 0) {
            codegen_process_deferred_annotations(c, loc);
        }
    }

    static void _PyCodegen_EnterAnonymousScope(Compile c, mod mod) {
        codegen_enter_scope(c, "<module>", COMPILE_SCOPE_MODULE,
                            new BlockKey(mod), 1, null, null);
    }

    private static void codegen_make_closure(Compile c, SourceLocation loc, PyCodeObject co,
            int flags) {
        if (co.co_nfreevars() != 0) {
            // C: for i from PyUnstable_Code_GetFirstFree(co) to co_nlocalsplus,
            // the free variables' names.
            for (String name : co.co_freevars) {
                /* Bypass com_addop_varname because it will generate
                   LOAD_DEREF but LOAD_CLOSURE is needed.
                */
                int arg = c._PyCompile_LookupArg(co, name);
                ADDOP_I(c, loc, LOAD_CLOSURE, arg);
            }
            flags |= MAKE_FUNCTION_CLOSURE;
            ADDOP_I(c, loc, BUILD_TUPLE, co.co_nfreevars());
        }
        ADDOP_LOAD_CONST(c, loc, co);

        ADDOP(c, loc, MAKE_FUNCTION);

        if ((flags & MAKE_FUNCTION_CLOSURE) != 0) {
            ADDOP_I(c, loc, SET_FUNCTION_ATTRIBUTE, MAKE_FUNCTION_CLOSURE);
        }
        if ((flags & MAKE_FUNCTION_ANNOTATIONS) != 0) {
            ADDOP_I(c, loc, SET_FUNCTION_ATTRIBUTE, MAKE_FUNCTION_ANNOTATIONS);
        }
        if ((flags & MAKE_FUNCTION_ANNOTATE) != 0) {
            ADDOP_I(c, loc, SET_FUNCTION_ATTRIBUTE, MAKE_FUNCTION_ANNOTATE);
        }
        if ((flags & MAKE_FUNCTION_KWDEFAULTS) != 0) {
            ADDOP_I(c, loc, SET_FUNCTION_ATTRIBUTE, MAKE_FUNCTION_KWDEFAULTS);
        }
        if ((flags & MAKE_FUNCTION_DEFAULTS) != 0) {
            ADDOP_I(c, loc, SET_FUNCTION_ATTRIBUTE, MAKE_FUNCTION_DEFAULTS);
        }
    }

    private static void codegen_decorators(Compile c, List<expr> decos) {
        if (decos == null) {
            return;
        }

        for (int i = 0; i < LEN(decos); i++) {
            codegen_visit_expr(c, decos.get(i));
        }
    }

    private static void codegen_apply_decorators(Compile c, List<expr> decos) {
        if (decos == null) {
            return;
        }

        for (int i = LEN(decos) - 1; i > -1; i--) {
            SourceLocation loc = LOC(decos.get(i));
            ADDOP_I(c, loc, CALL, 0);
        }
    }

    private static int codegen_kwonlydefaults(Compile c, SourceLocation loc,
            List<arg> kwonlyargs, List<expr> kw_defaults) {
        /* Push a dict of keyword-only default values.

           Return -1 on error, 0 if no dict pushed, 1 if a dict is pushed.
           */
        int default_count = 0;
        for (int i = 0; i < LEN(kwonlyargs); i++) {
            arg arg = kwonlyargs.get(i);
            expr default_ = kw_defaults.get(i);
            if (default_ != null) {
                default_count++;
                String mangled = c._PyCompile_MaybeMangle(arg.arg);
                ADDOP_LOAD_CONST(c, loc, mangled);
                codegen_visit_expr(c, default_);
            }
        }
        if (default_count != 0) {
            ADDOP_I(c, loc, BUILD_MAP, default_count);
            return 1;
        }
        else {
            return 0;
        }
    }

    private static void codegen_visit_annexpr(Compile c, expr annotation) {
        SourceLocation loc = LOC(annotation);
        ADDOP_LOAD_CONST(c, loc, AstUnparse._PyAST_ExprAsUnicode(annotation));
    }

    private static void codegen_argannotation(Compile c, String id, expr annotation,
            int[] annotations_len, SourceLocation loc) {
        if (annotation == null) {
            return;
        }
        String mangled = c._PyCompile_MaybeMangle(id);
        ADDOP_LOAD_CONST(c, loc, mangled);

        if ((FUTURE_FEATURES(c) & CO_FUTURE_ANNOTATIONS) != 0) {
            codegen_visit_annexpr(c, annotation);
        }
        else {
            if (annotation instanceof Starred) {
                // *args: *Ts (where Ts is a TypeVarTuple).
                // Do [annotation_value] = [*Ts].
                // (Note that in theory we could end up here even for an argument
                // other than *args, but in practice the grammar doesn't allow it.)
                codegen_visit_expr(c, ((Starred) annotation).value);
                ADDOP_I(c, loc, UNPACK_SEQUENCE, 1);
            }
            else {
                codegen_visit_expr(c, annotation);
            }
        }
        annotations_len[0] += 1;
    }

    private static void codegen_argannotations(Compile c, List<arg> args,
            int[] annotations_len, SourceLocation loc) {
        for (int i = 0; i < LEN(args); i++) {
            arg arg = args.get(i);
            codegen_argannotation(
                        c,
                        arg.arg,
                        arg.annotation,
                        annotations_len,
                        loc);
        }
    }

    private static void codegen_annotations_in_scope(Compile c, SourceLocation loc,
            arguments args, expr returns, int[] annotations_len) {
        codegen_argannotations(c, args.posonlyargs, annotations_len, loc);

        codegen_argannotations(c, args.args, annotations_len, loc);

        if (args.vararg != null && args.vararg.annotation != null) {
            codegen_argannotation(c, args.vararg.arg,
                                     args.vararg.annotation, annotations_len, loc);
        }

        codegen_argannotations(c, args.kwonlyargs, annotations_len, loc);

        if (args.kwarg != null && args.kwarg.annotation != null) {
            codegen_argannotation(c, args.kwarg.arg,
                                     args.kwarg.annotation, annotations_len, loc);
        }

        codegen_argannotation(c, "return", returns, annotations_len, loc);
    }

    private static int codegen_function_annotations(Compile c, SourceLocation loc,
            arguments args, expr returns) {
        /* Push arg annotation names and values.
           The expressions are evaluated separately from the rest of the source code.

           Return -1 on error, or a combination of flags to add to the function.
           */
        int[] annotations_len = {0};

        PySTEntryObject ste = SYMTABLE(c)._PySymtable_LookupOptional(new BlockKey(args));
        assert ste != null;

        if (ste.ste_annotations_used) {
            codegen_setup_annotations_scope(c, loc, new BlockKey(args), ste.ste_name);
            codegen_annotations_in_scope(c, loc, args, returns, annotations_len);
            ADDOP_I(c, loc, BUILD_MAP, annotations_len[0]);
            codegen_leave_annotations_scope(c, loc);
            return MAKE_FUNCTION_ANNOTATE;
        }

        return 0;
    }

    private static void codegen_defaults(Compile c, arguments args, SourceLocation loc) {
        VISIT_SEQ_expr(c, args.defaults);
        ADDOP_I(c, loc, BUILD_TUPLE, LEN(args.defaults));
    }

    private static int codegen_default_arguments(Compile c, SourceLocation loc,
            arguments args) {
        int funcflags = 0;
        if (args.defaults != null && LEN(args.defaults) > 0) {
            codegen_defaults(c, args, loc);
            funcflags |= MAKE_FUNCTION_DEFAULTS;
        }
        if (args.kwonlyargs != null) {
            int res = codegen_kwonlydefaults(c, loc,
                                             args.kwonlyargs,
                                             args.kw_defaults);
            if (res > 0) {
                funcflags |= MAKE_FUNCTION_KWDEFAULTS;
            }
        }
        return funcflags;
    }

    private static void codegen_wrap_in_stopiteration_handler(Compile c) {
        _PyJumpTargetLabel handler = NEW_JUMP_TARGET_LABEL(c);

        /* Insert SETUP_CLEANUP just after the initial RETURN_GENERATOR; POP_TOP */
        InstructionSequence seq = INSTR_SEQUENCE(c);
        int resume = 0;
        while (seq._PyInstructionSequence_GetInstruction(resume).i_opcode != RETURN_GENERATOR) {
            resume++;
            assert resume < seq.s_used();
        }
        resume++;
        assert seq._PyInstructionSequence_GetInstruction(resume).i_opcode == POP_TOP;
        resume++;
        assert resume < seq.s_used();
        seq._PyInstructionSequence_InsertInstruction(
                resume, SETUP_CLEANUP, handler.id, NO_LOCATION);

        ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
        ADDOP(c, NO_LOCATION, RETURN_VALUE);
        USE_LABEL(c, handler);
        ADDOP_I(c, NO_LOCATION, CALL_INTRINSIC_1, INTRINSIC_STOPITERATION_ERROR);
        ADDOP_I(c, NO_LOCATION, RERAISE, 1);
    }

    private static void codegen_type_param_bound_or_default(Compile c, expr e, String name,
            BlockKey key, boolean allow_starred) {
        PyTuple defaults = new PyTuple(BigInteger.ONE);
        ADDOP_LOAD_CONST(c, LOC(e), defaults);
        codegen_setup_annotations_scope(c, LOC(e), key, name);
        if (allow_starred && e instanceof Starred) {
            codegen_visit_expr(c, ((Starred) e).value);
            ADDOP_I(c, LOC(e), UNPACK_SEQUENCE, 1);
        }
        else {
            codegen_visit_expr(c, e);
        }
        ADDOP(c, LOC(e), RETURN_VALUE);
        PyCodeObject co = c._PyCompile_OptimizeAndAssemble(true);
        c._PyCompile_ExitScope();
        codegen_make_closure(c, LOC(e), co, MAKE_FUNCTION_DEFAULTS);
    }

    private static void codegen_type_params(Compile c, List<type_param> type_params) {
        if (type_params == null) {
            return;
        }
        int n = LEN(type_params);
        boolean seen_default = false;

        for (int i = 0; i < n; i++) {
            type_param typeparam = type_params.get(i);
            SourceLocation loc = LOC(typeparam);
            switch (typeparam.kind()) {
            case TypeVar: {
                TypeVar tv = (TypeVar) typeparam;
                ADDOP_LOAD_CONST(c, loc, tv.name);
                if (tv.bound != null) {
                    expr bound = tv.bound;
                    codegen_type_param_bound_or_default(c, bound, tv.name,
                                                        new BlockKey(typeparam), false);

                    int intrinsic = bound instanceof Tuple
                        ? INTRINSIC_TYPEVAR_WITH_CONSTRAINTS
                        : INTRINSIC_TYPEVAR_WITH_BOUND;
                    ADDOP_I(c, loc, CALL_INTRINSIC_2, intrinsic);
                }
                else {
                    ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_TYPEVAR);
                }
                if (tv.default_value != null) {
                    seen_default = true;
                    expr default_ = tv.default_value;
                    codegen_type_param_bound_or_default(c, default_, tv.name,
                                                        new BlockKey(typeparam, 1), false);
                    ADDOP_I(c, loc, CALL_INTRINSIC_2, INTRINSIC_SET_TYPEPARAM_DEFAULT);
                }
                else if (seen_default) {
                    throw c._PyCompile_Error(loc, "non-default type parameter '%s' "
                                            + "follows default type parameter",
                                            tv.name);
                }
                ADDOP_I(c, loc, COPY, 1);
                codegen_nameop(c, loc, tv.name, expr_contextType.Store);
                break;
            }
            case TypeVarTuple: {
                TypeVarTuple tvt = (TypeVarTuple) typeparam;
                ADDOP_LOAD_CONST(c, loc, tvt.name);
                ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_TYPEVARTUPLE);
                if (tvt.default_value != null) {
                    expr default_ = tvt.default_value;
                    codegen_type_param_bound_or_default(c, default_, tvt.name,
                                                        new BlockKey(typeparam), true);
                    ADDOP_I(c, loc, CALL_INTRINSIC_2, INTRINSIC_SET_TYPEPARAM_DEFAULT);
                    seen_default = true;
                }
                else if (seen_default) {
                    throw c._PyCompile_Error(loc, "non-default type parameter '%s' "
                                            + "follows default type parameter",
                                            tvt.name);
                }
                ADDOP_I(c, loc, COPY, 1);
                codegen_nameop(c, loc, tvt.name, expr_contextType.Store);
                break;
            }
            case ParamSpec: {
                ParamSpec ps = (ParamSpec) typeparam;
                ADDOP_LOAD_CONST(c, loc, ps.name);
                ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_PARAMSPEC);
                if (ps.default_value != null) {
                    expr default_ = ps.default_value;
                    codegen_type_param_bound_or_default(c, default_, ps.name,
                                                        new BlockKey(typeparam), false);
                    ADDOP_I(c, loc, CALL_INTRINSIC_2, INTRINSIC_SET_TYPEPARAM_DEFAULT);
                    seen_default = true;
                }
                else if (seen_default) {
                    throw c._PyCompile_Error(loc, "non-default type parameter '%s' "
                                            + "follows default type parameter",
                                            ps.name);
                }
                ADDOP_I(c, loc, COPY, 1);
                codegen_nameop(c, loc, ps.name, expr_contextType.Store);
                break;
            }
            }
        }
        ADDOP_I(c, LOC(type_params.get(0)), BUILD_TUPLE, n);
    }

    private static void codegen_function_body(Compile c, stmt s, boolean is_async,
            int funcflags, int firstlineno) {
        arguments args;
        String name;
        List<stmt> body;
        int scope_type;

        if (is_async) {
            AsyncFunctionDef f = (AsyncFunctionDef) s;

            args = f.args;
            name = f.name;
            body = f.body;

            scope_type = COMPILE_SCOPE_ASYNC_FUNCTION;
        } else {
            FunctionDef f = (FunctionDef) s;

            args = f.args;
            name = f.name;
            body = f.body;

            scope_type = COMPILE_SCOPE_FUNCTION;
        }

        _PyCompile_CodeUnitMetadata umd = new _PyCompile_CodeUnitMetadata();
        umd.u_argcount = LEN(args.args);
        umd.u_posonlyargcount = LEN(args.posonlyargs);
        umd.u_kwonlyargcount = LEN(args.kwonlyargs);
        codegen_enter_scope(c, name, scope_type, new BlockKey(s), firstlineno, null, umd);

        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        int first_instr = 0;
        if (ste.ste_has_docstring) {
            String docstring = Ast._PyAST_GetDocString(body);
            assert docstring != null;
            first_instr = 1;
            docstring = Compile._PyCompile_CleanDoc(docstring);
            c._PyCompile_AddConst(docstring);
        }

        _PyJumpTargetLabel start = NEW_JUMP_TARGET_LABEL(c);
        USE_LABEL(c, start);
        boolean add_stopiteration_handler = ste.ste_coroutine || ste.ste_generator;
        if (add_stopiteration_handler) {
            /* codegen_wrap_in_stopiteration_handler will push a block, so we need to account for that */
            c._PyCompile_PushFBlock(NO_LOCATION, COMPILE_FBLOCK_STOP_ITERATION,
                                    start, NO_LABEL, null);
        }

        for (int i = first_instr; i < LEN(body); i++) {
            codegen_visit_stmt(c, body.get(i));
        }
        if (add_stopiteration_handler) {
            codegen_wrap_in_stopiteration_handler(c);
            c._PyCompile_PopFBlock(COMPILE_FBLOCK_STOP_ITERATION, start);
        }
        PyCodeObject co = c._PyCompile_OptimizeAndAssemble(true);
        c._PyCompile_ExitScope();
        codegen_make_closure(c, LOC(s), co, funcflags);
    }

    private static void codegen_function(Compile c, stmt s, boolean is_async) {
        arguments args;
        expr returns;
        String name;
        List<expr> decos;
        List<type_param> type_params;
        int funcflags;
        int firstlineno;

        if (is_async) {
            AsyncFunctionDef f = (AsyncFunctionDef) s;

            args = f.args;
            returns = f.returns;
            decos = f.decorator_list;
            name = f.name;
            type_params = f.type_params;
        } else {
            FunctionDef f = (FunctionDef) s;

            args = f.args;
            returns = f.returns;
            decos = f.decorator_list;
            name = f.name;
            type_params = f.type_params;
        }

        codegen_decorators(c, decos);

        firstlineno = s.lineno;
        if (LEN(decos) != 0) {
            firstlineno = decos.get(0).lineno;
        }

        SourceLocation loc = LOC(s);

        boolean is_generic = LEN(type_params) > 0;

        funcflags = codegen_default_arguments(c, loc, args);

        int num_typeparam_args = 0;

        if (is_generic) {
            if ((funcflags & MAKE_FUNCTION_DEFAULTS) != 0) {
                num_typeparam_args += 1;
            }
            if ((funcflags & MAKE_FUNCTION_KWDEFAULTS) != 0) {
                num_typeparam_args += 1;
            }
            if (num_typeparam_args == 2) {
                ADDOP_I(c, loc, SWAP, 2);
            }
            String type_params_name = "<generic parameters of " + name + ">";
            _PyCompile_CodeUnitMetadata umd = new _PyCompile_CodeUnitMetadata();
            umd.u_argcount = num_typeparam_args;
            codegen_enter_scope(c, type_params_name, COMPILE_SCOPE_ANNOTATIONS,
                                new BlockKey(type_params), firstlineno, null, umd);
            codegen_type_params(c, type_params);
            for (int i = 0; i < num_typeparam_args; i++) {
                ADDOP_I(c, loc, LOAD_FAST, i);
            }
        }

        int annotations_flag = codegen_function_annotations(c, loc, args, returns);
        funcflags |= annotations_flag;

        codegen_function_body(c, s, is_async, funcflags, firstlineno);

        if (is_generic) {
            ADDOP_I(c, loc, SWAP, 2);
            ADDOP_I(c, loc, CALL_INTRINSIC_2, INTRINSIC_SET_FUNCTION_TYPE_PARAMS);

            PyCodeObject co = c._PyCompile_OptimizeAndAssemble(false);
            c._PyCompile_ExitScope();
            codegen_make_closure(c, loc, co, 0);
            if (num_typeparam_args > 0) {
                ADDOP_I(c, loc, SWAP, num_typeparam_args + 1);
                ADDOP_I(c, loc, CALL, num_typeparam_args - 1);
            }
            else {
                ADDOP(c, loc, PUSH_NULL);
                ADDOP_I(c, loc, CALL, 0);
            }
        }

        codegen_apply_decorators(c, decos);
        codegen_nameop(c, loc, name, expr_contextType.Store);
    }

    private static void codegen_set_type_params_in_class(Compile c, SourceLocation loc) {
        codegen_nameop(c, loc, ".type_params", expr_contextType.Load);
        codegen_nameop(c, loc, "__type_params__", expr_contextType.Store);
    }

    private static void codegen_class_body(Compile c, ClassDef s, int firstlineno) {
        /* ultimately generate code for:
             <name> = __build_class__(<func>, <name>, *<bases>, **<keywords>)
           where:
             <func> is a zero arg function/closure created from the class body.
                It mutates its locals to build the class namespace.
             <name> is the class name
             <bases> is the positional arguments and *varargs argument
             <keywords> is the keyword arguments and **kwds argument
           This borrows from codegen_call.
        */

        /* 1. compile the class body into a code object */
        codegen_enter_scope(c, s.name, COMPILE_SCOPE_CLASS,
                            new BlockKey(s), firstlineno, s.name, null);

        SourceLocation loc = LOCATION(firstlineno, firstlineno, 0, 0);
        /* load (global) __name__ ... */
        codegen_nameop(c, loc, "__name__", expr_contextType.Load);
        /* ... and store it as __module__ */
        codegen_nameop(c, loc, "__module__", expr_contextType.Store);
        ADDOP_LOAD_CONST(c, loc, QUALNAME(c));
        codegen_nameop(c, loc, "__qualname__", expr_contextType.Store);
        ADDOP_LOAD_CONST(c, loc, BigInteger.valueOf(METADATA(c).u_firstlineno));
        codegen_nameop(c, loc, "__firstlineno__", expr_contextType.Store);
        List<type_param> type_params = s.type_params;
        if (LEN(type_params) > 0) {
            codegen_set_type_params_in_class(c, loc);
        }
        if (SYMTABLE_ENTRY(c).ste_needs_classdict) {
            ADDOP(c, loc, LOAD_LOCALS);

            // We can't use codegen_nameop here because we need to generate a
            // STORE_DEREF in a class namespace, and codegen_nameop() won't do
            // that by default.
            ADDOP_N(c, loc, STORE_DEREF, "__classdict__", METADATA(c).u_cellvars);
        }
        if (SYMTABLE_ENTRY(c).ste_has_conditional_annotations) {
            ADDOP_I(c, loc, BUILD_SET, 0);
            ADDOP_N(c, loc, STORE_DEREF, "__conditional_annotations__", METADATA(c).u_cellvars);
        }
        /* compile the body proper */
        codegen_body(c, loc, s.body, false);
        PyTuple static_attributes = c._PyCompile_StaticAttributesAsTuple();
        ADDOP_LOAD_CONST(c, NO_LOCATION, static_attributes);
        codegen_nameop(c, NO_LOCATION, "__static_attributes__", expr_contextType.Store);
        /* The following code is artificial */
        /* Set __classdictcell__ if necessary */
        if (SYMTABLE_ENTRY(c).ste_needs_classdict) {
            /* Store __classdictcell__ into class namespace */
            int i = c._PyCompile_LookupCellvar("__classdict__");
            ADDOP_I(c, NO_LOCATION, LOAD_CLOSURE, i);
            codegen_nameop(c, NO_LOCATION, "__classdictcell__", expr_contextType.Store);
        }
        /* Return __classcell__ if it is referenced, otherwise return None */
        if (SYMTABLE_ENTRY(c).ste_needs_class_closure) {
            /* Store __classcell__ into class namespace & return it */
            int i = c._PyCompile_LookupCellvar("__class__");
            ADDOP_I(c, NO_LOCATION, LOAD_CLOSURE, i);
            ADDOP_I(c, NO_LOCATION, COPY, 1);
            codegen_nameop(c, NO_LOCATION, "__classcell__", expr_contextType.Store);
        }
        else {
            /* No methods referenced __class__, so just return None */
            ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
        }
        ADDOP(c, NO_LOCATION, RETURN_VALUE);
        /* create the code object */
        PyCodeObject co = c._PyCompile_OptimizeAndAssemble(true);

        /* leave the new scope */
        c._PyCompile_ExitScope();

        /* 2. load the 'build_class' function */

        // these instructions should be attributed to the class line,
        // not a decorator line
        loc = LOC(s);
        ADDOP(c, loc, LOAD_BUILD_CLASS);
        ADDOP(c, loc, PUSH_NULL);

        /* 3. load a function (or closure) made from the code object */
        codegen_make_closure(c, loc, co, 0);

        /* 4. load class name */
        ADDOP_LOAD_CONST(c, loc, s.name);
    }

    private static void codegen_class(Compile c, ClassDef s) {
        List<expr> decos = s.decorator_list;

        codegen_decorators(c, decos);

        int firstlineno = s.lineno;
        if (LEN(decos) != 0) {
            firstlineno = decos.get(0).lineno;
        }
        SourceLocation loc = LOC(s);

        List<type_param> type_params = s.type_params;
        boolean is_generic = LEN(type_params) > 0;
        if (is_generic) {
            String type_params_name = "<generic parameters of " + s.name + ">";
            codegen_enter_scope(c, type_params_name, COMPILE_SCOPE_ANNOTATIONS,
                                new BlockKey(type_params), firstlineno, s.name, null);
            codegen_type_params(c, type_params);
            codegen_nameop(c, loc, ".type_params", expr_contextType.Store);
        }

        codegen_class_body(c, s, firstlineno);

        /* generate the rest of the code for the call */

        if (is_generic) {
            codegen_nameop(c, loc, ".type_params", expr_contextType.Load);
            ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_SUBSCRIPT_GENERIC);
            codegen_nameop(c, loc, ".generic_base", expr_contextType.Store);

            codegen_call_helper_impl(c, loc, 2,
                                     s.bases,
                                     ".generic_base",
                                     s.keywords);

            PyCodeObject co = c._PyCompile_OptimizeAndAssemble(false);

            c._PyCompile_ExitScope();
            codegen_make_closure(c, loc, co, 0);
            ADDOP(c, loc, PUSH_NULL);
            ADDOP_I(c, loc, CALL, 0);
        } else {
            codegen_call_helper(c, loc, 2,
                                s.bases,
                                s.keywords);
        }

        /* 6. apply decorators */
        codegen_apply_decorators(c, decos);

        /* 7. store into <name> */
        codegen_nameop(c, loc, s.name, expr_contextType.Store);
    }

    private static void codegen_typealias_body(Compile c, TypeAlias s) {
        SourceLocation loc = LOC(s);
        String name = ((Name) s.name).id;
        PyTuple defaults = new PyTuple(BigInteger.ONE);
        ADDOP_LOAD_CONST(c, loc, defaults);
        codegen_setup_annotations_scope(c, LOC(s), new BlockKey(s), name);

        assert !SYMTABLE_ENTRY(c).ste_has_docstring;
        codegen_visit_expr(c, s.value);
        ADDOP(c, loc, RETURN_VALUE);
        PyCodeObject co = c._PyCompile_OptimizeAndAssemble(false);
        c._PyCompile_ExitScope();
        codegen_make_closure(c, loc, co, MAKE_FUNCTION_DEFAULTS);

        ADDOP_I(c, loc, BUILD_TUPLE, 3);
        ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_TYPEALIAS);
    }

    private static void codegen_typealias(Compile c, TypeAlias s) {
        SourceLocation loc = LOC(s);
        List<type_param> type_params = s.type_params;
        boolean is_generic = LEN(type_params) > 0;
        String name = ((Name) s.name).id;
        if (is_generic) {
            String type_params_name = "<generic parameters of " + name + ">";
            codegen_enter_scope(c, type_params_name, COMPILE_SCOPE_ANNOTATIONS,
                                new BlockKey(type_params), loc.lineno, null, null);
            ADDOP_LOAD_CONST(c, loc, name);
            codegen_type_params(c, type_params);
        }
        else {
            ADDOP_LOAD_CONST(c, loc, name);
            ADDOP_LOAD_CONST(c, loc, Singleton.None);
        }

        codegen_typealias_body(c, s);

        if (is_generic) {
            PyCodeObject co = c._PyCompile_OptimizeAndAssemble(false);
            c._PyCompile_ExitScope();
            codegen_make_closure(c, loc, co, 0);
            ADDOP(c, loc, PUSH_NULL);
            ADDOP_I(c, loc, CALL, 0);
        }
        codegen_nameop(c, loc, name, expr_contextType.Store);
    }

    private static boolean is_const_tuple(List<expr> elts) {
        for (int i = 0; i < LEN(elts); i++) {
            expr e = elts.get(i);
            if (!(e instanceof Constant)) {
                return false;
            }
        }
        return true;
    }

    /* Return false if the expression is a constant value except named singletons.
       Return true otherwise. */
    private static boolean check_is_arg(expr e) {
        if (e instanceof Tuple) {
            return !is_const_tuple(((Tuple) e).elts);
        }
        if (!(e instanceof Constant)) {
            return true;
        }
        Object value = ((Constant) e).value;
        return (value == Singleton.None
             || value == Singleton.False
             || value == Singleton.True
             || value == Singleton.Ellipsis);
    }

    /* Check operands of identity checks ("is" and "is not").
       Emit a warning if any operand is a constant except named singletons.
     */
    private static void codegen_check_compare(Compile c, Compare e) {
        int i, n;
        boolean left = check_is_arg(e.left);
        expr left_expr = e.left;
        n = LEN(e.ops);
        for (i = 0; i < n; i++) {
            cmpopType op = e.ops.get(i);
            expr right_expr = e.comparators.get(i);
            boolean right = check_is_arg(right_expr);
            if (op == cmpopType.Is || op == cmpopType.IsNot) {
                if (!right || !left) {
                    String msg = (op == cmpopType.Is)
                            ? "\"is\" with '%.200s' literal. Did you mean \"==\"?"
                            : "\"is not\" with '%.200s' literal. Did you mean \"!=\"?";
                    expr literal = !left ? left_expr : right_expr;
                    c._PyCompile_Warn(
                        LOC(e), msg, infer_type(literal)
                    );
                    return;
                }
            }
            left = right;
            left_expr = right_expr;
        }
    }

    private static void codegen_addcompare(Compile c, SourceLocation loc, cmpopType op) {
        int cmp;
        switch (op) {
        case Eq:
            cmp = Py_EQ;
            break;
        case NotEq:
            cmp = Py_NE;
            break;
        case Lt:
            cmp = Py_LT;
            break;
        case LtE:
            cmp = Py_LE;
            break;
        case Gt:
            cmp = Py_GT;
            break;
        case GtE:
            cmp = Py_GE;
            break;
        case Is:
            ADDOP_I(c, loc, IS_OP, 0);
            return;
        case IsNot:
            ADDOP_I(c, loc, IS_OP, 1);
            return;
        case In:
            ADDOP_I(c, loc, CONTAINS_OP, 0);
            return;
        case NotIn:
            ADDOP_I(c, loc, CONTAINS_OP, 1);
            return;
        default:
            throw new IllegalStateException("unreachable");
        }
        // cmp goes in top three bits of the oparg, while the low four bits are used
        // by quickened versions of this opcode to store the comparison mask. The
        // fifth-lowest bit indicates whether the result should be converted to bool
        // and is set later):
        ADDOP_I(c, loc, COMPARE_OP, (cmp << 5) | compare_masks(cmp));
    }

    private static void codegen_jump_if(Compile c, SourceLocation loc, expr e,
            _PyJumpTargetLabel next, boolean cond) {
        switch (e.kind()) {
        case UnaryOp:
            if (((UnaryOp) e).op == unaryopType.Not) {
                codegen_jump_if(c, loc, ((UnaryOp) e).operand, next, !cond);
                return;
            }
            /* fallback to general implementation */
            break;
        case BoolOp: {
            List<expr> s = ((BoolOp) e).values;
            int i, n = LEN(s) - 1;
            assert n >= 0;
            boolean cond2 = ((BoolOp) e).op == org.python.pegen.ast.boolopType.Or;
            _PyJumpTargetLabel next2 = next;
            if (cond2 != cond) {
                next2 = NEW_JUMP_TARGET_LABEL(c);
            }
            for (i = 0; i < n; ++i) {
                codegen_jump_if(c, loc, s.get(i), next2, cond2);
            }
            codegen_jump_if(c, loc, s.get(n), next, cond);
            if (!SAME_JUMP_TARGET_LABEL(next2, next)) {
                USE_LABEL(c, next2);
            }
            return;
        }
        case IfExp: {
            IfExp ie = (IfExp) e;
            _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
            _PyJumpTargetLabel next2 = NEW_JUMP_TARGET_LABEL(c);
            codegen_jump_if(c, loc, ie.test, next2, false);
            codegen_jump_if(c, loc, ie.body, next, cond);
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

            USE_LABEL(c, next2);
            codegen_jump_if(c, loc, ie.orelse, next, cond);

            USE_LABEL(c, end);
            return;
        }
        case Compare: {
            Compare ce = (Compare) e;
            int n = LEN(ce.ops) - 1;
            if (n > 0) {
                codegen_check_compare(c, ce);
                _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
                codegen_visit_expr(c, ce.left);
                for (int i = 0; i < n; i++) {
                    codegen_visit_expr(c, ce.comparators.get(i));
                    ADDOP_I(c, LOC(e), SWAP, 2);
                    ADDOP_I(c, LOC(e), COPY, 2);
                    ADDOP_COMPARE(c, LOC(e), ce.ops.get(i));
                    ADDOP(c, LOC(e), TO_BOOL);
                    ADDOP_JUMP(c, LOC(e), POP_JUMP_IF_FALSE, cleanup);
                }
                codegen_visit_expr(c, ce.comparators.get(n));
                ADDOP_COMPARE(c, LOC(e), ce.ops.get(n));
                ADDOP(c, LOC(e), TO_BOOL);
                ADDOP_JUMP(c, LOC(e), cond ? POP_JUMP_IF_TRUE : POP_JUMP_IF_FALSE, next);
                _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
                ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

                USE_LABEL(c, cleanup);
                ADDOP(c, LOC(e), POP_TOP);
                if (!cond) {
                    ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, next);
                }

                USE_LABEL(c, end);
                return;
            }
            /* fallback to general implementation */
            break;
        }
        default:
            /* fallback to general implementation */
            break;
        }

        /* general implementation */
        codegen_visit_expr(c, e);
        ADDOP(c, LOC(e), TO_BOOL);
        ADDOP_JUMP(c, LOC(e), cond ? POP_JUMP_IF_TRUE : POP_JUMP_IF_FALSE, next);
    }

    private static void codegen_ifexp(Compile c, IfExp e) {
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel next = NEW_JUMP_TARGET_LABEL(c);

        codegen_jump_if(c, LOC(e), e.test, next, false);

        codegen_visit_expr(c, e.body);
        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

        USE_LABEL(c, next);
        codegen_visit_expr(c, e.orelse);

        USE_LABEL(c, end);
    }

    private static void codegen_lambda(Compile c, Lambda e) {
        PyCodeObject co;
        int funcflags;
        arguments args = e.args;

        SourceLocation loc = LOC(e);
        funcflags = codegen_default_arguments(c, loc, args);

        _PyCompile_CodeUnitMetadata umd = new _PyCompile_CodeUnitMetadata();
        umd.u_argcount = LEN(args.args);
        umd.u_posonlyargcount = LEN(args.posonlyargs);
        umd.u_kwonlyargcount = LEN(args.kwonlyargs);
        codegen_enter_scope(c, "<lambda>", COMPILE_SCOPE_LAMBDA,
                            new BlockKey(e), e.lineno, null, umd);

        assert !SYMTABLE_ENTRY(c).ste_has_docstring;

        codegen_visit_expr(c, e.body);
        if (SYMTABLE_ENTRY(c).ste_generator) {
            co = c._PyCompile_OptimizeAndAssemble(false);
        }
        else {
            SourceLocation bloc = LOC(e.body);
            ADDOP(c, bloc, RETURN_VALUE);
            co = c._PyCompile_OptimizeAndAssemble(true);
        }
        c._PyCompile_ExitScope();

        codegen_make_closure(c, loc, co, funcflags);
    }

    private static void codegen_if(Compile c, If s) {
        _PyJumpTargetLabel next;
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        if (LEN(s.orelse) != 0) {
            _PyJumpTargetLabel orelse = NEW_JUMP_TARGET_LABEL(c);
            next = orelse;
        }
        else {
            next = end;
        }
        codegen_jump_if(c, LOC(s), s.test, next, false);

        VISIT_SEQ_stmt(c, s.body);
        if (LEN(s.orelse) != 0) {
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

            USE_LABEL(c, next);
            VISIT_SEQ_stmt(c, s.orelse);
        }

        USE_LABEL(c, end);
    }

    private static void codegen_for(Compile c, For s) {
        SourceLocation loc = LOC(s);
        _PyJumpTargetLabel start = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);

        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_FOR_LOOP, start, end, null);

        codegen_visit_expr(c, s.iter);

        loc = LOC(s.iter);
        ADDOP_I(c, loc, GET_ITER, 0);

        USE_LABEL(c, start);
        ADDOP_JUMP(c, loc, FOR_ITER, cleanup);

        /* Add NOP to ensure correct line tracing of multiline for statements.
         * It will be removed later if redundant.
         */
        ADDOP(c, LOC(s.target), NOP);

        USE_LABEL(c, body);
        codegen_visit_expr(c, s.target);
        VISIT_SEQ_stmt(c, s.body);
        /* Mark jump as artificial */
        ADDOP_JUMP(c, NO_LOCATION, JUMP, start);

        USE_LABEL(c, cleanup);
        /* It is important for instrumentation that the `END_FOR` comes first.
        * Iteration over a generator will jump to the first of these instructions,
        * but a non-generator will jump to the second instruction.
        */
        ADDOP(c, NO_LOCATION, END_FOR);
        ADDOP(c, NO_LOCATION, POP_ITER);

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_FOR_LOOP, start);

        VISIT_SEQ_stmt(c, s.orelse);

        USE_LABEL(c, end);
    }

    private static void codegen_async_for(Compile c, AsyncFor s) {
        SourceLocation loc = LOC(s);

        _PyJumpTargetLabel start = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel send = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel except = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);

        codegen_visit_expr(c, s.iter);
        ADDOP(c, LOC(s.iter), GET_AITER);

        USE_LABEL(c, start);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_ASYNC_FOR_LOOP, start, end, null);

        /* SETUP_FINALLY to guard the __anext__ call */
        ADDOP_JUMP(c, loc, SETUP_FINALLY, except);
        ADDOP(c, loc, GET_ANEXT);
        ADDOP(c, loc, PUSH_NULL);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        USE_LABEL(c, send);
        ADD_YIELD_FROM(c, loc, true);
        ADDOP(c, loc, POP_BLOCK);  /* for SETUP_FINALLY */
        ADDOP(c, loc, NOT_TAKEN);

        /* Success block for __anext__ */
        codegen_visit_expr(c, s.target);
        VISIT_SEQ_stmt(c, s.body);
        /* Mark jump as artificial */
        ADDOP_JUMP(c, NO_LOCATION, JUMP, start);

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_ASYNC_FOR_LOOP, start);

        /* Except block for __anext__ */
        USE_LABEL(c, except);

        /* Use same line number as the iterator,
         * as the END_ASYNC_FOR succeeds the `for`, not the body. */
        loc = LOC(s.iter);
        ADDOP_JUMP(c, loc, END_ASYNC_FOR, send);

        /* `else` block */
        VISIT_SEQ_stmt(c, s.orelse);

        USE_LABEL(c, end);
    }

    private static void codegen_while(Compile c, While s) {
        _PyJumpTargetLabel loop = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel anchor = NEW_JUMP_TARGET_LABEL(c);

        USE_LABEL(c, loop);

        c._PyCompile_PushFBlock(LOC(s), COMPILE_FBLOCK_WHILE_LOOP, loop, end, null);
        codegen_jump_if(c, LOC(s), s.test, anchor, false);

        VISIT_SEQ_stmt(c, s.body);
        ADDOP_JUMP(c, NO_LOCATION, JUMP, loop);

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_WHILE_LOOP, loop);

        USE_LABEL(c, anchor);
        if (s.orelse != null) {
            VISIT_SEQ_stmt(c, s.orelse);
        }

        USE_LABEL(c, end);
    }

    private static void codegen_return(Compile c, Return s) {
        SourceLocation[] loc = {LOC(s)};
        boolean preserve_tos = ((s.value != null) &&
                                !(s.value instanceof Constant));

        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        if (!_PyST_IsFunctionLike(ste)) {
            throw c._PyCompile_Error(loc[0], "'return' outside function");
        }
        if (s.value != null && ste.ste_coroutine && ste.ste_generator) {
            throw c._PyCompile_Error(loc[0], "'return' with value in async generator");
        }

        if (preserve_tos) {
            codegen_visit_expr(c, s.value);
        } else {
            /* Emit instruction with line number for return value */
            if (s.value != null) {
                loc[0] = LOC(s.value);
                ADDOP(c, loc[0], NOP);
            }
        }
        if (s.value == null || s.value.lineno != s.lineno) {
            loc[0] = LOC(s);
            ADDOP(c, loc[0], NOP);
        }

        codegen_unwind_fblock_stack(c, loc, preserve_tos, null);
        if (s.value == null) {
            ADDOP_LOAD_CONST(c, loc[0], Singleton.None);
        }
        else if (!preserve_tos) {
            ADDOP_LOAD_CONST(c, loc[0], ((Constant) s.value).value);
        }
        ADDOP(c, loc[0], RETURN_VALUE);
    }

    private static void codegen_break(Compile c, SourceLocation loc) {
        _PyCompile_FBlockInfo[] loop = {null};
        SourceLocation origin_loc = loc;
        SourceLocation[] ploc = {loc};
        /* Emit instruction with line number */
        ADDOP(c, ploc[0], NOP);
        codegen_unwind_fblock_stack(c, ploc, false, loop);
        if (loop[0] == null) {
            throw c._PyCompile_Error(origin_loc, "'break' outside loop");
        }
        codegen_unwind_fblock(c, ploc, loop[0], false);
        ADDOP_JUMP(c, ploc[0], JUMP, loop[0].fb_exit);
    }

    private static void codegen_continue(Compile c, SourceLocation loc) {
        _PyCompile_FBlockInfo[] loop = {null};
        SourceLocation origin_loc = loc;
        SourceLocation[] ploc = {loc};
        /* Emit instruction with line number */
        ADDOP(c, ploc[0], NOP);
        codegen_unwind_fblock_stack(c, ploc, false, loop);
        if (loop[0] == null) {
            throw c._PyCompile_Error(origin_loc, "'continue' not properly in loop");
        }
        ADDOP_JUMP(c, ploc[0], JUMP, loop[0].fb_block);
    }


    /* Code generated for "try: <body> finally: <finalbody>" is as follows:

            SETUP_FINALLY           L
            <code for body>
            POP_BLOCK
            <code for finalbody>
            JUMP E
        L:
            <code for finalbody>
        E:

       The special instructions use the block stack.  Each block
       stack entry contains the instruction that created it (here
       SETUP_FINALLY), the level of the value stack at the time the
       block stack entry was created, and a label (here L).

       SETUP_FINALLY:
        Pushes the current value stack level and the label
        onto the block stack.
       POP_BLOCK:
        Pops en entry from the block stack.

       The block stack is unwound when an exception is raised:
       when a SETUP_FINALLY entry is found, the raised and the caught
       exceptions are pushed onto the value stack (and the exception
       condition is cleared), and the interpreter jumps to the label
       gotten from the block stack.
    */

    private static void codegen_try_finally(Compile c, Try s) {
        SourceLocation loc = LOC(s);

        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);

        /* `try` block */
        ADDOP_JUMP(c, loc, SETUP_FINALLY, end);

        USE_LABEL(c, body);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_FINALLY_TRY, body, end,
                                s.finalbody);

        if (s.handlers != null && LEN(s.handlers) != 0) {
            codegen_try_except(c, s);
        }
        else {
            VISIT_SEQ_stmt(c, s.body);
        }
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_FINALLY_TRY, body);
        VISIT_SEQ_stmt(c, s.finalbody);

        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, exit);
        /* `finally` block */

        USE_LABEL(c, end);

        loc = NO_LOCATION;
        ADDOP_JUMP(c, loc, SETUP_CLEANUP, cleanup);
        ADDOP(c, loc, PUSH_EXC_INFO);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_FINALLY_END, end, NO_LABEL, null);
        VISIT_SEQ_stmt(c, s.finalbody);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_FINALLY_END, end);

        loc = NO_LOCATION;
        ADDOP_I(c, loc, RERAISE, 0);

        USE_LABEL(c, cleanup);
        POP_EXCEPT_AND_RERAISE(c, loc);

        USE_LABEL(c, exit);
    }

    private static void codegen_try_star_finally(Compile c, TryStar s) {
        SourceLocation loc = LOC(s);

        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
        /* `try` block */
        ADDOP_JUMP(c, loc, SETUP_FINALLY, end);

        USE_LABEL(c, body);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_FINALLY_TRY, body, end,
                                s.finalbody);

        if (s.handlers != null && LEN(s.handlers) != 0) {
            codegen_try_star_except(c, s);
        }
        else {
            VISIT_SEQ_stmt(c, s.body);
        }
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_FINALLY_TRY, body);
        VISIT_SEQ_stmt(c, s.finalbody);

        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, exit);

        /* `finally` block */
        USE_LABEL(c, end);

        loc = NO_LOCATION;
        ADDOP_JUMP(c, loc, SETUP_CLEANUP, cleanup);
        ADDOP(c, loc, PUSH_EXC_INFO);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_FINALLY_END, end, NO_LABEL, null);

        VISIT_SEQ_stmt(c, s.finalbody);

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_FINALLY_END, end);
        loc = NO_LOCATION;
        ADDOP_I(c, loc, RERAISE, 0);

        USE_LABEL(c, cleanup);
        POP_EXCEPT_AND_RERAISE(c, loc);

        USE_LABEL(c, exit);
    }


    /*
       Code generated for "try: S except E1 as V1: S1 except E2 as V2: S2 ...":
       (The contents of the value stack is shown in [], with the top
       at the right; 'tb' is trace-back info, 'val' the exception's
       associated value, and 'exc' the exception.)

       Value stack          Label   Instruction     Argument
       []                           SETUP_FINALLY   L1
       []                           <code for S>
       []                           POP_BLOCK
       []                           JUMP            L0

       [exc]                L1:     <evaluate E1>           )
       [exc, E1]                    CHECK_EXC_MATCH         )
       [exc, bool]                  POP_JUMP_IF_FALSE L2    ) only if E1
       [exc]                        <assign to V1>  (or POP if no V1)
       []                           <code for S1>
                                    JUMP            L0

       [exc]                L2:     <evaluate E2>
       .............................etc.......................

       [exc]                Ln+1:   RERAISE     # re-raise exception

       []                   L0:     <next statement>

       Of course, parts are not generated if Vi or Ei is not present.
    */
    private static void codegen_try_except(Compile c, Try s) {
        SourceLocation loc = LOC(s);
        int i, n;

        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel except = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);

        ADDOP_JUMP(c, loc, SETUP_FINALLY, except);

        USE_LABEL(c, body);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_TRY_EXCEPT, body, NO_LABEL, null);
        VISIT_SEQ_stmt(c, s.body);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_TRY_EXCEPT, body);
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        if (s.orelse != null && LEN(s.orelse) != 0) {
            VISIT_SEQ_stmt(c, s.orelse);
        }
        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);
        n = LEN(s.handlers);

        USE_LABEL(c, except);

        ADDOP_JUMP(c, NO_LOCATION, SETUP_CLEANUP, cleanup);
        ADDOP(c, NO_LOCATION, PUSH_EXC_INFO);

        /* Runtime will push a block here, so we need to account for that */
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_EXCEPTION_HANDLER,
                                NO_LABEL, NO_LABEL, null);

        for (i = 0; i < n; i++) {
            ExceptHandler handler = (ExceptHandler) s.handlers.get(i);
            SourceLocation hloc = LOC(handler);
            if (handler.type == null && i < n-1) {
                throw c._PyCompile_Error(hloc, "default 'except:' must be last");
            }
            _PyJumpTargetLabel next_except = NEW_JUMP_TARGET_LABEL(c);
            except = next_except;
            if (handler.type != null) {
                codegen_visit_expr(c, handler.type);
                ADDOP(c, hloc, CHECK_EXC_MATCH);
                ADDOP_JUMP(c, hloc, POP_JUMP_IF_FALSE, except);
            }
            if (handler.name != null) {
                _PyJumpTargetLabel cleanup_end = NEW_JUMP_TARGET_LABEL(c);
                _PyJumpTargetLabel cleanup_body = NEW_JUMP_TARGET_LABEL(c);

                codegen_nameop(c, hloc, handler.name, expr_contextType.Store);

                /*
                  try:
                      # body
                  except type as name:
                      try:
                          # body
                      finally:
                          name = None # in case body contains "del name"
                          del name
                */

                /* second try: */
                ADDOP_JUMP(c, hloc, SETUP_CLEANUP, cleanup_end);

                USE_LABEL(c, cleanup_body);
                c._PyCompile_PushFBlock(hloc, COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body,
                                        NO_LABEL, handler.name);

                /* second # body */
                VISIT_SEQ_stmt(c, handler.body);
                c._PyCompile_PopFBlock(COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body);
                /* name = None; del name; # Mark as artificial */
                ADDOP(c, NO_LOCATION, POP_BLOCK);
                ADDOP(c, NO_LOCATION, POP_BLOCK);
                ADDOP(c, NO_LOCATION, POP_EXCEPT);
                ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Store);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Del);
                ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

                /* except: */
                USE_LABEL(c, cleanup_end);

                /* name = None; del name; # artificial */
                ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Store);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Del);

                ADDOP_I(c, NO_LOCATION, RERAISE, 1);
            }
            else {
                _PyJumpTargetLabel cleanup_body = NEW_JUMP_TARGET_LABEL(c);

                ADDOP(c, hloc, POP_TOP); /* exc_value */

                USE_LABEL(c, cleanup_body);
                c._PyCompile_PushFBlock(hloc, COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body,
                                        NO_LABEL, null);

                VISIT_SEQ_stmt(c, handler.body);
                c._PyCompile_PopFBlock(COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body);
                ADDOP(c, NO_LOCATION, POP_BLOCK);
                ADDOP(c, NO_LOCATION, POP_EXCEPT);
                ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);
            }

            USE_LABEL(c, except);
        }
        /* artificial */
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_EXCEPTION_HANDLER, NO_LABEL);
        ADDOP_I(c, NO_LOCATION, RERAISE, 0);

        USE_LABEL(c, cleanup);
        POP_EXCEPT_AND_RERAISE(c, NO_LOCATION);

        USE_LABEL(c, end);
    }

    /*
       Code generated for "try: S except* E1 as V1: S1 except* E2 as V2: S2 ...":
       (see the comment in codegen.c for the value stack at each step)
    */
    private static void codegen_try_star_except(Compile c, TryStar s) {
        SourceLocation loc = LOC(s);

        _PyJumpTargetLabel body = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel except = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel orelse = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel reraise_star = NEW_JUMP_TARGET_LABEL(c);

        ADDOP_JUMP(c, loc, SETUP_FINALLY, except);

        USE_LABEL(c, body);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_TRY_EXCEPT, body, NO_LABEL, null);
        VISIT_SEQ_stmt(c, s.body);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_TRY_EXCEPT, body);
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, orelse);
        int n = LEN(s.handlers);

        USE_LABEL(c, except);

        ADDOP_JUMP(c, NO_LOCATION, SETUP_CLEANUP, cleanup);
        ADDOP(c, NO_LOCATION, PUSH_EXC_INFO);

        /* Runtime will push a block here, so we need to account for that */
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER,
                                NO_LABEL, NO_LABEL, "except handler");

        for (int i = 0; i < n; i++) {
            ExceptHandler handler = (ExceptHandler) s.handlers.get(i);
            SourceLocation hloc = LOC(handler);
            _PyJumpTargetLabel next_except = NEW_JUMP_TARGET_LABEL(c);
            except = next_except;
            _PyJumpTargetLabel except_with_error = NEW_JUMP_TARGET_LABEL(c);
            _PyJumpTargetLabel no_match = NEW_JUMP_TARGET_LABEL(c);
            if (i == 0) {
                /* create empty list for exceptions raised/reraise in the except* blocks */
                /*
                   [orig]       BUILD_LIST
                */
                /* Create a copy of the original EG */
                /*
                   [orig, []]   COPY 2
                   [orig, [], exc]
                */
                ADDOP_I(c, hloc, BUILD_LIST, 0);
                ADDOP_I(c, hloc, COPY, 2);
            }
            if (handler.type != null) {
                codegen_visit_expr(c, handler.type);
                ADDOP(c, hloc, CHECK_EG_MATCH);
                ADDOP_I(c, hloc, COPY, 1);
                ADDOP_JUMP(c, hloc, POP_JUMP_IF_NONE, no_match);
            }

            _PyJumpTargetLabel cleanup_end = NEW_JUMP_TARGET_LABEL(c);
            _PyJumpTargetLabel cleanup_body = NEW_JUMP_TARGET_LABEL(c);

            if (handler.name != null) {
                codegen_nameop(c, hloc, handler.name, expr_contextType.Store);
            }
            else {
                ADDOP(c, hloc, POP_TOP);  // match
            }

            /*
              try:
                  # body
              except type as name:
                  try:
                      # body
                  finally:
                      name = None # in case body contains "del name"
                      del name
            */
            /* second try: */
            ADDOP_JUMP(c, hloc, SETUP_CLEANUP, cleanup_end);

            USE_LABEL(c, cleanup_body);
            c._PyCompile_PushFBlock(hloc, COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body,
                                    NO_LABEL, handler.name);

            /* second # body */
            VISIT_SEQ_stmt(c, handler.body);
            c._PyCompile_PopFBlock(COMPILE_FBLOCK_HANDLER_CLEANUP, cleanup_body);
            /* name = None; del name; # artificial */
            ADDOP(c, NO_LOCATION, POP_BLOCK);
            if (handler.name != null) {
                ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Store);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Del);
            }
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, except);

            /* except: */
            USE_LABEL(c, cleanup_end);

            /* name = None; del name; # artificial */
            if (handler.name != null) {
                ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Store);
                codegen_nameop(c, NO_LOCATION, handler.name, expr_contextType.Del);
            }

            /* add exception raised to the res list */
            ADDOP_I(c, NO_LOCATION, LIST_APPEND, 3); // exc
            ADDOP(c, NO_LOCATION, POP_TOP); // lasti
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, except_with_error);

            USE_LABEL(c, except);
            ADDOP(c, NO_LOCATION, NOP);  // to hold a propagated location info
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, except_with_error);

            USE_LABEL(c, no_match);
            ADDOP(c, hloc, POP_TOP);  // match (None)

            USE_LABEL(c, except_with_error);

            if (i == n - 1) {
                /* Add exc to the list (if not None it's the unhandled part of the EG) */
                ADDOP_I(c, NO_LOCATION, LIST_APPEND, 1);
                ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, reraise_star);
            }
        }
        /* artificial */
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER, NO_LABEL);
        _PyJumpTargetLabel reraise = NEW_JUMP_TARGET_LABEL(c);

        USE_LABEL(c, reraise_star);
        ADDOP_I(c, NO_LOCATION, CALL_INTRINSIC_2, INTRINSIC_PREP_RERAISE_STAR);
        ADDOP_I(c, NO_LOCATION, COPY, 1);
        ADDOP_JUMP(c, NO_LOCATION, POP_JUMP_IF_NOT_NONE, reraise);

        /* Nothing to reraise */
        ADDOP(c, NO_LOCATION, POP_TOP);
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        ADDOP(c, NO_LOCATION, POP_EXCEPT);
        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

        USE_LABEL(c, reraise);
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        ADDOP_I(c, NO_LOCATION, SWAP, 2);
        ADDOP(c, NO_LOCATION, POP_EXCEPT);
        ADDOP_I(c, NO_LOCATION, RERAISE, 0);

        USE_LABEL(c, cleanup);
        POP_EXCEPT_AND_RERAISE(c, NO_LOCATION);

        USE_LABEL(c, orelse);
        VISIT_SEQ_stmt(c, s.orelse);

        USE_LABEL(c, end);
    }

    private static void codegen_try(Compile c, Try s) {
        if (s.finalbody != null && LEN(s.finalbody) != 0)
            codegen_try_finally(c, s);
        else
            codegen_try_except(c, s);
    }

    private static void codegen_try_star(Compile c, TryStar s) {
        if (s.finalbody != null && LEN(s.finalbody) != 0) {
            codegen_try_star_finally(c, s);
        }
        else {
            codegen_try_star_except(c, s);
        }
    }

    private static void codegen_import_as(Compile c, SourceLocation loc, String name,
            String asname) {
        /* The IMPORT_NAME opcode was already generated.  This function
           merely needs to bind the result to a name.

           If there is a dot in name, we need to split it and emit a
           IMPORT_FROM for each name.
        */
        int len = name.length();
        int dot = name.indexOf('.');
        if (dot != -1) {
            /* Consume the base module name to get the first attribute */
            while (true) {
                int pos = dot + 1;
                String attr;
                dot = name.indexOf('.', pos);
                attr = name.substring(pos, (dot != -1) ? dot : len);
                ADDOP_N(c, loc, IMPORT_FROM, attr, METADATA(c).u_names);
                if (dot == -1) {
                    break;
                }
                ADDOP_I(c, loc, SWAP, 2);
                ADDOP(c, loc, POP_TOP);
            }
            codegen_nameop(c, loc, asname, expr_contextType.Store);
            ADDOP(c, loc, POP_TOP);
            return;
        }
        codegen_nameop(c, loc, asname, expr_contextType.Store);
    }

    private static void codegen_validate_lazy_import(Compile c, SourceLocation loc) {
        if (c._PyCompile_ScopeType() != COMPILE_SCOPE_MODULE) {
            throw c._PyCompile_Error(
                loc, "lazy imports only allowed in module scope");
        }
    }

    private static void codegen_import(Compile c, Import s) {
        SourceLocation loc = LOC(s);
        /* The Import node stores a module name like a.b.c as a single
           string.  This is convenient for all cases except
             import a.b.c as d
           where we need to parse that string to extract the individual
           module names.
           XXX Perhaps change the representation to make this case simpler?
         */
        int i, n = LEN(s.names);

        Object zero = BigInteger.ZERO;
        for (i = 0; i < n; i++) {
            alias alias = s.names.get(i);

            ADDOP_LOAD_CONST(c, loc, zero);
            ADDOP_LOAD_CONST(c, loc, Singleton.None);
            if (s.is_lazy != 0) {
                codegen_validate_lazy_import(c, loc);
                ADDOP_NAME_CUSTOM(c, loc, IMPORT_NAME, alias.name, METADATA(c).u_names, 2, 1);
            } else {
                if (c._PyCompile_InExceptionHandler() ||
                    c._PyCompile_ScopeType() != COMPILE_SCOPE_MODULE) {
                    // force eager import in try/except block
                    ADDOP_NAME_CUSTOM(c, loc, IMPORT_NAME, alias.name, METADATA(c).u_names, 2, 2);
                } else {
                    ADDOP_NAME_CUSTOM(c, loc, IMPORT_NAME, alias.name, METADATA(c).u_names, 2, 0);
                }
            }

            if (alias.asname != null) {
                codegen_import_as(c, loc, alias.name, alias.asname);
            }
            else {
                String tmp = alias.name;
                int dot = alias.name.indexOf('.');
                if (dot != -1) {
                    tmp = alias.name.substring(0, dot);
                }
                codegen_nameop(c, loc, tmp, expr_contextType.Store);
            }
        }
    }

    private static void codegen_from_import(Compile c, ImportFrom s) {
        int n = LEN(s.names);

        ADDOP_LOAD_CONST(c, LOC(s), BigInteger.valueOf(s.level));

        Object[] names = new Object[n];

        /* build up the names */
        for (int i = 0; i < n; i++) {
            alias alias = s.names.get(i);
            names[i] = alias.name;
        }

        ADDOP_LOAD_CONST(c, LOC(s), new PyTuple(names));

        String from = "";
        if (s.module != null) {
            from = s.module;
        }
        if (s.is_lazy != 0) {
            alias alias = s.names.get(0);
            if (alias.name.charAt(0) == '*') {
                throw c._PyCompile_Error(LOC(s), "cannot lazy import *");
            }
            codegen_validate_lazy_import(c, LOC(s));
            ADDOP_NAME_CUSTOM(c, LOC(s), IMPORT_NAME, from, METADATA(c).u_names, 2, 1);
        } else {
            alias alias = s.names.get(0);
            if (c._PyCompile_InExceptionHandler() ||
                c._PyCompile_ScopeType() != COMPILE_SCOPE_MODULE ||
                alias.name.charAt(0) == '*') {
                // forced non-lazy import due to try/except or import *
                ADDOP_NAME_CUSTOM(c, LOC(s), IMPORT_NAME, from, METADATA(c).u_names, 2, 2);
            } else {
                ADDOP_NAME_CUSTOM(c, LOC(s), IMPORT_NAME, from, METADATA(c).u_names, 2, 0);
            }
        }

        for (int i = 0; i < n; i++) {
            alias alias = s.names.get(i);
            String store_name;

            if (i == 0 && alias.name.charAt(0) == '*') {
                assert n == 1;
                ADDOP_I(c, LOC(s), CALL_INTRINSIC_1, INTRINSIC_IMPORT_STAR);
                ADDOP(c, NO_LOCATION, POP_TOP);
                return;
            }

            ADDOP_NAME(c, LOC(s), IMPORT_FROM, alias.name, METADATA(c).u_names);
            store_name = alias.name;
            if (alias.asname != null) {
                store_name = alias.asname;
            }

            codegen_nameop(c, LOC(s), store_name, expr_contextType.Store);
        }
        /* remove imported module */
        ADDOP(c, LOC(s), POP_TOP);
    }

    private static void codegen_assert(Compile c, Assert s) {
        /* Always emit a warning if the test is a non-zero length tuple */
        if ((s.test instanceof Tuple &&
            LEN(((Tuple) s.test).elts) > 0) ||
            (s.test instanceof Constant &&
             ((Constant) s.test).value instanceof PyTuple &&
             ((PyTuple) ((Constant) s.test).value).size() > 0))
        {
            c._PyCompile_Warn(LOC(s), "assertion is always true, "
                                    + "perhaps remove parentheses?");
        }
        if (OPTIMIZATION_LEVEL(c) != 0) {
            return;
        }
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        codegen_jump_if(c, LOC(s), s.test, end, true);
        ADDOP_I(c, LOC(s), LOAD_COMMON_CONSTANT, CONSTANT_ASSERTIONERROR);
        if (s.msg != null) {
            codegen_visit_expr(c, s.msg);
            ADDOP_I(c, LOC(s), CALL, 0);
        }
        ADDOP_I(c, LOC(s.test), RAISE_VARARGS, 1);

        USE_LABEL(c, end);
    }

    private static void codegen_stmt_expr(Compile c, SourceLocation loc, expr value) {
        if (IS_INTERACTIVE_TOP_LEVEL(c)) {
            codegen_visit_expr(c, value);
            ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_PRINT);
            ADDOP(c, NO_LOCATION, POP_TOP);
            return;
        }

        if (value instanceof Constant) {
            /* ignore constant statement */
            ADDOP(c, loc, NOP);
            return;
        }

        codegen_visit_expr(c, value);
        ADDOP(c, NO_LOCATION, POP_TOP); /* artificial */
    }

    /**
     * C: CODEGEN_COND_BLOCK(FUNC, C, S). (An error aborts the compile, so
     * the block is left only on success.)
     */
    private static void CODEGEN_COND_BLOCK(Compile c, Runnable func) {
        c._PyCompile_EnterConditionalBlock();
        func.run();
        c._PyCompile_LeaveConditionalBlock();
    }

    private static void codegen_visit_stmt(Compile c, stmt s) {

        switch (s.kind()) {
        case FunctionDef:
            codegen_function(c, s, false);
            return;
        case ClassDef:
            codegen_class(c, (ClassDef) s);
            return;
        case TypeAlias:
            codegen_typealias(c, (TypeAlias) s);
            return;
        case Return:
            codegen_return(c, (Return) s);
            return;
        case Delete:
            VISIT_SEQ_expr(c, ((Delete) s).targets);
            break;
        case Assign:
        {
            Assign a = (Assign) s;
            int n = LEN(a.targets);
            codegen_visit_expr(c, a.value);
            for (int i = 0; i < n; i++) {
                if (i < n - 1) {
                    ADDOP_I(c, LOC(s), COPY, 1);
                }
                codegen_visit_expr(c, a.targets.get(i));
            }
            break;
        }
        case AugAssign:
            codegen_augassign(c, (AugAssign) s);
            return;
        case AnnAssign:
            codegen_annassign(c, (AnnAssign) s);
            return;
        case For:
            CODEGEN_COND_BLOCK(c, () -> codegen_for(c, (For) s));
            break;
        case While:
            CODEGEN_COND_BLOCK(c, () -> codegen_while(c, (While) s));
            break;
        case If:
            CODEGEN_COND_BLOCK(c, () -> codegen_if(c, (If) s));
            break;
        case Match:
            CODEGEN_COND_BLOCK(c, () -> codegen_match(c, (Match) s));
            break;
        case Raise:
        {
            Raise r = (Raise) s;
            int n = 0;
            if (r.exc != null) {
                codegen_visit_expr(c, r.exc);
                n++;
                if (r.cause != null) {
                    codegen_visit_expr(c, r.cause);
                    n++;
                }
            }
            ADDOP_I(c, LOC(s), RAISE_VARARGS, n);
            break;
        }
        case Try:
            CODEGEN_COND_BLOCK(c, () -> codegen_try(c, (Try) s));
            break;
        case TryStar:
            CODEGEN_COND_BLOCK(c, () -> codegen_try_star(c, (TryStar) s));
            break;
        case Assert:
            codegen_assert(c, (Assert) s);
            return;
        case Import:
            codegen_import(c, (Import) s);
            return;
        case ImportFrom:
            codegen_from_import(c, (ImportFrom) s);
            return;
        case Global:
        case Nonlocal:
            break;
        case Expr:
        {
            codegen_stmt_expr(c, LOC(s), ((Expr) s).value);
            return;
        }
        case Pass:
        {
            ADDOP(c, LOC(s), NOP);
            break;
        }
        case Break:
        {
            codegen_break(c, LOC(s));
            return;
        }
        case Continue:
        {
            codegen_continue(c, LOC(s));
            return;
        }
        case With:
            CODEGEN_COND_BLOCK(c, () -> codegen_with(c, (With) s));
            break;
        case AsyncFunctionDef:
            codegen_function(c, s, true);
            return;
        case AsyncWith:
            CODEGEN_COND_BLOCK(c, () -> codegen_async_with(c, (AsyncWith) s));
            break;
        case AsyncFor:
            CODEGEN_COND_BLOCK(c, () -> codegen_async_for(c, (AsyncFor) s));
            break;
        }
    }

    private static int unaryop(unaryopType op) {
        switch (op) {
        case Invert:
            return UNARY_INVERT;
        case USub:
            return UNARY_NEGATIVE;
        default:
            throw new IllegalStateException("unary op " + op + " should not be possible");
        }
    }

    private static void addop_binary(Compile c, SourceLocation loc, operatorType binop,
            boolean inplace) {
        int oparg;
        switch (binop) {
            case Add:
                oparg = inplace ? NB_INPLACE_ADD : NB_ADD;
                break;
            case Sub:
                oparg = inplace ? NB_INPLACE_SUBTRACT : NB_SUBTRACT;
                break;
            case Mult:
                oparg = inplace ? NB_INPLACE_MULTIPLY : NB_MULTIPLY;
                break;
            case MatMult:
                oparg = inplace ? NB_INPLACE_MATRIX_MULTIPLY : NB_MATRIX_MULTIPLY;
                break;
            case Div:
                oparg = inplace ? NB_INPLACE_TRUE_DIVIDE : NB_TRUE_DIVIDE;
                break;
            case Mod:
                oparg = inplace ? NB_INPLACE_REMAINDER : NB_REMAINDER;
                break;
            case Pow:
                oparg = inplace ? NB_INPLACE_POWER : NB_POWER;
                break;
            case LShift:
                oparg = inplace ? NB_INPLACE_LSHIFT : NB_LSHIFT;
                break;
            case RShift:
                oparg = inplace ? NB_INPLACE_RSHIFT : NB_RSHIFT;
                break;
            case BitOr:
                oparg = inplace ? NB_INPLACE_OR : NB_OR;
                break;
            case BitXor:
                oparg = inplace ? NB_INPLACE_XOR : NB_XOR;
                break;
            case BitAnd:
                oparg = inplace ? NB_INPLACE_AND : NB_AND;
                break;
            case FloorDiv:
                oparg = inplace ? NB_INPLACE_FLOOR_DIVIDE : NB_FLOOR_DIVIDE;
                break;
            default:
                throw new IllegalStateException((inplace ? "inplace" : "binary") + " op "
                        + binop + " should not be possible");
        }
        ADDOP_I(c, loc, BINARY_OP, oparg);
    }


    private static void codegen_addop_yield(Compile c, SourceLocation loc) {
        PySTEntryObject ste = SYMTABLE_ENTRY(c);
        if (ste.ste_generator && ste.ste_coroutine) {
            ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_ASYNC_GEN_WRAP);
        }
        ADDOP_I(c, loc, YIELD_VALUE, 0);
        ADDOP_I(c, loc, RESUME, RESUME_AFTER_YIELD);
    }

    private static void codegen_load_classdict_freevar(Compile c, SourceLocation loc) {
        ADDOP_N(c, loc, LOAD_DEREF, "__classdict__", METADATA(c).u_freevars);
    }

    private static void codegen_nameop(Compile c, SourceLocation loc, String name,
            expr_contextType ctx) {
        assert !name.equals("None") &&
               !name.equals("True") &&
               !name.equals("False");

        String mangled = c._PyCompile_MaybeMangle(name);

        int scope = _PyST_GetScope(SYMTABLE_ENTRY(c), mangled);

        int[] resolved = c._PyCompile_ResolveNameop(mangled, scope);
        int optype = resolved[0];
        int arg = resolved[1];

        /* XXX Leave assert here, but handle __doc__ and the like better */
        assert scope != 0 || name.charAt(0) == '_';

        int op = 0;
        switch (optype) {
        case COMPILE_OP_DEREF:
            switch (ctx) {
            case Load:
                if (SYMTABLE_ENTRY(c).ste_type == _Py_block_ty.ClassBlock
                        && c._PyCompile_IsInInlinedComp() == 0) {
                    op = LOAD_FROM_DICT_OR_DEREF;
                    // First load the locals
                    codegen_addop_noarg(INSTR_SEQUENCE(c), LOAD_LOCALS, loc);
                }
                else if (SYMTABLE_ENTRY(c).ste_can_see_class_scope) {
                    op = LOAD_FROM_DICT_OR_DEREF;
                    // First load the classdict
                    codegen_load_classdict_freevar(c, loc);
                }
                else {
                    op = LOAD_DEREF;
                }
                break;
            case Store: op = STORE_DEREF; break;
            case Del: op = DELETE_DEREF; break;
            }
            break;
        case COMPILE_OP_FAST:
            switch (ctx) {
            case Load: op = LOAD_FAST; break;
            case Store: op = STORE_FAST; break;
            case Del: op = DELETE_FAST; break;
            }
            ADDOP_N(c, loc, op, mangled, METADATA(c).u_varnames);
            return;
        case COMPILE_OP_GLOBAL:
            switch (ctx) {
            case Load:
                if (SYMTABLE_ENTRY(c).ste_can_see_class_scope && scope == GLOBAL_IMPLICIT) {
                    op = LOAD_FROM_DICT_OR_GLOBALS;
                    // First load the classdict
                    codegen_load_classdict_freevar(c, loc);
                } else {
                    op = LOAD_GLOBAL;
                }
                break;
            case Store: op = STORE_GLOBAL; break;
            case Del: op = DELETE_GLOBAL; break;
            }
            break;
        case COMPILE_OP_NAME:
            switch (ctx) {
            case Load:
                op = (SYMTABLE_ENTRY(c).ste_type == _Py_block_ty.ClassBlock
                        && c._PyCompile_IsInInlinedComp() != 0)
                    ? LOAD_GLOBAL
                    : LOAD_NAME;
                break;
            case Store: op = STORE_NAME; break;
            case Del: op = DELETE_NAME; break;
            }
            break;
        }

        assert op != 0;
        if (op == LOAD_GLOBAL) {
            arg <<= 1;
        }
        ADDOP_I(c, loc, op, arg);
    }

    private static void codegen_boolop(Compile c, BoolOp e) {
        int jumpi;
        int i, n;
        List<expr> s;

        SourceLocation loc = LOC(e);
        if (e.op == org.python.pegen.ast.boolopType.And)
            jumpi = JUMP_IF_FALSE;
        else
            jumpi = JUMP_IF_TRUE;
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        s = e.values;
        n = LEN(s) - 1;
        assert n >= 0;
        for (i = 0; i < n; ++i) {
            codegen_visit_expr(c, s.get(i));
            ADDOP_JUMP(c, loc, jumpi, end);
            ADDOP(c, loc, POP_TOP);
        }
        codegen_visit_expr(c, s.get(n));

        USE_LABEL(c, end);
    }

    private static void starunpack_helper_impl(Compile c, SourceLocation loc,
            List<expr> elts, String injected_arg, int pushed,
            int build, int add, int extend, boolean tuple) {
        int n = LEN(elts);
        boolean big = n + pushed + (injected_arg != null ? 1 : 0) > _PY_STACK_USE_GUIDELINE;
        boolean seen_star = false;
        for (int i = 0; i < n; i++) {
            expr elt = elts.get(i);
            if (elt instanceof Starred) {
                seen_star = true;
                break;
            }
        }
        if (!seen_star && !big) {
            for (int i = 0; i < n; i++) {
                expr elt = elts.get(i);
                codegen_visit_expr(c, elt);
            }
            if (injected_arg != null) {
                codegen_nameop(c, loc, injected_arg, expr_contextType.Load);
                n++;
            }
            if (tuple) {
                ADDOP_I(c, loc, BUILD_TUPLE, n+pushed);
            } else {
                ADDOP_I(c, loc, build, n+pushed);
            }
            return;
        }
        boolean sequence_built = false;
        if (big) {
            ADDOP_I(c, loc, build, pushed);
            sequence_built = true;
        }
        for (int i = 0; i < n; i++) {
            expr elt = elts.get(i);
            if (elt instanceof Starred) {
                if (!sequence_built) {
                    ADDOP_I(c, loc, build, i+pushed);
                    sequence_built = true;
                }
                codegen_visit_expr(c, ((Starred) elt).value);
                ADDOP_I(c, loc, extend, 1);
            }
            else {
                codegen_visit_expr(c, elt);
                if (sequence_built) {
                    ADDOP_I(c, loc, add, 1);
                }
            }
        }
        assert sequence_built;
        if (injected_arg != null) {
            codegen_nameop(c, loc, injected_arg, expr_contextType.Load);
            ADDOP_I(c, loc, add, 1);
        }
        if (tuple) {
            ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_LIST_TO_TUPLE);
        }
    }

    private static void starunpack_helper(Compile c, SourceLocation loc, List<expr> elts,
            int pushed, int build, int add, int extend, boolean tuple) {
        starunpack_helper_impl(c, loc, elts, null, pushed,
                               build, add, extend, tuple);
    }

    private static void unpack_helper(Compile c, SourceLocation loc, List<expr> elts) {
        int n = LEN(elts);
        boolean seen_star = false;
        for (int i = 0; i < n; i++) {
            expr elt = elts.get(i);
            if (elt instanceof Starred && !seen_star) {
                if ((i >= (1 << 8)) ||
                    (n-i-1 >= (Integer.MAX_VALUE >> 8))) {
                    throw c._PyCompile_Error(loc,
                        "too many expressions in "
                        + "star-unpacking assignment");
                }
                ADDOP_I(c, loc, UNPACK_EX, (i + ((n-i-1) << 8)));
                seen_star = true;
            }
            else if (elt instanceof Starred) {
                throw c._PyCompile_Error(loc,
                    "multiple starred expressions in assignment");
            }
        }
        if (!seen_star) {
            ADDOP_I(c, loc, UNPACK_SEQUENCE, n);
        }
    }

    private static void assignment_helper(Compile c, SourceLocation loc, List<expr> elts) {
        int n = LEN(elts);
        unpack_helper(c, loc, elts);
        for (int i = 0; i < n; i++) {
            expr elt = elts.get(i);
            codegen_visit_expr(c, !(elt instanceof Starred) ? elt : ((Starred) elt).value);
        }
    }

    private static void codegen_list(Compile c, org.python.pegen.ast.List e) {
        SourceLocation loc = LOC(e);
        List<expr> elts = e.elts;
        if (e.ctx == expr_contextType.Store) {
            assignment_helper(c, loc, elts);
        }
        else if (e.ctx == expr_contextType.Load) {
            starunpack_helper(c, loc, elts, 0,
                              BUILD_LIST, LIST_APPEND, LIST_EXTEND, false);
        }
        else {
            VISIT_SEQ_expr(c, elts);
        }
    }

    private static void codegen_tuple(Compile c, Tuple e) {
        SourceLocation loc = LOC(e);
        List<expr> elts = e.elts;
        if (e.ctx == expr_contextType.Store) {
            assignment_helper(c, loc, elts);
        }
        else if (e.ctx == expr_contextType.Load) {
            starunpack_helper(c, loc, elts, 0,
                              BUILD_LIST, LIST_APPEND, LIST_EXTEND, true);
        }
        else {
            VISIT_SEQ_expr(c, elts);
        }
    }

    private static void codegen_set(Compile c, org.python.pegen.ast.Set e) {
        SourceLocation loc = LOC(e);
        starunpack_helper(c, loc, e.elts, 0,
                          BUILD_SET, SET_ADD, SET_UPDATE, false);
    }

    private static void codegen_subdict(Compile c, Dict e, int begin, int end) {
        int i, n = end - begin;
        boolean big = n*2 > _PY_STACK_USE_GUIDELINE;
        SourceLocation loc = LOC(e);
        if (big) {
            ADDOP_I(c, loc, BUILD_MAP, 0);
        }
        for (i = begin; i < end; i++) {
            codegen_visit_expr(c, e.keys.get(i));
            codegen_visit_expr(c, e.values.get(i));
            if (big) {
                ADDOP_I(c, loc, MAP_ADD, 1);
            }
        }
        if (!big) {
            ADDOP_I(c, loc, BUILD_MAP, n);
        }
    }

    private static void codegen_dict(Compile c, Dict e) {
        SourceLocation loc = LOC(e);
        int i, n, elements;
        boolean have_dict;
        boolean is_unpacking = false;
        n = LEN(e.values);
        have_dict = false;
        elements = 0;
        for (i = 0; i < n; i++) {
            is_unpacking = e.keys.get(i) == null;
            if (is_unpacking) {
                if (elements != 0) {
                    codegen_subdict(c, e, i - elements, i);
                    if (have_dict) {
                        ADDOP_I(c, loc, DICT_UPDATE, 1);
                    }
                    have_dict = true;
                    elements = 0;
                }
                if (!have_dict) {
                    ADDOP_I(c, loc, BUILD_MAP, 0);
                    have_dict = true;
                }
                codegen_visit_expr(c, e.values.get(i));
                ADDOP_I(c, loc, DICT_UPDATE, 1);
            }
            else {
                if (elements*2 > _PY_STACK_USE_GUIDELINE) {
                    codegen_subdict(c, e, i - elements, i + 1);
                    if (have_dict) {
                        ADDOP_I(c, loc, DICT_UPDATE, 1);
                    }
                    have_dict = true;
                    elements = 0;
                }
                else {
                    elements++;
                }
            }
        }
        if (elements != 0) {
            codegen_subdict(c, e, n - elements, n);
            if (have_dict) {
                ADDOP_I(c, loc, DICT_UPDATE, 1);
            }
            have_dict = true;
        }
        if (!have_dict) {
            ADDOP_I(c, loc, BUILD_MAP, 0);
        }
    }

    private static void codegen_compare(Compile c, Compare e) {
        SourceLocation loc = LOC(e);
        int i, n;

        codegen_check_compare(c, e);
        codegen_visit_expr(c, e.left);
        assert LEN(e.ops) > 0;
        n = LEN(e.ops) - 1;
        if (n == 0) {
            codegen_visit_expr(c, e.comparators.get(0));
            ADDOP_COMPARE(c, loc, e.ops.get(0));
        }
        else {
            _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
            for (i = 0; i < n; i++) {
                codegen_visit_expr(c, e.comparators.get(i));
                ADDOP_I(c, loc, SWAP, 2);
                ADDOP_I(c, loc, COPY, 2);
                ADDOP_COMPARE(c, loc, e.ops.get(i));
                ADDOP_I(c, loc, COPY, 1);
                ADDOP(c, loc, TO_BOOL);
                ADDOP_JUMP(c, loc, POP_JUMP_IF_FALSE, cleanup);
                ADDOP(c, loc, POP_TOP);
            }
            codegen_visit_expr(c, e.comparators.get(n));
            ADDOP_COMPARE(c, loc, e.ops.get(n));
            _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

            USE_LABEL(c, cleanup);
            ADDOP_I(c, loc, SWAP, 2);
            ADDOP(c, loc, POP_TOP);

            USE_LABEL(c, end);
        }
    }

    /** C: infer_type, returning the type's tp_name (or null for NULL). */
    private static String infer_type(expr e) {
        switch (e.kind()) {
        case Tuple:
            return "tuple";
        case List:
        case ListComp:
            return "list";
        case Dict:
        case DictComp:
            return "dict";
        case Set:
        case SetComp:
            return "set";
        case GeneratorExp:
            return "generator";
        case Lambda:
            return "function";
        case TemplateStr:
        case Interpolation:
            return "string.templatelib.Template";
        case JoinedStr:
        case FormattedValue:
            return "str";
        case Constant:
            return Repr.typeName(((Constant) e).value);
        default:
            return null;
        }
    }

    private static void check_caller(Compile c, expr e) {
        switch (e.kind()) {
        case Constant:
        case Tuple:
        case List:
        case ListComp:
        case Dict:
        case DictComp:
        case Set:
        case SetComp:
        case GeneratorExp:
        case JoinedStr:
        case TemplateStr:
        case FormattedValue:
        case Interpolation: {
            SourceLocation loc = LOC(e);
            c._PyCompile_Warn(loc, "'%.200s' object is not callable; "
                                 + "perhaps you missed a comma?",
                                 infer_type(e));
            return;
        }
        default:
            return;
        }
    }

    private static void check_subscripter(Compile c, expr e) {
        Object v;

        switch (e.kind()) {
        case Constant:
            v = ((Constant) e).value;
            if (!(v == Singleton.None || v == Singleton.Ellipsis ||
                  v instanceof BigInteger || v == Singleton.True || v == Singleton.False ||
                  v instanceof Double || v instanceof org.python.pegen.ast.Complex ||
                  v instanceof PyFrozenSet))
            {
                return;
            }
            // fall through
        case Set:
        case SetComp:
        case GeneratorExp:
        case TemplateStr:
        case Interpolation:
        case Lambda: {
            SourceLocation loc = LOC(e);
            c._PyCompile_Warn(loc, "'%.200s' object is not subscriptable; "
                                 + "perhaps you missed a comma?",
                                 infer_type(e));
            return;
        }
        default:
            return;
        }
    }

    private static void check_index(Compile c, expr e, expr s) {
        Object v;

        String index_type = infer_type(s);
        if (index_type == null
            || index_type.equals("int") || index_type.equals("bool")
            || index_type.equals("slice")) {
            return;
        }

        switch (e.kind()) {
        case Constant:
            v = ((Constant) e).value;
            if (!(v instanceof String || v instanceof org.python.pegen.ast.Bytes
                    || v instanceof PyTuple)) {
                return;
            }
            // fall through
        case Tuple:
        case List:
        case ListComp:
        case JoinedStr:
        case FormattedValue: {
            SourceLocation loc = LOC(e);
            c._PyCompile_Warn(loc, "%.200s indices must be integers "
                                 + "or slices, not %.200s; "
                                 + "perhaps you missed a comma?",
                                 infer_type(e),
                                 index_type);
            return;
        }
        default:
            return;
        }
    }

    private static boolean is_import_originated(Compile c, expr e) {
        /* Check whether the global scope has an import named
         e, if it is a Name object. For not traversing all the
         scope stack every time this function is called, it will
         only check the global scope to determine whether something
         is imported or not. */

        if (!(e instanceof Name)) {
            return false;
        }

        int flags = _PyST_GetSymbol(SYMTABLE(c).st_top, ((Name) e).id);
        return (flags & DEF_IMPORT) != 0;
    }

    private static boolean can_optimize_super_call(Compile c, Attribute attr) {
        expr e = attr.value;
        if (!(e instanceof Call) ||
            !(((Call) e).func instanceof Name) ||
            !((Name) ((Call) e).func).id.equals("super") ||
            attr.attr.equals("__class__") ||
            LEN(((Call) e).keywords) != 0) {
            return false;
        }
        Call call = (Call) e;
        int num_args = LEN(call.args);

        String super_name = ((Name) call.func).id;
        // detect statically-visible shadowing of 'super' name
        int scope = _PyST_GetScope(SYMTABLE_ENTRY(c), super_name);
        if (scope != GLOBAL_IMPLICIT) {
            return false;
        }
        scope = _PyST_GetScope(SYMTABLE(c).st_top, super_name);
        if (scope != 0) {
            return false;
        }

        if (num_args == 2) {
            for (int i = 0; i < num_args; i++) {
                expr elt = call.args.get(i);
                if (elt instanceof Starred) {
                    return false;
                }
            }
            // exactly two non-starred args; we can just load
            // the provided args
            return true;
        }

        if (num_args != 0) {
            return false;
        }
        // we need the following for zero-arg super():

        // enclosing function should have at least one argument
        if (METADATA(c).u_argcount == 0 &&
            METADATA(c).u_posonlyargcount == 0) {
            return false;
        }
        // __class__ cell should be available
        if (c._PyCompile_GetRefType("__class__") == FREE) {
            return true;
        }
        return false;
    }

    private static void load_args_for_super(Compile c, Call e) {
        SourceLocation loc = LOC(e);

        // load super() global
        String super_name = ((Name) e.func).id;
        codegen_nameop(c, LOC(e.func), super_name, expr_contextType.Load);

        if (LEN(e.args) == 2) {
            codegen_visit_expr(c, e.args.get(0));
            codegen_visit_expr(c, e.args.get(1));
            return;
        }

        // load __class__ cell
        String name = "__class__";
        assert c._PyCompile_GetRefType(name) == FREE;
        codegen_nameop(c, loc, name, expr_contextType.Load);

        // load self (first argument)
        Object key = METADATA(c).u_varnames.keySet().iterator().next();
        codegen_nameop(c, loc, (String) key, expr_contextType.Load);
    }

    // If an attribute access spans multiple lines, update the current start
    // location to point to the attribute name.
    private static SourceLocation update_start_location_to_match_attr(Compile c,
            SourceLocation loc, Attribute attr) {
        if (loc.lineno != attr.end_lineno) {
            int lineno = attr.end_lineno;
            int col_offset = loc.col_offset;
            int end_lineno = loc.end_lineno;
            int end_col_offset = loc.end_col_offset;
            int len = attr.attr.codePointCount(0, attr.attr.length());
            if (len <= attr.end_col_offset) {
                col_offset = attr.end_col_offset - len;
            }
            else {
                // GH-94694: Somebody's compiling weird ASTs. Just drop the columns:
                col_offset = -1;
                end_col_offset = -1;
            }
            // Make sure the end position still follows the start position, even for
            // weird ASTs:
            end_lineno = Math.max(lineno, end_lineno);
            if (lineno == end_lineno) {
                end_col_offset = Math.max(col_offset, end_col_offset);
            }
            return new SourceLocation(lineno, end_lineno, col_offset, end_col_offset);
        }
        return loc;
    }

    private static boolean maybe_optimize_function_call(Compile c, Call e,
            _PyJumpTargetLabel end) {
        List<expr> args = e.args;
        List<keyword> kwds = e.keywords;
        expr func = e.func;

        if (! (func instanceof Name &&
               LEN(args) == 1 &&
               LEN(kwds) == 0 &&
               args.get(0) instanceof GeneratorExp))
        {
            return false;
        }

        expr generator_exp = args.get(0);
        PySTEntryObject generator_entry =
                SYMTABLE(c)._PySymtable_Lookup(new BlockKey(generator_exp));
        if (generator_entry.ste_coroutine) {
            return false;
        }

        SourceLocation loc = LOC(func);

        boolean optimized = false;
        _PyJumpTargetLabel skip_optimization = NEW_JUMP_TARGET_LABEL(c);

        int const_oparg = -1;
        Object initial_res = null;
        int continue_jump_opcode = -1;
        String id = ((Name) func).id;
        if (id.equals("all")) {
            const_oparg = CONSTANT_BUILTIN_ALL;
            initial_res = Singleton.True;
            continue_jump_opcode = POP_JUMP_IF_TRUE;
        }
        else if (id.equals("any")) {
            const_oparg = CONSTANT_BUILTIN_ANY;
            initial_res = Singleton.False;
            continue_jump_opcode = POP_JUMP_IF_FALSE;
        }
        else if (id.equals("tuple")) {
            const_oparg = CONSTANT_BUILTIN_TUPLE;
        }
        else if (id.equals("list")) {
            const_oparg = CONSTANT_BUILTIN_LIST;
        }
        else if (id.equals("set")) {
            const_oparg = CONSTANT_BUILTIN_SET;
        }
        if (const_oparg != -1) {
            ADDOP_I(c, loc, COPY, 1); // the function
            ADDOP_I(c, loc, LOAD_COMMON_CONSTANT, const_oparg);
            ADDOP_COMPARE(c, loc, cmpopType.Is);
            ADDOP_JUMP(c, loc, POP_JUMP_IF_FALSE, skip_optimization);
            ADDOP(c, loc, POP_TOP);

            if (const_oparg == CONSTANT_BUILTIN_TUPLE || const_oparg == CONSTANT_BUILTIN_LIST) {
                ADDOP_I(c, loc, BUILD_LIST, 0);
            } else if (const_oparg == CONSTANT_BUILTIN_SET) {
                ADDOP_I(c, loc, BUILD_SET, 0);
            }
            codegen_visit_expr(c, generator_exp);

            _PyJumpTargetLabel loop = NEW_JUMP_TARGET_LABEL(c);
            _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);

            ADDOP(c, loc, PUSH_NULL); // Push NULL index for loop
            USE_LABEL(c, loop);
            ADDOP_JUMP(c, loc, FOR_ITER, cleanup);
            if (const_oparg == CONSTANT_BUILTIN_TUPLE || const_oparg == CONSTANT_BUILTIN_LIST) {
                ADDOP_I(c, loc, LIST_APPEND, 3);
                ADDOP_JUMP(c, loc, JUMP, loop);
            } else if (const_oparg == CONSTANT_BUILTIN_SET) {
                ADDOP_I(c, loc, SET_ADD, 3);
                ADDOP_JUMP(c, loc, JUMP, loop);
            }
            else {
                ADDOP(c, loc, TO_BOOL);
                ADDOP_JUMP(c, loc, continue_jump_opcode, loop);
            }

            ADDOP(c, NO_LOCATION, POP_ITER);
            if (const_oparg != CONSTANT_BUILTIN_TUPLE &&
                const_oparg != CONSTANT_BUILTIN_LIST &&
                const_oparg != CONSTANT_BUILTIN_SET) {
                ADDOP_LOAD_CONST(c, loc,
                        initial_res == Singleton.True ? Singleton.False : Singleton.True);
            }
            ADDOP_JUMP(c, loc, JUMP, end);

            USE_LABEL(c, cleanup);
            ADDOP(c, NO_LOCATION, END_FOR);
            ADDOP(c, NO_LOCATION, POP_ITER);
            if (const_oparg == CONSTANT_BUILTIN_TUPLE) {
                ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_LIST_TO_TUPLE);
            } else if (const_oparg == CONSTANT_BUILTIN_LIST) {
                // result is already a list
            } else if (const_oparg == CONSTANT_BUILTIN_SET) {
                // result is already a set
            }
            else {
                ADDOP_LOAD_CONST(c, loc, initial_res);
            }

            optimized = true;
            ADDOP_JUMP(c, loc, JUMP, end);
        }
        USE_LABEL(c, skip_optimization);
        return optimized;
    }

    // Return true if the method call was optimized, false if not.
    private static boolean maybe_optimize_method_call(Compile c, Call e) {
        int argsl, i, kwdsl;
        expr meth = e.func;
        List<expr> args = e.args;
        List<keyword> kwds = e.keywords;

        /* Check that the call node is an attribute access */
        if (!(meth instanceof Attribute)
                || ((Attribute) meth).ctx != expr_contextType.Load) {
            return false;
        }
        Attribute attr = (Attribute) meth;

        /* Check that the base object is not something that is imported */
        if (is_import_originated(c, attr.value)) {
            return false;
        }

        /* Check that there aren't too many arguments */
        argsl = LEN(args);
        kwdsl = LEN(kwds);
        if (argsl + kwdsl + (kwdsl != 0 ? 1 : 0) >= _PY_STACK_USE_GUIDELINE) {
            return false;
        }
        /* Check that there are no *varargs types of arguments. */
        for (i = 0; i < argsl; i++) {
            expr elt = args.get(i);
            if (elt instanceof Starred) {
                return false;
            }
        }

        for (i = 0; i < kwdsl; i++) {
            keyword kw = kwds.get(i);
            if (kw.arg == null) {
                return false;
            }
        }

        /* Alright, we can optimize the code. */
        SourceLocation loc = LOC(meth);

        if (can_optimize_super_call(c, attr)) {
            load_args_for_super(c, (Call) attr.value);
            int opcode = LEN(((Call) attr.value).args) != 0 ?
                LOAD_SUPER_METHOD : LOAD_ZERO_SUPER_METHOD;
            ADDOP_NAME(c, loc, opcode, attr.attr, METADATA(c).u_names);
            loc = update_start_location_to_match_attr(c, loc, attr);
            ADDOP(c, loc, NOP);
        } else {
            codegen_visit_expr(c, attr.value);
            loc = update_start_location_to_match_attr(c, loc, attr);
            ADDOP_NAME(c, loc, LOAD_METHOD, attr.attr, METADATA(c).u_names);
        }

        VISIT_SEQ_expr(c, e.args);

        if (kwdsl != 0) {
            VISIT_SEQ_keyword(c, kwds);
            codegen_call_simple_kw_helper(c, loc, kwds, kwdsl);
            loc = update_start_location_to_match_attr(c, LOC(e), attr);
            ADDOP_I(c, loc, CALL_KW, argsl + kwdsl);
        }
        else {
            loc = update_start_location_to_match_attr(c, LOC(e), attr);
            ADDOP_I(c, loc, CALL, argsl);
        }
        return true;
    }

    private static void codegen_validate_keywords(Compile c, List<keyword> keywords) {
        int nkeywords = LEN(keywords);
        for (int i = 0; i < nkeywords; i++) {
            keyword key = keywords.get(i);
            if (key.arg == null) {
                continue;
            }
            for (int j = i + 1; j < nkeywords; j++) {
                keyword other = keywords.get(j);
                if (other.arg != null && key.arg.equals(other.arg)) {
                    throw c._PyCompile_Error(LOC(other), "keyword argument repeated: %s",
                            key.arg);
                }
            }
        }
    }

    private static void codegen_call(Compile c, Call e) {
        codegen_validate_keywords(c, e.keywords);
        if (maybe_optimize_method_call(c, e)) {
            return;
        }
        _PyJumpTargetLabel skip_normal_call = NEW_JUMP_TARGET_LABEL(c);
        check_caller(c, e.func);
        codegen_visit_expr(c, e.func);
        maybe_optimize_function_call(c, e, skip_normal_call);
        SourceLocation loc = LOC(e.func);
        ADDOP(c, loc, PUSH_NULL);
        loc = LOC(e);
        codegen_call_helper(c, loc, 0,
                            e.args,
                            e.keywords);
        USE_LABEL(c, skip_normal_call);
    }

    private static void codegen_template_str(Compile c, TemplateStr e) {
        SourceLocation loc = LOC(e);
        expr value;

        int value_count = LEN(e.values);
        boolean last_was_interpolation = true;
        int stringslen = 0;
        for (int i = 0; i < value_count; i++) {
            value = e.values.get(i);
            if (value instanceof Interpolation) {
                if (last_was_interpolation) {
                    ADDOP_LOAD_CONST(c, loc, "");
                    stringslen++;
                }
                last_was_interpolation = true;
            }
            else {
                codegen_visit_expr(c, value);
                stringslen++;
                last_was_interpolation = false;
            }
        }
        if (last_was_interpolation) {
            ADDOP_LOAD_CONST(c, loc, "");
            stringslen++;
        }
        ADDOP_I(c, loc, BUILD_TUPLE, stringslen);

        int interpolationslen = 0;
        for (int i = 0; i < value_count; i++) {
            value = e.values.get(i);
            if (value instanceof Interpolation) {
                codegen_visit_expr(c, value);
                interpolationslen++;
            }
        }
        ADDOP_I(c, loc, BUILD_TUPLE, interpolationslen);
        ADDOP(c, loc, BUILD_TEMPLATE);
    }

    private static void codegen_joined_str(Compile c, JoinedStr e) {
        SourceLocation loc = LOC(e);
        int value_count = LEN(e.values);
        if (value_count > _PY_STACK_USE_GUIDELINE) {
            ADDOP_LOAD_CONST(c, loc, "");
            ADDOP_NAME(c, loc, LOAD_METHOD, "join", METADATA(c).u_names);
            ADDOP_I(c, loc, BUILD_LIST, 0);
            for (int i = 0; i < LEN(e.values); i++) {
                codegen_visit_expr(c, e.values.get(i));
                ADDOP_I(c, loc, LIST_APPEND, 1);
            }
            ADDOP_I(c, loc, CALL, 1);
        }
        else {
            VISIT_SEQ_expr(c, e.values);
            if (value_count > 1) {
                ADDOP_I(c, loc, BUILD_STRING, value_count);
            }
            else if (value_count == 0) {
                ADDOP_LOAD_CONST(c, loc, "");
            }
        }
    }

    /* Include/ceval.h: FVC_* conversions of CONVERT_VALUE */
    private static final int FVC_STR = 0x1;
    private static final int FVC_REPR = 0x2;
    private static final int FVC_ASCII = 0x3;

    private static void codegen_interpolation(Compile c, Interpolation e) {
        SourceLocation loc = LOC(e);

        codegen_visit_expr(c, e.value);
        ADDOP_LOAD_CONST(c, loc, e.str);

        int oparg = 2;
        if (e.format_spec != null) {
            oparg++;
            codegen_visit_expr(c, e.format_spec);
        }

        int conversion = e.conversion;
        if (conversion != -1) {
            switch (conversion) {
            case 's': oparg |= FVC_STR << 2;   break;
            case 'r': oparg |= FVC_REPR << 2;  break;
            case 'a': oparg |= FVC_ASCII << 2; break;
            default:
                throw new IllegalStateException(
                         "Unrecognized conversion character " + conversion);
            }
        }

        ADDOP_I(c, loc, BUILD_INTERPOLATION, oparg);
    }

    /* Used to implement f-strings. Format a single value. */
    private static void codegen_formatted_value(Compile c, FormattedValue e) {
        int conversion = e.conversion;
        int oparg;

        /* The expression to be formatted. */
        codegen_visit_expr(c, e.value);

        SourceLocation loc = LOC(e);
        if (conversion != -1) {
            switch (conversion) {
            case 's': oparg = FVC_STR;   break;
            case 'r': oparg = FVC_REPR;  break;
            case 'a': oparg = FVC_ASCII; break;
            default:
                throw new IllegalStateException(
                         "Unrecognized conversion character " + conversion);
            }
            ADDOP_I(c, loc, CONVERT_VALUE, oparg);
        }
        if (e.format_spec != null) {
            /* Evaluate the format spec, and update our opcode arg. */
            codegen_visit_expr(c, e.format_spec);
            ADDOP(c, loc, FORMAT_WITH_SPEC);
        } else {
            ADDOP(c, loc, FORMAT_SIMPLE);
        }
    }

    private static void codegen_subkwargs(Compile c, SourceLocation loc, List<keyword> keywords,
            int begin, int end) {
        int i, n = end - begin;
        keyword kw;
        assert n > 0;
        boolean big = n*2 > _PY_STACK_USE_GUIDELINE;
        if (big) {
            ADDOP_I(c, NO_LOCATION, BUILD_MAP, 0);
        }
        for (i = begin; i < end; i++) {
            kw = keywords.get(i);
            ADDOP_LOAD_CONST(c, loc, kw.arg);
            codegen_visit_expr(c, kw.value);
            if (big) {
                ADDOP_I(c, NO_LOCATION, MAP_ADD, 1);
            }
        }
        if (!big) {
            ADDOP_I(c, loc, BUILD_MAP, n);
        }
    }

    /* Used by codegen_call_helper and maybe_optimize_method_call to emit
     * a tuple of keyword names before CALL.
     */
    private static void codegen_call_simple_kw_helper(Compile c, SourceLocation loc,
            List<keyword> keywords, int nkwelts) {
        Object[] names = new Object[nkwelts];
        for (int i = 0; i < nkwelts; i++) {
            keyword kw = keywords.get(i);
            names[i] = kw.arg;
        }
        ADDOP_LOAD_CONST(c, loc, new PyTuple(names));
    }

    /* shared code between codegen_call and codegen_class */
    private static void codegen_call_helper_impl(Compile c, SourceLocation loc,
            int n, /* Args already pushed */
            List<expr> args, String injected_arg, List<keyword> keywords) {
        int i, nseen, nelts, nkwelts;

        codegen_validate_keywords(c, keywords);

        nelts = LEN(args);
        nkwelts = LEN(keywords);

        boolean ex_call = nelts + nkwelts*2 > _PY_STACK_USE_GUIDELINE;
        for (i = 0; !ex_call && i < nelts; i++) {
            expr elt = args.get(i);
            if (elt instanceof Starred) {
                ex_call = true;
            }
        }
        for (i = 0; !ex_call && i < nkwelts; i++) {
            keyword kw = keywords.get(i);
            if (kw.arg == null) {
                ex_call = true;
            }
        }

        if (!ex_call) {
            /* No * or ** args, so can use faster calling sequence */
            for (i = 0; i < nelts; i++) {
                expr elt = args.get(i);
                assert !(elt instanceof Starred);
                codegen_visit_expr(c, elt);
            }
            if (injected_arg != null) {
                codegen_nameop(c, loc, injected_arg, expr_contextType.Load);
                nelts++;
            }
            if (nkwelts != 0) {
                VISIT_SEQ_keyword(c, keywords);
                codegen_call_simple_kw_helper(c, loc, keywords, nkwelts);
                ADDOP_I(c, loc, CALL_KW, n + nelts + nkwelts);
            }
            else {
                ADDOP_I(c, loc, CALL, n + nelts);
            }
            return;
        }

        // ex_call:

        /* Do positional arguments. */
        if (n == 0 && nelts == 1 && args.get(0) instanceof Starred) {
            codegen_visit_expr(c, ((Starred) args.get(0)).value);
        }
        else {
            starunpack_helper_impl(c, loc, args, injected_arg, n,
                                   BUILD_LIST, LIST_APPEND, LIST_EXTEND, true);
        }
        /* Then keyword arguments */
        if (nkwelts != 0) {
            /* Has a new dict been pushed */
            boolean have_dict = false;

            nseen = 0;  /* the number of keyword arguments on the stack following */
            for (i = 0; i < nkwelts; i++) {
                keyword kw = keywords.get(i);
                if (kw.arg == null) {
                    /* A keyword argument unpacking. */
                    if (nseen != 0) {
                        codegen_subkwargs(c, loc, keywords, i - nseen, i);
                        if (have_dict) {
                            ADDOP_I(c, loc, DICT_MERGE, 1);
                        }
                        have_dict = true;
                        nseen = 0;
                    }
                    if (!have_dict) {
                        ADDOP_I(c, loc, BUILD_MAP, 0);
                        have_dict = true;
                    }
                    codegen_visit_expr(c, kw.value);
                    ADDOP_I(c, loc, DICT_MERGE, 1);
                }
                else {
                    nseen++;
                }
            }
            if (nseen != 0) {
                /* Pack up any trailing keyword arguments. */
                codegen_subkwargs(c, loc, keywords, nkwelts - nseen, nkwelts);
                if (have_dict) {
                    ADDOP_I(c, loc, DICT_MERGE, 1);
                }
                have_dict = true;
            }
            assert have_dict;
        }
        if (nkwelts == 0) {
            ADDOP(c, loc, PUSH_NULL);
        }
        ADDOP(c, loc, CALL_FUNCTION_EX);
    }

    private static void codegen_call_helper(Compile c, SourceLocation loc,
            int n, /* Args already pushed */
            List<expr> args, List<keyword> keywords) {
        codegen_call_helper_impl(c, loc, n, args, null, keywords);
    }

    /* List and set comprehensions work by being inlined at the location where
      they are defined. The isolation of iteration variables is provided by
      pushing/popping clashing locals on the stack. Generator expressions work
      by creating a nested function to perform the actual iteration.
      This means that the iteration variables don't leak into the current scope.
      See https://peps.python.org/pep-0709/ for additional information.
      The defined function is called immediately following its definition, with the
      result of that call being the result of the expression.
      The LC/SC version returns the populated container, while the GE version is
      flagged in symtable.c as a generator, so it returns the generator object
      when the function is called.

      Possible cleanups:
        - iterate over the generator sequence instead of using recursion
    */


    private static void codegen_comprehension_generator(Compile c, SourceLocation loc,
            List<comprehension> generators, int gen_index, int depth,
            expr elt, expr val, int type, int iter_pos) {
        comprehension gen;
        gen = generators.get(gen_index);
        if (gen.is_async != 0) {
            codegen_async_comprehension_generator(
                c, loc, generators, gen_index, depth, elt, val, type,
                iter_pos);
        } else {
            codegen_sync_comprehension_generator(
                c, loc, generators, gen_index, depth, elt, val, type,
                iter_pos);
        }
    }

    private static void codegen_sync_comprehension_generator(Compile c, SourceLocation loc,
            List<comprehension> generators, int gen_index, int depth,
            expr elt, expr val, int type, int iter_pos) {
        /* generate code for the iterator, then each of the ifs,
           and then write to the element */

        _PyJumpTargetLabel start = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel if_cleanup = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel anchor = NEW_JUMP_TARGET_LABEL(c);

        comprehension gen = generators.get(gen_index);

        if (iter_pos == ITERABLE_IN_LOCAL) {
            if (gen_index == 0) {
                assert METADATA(c).u_argcount == 1;
                ADDOP_I(c, loc, LOAD_FAST, 0);
            }
            else {
                /* Sub-iter - calculate on the fly */
                /* Fast path for the temporary variable assignment idiom:
                    for y in [f(x)]
                */
                List<expr> elts;
                switch (gen.iter.kind()) {
                    case List:
                        elts = ((org.python.pegen.ast.List) gen.iter).elts;
                        break;
                    case Tuple:
                        elts = ((Tuple) gen.iter).elts;
                        break;
                    default:
                        elts = null;
                }
                if (LEN(elts) == 1) {
                    expr elt0 = elts.get(0);
                    if (!(elt0 instanceof Starred)) {
                        codegen_visit_expr(c, elt0);
                        start = NO_LABEL;
                    }
                }
                if (IS_JUMP_TARGET_LABEL(start)) {
                    codegen_visit_expr(c, gen.iter);
                }
            }
        }

        if (IS_JUMP_TARGET_LABEL(start)) {
            if (iter_pos != ITERATOR_ON_STACK) {
                ADDOP_I(c, LOC(gen.iter), GET_ITER, 0);
                depth += 1;
            }
            USE_LABEL(c, start);
            depth += 1;
            ADDOP_JUMP(c, LOC(gen.iter), FOR_ITER, anchor);
        }
        codegen_visit_expr(c, gen.target);

        /* XXX this needs to be cleaned up...a lot! */
        int n = LEN(gen.ifs);
        for (int i = 0; i < n; i++) {
            expr e = gen.ifs.get(i);
            codegen_jump_if(c, loc, e, if_cleanup, false);
        }

        if (++gen_index < LEN(generators)) {
            codegen_comprehension_generator(c, loc,
                                            generators, gen_index, depth,
                                            elt, val, type, ITERABLE_IN_LOCAL);
        }

        SourceLocation elt_loc = LOC(elt);

        /* only append after the last for generator */
        if (gen_index >= LEN(generators)) {
            codegen_comprehension_element(c, elt, val, type, depth, elt_loc);
            if (type == COMP_DICTCOMP && val != null) {
                elt_loc = LOCATION(elt.lineno,
                                   val.end_lineno,
                                   elt.col_offset,
                                   val.end_col_offset);
            }
        }

        USE_LABEL(c, if_cleanup);
        if (IS_JUMP_TARGET_LABEL(start)) {
            ADDOP_JUMP(c, elt_loc, JUMP, start);

            USE_LABEL(c, anchor);
            /* It is important for instrumentation that the `END_FOR` comes first.
            * Iteration over a generator will jump to the first of these instructions,
            * but a non-generator will jump to a later instruction.
            */
            ADDOP(c, NO_LOCATION, END_FOR);
            ADDOP(c, NO_LOCATION, POP_ITER);
        }
    }

    /**
     * The "comprehension specific code" C repeats in both
     * codegen_sync_comprehension_generator and
     * codegen_async_comprehension_generator.
     */
    private static void codegen_comprehension_element(Compile c, expr elt, expr val, int type,
            int depth, SourceLocation elt_loc) {
        switch (type) {
        case COMP_GENEXP:
            if (elt instanceof Starred) {
                _PyJumpTargetLabel unpack_start = NEW_JUMP_TARGET_LABEL(c);
                _PyJumpTargetLabel unpack_end = NEW_JUMP_TARGET_LABEL(c);
                codegen_visit_expr(c, ((Starred) elt).value);
                ADDOP_I(c, elt_loc, GET_ITER, 0);
                USE_LABEL(c, unpack_start);
                ADDOP_JUMP(c, elt_loc, FOR_ITER, unpack_end);
                ADDOP_YIELD(c, elt_loc);
                ADDOP(c, elt_loc, POP_TOP);
                ADDOP_JUMP(c, NO_LOCATION, JUMP, unpack_start);
                USE_LABEL(c, unpack_end);
                ADDOP(c, NO_LOCATION, END_FOR);
                ADDOP(c, NO_LOCATION, POP_ITER);
            }
            else {
                codegen_visit_expr(c, elt);
                ADDOP_YIELD(c, elt_loc);
                ADDOP(c, elt_loc, POP_TOP);
            }
            break;
        case COMP_LISTCOMP:
            if (elt instanceof Starred) {
                codegen_visit_expr(c, ((Starred) elt).value);
                ADDOP_I(c, elt_loc, LIST_EXTEND, depth + 1);
            }
            else {
                codegen_visit_expr(c, elt);
                ADDOP_I(c, elt_loc, LIST_APPEND, depth + 1);
            }
            break;
        case COMP_SETCOMP:
            if (elt instanceof Starred) {
                codegen_visit_expr(c, ((Starred) elt).value);
                ADDOP_I(c, elt_loc, SET_UPDATE, depth + 1);
            }
            else {
                codegen_visit_expr(c, elt);
                ADDOP_I(c, elt_loc, SET_ADD, depth + 1);
            }
            break;
        case COMP_DICTCOMP:
            if (val == null) {
                /* unpacking (**) case */
                codegen_visit_expr(c, elt);
                ADDOP_I(c, elt_loc, DICT_UPDATE, depth+1);
            }
            else {
                /* With '{k: v}', k is evaluated before v, so we do
                the same. */
                codegen_visit_expr(c, elt);
                codegen_visit_expr(c, val);
                SourceLocation map_loc = LOCATION(elt.lineno,
                                                  val.end_lineno,
                                                  elt.col_offset,
                                                  val.end_col_offset);
                ADDOP_I(c, map_loc, MAP_ADD, depth + 1);
            }
            break;
        default:
            throw new IllegalStateException("unknown comprehension type " + type);
        }
    }

    private static void codegen_async_comprehension_generator(Compile c, SourceLocation loc,
            List<comprehension> generators, int gen_index, int depth,
            expr elt, expr val, int type, int iter_pos) {
        _PyJumpTargetLabel start = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel send = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel except = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel if_cleanup = NEW_JUMP_TARGET_LABEL(c);

        comprehension gen = generators.get(gen_index);

        if (iter_pos == ITERABLE_IN_LOCAL) {
            if (gen_index == 0) {
                assert METADATA(c).u_argcount == 1;
                ADDOP_I(c, loc, LOAD_FAST, 0);
            }
            else {
                /* Sub-iter - calculate on the fly */
                codegen_visit_expr(c, gen.iter);
            }
        }
        if (iter_pos != ITERATOR_ON_STACK) {
            ADDOP(c, LOC(gen.iter), GET_AITER);
        }

        USE_LABEL(c, start);
        /* Runtime will push a block here, so we need to account for that */
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_ASYNC_COMPREHENSION_GENERATOR,
                                start, NO_LABEL, null);

        ADDOP_JUMP(c, loc, SETUP_FINALLY, except);
        ADDOP(c, loc, GET_ANEXT);
        ADDOP(c, loc, PUSH_NULL);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        USE_LABEL(c, send);
        ADD_YIELD_FROM(c, loc, true);
        ADDOP(c, loc, POP_BLOCK);
        codegen_visit_expr(c, gen.target);

        int n = LEN(gen.ifs);
        for (int i = 0; i < n; i++) {
            expr e = gen.ifs.get(i);
            codegen_jump_if(c, loc, e, if_cleanup, false);
        }

        depth++;
        if (++gen_index < LEN(generators)) {
            codegen_comprehension_generator(c, loc,
                                            generators, gen_index, depth,
                                            elt, val, type, 0);
        }

        SourceLocation elt_loc = LOC(elt);
        /* only append after the last for generator */
        if (gen_index >= LEN(generators)) {
            codegen_comprehension_element(c, elt, val, type, depth, elt_loc);
            if (type == COMP_DICTCOMP && val != null) {
                elt_loc = LOCATION(elt.lineno,
                                   val.end_lineno,
                                   elt.col_offset,
                                   val.end_col_offset);
            }
        }

        USE_LABEL(c, if_cleanup);
        ADDOP_JUMP(c, elt_loc, JUMP, start);

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_ASYNC_COMPREHENSION_GENERATOR, start);

        USE_LABEL(c, except);

        ADDOP_JUMP(c, loc, END_ASYNC_FOR, send);
    }

    private static void codegen_push_inlined_comprehension_locals(Compile c,
            SourceLocation loc, PySTEntryObject comp,
            _PyCompile_InlinedComprehensionState state) {
        boolean in_class_block = (SYMTABLE_ENTRY(c).ste_type == _Py_block_ty.ClassBlock) &&
                                 c._PyCompile_IsInInlinedComp() == 0;
        PySTEntryObject outer = SYMTABLE_ENTRY(c);
        // iterate over names bound in the comprehension and ensure we isolate
        // them from the outer scope as needed
        for (Map.Entry<String, Integer> kv : comp.ste_symbols.entrySet()) {
            String k = kv.getKey();
            int symbol = kv.getValue();
            int scope = SYMBOL_TO_SCOPE(symbol);

            int outsymbol = _PyST_GetSymbol(outer, k);
            int outsc = SYMBOL_TO_SCOPE(outsymbol);

            if (((symbol & DEF_LOCAL) != 0 && (symbol & DEF_NONLOCAL) == 0) || in_class_block) {
                // local names bound in comprehension must be isolated from
                // outer scope; push existing value (which may be NULL if
                // not defined) on stack
                if (state.pushed_locals == null) {
                    state.pushed_locals = new ArrayList<>();
                }
                // in the case of a cell, this will actually push the cell
                // itself to the stack, then we'll create a new one for the
                // comprehension and restore the original one after
                ADDOP_NAME(c, loc, LOAD_FAST_AND_CLEAR, k, METADATA(c).u_varnames);
                if (scope == CELL) {
                    if (outsc == FREE) {
                        ADDOP_NAME(c, loc, MAKE_CELL, k, METADATA(c).u_freevars);
                    } else {
                        ADDOP_NAME(c, loc, MAKE_CELL, k, METADATA(c).u_cellvars);
                    }
                }
                state.pushed_locals.add(k);
            }
        }
        if (state.pushed_locals != null) {
            // Outermost iterable expression was already evaluated and is on the
            // stack, we need to swap it back to TOS. This also rotates the order of
            // `pushed_locals` on the stack, but this will be reversed when we swap
            // out the comprehension result in pop_inlined_comprehension_state
            ADDOP_I(c, loc, SWAP, state.pushed_locals.size() + 1);

            // Add our own cleanup handler to restore comprehension locals in case
            // of exception, so they have the correct values inside an exception
            // handler or finally block.
            _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);
            state.cleanup = cleanup;

            // no need to push an fblock for this "virtual" try/finally; there can't
            // be return/continue/break inside a comprehension
            ADDOP_JUMP(c, loc, SETUP_FINALLY, cleanup);
        }
    }

    private static void push_inlined_comprehension_state(Compile c, SourceLocation loc,
            PySTEntryObject comp, _PyCompile_InlinedComprehensionState state) {
        c._PyCompile_TweakInlinedComprehensionScopes(loc, comp, state);
        codegen_push_inlined_comprehension_locals(c, loc, comp, state);
    }

    private static void restore_inlined_comprehension_locals(Compile c, SourceLocation loc,
            _PyCompile_InlinedComprehensionState state) {
        String k;
        // pop names we pushed to stack earlier
        int npops = state.pushed_locals.size();
        // Preserve the comprehension result (or exception) as TOS. This
        // reverses the SWAP we did in push_inlined_comprehension_state
        // to get the outermost iterable to TOS, so we can still just iterate
        // pushed_locals in simple reverse order
        ADDOP_I(c, loc, SWAP, npops + 1);
        for (int i = npops - 1; i >= 0; --i) {
            k = state.pushed_locals.get(i);
            ADDOP_NAME(c, loc, STORE_FAST_MAYBE_NULL, k, METADATA(c).u_varnames);
        }
    }

    private static void codegen_pop_inlined_comprehension_locals(Compile c, SourceLocation loc,
            _PyCompile_InlinedComprehensionState state) {
        if (state.pushed_locals != null) {
            ADDOP(c, NO_LOCATION, POP_BLOCK);

            _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
            ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, end);

            // cleanup from an exception inside the comprehension
            USE_LABEL(c, state.cleanup);
            // discard incomplete comprehension result (beneath exc on stack)
            ADDOP_I(c, NO_LOCATION, SWAP, 2);
            ADDOP(c, NO_LOCATION, POP_TOP);
            restore_inlined_comprehension_locals(c, loc, state);
            ADDOP_I(c, NO_LOCATION, RERAISE, 0);

            USE_LABEL(c, end);
            restore_inlined_comprehension_locals(c, loc, state);
            state.pushed_locals = null;
        }
    }

    private static void pop_inlined_comprehension_state(Compile c, SourceLocation loc,
            _PyCompile_InlinedComprehensionState state) {
        codegen_pop_inlined_comprehension_locals(c, loc, state);
        c._PyCompile_RevertInlinedComprehensionScopes(loc, state);
    }

    private static void codegen_comprehension(Compile c, expr e, int type, String name,
            List<comprehension> generators, expr elt, expr val) {
        PyCodeObject co = null;
        _PyCompile_InlinedComprehensionState inline_state =
                new _PyCompile_InlinedComprehensionState();
        inline_state.cleanup = NO_LABEL;
        comprehension outermost;
        PySTEntryObject entry = SYMTABLE(c)._PySymtable_Lookup(new BlockKey(e));
        boolean is_inlined = entry.ste_comp_inlined;
        boolean is_async_comprehension = entry.ste_coroutine;

        SourceLocation loc = LOC(e);

        outermost = generators.get(0);
        int iter_state;
        if (is_inlined) {
            codegen_visit_expr(c, outermost.iter);
            push_inlined_comprehension_state(c, loc, entry, inline_state);
            iter_state = ITERABLE_ON_STACK;
        }
        else {
            /* Receive outermost iter as an implicit argument */
            _PyCompile_CodeUnitMetadata umd = new _PyCompile_CodeUnitMetadata();
            umd.u_argcount = 1;
            codegen_enter_scope(c, name, COMPILE_SCOPE_COMPREHENSION,
                                new BlockKey(e), e.lineno, null, umd);
            if (type == COMP_GENEXP) {
                /* Insert GET_ITER before RETURN_GENERATOR.
                   https://docs.python.org/3/reference/expressions.html#generator-expressions */
                INSTR_SEQUENCE(c)._PyInstructionSequence_InsertInstruction(
                        0, RESUME, RESUME_AT_GEN_EXPR_START, NO_LOCATION);
                INSTR_SEQUENCE(c)._PyInstructionSequence_InsertInstruction(
                        1, LOAD_FAST, 0, LOC(outermost.iter));
                INSTR_SEQUENCE(c)._PyInstructionSequence_InsertInstruction(
                        2, outermost.is_async != 0 ? GET_AITER : GET_ITER,
                        0, LOC(outermost.iter));
                iter_state = ITERATOR_ON_STACK;
            }
            else {
                iter_state = ITERABLE_IN_LOCAL;
            }
        }

        if (type != COMP_GENEXP) {
            int op;
            switch (type) {
            case COMP_LISTCOMP:
                op = BUILD_LIST;
                break;
            case COMP_SETCOMP:
                op = BUILD_SET;
                break;
            case COMP_DICTCOMP:
                op = BUILD_MAP;
                break;
            default:
                throw new IllegalStateException("unknown comprehension type " + type);
            }

            ADDOP_I(c, loc, op, 0);
            if (is_inlined) {
                ADDOP_I(c, loc, SWAP, 2);
            }
        }
        codegen_comprehension_generator(c, loc, generators, 0, 0,
                                        elt, val, type, iter_state);

        if (is_inlined) {
            pop_inlined_comprehension_state(c, loc, inline_state);
            return;
        }

        if (type != COMP_GENEXP) {
            ADDOP(c, LOC(e), RETURN_VALUE);
        }
        if (type == COMP_GENEXP) {
            codegen_wrap_in_stopiteration_handler(c);
        }

        co = c._PyCompile_OptimizeAndAssemble(true);
        c._PyCompile_ExitScope();

        loc = LOC(e);
        codegen_make_closure(c, loc, co, 0);

        codegen_visit_expr(c, outermost.iter);
        ADDOP_I(c, loc, CALL, 0);

        if (is_async_comprehension && type != COMP_GENEXP) {
            ADDOP_I(c, loc, GET_AWAITABLE, 0);
            ADDOP(c, loc, PUSH_NULL);
            ADDOP_LOAD_CONST(c, loc, Singleton.None);
            ADD_YIELD_FROM(c, loc, true);
        }
    }

    private static void codegen_genexp(Compile c, GeneratorExp e) {
        codegen_comprehension(c, e, COMP_GENEXP, "<genexpr>",
                              e.generators,
                              e.elt, null);
    }

    private static void codegen_listcomp(Compile c, ListComp e) {
        codegen_comprehension(c, e, COMP_LISTCOMP, "<listcomp>",
                              e.generators,
                              e.elt, null);
    }

    private static void codegen_setcomp(Compile c, SetComp e) {
        codegen_comprehension(c, e, COMP_SETCOMP, "<setcomp>",
                              e.generators,
                              e.elt, null);
    }


    private static void codegen_dictcomp(Compile c, DictComp e) {
        codegen_comprehension(c, e, COMP_DICTCOMP, "<dictcomp>",
                              e.generators,
                              e.key, e.value);
    }


    private static void codegen_visit_keyword(Compile c, keyword k) {
        codegen_visit_expr(c, k.value);
    }


    private static void codegen_with_except_finish(Compile c, _PyJumpTargetLabel cleanup) {
        _PyJumpTargetLabel suppress = NEW_JUMP_TARGET_LABEL(c);
        ADDOP(c, NO_LOCATION, TO_BOOL);
        ADDOP_JUMP(c, NO_LOCATION, POP_JUMP_IF_TRUE, suppress);
        ADDOP_I(c, NO_LOCATION, RERAISE, 2);

        USE_LABEL(c, suppress);
        ADDOP(c, NO_LOCATION, POP_TOP); /* exc_value */
        ADDOP(c, NO_LOCATION, POP_BLOCK);
        ADDOP(c, NO_LOCATION, POP_EXCEPT);
        ADDOP(c, NO_LOCATION, POP_TOP);
        ADDOP(c, NO_LOCATION, POP_TOP);
        ADDOP(c, NO_LOCATION, POP_TOP);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);
        ADDOP_JUMP(c, NO_LOCATION, JUMP_NO_INTERRUPT, exit);

        USE_LABEL(c, cleanup);
        POP_EXCEPT_AND_RERAISE(c, NO_LOCATION);

        USE_LABEL(c, exit);
    }

    /*
       Implements the async with statement.

       The semantics outlined in that PEP are as follows:

       async with EXPR as VAR:
           BLOCK

       It is implemented roughly as:

       context = EXPR
       exit = context.__aexit__  # not calling it
       value = await context.__aenter__()
       try:
           VAR = value  # if VAR present in the syntax
           BLOCK
       finally:
           if an exception was raised:
               exc = copy of (exception, instance, traceback)
           else:
               exc = (None, None, None)
           if not (await exit(*exc)):
               raise
     */
    private static void codegen_async_with_inner(Compile c, AsyncWith s, int pos) {
        SourceLocation loc = LOC(s);
        withitem item = s.items.get(pos);

        _PyJumpTargetLabel block = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel final_ = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);

        /* Evaluate EXPR */
        codegen_visit_expr(c, item.context_expr);
        loc = LOC(item.context_expr);
        ADDOP_I(c, loc, COPY, 1);
        ADDOP_I(c, loc, LOAD_SPECIAL, SPECIAL___AEXIT__);
        ADDOP_I(c, loc, SWAP, 2);
        ADDOP_I(c, loc, SWAP, 3);
        ADDOP_I(c, loc, LOAD_SPECIAL, SPECIAL___AENTER__);
        ADDOP_I(c, loc, CALL, 0);
        ADDOP_I(c, loc, GET_AWAITABLE, 1);
        ADDOP(c, loc, PUSH_NULL);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADD_YIELD_FROM(c, loc, true);

        ADDOP_JUMP(c, loc, SETUP_WITH, final_);

        /* SETUP_WITH pushes a finally block. */
        USE_LABEL(c, block);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_ASYNC_WITH, block, final_, s);

        if (item.optional_vars != null) {
            codegen_visit_expr(c, item.optional_vars);
        }
        else {
            /* Discard result from context.__aenter__() */
            ADDOP(c, loc, POP_TOP);
        }

        pos++;
        if (pos == LEN(s.items)) {
            /* BLOCK code */
            VISIT_SEQ_stmt(c, s.body);
        }
        else {
            codegen_async_with_inner(c, s, pos);
        }

        c._PyCompile_PopFBlock(COMPILE_FBLOCK_ASYNC_WITH, block);

        ADDOP(c, loc, POP_BLOCK);
        /* End of body; start the cleanup */

        /* For successful outcome:
         * call __exit__(None, None, None)
         */
        codegen_call_exit_with_nones(c, loc);
        ADDOP_I(c, loc, GET_AWAITABLE, 2);
        ADDOP(c, loc, PUSH_NULL);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADD_YIELD_FROM(c, loc, true);

        ADDOP(c, loc, POP_TOP);

        ADDOP_JUMP(c, loc, JUMP, exit);

        /* For exceptional outcome: */
        USE_LABEL(c, final_);

        ADDOP_JUMP(c, loc, SETUP_CLEANUP, cleanup);
        ADDOP(c, loc, PUSH_EXC_INFO);
        ADDOP(c, loc, WITH_EXCEPT_START);
        ADDOP_I(c, loc, GET_AWAITABLE, 2);
        ADDOP(c, loc, PUSH_NULL);
        ADDOP_LOAD_CONST(c, loc, Singleton.None);
        ADD_YIELD_FROM(c, loc, true);
        codegen_with_except_finish(c, cleanup);

        USE_LABEL(c, exit);
    }

    private static void codegen_async_with(Compile c, AsyncWith s) {
        codegen_async_with_inner(c, s, 0);
    }


    /*
       Implements the with statement from PEP 343.
       with EXPR as VAR:
           BLOCK
       is implemented as:
            <code for EXPR>
            SETUP_WITH  E
            <code to store to VAR> or POP_TOP
            <code for BLOCK>
            LOAD_CONST (None, None, None)
            CALL_FUNCTION_EX 0
            JUMP  EXIT
        E:  WITH_EXCEPT_START (calls EXPR.__exit__)
            POP_JUMP_IF_TRUE T:
            RERAISE
        T:  POP_TOP (remove exception from stack)
            POP_EXCEPT
            POP_TOP
        EXIT:
     */

    private static void codegen_with_inner(Compile c, With s, int pos) {
        withitem item = s.items.get(pos);

        _PyJumpTargetLabel block = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel final_ = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel exit = NEW_JUMP_TARGET_LABEL(c);
        _PyJumpTargetLabel cleanup = NEW_JUMP_TARGET_LABEL(c);

        /* Evaluate EXPR */
        codegen_visit_expr(c, item.context_expr);
        /* Will push bound __exit__ */
        SourceLocation loc = LOC(item.context_expr);
        ADDOP_I(c, loc, COPY, 1);
        ADDOP_I(c, loc, LOAD_SPECIAL, SPECIAL___EXIT__);
        ADDOP_I(c, loc, SWAP, 2);
        ADDOP_I(c, loc, SWAP, 3);
        ADDOP_I(c, loc, LOAD_SPECIAL, SPECIAL___ENTER__);
        ADDOP_I(c, loc, CALL, 0);
        ADDOP_JUMP(c, loc, SETUP_WITH, final_);

        /* SETUP_WITH pushes a finally block. */
        USE_LABEL(c, block);
        c._PyCompile_PushFBlock(loc, COMPILE_FBLOCK_WITH, block, final_, s);

        if (item.optional_vars != null) {
            codegen_visit_expr(c, item.optional_vars);
        }
        else {
        /* Discard result from context.__enter__() */
            ADDOP(c, loc, POP_TOP);
        }

        pos++;
        if (pos == LEN(s.items)) {
            /* BLOCK code */
            VISIT_SEQ_stmt(c, s.body);
        }
        else {
            codegen_with_inner(c, s, pos);
        }

        ADDOP(c, NO_LOCATION, POP_BLOCK);
        c._PyCompile_PopFBlock(COMPILE_FBLOCK_WITH, block);

        /* End of body; start the cleanup. */

        /* For successful outcome:
         * call __exit__(None, None, None)
         */
        codegen_call_exit_with_nones(c, loc);
        ADDOP(c, loc, POP_TOP);
        ADDOP_JUMP(c, loc, JUMP, exit);

        /* For exceptional outcome: */
        USE_LABEL(c, final_);

        ADDOP_JUMP(c, loc, SETUP_CLEANUP, cleanup);
        ADDOP(c, loc, PUSH_EXC_INFO);
        ADDOP(c, loc, WITH_EXCEPT_START);
        codegen_with_except_finish(c, cleanup);

        USE_LABEL(c, exit);
    }

    private static void codegen_with(Compile c, With s) {
        codegen_with_inner(c, s, 0);
    }

    private static void codegen_visit_expr(Compile c, expr e) {
        // C: Py_EnterRecursiveCall(" during compilation"); see _PyCompile_CodeGen.
        SourceLocation loc = LOC(e);
        switch (e.kind()) {
        case NamedExpr: {
            NamedExpr ne = (NamedExpr) e;
            codegen_visit_expr(c, ne.value);
            ADDOP_I(c, loc, COPY, 1);
            codegen_visit_expr(c, ne.target);
            break;
        }
        case BoolOp:
            codegen_boolop(c, (BoolOp) e);
            return;
        case BinOp: {
            BinOp b = (BinOp) e;
            codegen_visit_expr(c, b.left);
            codegen_visit_expr(c, b.right);
            ADDOP_BINARY(c, loc, b.op);
            break;
        }
        case UnaryOp: {
            UnaryOp u = (UnaryOp) e;
            codegen_visit_expr(c, u.operand);
            if (u.op == unaryopType.UAdd) {
                ADDOP_I(c, loc, CALL_INTRINSIC_1, INTRINSIC_UNARY_POSITIVE);
            }
            else if (u.op == unaryopType.Not) {
                ADDOP(c, loc, TO_BOOL);
                ADDOP(c, loc, UNARY_NOT);
            }
            else {
                ADDOP(c, loc, unaryop(u.op));
            }
            break;
        }
        case Lambda:
            codegen_lambda(c, (Lambda) e);
            return;
        case IfExp:
            codegen_ifexp(c, (IfExp) e);
            return;
        case Dict:
            codegen_dict(c, (Dict) e);
            return;
        case Set:
            codegen_set(c, (org.python.pegen.ast.Set) e);
            return;
        case GeneratorExp:
            codegen_genexp(c, (GeneratorExp) e);
            return;
        case ListComp:
            codegen_listcomp(c, (ListComp) e);
            return;
        case SetComp:
            codegen_setcomp(c, (SetComp) e);
            return;
        case DictComp:
            codegen_dictcomp(c, (DictComp) e);
            return;
        case Yield:
            if (!_PyST_IsFunctionLike(SYMTABLE_ENTRY(c))) {
                throw c._PyCompile_Error(loc, "'yield' outside function");
            }
            if (((Yield) e).value != null) {
                codegen_visit_expr(c, ((Yield) e).value);
            }
            else {
                ADDOP_LOAD_CONST(c, loc, Singleton.None);
            }
            ADDOP_YIELD(c, loc);
            break;
        case YieldFrom:
            if (!_PyST_IsFunctionLike(SYMTABLE_ENTRY(c))) {
                throw c._PyCompile_Error(loc, "'yield from' outside function");
            }
            if (SCOPE_TYPE(c) == COMPILE_SCOPE_ASYNC_FUNCTION) {
                throw c._PyCompile_Error(loc, "'yield from' inside async function");
            }
            codegen_visit_expr(c, ((YieldFrom) e).value);
            ADDOP_I(c, loc, GET_ITER, GET_ITER_YIELD_FROM);
            ADDOP_LOAD_CONST(c, loc, Singleton.None);
            ADD_YIELD_FROM(c, loc, false);
            break;
        case Await:
            codegen_visit_expr(c, ((Await) e).value);
            ADDOP_I(c, loc, GET_AWAITABLE, 0);
            ADDOP(c, loc, PUSH_NULL);
            ADDOP_LOAD_CONST(c, loc, Singleton.None);
            ADD_YIELD_FROM(c, loc, true);
            break;
        case Compare:
            codegen_compare(c, (Compare) e);
            return;
        case Call:
            codegen_call(c, (Call) e);
            return;
        case Constant:
            ADDOP_LOAD_CONST(c, loc, ((Constant) e).value);
            break;
        case JoinedStr:
            codegen_joined_str(c, (JoinedStr) e);
            return;
        case TemplateStr:
            codegen_template_str(c, (TemplateStr) e);
            return;
        case FormattedValue:
            codegen_formatted_value(c, (FormattedValue) e);
            return;
        case Interpolation:
            codegen_interpolation(c, (Interpolation) e);
            return;
        /* The following exprs can be assignment targets. */
        case Attribute: {
            Attribute a = (Attribute) e;
            if (a.ctx == expr_contextType.Load) {
                if (can_optimize_super_call(c, a)) {
                    load_args_for_super(c, (Call) a.value);
                    int opcode = LEN(((Call) a.value).args) != 0 ?
                        LOAD_SUPER_ATTR : LOAD_ZERO_SUPER_ATTR;
                    ADDOP_NAME(c, loc, opcode, a.attr, METADATA(c).u_names);
                    loc = update_start_location_to_match_attr(c, loc, a);
                    ADDOP(c, loc, NOP);
                    return;
                }
            }
            c._PyCompile_MaybeAddStaticAttributeToClass(a);
            codegen_visit_expr(c, a.value);
            loc = LOC(e);
            loc = update_start_location_to_match_attr(c, loc, a);
            switch (a.ctx) {
            case Load:
                ADDOP_NAME(c, loc, LOAD_ATTR, a.attr, METADATA(c).u_names);
                break;
            case Store:
                ADDOP_NAME(c, loc, STORE_ATTR, a.attr, METADATA(c).u_names);
                break;
            case Del:
                ADDOP_NAME(c, loc, DELETE_ATTR, a.attr, METADATA(c).u_names);
                break;
            }
            break;
        }
        case Subscript:
            codegen_subscript(c, (Subscript) e);
            return;
        case Starred:
            switch (((Starred) e).ctx) {
            case Store:
                /* In all legitimate cases, the Starred node was already replaced
                 * by codegen_list/codegen_tuple. XXX: is that okay? */
                throw c._PyCompile_Error(loc,
                    "starred assignment target must be in a list or tuple");
            default:
                throw c._PyCompile_Error(loc,
                    "can't use starred expression here");
            }
        case Slice:
            codegen_slice(c, (Slice) e);
            break;
        case Name:
            codegen_nameop(c, loc, ((Name) e).id, ((Name) e).ctx);
            return;
        /* child nodes of List and Tuple will have expr_context set */
        case List:
            codegen_list(c, (org.python.pegen.ast.List) e);
            return;
        case Tuple:
            codegen_tuple(c, (Tuple) e);
            return;
        }
    }

    private static boolean is_constant_slice(expr s) {
        if (!(s instanceof Slice)) {
            return false;
        }
        Slice sl = (Slice) s;
        return (sl.lower == null ||
                sl.lower instanceof Constant) &&
               (sl.upper == null ||
                sl.upper instanceof Constant) &&
               (sl.step == null ||
                sl.step instanceof Constant);
    }

    private static boolean should_apply_two_element_slice_optimization(expr s) {
        return !is_constant_slice(s) &&
               s instanceof Slice &&
               ((Slice) s).step == null;
    }

    private static void codegen_augassign(Compile c, AugAssign s) {
        expr e = s.target;

        SourceLocation loc = LOC(e);

        switch (e.kind()) {
        case Attribute: {
            Attribute a = (Attribute) e;
            codegen_visit_expr(c, a.value);
            ADDOP_I(c, loc, COPY, 1);
            loc = update_start_location_to_match_attr(c, loc, a);
            ADDOP_NAME(c, loc, LOAD_ATTR, a.attr, METADATA(c).u_names);
            break;
        }
        case Subscript: {
            Subscript sub = (Subscript) e;
            codegen_visit_expr(c, sub.value);
            if (should_apply_two_element_slice_optimization(sub.slice)) {
                codegen_slice_two_parts(c, (Slice) sub.slice);
                ADDOP_I(c, loc, COPY, 3);
                ADDOP_I(c, loc, COPY, 3);
                ADDOP_I(c, loc, COPY, 3);
                ADDOP(c, loc, BINARY_SLICE);
            }
            else {
                codegen_visit_expr(c, sub.slice);
                ADDOP_I(c, loc, COPY, 2);
                ADDOP_I(c, loc, COPY, 2);
                ADDOP_I(c, loc, BINARY_OP, NB_SUBSCR);
            }
            break;
        }
        case Name:
            codegen_nameop(c, loc, ((Name) e).id, expr_contextType.Load);
            break;
        default:
            throw new IllegalStateException(
                "invalid node type (" + e.kind() + ") for augmented assignment");
        }

        loc = LOC(s);

        codegen_visit_expr(c, s.value);
        ADDOP_INPLACE(c, loc, s.op);

        loc = LOC(e);

        switch (e.kind()) {
        case Attribute:
            loc = update_start_location_to_match_attr(c, loc, (Attribute) e);
            ADDOP_I(c, loc, SWAP, 2);
            ADDOP_NAME(c, loc, STORE_ATTR, ((Attribute) e).attr, METADATA(c).u_names);
            break;
        case Subscript:
            if (should_apply_two_element_slice_optimization(((Subscript) e).slice)) {
                ADDOP_I(c, loc, SWAP, 4);
                ADDOP_I(c, loc, SWAP, 3);
                ADDOP_I(c, loc, SWAP, 2);
                ADDOP(c, loc, STORE_SLICE);
            }
            else {
                ADDOP_I(c, loc, SWAP, 3);
                ADDOP_I(c, loc, SWAP, 2);
                ADDOP(c, loc, STORE_SUBSCR);
            }
            break;
        case Name:
            codegen_nameop(c, loc, ((Name) e).id, expr_contextType.Store);
            return;
        default:
            throw new IllegalStateException("unreachable");
        }
    }

    private static void codegen_check_ann_expr(Compile c, expr e) {
        codegen_visit_expr(c, e);
        ADDOP(c, LOC(e), POP_TOP);
    }

    private static void codegen_check_ann_subscr(Compile c, expr e) {
        /* We check that everything in a subscript is defined at runtime. */
        switch (e.kind()) {
        case Slice: {
            Slice s = (Slice) e;
            if (s.lower != null) {
                codegen_check_ann_expr(c, s.lower);
            }
            if (s.upper != null) {
                codegen_check_ann_expr(c, s.upper);
            }
            if (s.step != null) {
                codegen_check_ann_expr(c, s.step);
            }
            return;
        }
        case Tuple: {
            /* extended slice */
            List<expr> elts = ((Tuple) e).elts;
            int i, n = LEN(elts);
            for (i = 0; i < n; i++) {
                codegen_check_ann_subscr(c, elts.get(i));
            }
            return;
        }
        default:
            codegen_check_ann_expr(c, e);
        }
    }

    private static void codegen_annassign(Compile c, AnnAssign s) {
        SourceLocation loc = LOC(s);
        expr targ = s.target;
        boolean future_annotations = (FUTURE_FEATURES(c) & CO_FUTURE_ANNOTATIONS) != 0;
        String mangled;

        /* We perform the actual assignment first. */
        if (s.value != null) {
            codegen_visit_expr(c, s.value);
            codegen_visit_expr(c, targ);
        }
        switch (targ.kind()) {
        case Name:
            /* If we have a simple name in a module or class, store annotation. */
            if (s.simple != 0 &&
                (SCOPE_TYPE(c) == COMPILE_SCOPE_MODULE ||
                 SCOPE_TYPE(c) == COMPILE_SCOPE_CLASS)) {
                if (future_annotations) {
                    codegen_visit_annexpr(c, s.annotation);
                    ADDOP_NAME(c, loc, LOAD_NAME, "__annotations__", METADATA(c).u_names);
                    mangled = c._PyCompile_MaybeMangle(((Name) targ).id);
                    ADDOP_LOAD_CONST(c, loc, mangled);
                    ADDOP(c, loc, STORE_SUBSCR);
                }
                else {
                    Integer conditional_annotation_index = c._PyCompile_AddDeferredAnnotation(s);
                    if (conditional_annotation_index != null) {
                        if (SCOPE_TYPE(c) == COMPILE_SCOPE_CLASS) {
                            ADDOP_NAME(c, loc, LOAD_DEREF, "__conditional_annotations__",
                                    METADATA(c).u_cellvars);
                        }
                        else {
                            ADDOP_NAME(c, loc, LOAD_NAME, "__conditional_annotations__",
                                    METADATA(c).u_names);
                        }
                        ADDOP_LOAD_CONST(c, loc,
                                BigInteger.valueOf(conditional_annotation_index));
                        ADDOP_I(c, loc, SET_ADD, 1);
                        ADDOP(c, loc, POP_TOP);
                    }
                }
            }
            break;
        case Attribute:
            if (s.value == null) {
                codegen_check_ann_expr(c, ((Attribute) targ).value);
            }
            break;
        case Subscript:
            if (s.value == null) {
                codegen_check_ann_expr(c, ((Subscript) targ).value);
                codegen_check_ann_subscr(c, ((Subscript) targ).slice);
            }
            break;
        default:
            throw new IllegalStateException(
                "invalid node type (" + targ.kind() + ") for annotated assignment");
        }
    }

    private static void codegen_subscript(Compile c, Subscript e) {
        SourceLocation loc = LOC(e);
        expr_contextType ctx = e.ctx;

        if (ctx == expr_contextType.Load) {
            check_subscripter(c, e.value);
            check_index(c, e.value, e.slice);
        }

        codegen_visit_expr(c, e.value);
        if (should_apply_two_element_slice_optimization(e.slice) &&
            ctx != expr_contextType.Del
        ) {
            codegen_slice_two_parts(c, (Slice) e.slice);
            if (ctx == expr_contextType.Load) {
                ADDOP(c, loc, BINARY_SLICE);
            }
            else {
                assert ctx == expr_contextType.Store;
                ADDOP(c, loc, STORE_SLICE);
            }
        }
        else {
            codegen_visit_expr(c, e.slice);
            switch (ctx) {
                case Load:
                    ADDOP_I(c, loc, BINARY_OP, NB_SUBSCR);
                    break;
                case Store:
                    ADDOP(c, loc, STORE_SUBSCR);
                    break;
                case Del:
                    ADDOP(c, loc, DELETE_SUBSCR);
                    break;
            }
        }
    }

    private static void codegen_slice_two_parts(Compile c, Slice s) {
        if (s.lower != null) {
            codegen_visit_expr(c, s.lower);
        }
        else {
            ADDOP_LOAD_CONST(c, LOC(s), Singleton.None);
        }

        if (s.upper != null) {
            codegen_visit_expr(c, s.upper);
        }
        else {
            ADDOP_LOAD_CONST(c, LOC(s), Singleton.None);
        }
    }

    private static void codegen_slice(Compile c, Slice s) {
        int n = 2;

        if (is_constant_slice(s)) {
            Object start = Singleton.None;
            if (s.lower != null) {
                start = ((Constant) s.lower).value;
            }
            Object stop = Singleton.None;
            if (s.upper != null) {
                stop = ((Constant) s.upper).value;
            }
            Object step = Singleton.None;
            if (s.step != null) {
                step = ((Constant) s.step).value;
            }
            PySlice slice = new PySlice(start, stop, step);
            ADDOP_LOAD_CONST(c, LOC(s), slice);
            return;
        }

        codegen_slice_two_parts(c, s);

        if (s.step != null) {
            n++;
            codegen_visit_expr(c, s.step);
        }

        ADDOP_I(c, LOC(s), BUILD_SLICE, n);
    }


    // PEP 634: Structural Pattern Matching

    // To keep things simple, all codegen_pattern_* routines follow the convention
    // of consuming TOS (the subject for the given pattern) and calling
    // jump_to_fail_pop on failure (no match).

    // When calling into these routines, it's important that pc->on_top be kept
    // updated to reflect the current number of items that we are using on the top
    // of the stack: they will be popped on failure, and any name captures will be
    // stored *underneath* them on success. This lets us defer all names stores
    // until the *entire* pattern matches.

    private static boolean WILDCARD_CHECK(pattern n) {
        return n instanceof MatchAs && ((MatchAs) n).name == null;
    }

    private static boolean WILDCARD_STAR_CHECK(pattern n) {
        return n instanceof MatchStar && ((MatchStar) n).name == null;
    }

    // Limit permitted subexpressions, even if the parser & AST validator let them through
    private static boolean MATCH_VALUE_EXPR(expr n) {
        return n instanceof Constant || n instanceof Attribute;
    }

    // Allocate or resize pc->fail_pop to allow for n items to be popped on failure.
    private static void ensure_fail_pop(Compile c, pattern_context pc, int n) {
        int size = n + 1;
        if (size <= pc.fail_pop_size) {
            return;
        }
        _PyJumpTargetLabel[] resized = new _PyJumpTargetLabel[size];
        if (pc.fail_pop != null) {
            System.arraycopy(pc.fail_pop, 0, resized, 0, pc.fail_pop_size);
        }
        pc.fail_pop = resized;
        while (pc.fail_pop_size < size) {
            _PyJumpTargetLabel new_block = NEW_JUMP_TARGET_LABEL(c);
            pc.fail_pop[pc.fail_pop_size++] = new_block;
        }
    }

    // Use op to jump to the correct fail_pop block.
    private static void jump_to_fail_pop(Compile c, SourceLocation loc, pattern_context pc,
            int op) {
        // Pop any items on the top of the stack, plus any objects we were going to
        // capture on success:
        int pops = pc.on_top + pc.stores.size();
        ensure_fail_pop(c, pc, pops);
        ADDOP_JUMP(c, loc, op, pc.fail_pop[pops]);
    }

    // Build all of the fail_pop blocks and reset fail_pop.
    private static void emit_and_reset_fail_pop(Compile c, SourceLocation loc,
            pattern_context pc) {
        if (pc.fail_pop_size == 0) {
            assert pc.fail_pop == null;
            return;
        }
        while (--pc.fail_pop_size != 0) {
            USE_LABEL(c, pc.fail_pop[pc.fail_pop_size]);
            codegen_addop_noarg(INSTR_SEQUENCE(c), POP_TOP, loc);
        }
        USE_LABEL(c, pc.fail_pop[0]);
        pc.fail_pop = null;
    }

    private static RuntimeException codegen_error_duplicate_store(Compile c,
            SourceLocation loc, String n) {
        return c._PyCompile_Error(loc,
            "multiple assignments to name %s in pattern", Repr.repr(n));
    }

    // Duplicate the effect of 3.10's ROT_* instructions using SWAPs.
    private static void codegen_pattern_helper_rotate(Compile c, SourceLocation loc,
            int count) {
        while (1 < count) {
            ADDOP_I(c, loc, SWAP, count--);
        }
    }

    private static void codegen_pattern_helper_store_name(Compile c, SourceLocation loc,
            String n, pattern_context pc) {
        if (n == null) {
            ADDOP(c, loc, POP_TOP);
            return;
        }
        // Can't assign to the same name twice:
        boolean duplicate = pc.stores.contains(n);
        if (duplicate) {
            throw codegen_error_duplicate_store(c, loc, n);
        }
        // Rotate this object underneath any items we need to preserve:
        int rotations = pc.on_top + pc.stores.size() + 1;
        codegen_pattern_helper_rotate(c, loc, rotations);
        pc.stores.add(n);
    }


    private static void codegen_pattern_unpack_helper(Compile c, SourceLocation loc,
            List<pattern> elts) {
        int n = LEN(elts);
        boolean seen_star = false;
        for (int i = 0; i < n; i++) {
            pattern elt = elts.get(i);
            if (elt instanceof MatchStar && !seen_star) {
                if ((i >= (1 << 8)) ||
                    (n-i-1 >= (Integer.MAX_VALUE >> 8))) {
                    throw c._PyCompile_Error(loc,
                        "too many expressions in "
                        + "star-unpacking sequence pattern");
                }
                ADDOP_I(c, loc, UNPACK_EX, (i + ((n-i-1) << 8)));
                seen_star = true;
            }
            else if (elt instanceof MatchStar) {
                throw c._PyCompile_Error(loc,
                    "multiple starred expressions in sequence pattern");
            }
        }
        if (!seen_star) {
            ADDOP_I(c, loc, UNPACK_SEQUENCE, n);
        }
    }

    private static void pattern_helper_sequence_unpack(Compile c, SourceLocation loc,
            List<pattern> patterns, int star, pattern_context pc) {
        codegen_pattern_unpack_helper(c, loc, patterns);
        int size = LEN(patterns);
        // We've now got a bunch of new subjects on the stack. They need to remain
        // there after each subpattern match:
        pc.on_top += size;
        for (int i = 0; i < size; i++) {
            // One less item to keep track of each time we loop through:
            pc.on_top--;
            pattern pattern = patterns.get(i);
            codegen_pattern_subpattern(c, pattern, pc);
        }
    }

    // Like pattern_helper_sequence_unpack, but uses BINARY_OP/NB_SUBSCR instead of
    // UNPACK_SEQUENCE / UNPACK_EX. This is more efficient for patterns with a
    // starred wildcard like [first, *_] / [first, *_, last] / [*_, last] / etc.
    private static void pattern_helper_sequence_subscr(Compile c, SourceLocation loc,
            List<pattern> patterns, int star, pattern_context pc) {
        // We need to keep the subject around for extracting elements:
        pc.on_top++;
        int size = LEN(patterns);
        for (int i = 0; i < size; i++) {
            pattern pattern = patterns.get(i);
            if (WILDCARD_CHECK(pattern)) {
                continue;
            }
            if (i == star) {
                assert WILDCARD_STAR_CHECK(pattern);
                continue;
            }
            ADDOP_I(c, loc, COPY, 1);
            if (i < star) {
                ADDOP_LOAD_CONST(c, loc, BigInteger.valueOf(i));
            }
            else {
                // The subject may not support negative indexing! Compute a
                // nonnegative index:
                ADDOP(c, loc, GET_LEN);
                ADDOP_LOAD_CONST(c, loc, BigInteger.valueOf(size - i));
                ADDOP_BINARY(c, loc, operatorType.Sub);
            }
            ADDOP_I(c, loc, BINARY_OP, NB_SUBSCR);
            codegen_pattern_subpattern(c, pattern, pc);
        }
        // Pop the subject, we're done with it:
        pc.on_top--;
        ADDOP(c, loc, POP_TOP);
    }

    // Like codegen_pattern, but turn off checks for irrefutability.
    private static void codegen_pattern_subpattern(Compile c, pattern p, pattern_context pc) {
        boolean allow_irrefutable = pc.allow_irrefutable;
        pc.allow_irrefutable = true;
        codegen_pattern(c, p, pc);
        pc.allow_irrefutable = allow_irrefutable;
    }

    private static void codegen_pattern_as(Compile c, MatchAs p, pattern_context pc) {
        if (p.pattern == null) {
            // An irrefutable match:
            if (!pc.allow_irrefutable) {
                if (p.name != null) {
                    throw c._PyCompile_Error(LOC(p),
                            "name capture %s makes remaining patterns unreachable",
                            Repr.repr(p.name));
                }
                throw c._PyCompile_Error(LOC(p),
                        "wildcard makes remaining patterns unreachable");
            }
            codegen_pattern_helper_store_name(c, LOC(p), p.name, pc);
            return;
        }
        // Need to make a copy for (possibly) storing later:
        pc.on_top++;
        ADDOP_I(c, LOC(p), COPY, 1);
        codegen_pattern(c, p.pattern, pc);
        // Success! Store it:
        pc.on_top--;
        codegen_pattern_helper_store_name(c, LOC(p), p.name, pc);
    }

    private static void codegen_pattern_star(Compile c, MatchStar p, pattern_context pc) {
        codegen_pattern_helper_store_name(c, LOC(p), p.name, pc);
    }

    private static void validate_kwd_attrs(Compile c, List<String> attrs,
            List<pattern> patterns) {
        // Any errors will point to the pattern rather than the arg name as the
        // parser is only supplying identifiers rather than Name or keyword nodes
        int nattrs = LEN(attrs);
        for (int i = 0; i < nattrs; i++) {
            String attr = attrs.get(i);
            for (int j = i + 1; j < nattrs; j++) {
                String other = attrs.get(j);
                if (attr.equals(other)) {
                    SourceLocation loc = LOC(patterns.get(j));
                    throw c._PyCompile_Error(loc, "attribute name repeated "
                                                + "in class pattern: %s", attr);
                }
            }
        }
    }

    private static void codegen_pattern_class(Compile c, MatchClass p, pattern_context pc) {
        List<pattern> patterns = p.patterns;
        List<String> kwd_attrs = p.kwd_attrs;
        List<pattern> kwd_patterns = p.kwd_patterns;
        int nargs = LEN(patterns);
        int nattrs = LEN(kwd_attrs);
        int nkwd_patterns = LEN(kwd_patterns);
        if (nattrs != nkwd_patterns) {
            // AST validator shouldn't let this happen, but if it does,
            // just fail, don't crash out of the interpreter
            throw c._PyCompile_Error(LOC(p),
                    "kwd_attrs (%d) / kwd_patterns (%d) length mismatch in class pattern",
                    nattrs, nkwd_patterns);
        }
        if (nattrs != 0) {
            validate_kwd_attrs(c, kwd_attrs, kwd_patterns);
        }
        codegen_visit_expr(c, p.cls);
        Object[] attr_names = new Object[nattrs];
        int i;
        for (i = 0; i < nattrs; i++) {
            attr_names[i] = kwd_attrs.get(i);
        }
        ADDOP_LOAD_CONST(c, LOC(p), new PyTuple(attr_names));
        ADDOP_I(c, LOC(p), MATCH_CLASS, nargs);
        ADDOP_I(c, LOC(p), COPY, 1);
        ADDOP_LOAD_CONST(c, LOC(p), Singleton.None);
        ADDOP_I(c, LOC(p), IS_OP, 1);
        // TOS is now a tuple of (nargs + nattrs) attributes (or None):
        pc.on_top++;
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        ADDOP_I(c, LOC(p), UNPACK_SEQUENCE, nargs + nattrs);
        pc.on_top += nargs + nattrs - 1;
        for (i = 0; i < nargs + nattrs; i++) {
            pc.on_top--;
            pattern pattern;
            if (i < nargs) {
                // Positional:
                pattern = patterns.get(i);
            }
            else {
                // Keyword:
                pattern = kwd_patterns.get(i - nargs);
            }
            if (WILDCARD_CHECK(pattern)) {
                ADDOP(c, LOC(p), POP_TOP);
                continue;
            }
            codegen_pattern_subpattern(c, pattern, pc);
        }
        // Success! Pop the tuple of attributes:
    }

    private static void codegen_pattern_mapping_key(Compile c, Set<Object> seen,
            MatchMapping p, int i) {
        List<expr> keys = p.keys;
        List<pattern> patterns = p.patterns;
        expr key = keys.get(i);
        if (key == null) {
            SourceLocation loc = LOC(patterns.get(i));
            throw c._PyCompile_Error(loc, "can't use NULL keys in MatchMapping "
                                        + "(set 'rest' parameter instead)");
        }

        if (key instanceof Constant) {
            Object value = ((Constant) key).value;
            // C: a set of the values, compared as Python compares them.
            Object seenKey = pyHashKey(value);
            boolean in_seen = seen.contains(seenKey);
            if (in_seen) {
                throw c._PyCompile_Error(LOC(p), "mapping pattern checks duplicate key (%s)",
                        Repr.repr(value));
            }
            seen.add(seenKey);
        }
        else if (!(key instanceof Attribute)) {
            throw c._PyCompile_Error(LOC(p),
                    "mapping pattern keys may only match literals and attribute lookups");
        }
        codegen_visit_expr(c, key);
    }

    /**
     * A key under which constants equal by Python's == (and so the same set
     * entry) compare equal: numbers by value across int, float, complex and
     * bool, the rest by their own equality.
     */
    private static Object pyHashKey(Object v) {
        if (v == Singleton.True) {
            return new java.math.BigDecimal(1);
        } else if (v == Singleton.False) {
            return new java.math.BigDecimal(0);
        } else if (v instanceof BigInteger) {
            return new java.math.BigDecimal((BigInteger) v);
        } else if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return v;
            }
            return new java.math.BigDecimal(d).stripTrailingZeros();
        } else if (v instanceof org.python.pegen.ast.Complex) {
            org.python.pegen.ast.Complex z = (org.python.pegen.ast.Complex) v;
            if (z.imag == 0.0) {
                return pyHashKey(z.real);
            }
            return java.util.Arrays.asList(pyHashKey(z.real), pyHashKey(z.imag));
        }
        if (v instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) v).stripTrailingZeros();
        }
        return v;
    }

    private static void codegen_pattern_mapping(Compile c, MatchMapping p,
            pattern_context pc) {
        List<expr> keys = p.keys;
        List<pattern> patterns = p.patterns;
        int size = LEN(keys);
        int npatterns = LEN(patterns);
        if (size != npatterns) {
            // AST validator shouldn't let this happen, but if it does,
            // just fail, don't crash out of the interpreter
            throw c._PyCompile_Error(LOC(p),
                    "keys (%d) / patterns (%d) length mismatch in mapping pattern",
                    size, npatterns);
        }
        // We have a double-star target if "rest" is set
        String star_target = p.rest;
        // We need to keep the subject on top during the mapping and length checks:
        pc.on_top++;
        ADDOP(c, LOC(p), MATCH_MAPPING);
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        if (size == 0 && star_target == null) {
            // If the pattern is just "{}", we're done! Pop the subject:
            pc.on_top--;
            ADDOP(c, LOC(p), POP_TOP);
            return;
        }
        if (size != 0) {
            // If the pattern has any keys in it, perform a length check:
            ADDOP(c, LOC(p), GET_LEN);
            ADDOP_LOAD_CONST(c, LOC(p), BigInteger.valueOf(size));
            ADDOP_COMPARE(c, LOC(p), cmpopType.GtE);
            jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        }
        // Collect all of the keys into a tuple for MATCH_KEYS and
        // **rest. They can either be dotted names or literals:

        // Maintaining a set of Constant_kind kind keys allows us to raise a
        // SyntaxError in the case of duplicates.
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < size; i++) {
            codegen_pattern_mapping_key(c, seen, p, i);
        }

        // all keys have been checked; there are no duplicates

        ADDOP_I(c, LOC(p), BUILD_TUPLE, size);
        ADDOP(c, LOC(p), MATCH_KEYS);
        // There's now a tuple of keys and a tuple of values on top of the subject:
        pc.on_top += 2;
        ADDOP_I(c, LOC(p), COPY, 1);
        ADDOP_LOAD_CONST(c, LOC(p), Singleton.None);
        ADDOP_I(c, LOC(p), IS_OP, 1);
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        // So far so good. Use that tuple of values on the stack to match
        // sub-patterns against:
        ADDOP_I(c, LOC(p), UNPACK_SEQUENCE, size);
        pc.on_top += size - 1;
        for (int i = 0; i < size; i++) {
            pc.on_top--;
            pattern pattern = patterns.get(i);
            codegen_pattern_subpattern(c, pattern, pc);
        }
        // If we get this far, it's a match! Whatever happens next should consume
        // the tuple of keys and the subject:
        pc.on_top -= 2;
        if (star_target != null) {
            // If we have a starred name, bind a dict of remaining items to it (this may
            // seem a bit inefficient, but keys is rarely big enough to actually impact
            // runtime):
            // rest = dict(TOS1)
            // for key in TOS:
            //     del rest[key]
            ADDOP_I(c, LOC(p), BUILD_MAP, 0);           // [subject, keys, empty]
            ADDOP_I(c, LOC(p), SWAP, 3);                // [empty, keys, subject]
            ADDOP_I(c, LOC(p), DICT_UPDATE, 2);         // [copy, keys]
            ADDOP_I(c, LOC(p), UNPACK_SEQUENCE, size);  // [copy, keys...]
            while (size != 0) {
                ADDOP_I(c, LOC(p), COPY, 1 + size--);   // [copy, keys..., copy]
                ADDOP_I(c, LOC(p), SWAP, 2);            // [copy, keys..., copy, key]
                ADDOP(c, LOC(p), DELETE_SUBSCR);        // [copy, keys...]
            }
            codegen_pattern_helper_store_name(c, LOC(p), star_target, pc);
        }
        else {
            ADDOP(c, LOC(p), POP_TOP);  // Tuple of keys.
            ADDOP(c, LOC(p), POP_TOP);  // Subject.
        }
    }

    private static void codegen_pattern_or(Compile c, MatchOr p, pattern_context pc) {
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        int size = LEN(p.patterns);
        assert size > 1;
        // We're going to be messing with pc. Keep the original info handy:
        pattern_context old_pc = pc.copy();
        // control is the list of names bound by the first alternative. It is used
        // for checking different name bindings in alternatives, and for correcting
        // the order in which extracted elements are placed on the stack.
        List<String> control = null;
        for (int i = 0; i < size; i++) {
            pattern alt = p.patterns.get(i);
            pc.stores = new ArrayList<>();
            // An irrefutable sub-pattern must be last, if it is allowed at all:
            pc.allow_irrefutable = (i == size - 1) && old_pc.allow_irrefutable;
            pc.fail_pop = null;
            pc.fail_pop_size = 0;
            pc.on_top = 0;
            codegen_addop_i(INSTR_SEQUENCE(c), COPY, 1, LOC(alt));
            codegen_pattern(c, alt, pc);
            // Success!
            int nstores = pc.stores.size();
            if (i == 0) {
                // This is the first alternative, so save its stores as a "control"
                // for the others (they can't bind a different set of names, and
                // might need to be reordered):
                assert control == null;
                control = pc.stores;
            }
            else if (nstores != control.size()) {
                throw c._PyCompile_Error(LOC(p), "alternative patterns bind different names");
            }
            else if (nstores != 0) {
                // There were captures. Check to see if we differ from control:
                int icontrol = nstores;
                while (icontrol-- != 0) {
                    String name = control.get(icontrol);
                    int istores = pc.stores.indexOf(name);
                    if (istores < 0) {
                        throw c._PyCompile_Error(LOC(p),
                                "alternative patterns bind different names");
                    }
                    if (icontrol != istores) {
                        // Reorder the names on the stack to match the order of the
                        // names in control. There's probably a better way of doing
                        // this; the current solution is potentially very
                        // inefficient when each alternative subpattern binds lots
                        // of names in different orders. It's fine for reasonable
                        // cases, though, and the peephole optimizer will ensure
                        // that the final code is as efficient as possible.
                        assert istores < icontrol;
                        int rotations = istores + 1;
                        // Perform the same rotation on pc->stores:
                        List<String> rotated = new ArrayList<>(pc.stores.subList(0, rotations));
                        pc.stores.subList(0, rotations).clear();
                        pc.stores.addAll(icontrol - istores, rotated);
                        // That just did:
                        // rotated = pc_stores[:rotations]
                        // del pc_stores[:rotations]
                        // pc_stores[icontrol-istores:icontrol-istores] = rotated
                        // Do the same thing to the stack, using several
                        // rotations:
                        while (rotations-- != 0) {
                            codegen_pattern_helper_rotate(c, LOC(alt), icontrol + 1);
                        }
                    }
                }
            }
            assert control != null;
            codegen_addop_j(INSTR_SEQUENCE(c), LOC(alt), JUMP, end);
            emit_and_reset_fail_pop(c, LOC(alt), pc);
        }
        pc.set(old_pc);
        // No match. Pop the remaining copy of the subject and fail:
        codegen_addop_noarg(INSTR_SEQUENCE(c), POP_TOP, LOC(p));
        jump_to_fail_pop(c, LOC(p), pc, JUMP);

        USE_LABEL(c, end);
        int nstores = control.size();
        // There's a bunch of stuff on the stack between where the new stores
        // are and where they need to be:
        // - The other stores.
        // - A copy of the subject.
        // - Anything else that may be on top of the stack.
        // - Any previous stores we've already stashed away on the stack.
        int nrots = nstores + 1 + pc.on_top + pc.stores.size();
        for (int i = 0; i < nstores; i++) {
            // Rotate this capture to its proper place on the stack:
            codegen_pattern_helper_rotate(c, LOC(p), nrots);
            // Update the list of previous stores with this new name, checking for
            // duplicates:
            String name = control.get(i);
            boolean dupe = pc.stores.contains(name);
            if (dupe) {
                throw codegen_error_duplicate_store(c, LOC(p), name);
            }
            pc.stores.add(name);
        }
        // Pop the copy of the subject:
        ADDOP(c, LOC(p), POP_TOP);
    }


    private static void codegen_pattern_sequence(Compile c, MatchSequence p,
            pattern_context pc) {
        List<pattern> patterns = p.patterns;
        int size = LEN(patterns);
        int star = -1;
        boolean only_wildcard = true;
        boolean star_wildcard = false;
        // Find a starred name, if it exists. There may be at most one:
        for (int i = 0; i < size; i++) {
            pattern pattern = patterns.get(i);
            if (pattern instanceof MatchStar) {
                if (star >= 0) {
                    throw c._PyCompile_Error(LOC(p),
                            "multiple starred names in sequence pattern");
                }
                star_wildcard = WILDCARD_STAR_CHECK(pattern);
                only_wildcard &= star_wildcard;
                star = i;
                continue;
            }
            only_wildcard &= WILDCARD_CHECK(pattern);
        }
        // We need to keep the subject on top during the sequence and length checks:
        pc.on_top++;
        ADDOP(c, LOC(p), MATCH_SEQUENCE);
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        if (star < 0) {
            // No star: len(subject) == size
            ADDOP(c, LOC(p), GET_LEN);
            ADDOP_LOAD_CONST(c, LOC(p), BigInteger.valueOf(size));
            ADDOP_COMPARE(c, LOC(p), cmpopType.Eq);
            jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        }
        else if (size > 1) {
            // Star: len(subject) >= size - 1
            ADDOP(c, LOC(p), GET_LEN);
            ADDOP_LOAD_CONST(c, LOC(p), BigInteger.valueOf(size - 1));
            ADDOP_COMPARE(c, LOC(p), cmpopType.GtE);
            jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
        }
        // Whatever comes next should consume the subject:
        pc.on_top--;
        if (only_wildcard) {
            // Patterns like: [] / [_] / [_, _] / [*_] / [_, *_] / [_, _, *_] / etc.
            ADDOP(c, LOC(p), POP_TOP);
        }
        else if (star_wildcard) {
            pattern_helper_sequence_subscr(c, LOC(p), patterns, star, pc);
        }
        else {
            pattern_helper_sequence_unpack(c, LOC(p), patterns, star, pc);
        }
    }

    private static void codegen_pattern_value(Compile c, MatchValue p, pattern_context pc) {
        expr value = p.value;
        if (!MATCH_VALUE_EXPR(value)) {
            throw c._PyCompile_Error(LOC(p),
                    "patterns may only match literals and attribute lookups");
        }
        codegen_visit_expr(c, value);
        ADDOP_COMPARE(c, LOC(p), cmpopType.Eq);
        ADDOP(c, LOC(p), TO_BOOL);
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
    }

    private static void codegen_pattern_singleton(Compile c, MatchSingleton p,
            pattern_context pc) {
        ADDOP_LOAD_CONST(c, LOC(p), p.value);
        ADDOP_COMPARE(c, LOC(p), cmpopType.Is);
        jump_to_fail_pop(c, LOC(p), pc, POP_JUMP_IF_FALSE);
    }

    private static void codegen_pattern(Compile c, pattern p, pattern_context pc) {
        switch (p.kind()) {
            case MatchValue:
                codegen_pattern_value(c, (MatchValue) p, pc);
                return;
            case MatchSingleton:
                codegen_pattern_singleton(c, (MatchSingleton) p, pc);
                return;
            case MatchSequence:
                codegen_pattern_sequence(c, (MatchSequence) p, pc);
                return;
            case MatchMapping:
                codegen_pattern_mapping(c, (MatchMapping) p, pc);
                return;
            case MatchClass:
                codegen_pattern_class(c, (MatchClass) p, pc);
                return;
            case MatchStar:
                codegen_pattern_star(c, (MatchStar) p, pc);
                return;
            case MatchAs:
                codegen_pattern_as(c, (MatchAs) p, pc);
                return;
            case MatchOr:
                codegen_pattern_or(c, (MatchOr) p, pc);
                return;
        }
        // AST validator shouldn't let this happen, but if it does,
        // just fail, don't crash out of the interpreter
        throw c._PyCompile_Error(LOC(p), "invalid match pattern node in AST (kind=%s)",
                p.kind());
    }

    private static void codegen_match_inner(Compile c, Match s, pattern_context pc) {
        codegen_visit_expr(c, s.subject);
        _PyJumpTargetLabel end = NEW_JUMP_TARGET_LABEL(c);
        int cases = LEN(s.cases);
        assert cases > 0;
        match_case m = s.cases.get(cases - 1);
        int has_default = WILDCARD_CHECK(m.pattern) && 1 < cases ? 1 : 0;
        for (int i = 0; i < cases - has_default; i++) {
            m = s.cases.get(i);
            // Only copy the subject if we're *not* on the last case:
            if (i != cases - has_default - 1) {
                ADDOP_I(c, LOC(m.pattern), COPY, 1);
            }
            pc.stores = new ArrayList<>();
            // Irrefutable cases must be either guarded, last, or both:
            pc.allow_irrefutable = m.guard != null || i == cases - 1;
            pc.fail_pop = null;
            pc.fail_pop_size = 0;
            pc.on_top = 0;
            codegen_pattern(c, m.pattern, pc);
            assert pc.on_top == 0;
            // It's a match! Store all of the captured names (they're on the stack).
            int nstores = pc.stores.size();
            for (int n = 0; n < nstores; n++) {
                String name = pc.stores.get(n);
                codegen_nameop(c, LOC(m.pattern), name, expr_contextType.Store);
            }
            if (m.guard != null) {
                ensure_fail_pop(c, pc, 0);
                codegen_jump_if(c, LOC(m.pattern), m.guard, pc.fail_pop[0], false);
            }
            // Success! Pop the subject off, we're done with it:
            if (i != cases - has_default - 1) {
                /* Use the next location to give better locations for branch events */
                ADDOP(c, NEXT_LOCATION, POP_TOP);
            }
            VISIT_SEQ_stmt(c, m.body);
            ADDOP_JUMP(c, NO_LOCATION, JUMP, end);
            // If the pattern fails to match, we want the line number of the
            // cleanup to be associated with the failed pattern, not the last line
            // of the body
            emit_and_reset_fail_pop(c, LOC(m.pattern), pc);
        }
        if (has_default != 0) {
            // A trailing "case _" is common, and lets us save a bit of redundant
            // pushing and popping in the loop above:
            m = s.cases.get(cases - 1);
            if (cases == 1) {
                // No matches. Done with the subject:
                ADDOP(c, LOC(m.pattern), POP_TOP);
            }
            else {
                // Show line coverage for default case (it doesn't create bytecode)
                ADDOP(c, LOC(m.pattern), NOP);
            }
            if (m.guard != null) {
                codegen_jump_if(c, LOC(m.pattern), m.guard, end, false);
            }
            VISIT_SEQ_stmt(c, m.body);
        }
        USE_LABEL(c, end);
    }

    private static void codegen_match(Compile c, Match s) {
        pattern_context pc = new pattern_context();
        pc.fail_pop = null;
        codegen_match_inner(c, s, pc);
    }


    static void _PyCodegen_AddReturnAtEnd(Compile c, boolean addNone) {
        /* Make sure every instruction stream that falls off the end returns None.
         * This also ensures that no jump target offsets are out of bounds.
         */
        if (addNone) {
            ADDOP_LOAD_CONST(c, NO_LOCATION, Singleton.None);
        }
        ADDOP(c, NO_LOCATION, RETURN_VALUE);
    }
}
