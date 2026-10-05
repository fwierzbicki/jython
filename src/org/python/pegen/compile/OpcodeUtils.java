package org.python.pegen.compile;

import static org.python.pegen.compile.Opcode.*;

/**
 * A port of Include/internal/pycore_opcode_utils.h, with the COMPARISON_*
 * constants of pycore_code.h. The opcodes and their flags are generated, in
 * {@link Opcode}.
 */
public final class OpcodeUtils {

    private OpcodeUtils() {}

    public static final int MAX_REAL_OPCODE = 254;

    /** pycore_opcode_metadata.h IS_PSEUDO_INSTR */
    public static boolean IS_PSEUDO_INSTR(int opcode) {
        return opcode >= ANNOTATIONS_PLACEHOLDER && opcode < MAX_OPCODE_PLUS_ONE;
    }

    public static boolean IS_WITHIN_OPCODE_RANGE(int opcode) {
        return (opcode >= 0 && opcode <= MAX_REAL_OPCODE) || IS_PSEUDO_INSTR(opcode);
    }

    public static boolean IS_BLOCK_PUSH_OPCODE(int opcode) {
        return opcode == SETUP_FINALLY ||
               opcode == SETUP_WITH ||
               opcode == SETUP_CLEANUP;
    }

    public static boolean HAS_TARGET(int opcode) {
        return OPCODE_HAS_JUMP(opcode) || IS_BLOCK_PUSH_OPCODE(opcode);
    }

    /* opcodes that must be last in the basicblock */
    public static boolean IS_TERMINATOR_OPCODE(int opcode) {
        return OPCODE_HAS_JUMP(opcode) || IS_SCOPE_EXIT_OPCODE(opcode);
    }

    /* opcodes which are not emitted in codegen stage, only by the assembler */
    public static boolean IS_ASSEMBLER_OPCODE(int opcode) {
        return opcode == JUMP_FORWARD ||
               opcode == JUMP_BACKWARD ||
               opcode == JUMP_BACKWARD_NO_INTERRUPT;
    }

    public static boolean IS_BACKWARDS_JUMP_OPCODE(int opcode) {
        return opcode == JUMP_BACKWARD ||
               opcode == JUMP_BACKWARD_NO_INTERRUPT;
    }

    public static boolean IS_UNCONDITIONAL_JUMP_OPCODE(int opcode) {
        return opcode == JUMP ||
               opcode == JUMP_NO_INTERRUPT ||
               opcode == JUMP_FORWARD ||
               opcode == JUMP_BACKWARD ||
               opcode == JUMP_BACKWARD_NO_INTERRUPT;
    }

    public static boolean IS_CONDITIONAL_JUMP_OPCODE(int opcode) {
        return opcode == POP_JUMP_IF_FALSE ||
               opcode == POP_JUMP_IF_TRUE ||
               opcode == POP_JUMP_IF_NONE ||
               opcode == POP_JUMP_IF_NOT_NONE;
    }

    public static boolean IS_SCOPE_EXIT_OPCODE(int opcode) {
        return opcode == RETURN_VALUE ||
               opcode == RAISE_VARARGS ||
               opcode == RERAISE;
    }

    public static boolean IS_RETURN_OPCODE(int opcode) {
        return opcode == RETURN_VALUE;
    }

    public static boolean IS_RAISE_OPCODE(int opcode) {
        return opcode == RAISE_VARARGS || opcode == RERAISE;
    }

    /* Flags used in the oparg for MAKE_FUNCTION */
    public static final int MAKE_FUNCTION_DEFAULTS    = 0x01;
    public static final int MAKE_FUNCTION_KWDEFAULTS  = 0x02;
    public static final int MAKE_FUNCTION_ANNOTATIONS = 0x04;
    public static final int MAKE_FUNCTION_CLOSURE     = 0x08;
    public static final int MAKE_FUNCTION_ANNOTATE    = 0x10;

    /* Values used as the oparg for LOAD_COMMON_CONSTANT */
    public static final int CONSTANT_ASSERTIONERROR = 0;
    public static final int CONSTANT_NOTIMPLEMENTEDERROR = 1;
    public static final int CONSTANT_BUILTIN_TUPLE = 2;
    public static final int CONSTANT_BUILTIN_ALL = 3;
    public static final int CONSTANT_BUILTIN_ANY = 4;
    public static final int CONSTANT_BUILTIN_LIST = 5;
    public static final int CONSTANT_BUILTIN_SET = 6;
    public static final int CONSTANT_NONE = 7;
    public static final int CONSTANT_EMPTY_STR = 8;
    public static final int CONSTANT_TRUE = 9;
    public static final int CONSTANT_FALSE = 10;
    public static final int CONSTANT_MINUS_ONE = 11;
    public static final int NUM_COMMON_CONSTANTS = 12;

    /* Values used in the oparg for RESUME */
    public static final int RESUME_AT_FUNC_START = 0;
    public static final int RESUME_AFTER_YIELD = 1;
    public static final int RESUME_AFTER_YIELD_FROM = 2;
    public static final int RESUME_AFTER_AWAIT = 3;
    public static final int RESUME_AT_GEN_EXPR_START = 4;

    public static final int RESUME_OPARG_LOCATION_MASK = 0x7;
    public static final int RESUME_OPARG_DEPTH1_MASK = 0x8;

    public static final int GET_ITER_YIELD_FROM = 1;
    public static final int GET_ITER_YIELD_FROM_NO_CHECK = 2;
    public static final int GET_ITER_YIELD_FROM_CORO_CHECK = 3;

    /* pycore_code.h: the low bits of COMPARE_OP's oparg */
    public static final int COMPARISON_UNORDERED = 1;
    public static final int COMPARISON_LESS_THAN = 2;
    public static final int COMPARISON_GREATER_THAN = 4;
    public static final int COMPARISON_EQUALS = 8;
    public static final int COMPARISON_NOT_EQUALS =
            COMPARISON_UNORDERED | COMPARISON_LESS_THAN | COMPARISON_GREATER_THAN;

    /* Include/object.h: rich comparison opcodes */
    public static final int Py_LT = 0;
    public static final int Py_LE = 1;
    public static final int Py_EQ = 2;
    public static final int Py_NE = 3;
    public static final int Py_GT = 4;
    public static final int Py_GE = 5;
}
