package org.python.pegen.compile;

import static org.python.pegen.compile.Opcode.*;
import static org.python.pegen.compile.OpcodeUtils.*;
import static org.python.pegen.compile.SourceLocation.NO_LOCATION;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.python.pegen.ast.Bytes;
import org.python.pegen.ast.Singleton;
import org.python.pegen.compile.Compile._PyCompile_CodeUnitMetadata;
import org.python.pegen.compile.InstructionSequence._PyExceptHandlerInfo;
import org.python.pegen.compile.InstructionSequence._PyInstruction;

/**
 * A port of Python/flowgraph.c: the control flow graph a code unit's
 * instruction sequence is turned into, its optimizations, and the
 * conversion back to an instruction sequence for the assembler.
 *
 * <p>C's error returns are thrown (as IllegalStateException, standing for
 * SystemError and ValueError, which only malformed input raises). A C
 * {@code cfg_instr} is a struct copied by value, so here every slot of a
 * block's instruction array is its own object, and C's struct assignment
 * is {@link cfg_instr#set}: a reference to a slot is a C pointer into the
 * array. Constant folding's operations on constants are in
 * {@link Abstract}.
 */
public final class Flowgraph {

    private Flowgraph() {}

    private static final int DEFAULT_BLOCK_SIZE = 16;

    /** C: _PyCfgInstruction (cfg_instr). */
    static final class cfg_instr {
        int i_opcode;
        int i_oparg;
        SourceLocation i_loc;
        /** target block (if jump instruction) */
        basicblock i_target;
        /** target block when exception is raised */
        basicblock i_except;

        cfg_instr() {}

        cfg_instr(int opcode, int oparg, SourceLocation loc, basicblock target,
                basicblock except) {
            i_opcode = opcode;
            i_oparg = oparg;
            i_loc = loc;
            i_target = target;
            i_except = except;
        }

        /** C's struct assignment, *this = *o. */
        void set(cfg_instr o) {
            i_opcode = o.i_opcode;
            i_oparg = o.i_oparg;
            i_loc = o.i_loc;
            i_target = o.i_target;
            i_except = o.i_except;
        }

        /** C: memset(this, 0, sizeof(cfg_instr)). */
        void clear() {
            i_opcode = 0;
            i_oparg = 0;
            i_loc = null;
            i_target = null;
            i_except = null;
        }
    }

    /** C: _PyCfgBasicblock (basicblock). */
    static final class basicblock {
        /*
         * Each basicblock in a compilation unit is linked via b_list in the
         * reverse order that the block are allocated. b_list points to the
         * next block in this list, not to be confused with b_next, which is
         * next by control flow.
         */
        basicblock b_list;
        /** The label of this block if it is a jump target, -1 otherwise */
        int b_label = NO_LABEL;
        /** Exception stack at start of block, used by assembler to create the exception handling table */
        _PyCfgExceptStack b_exceptstack;
        /** array of instructions, initially null */
        cfg_instr[] b_instr;
        /** If b_next is non-null, the next block reached by normal control flow. */
        basicblock b_next;
        /** number of instructions used */
        int b_iused;
        /** length of instruction array (b_instr) */
        int b_ialloc;
        /** Used by add_checks_for_loads_of_unknown_variables */
        long b_unsafe_locals_mask;
        /** Number of predecessors that a block has. */
        int b_predecessors;
        /** depth of stack upon entry of block, computed by stackdepth() */
        int b_startdepth;
        /** Basic block is an exception handler that preserves lasti */
        boolean b_preserve_lasti;
        /** Used by compiler passes to mark whether they have visited a basic block. */
        boolean b_visited;
        /** b_except_handler is used by the cold-detection algorithm to mark exception targets */
        boolean b_except_handler;
        /** b_cold is true if this block is not perf critical (like an exception handler) */
        boolean b_cold;
        /** b_warm is used by the cold-detection algorithm to mark blocks which are definitely not cold */
        boolean b_warm;
    }

    /** C: _PyCfgBuilder (cfg_builder). */
    public static final class cfg_builder {
        /*
         * The entryblock, at which control flow begins. All blocks of the
         * CFG are reachable through the b_next links
         */
        basicblock g_entryblock;
        /*
         * Pointer to the most recently allocated block. By following b_list
         * links, you can reach all allocated blocks.
         */
        basicblock g_block_list;
        /** pointer to the block currently being constructed */
        basicblock g_curblock;
        /** label for the next instruction to be placed */
        int g_current_label;
    }

    /** C: jump_target_label's NO_LABEL; a label is its id here. */
    static final int NO_LABEL = -1;

    static boolean IS_LABEL(int l) {
        return l != NO_LABEL;
    }

    private static boolean is_block_push(cfg_instr i) {
        assert OPCODE_HAS_ARG(i.i_opcode) || !IS_BLOCK_PUSH_OPCODE(i.i_opcode);
        return IS_BLOCK_PUSH_OPCODE(i.i_opcode);
    }

    private static boolean is_jump(cfg_instr i) {
        return OPCODE_HAS_JUMP(i.i_opcode);
    }

    /* One arg */
    private static void INSTR_SET_OP1(cfg_instr i, int op, int arg) {
        assert OPCODE_HAS_ARG(op);
        i.i_opcode = op;
        i.i_oparg = arg;
    }

    /* No args */
    private static void INSTR_SET_OP0(cfg_instr i, int op) {
        assert !OPCODE_HAS_ARG(op);
        i.i_opcode = op;
        i.i_oparg = 0;
    }

    private static void INSTR_SET_LOC(cfg_instr i, SourceLocation loc) {
        i.i_loc = loc;
    }

    /***** Blocks *****/

    /*
     * Returns the offset of the next instruction in the current block's
     * b_instr array. Resizes the b_instr as necessary.
     */
    private static int basicblock_next_instr(basicblock b) {
        assert b != null;
        // C: _Py_CArray_EnsureCapacity, whose new entries are zeroed.
        int idx = b.b_iused + 1;
        if (b.b_instr == null) {
            int new_alloc = DEFAULT_BLOCK_SIZE;
            while (idx >= new_alloc) {
                new_alloc *= 2;
            }
            b.b_instr = new cfg_instr[new_alloc];
            for (int i = 0; i < new_alloc; i++) {
                b.b_instr[i] = new cfg_instr();
            }
            b.b_ialloc = new_alloc;
        }
        else if (idx >= b.b_ialloc) {
            int old_alloc = b.b_ialloc;
            int new_alloc = old_alloc * 2;
            while (idx >= new_alloc) {
                new_alloc *= 2;
            }
            cfg_instr[] grown = new cfg_instr[new_alloc];
            System.arraycopy(b.b_instr, 0, grown, 0, old_alloc);
            for (int i = old_alloc; i < new_alloc; i++) {
                grown[i] = new cfg_instr();
            }
            b.b_instr = grown;
            b.b_ialloc = new_alloc;
        }
        return b.b_iused++;
    }

    private static cfg_instr basicblock_last_instr(basicblock b) {
        assert b.b_iused >= 0;
        if (b.b_iused > 0) {
            assert b.b_instr != null;
            return b.b_instr[b.b_iused - 1];
        }
        return null;
    }

    /* Allocate a new block and return a pointer to it. */
    private static basicblock cfg_builder_new_block(cfg_builder g) {
        basicblock b = new basicblock();
        /* Extend the singly linked list of blocks with new block. */
        b.b_list = g.g_block_list;
        g.g_block_list = b;
        b.b_label = NO_LABEL;
        return b;
    }

    private static void basicblock_addop(basicblock b, int opcode, int oparg, SourceLocation loc) {
        assert IS_WITHIN_OPCODE_RANGE(opcode);
        assert !IS_ASSEMBLER_OPCODE(opcode);
        assert OPCODE_HAS_ARG(opcode) || HAS_TARGET(opcode) || oparg == 0;
        assert 0 <= oparg && oparg < (1 << 30);

        int off = basicblock_next_instr(b);
        cfg_instr i = b.b_instr[off];
        i.i_opcode = opcode;
        i.i_oparg = oparg;
        i.i_loc = loc;
        // memory is already zero initialized
        assert i.i_target == null;
        assert i.i_except == null;
    }

    private static void basicblock_add_jump(basicblock b, int opcode, basicblock target,
            SourceLocation loc) {
        cfg_instr last = basicblock_last_instr(b);
        if (last != null && is_jump(last)) {
            throw new IllegalStateException("jump added after a jump");
        }

        basicblock_addop(b, opcode, target.b_label, loc);
        last = basicblock_last_instr(b);
        assert last != null && last.i_opcode == opcode;
        last.i_target = target;
    }

    private static void basicblock_append_instructions(basicblock to, basicblock from) {
        for (int i = 0; i < from.b_iused; i++) {
            int n = basicblock_next_instr(to);
            to.b_instr[n].set(from.b_instr[i]);
        }
    }

    private static boolean basicblock_nofallthrough(basicblock b) {
        cfg_instr last = basicblock_last_instr(b);
        return (last != null &&
                (IS_SCOPE_EXIT_OPCODE(last.i_opcode) ||
                 IS_UNCONDITIONAL_JUMP_OPCODE(last.i_opcode)));
    }

    private static boolean BB_NO_FALLTHROUGH(basicblock b) {
        return basicblock_nofallthrough(b);
    }

    private static boolean BB_HAS_FALLTHROUGH(basicblock b) {
        return !basicblock_nofallthrough(b);
    }

    private static basicblock copy_basicblock(cfg_builder g, basicblock block) {
        /* Cannot copy a block if it has a fallthrough, since
         * a block can only have one fallthrough predecessor.
         */
        assert BB_NO_FALLTHROUGH(block);
        basicblock result = cfg_builder_new_block(g);
        basicblock_append_instructions(result, block);
        return result;
    }

    private static void basicblock_insert_instruction(basicblock block, int pos, cfg_instr instr) {
        basicblock_next_instr(block);
        for (int i = block.b_iused - 1; i > pos; i--) {
            block.b_instr[i].set(block.b_instr[i-1]);
        }
        block.b_instr[pos].set(instr);
    }

    /***** CFG construction and modification *****/

    private static basicblock cfg_builder_use_next_block(cfg_builder g, basicblock block) {
        assert block != null;
        g.g_curblock.b_next = block;
        g.g_curblock = block;
        return block;
    }

    private static boolean basicblock_exits_scope(basicblock b) {
        cfg_instr last = basicblock_last_instr(b);
        return last != null && IS_SCOPE_EXIT_OPCODE(last.i_opcode);
    }

    private static boolean basicblock_has_eval_break(basicblock b) {
        for (int i = 0; i < b.b_iused; i++) {
            if (OPCODE_HAS_EVAL_BREAK(b.b_instr[i].i_opcode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cfg_builder_current_block_is_terminated(cfg_builder g) {
        cfg_instr last = basicblock_last_instr(g.g_curblock);
        if (last != null && IS_TERMINATOR_OPCODE(last.i_opcode)) {
            return true;
        }
        if (IS_LABEL(g.g_current_label)) {
            if (last != null || IS_LABEL(g.g_curblock.b_label)) {
                return true;
            }
            else {
                /* current block is empty, label it */
                g.g_curblock.b_label = g.g_current_label;
                g.g_current_label = NO_LABEL;
            }
        }
        return false;
    }

    private static void cfg_builder_maybe_start_new_block(cfg_builder g) {
        if (cfg_builder_current_block_is_terminated(g)) {
            basicblock b = cfg_builder_new_block(g);
            b.b_label = g.g_current_label;
            g.g_current_label = NO_LABEL;
            cfg_builder_use_next_block(g, b);
        }
    }

    private static void init_cfg_builder(cfg_builder g) {
        g.g_block_list = null;
        basicblock block = cfg_builder_new_block(g);
        g.g_curblock = g.g_entryblock = block;
        g.g_current_label = NO_LABEL;
    }

    public static cfg_builder _PyCfgBuilder_New() {
        cfg_builder g = new cfg_builder();
        init_cfg_builder(g);
        return g;
    }

    static void _PyCfgBuilder_UseLabel(cfg_builder g, int lbl) {
        g.g_current_label = lbl;
        cfg_builder_maybe_start_new_block(g);
    }

    static void _PyCfgBuilder_Addop(cfg_builder g, int opcode, int oparg, SourceLocation loc) {
        cfg_builder_maybe_start_new_block(g);
        basicblock_addop(g.g_curblock, opcode, oparg, loc);
    }

    private static basicblock next_nonempty_block(basicblock b) {
        while (b != null && b.b_iused == 0) {
            b = b.b_next;
        }
        return b;
    }

    /***** CFG preprocessing (jump targets and exceptions) *****/

    private static void normalize_jumps_in_block(cfg_builder g, basicblock b) {
        cfg_instr last = basicblock_last_instr(b);
        if (last == null || !IS_CONDITIONAL_JUMP_OPCODE(last.i_opcode)) {
            return;
        }
        assert !IS_ASSEMBLER_OPCODE(last.i_opcode);

        boolean is_forward = !last.i_target.b_visited;
        if (is_forward) {
            basicblock_addop(b, NOT_TAKEN, 0, last.i_loc);
            return;
        }

        int reversed_opcode = 0;
        switch (last.i_opcode) {
            case POP_JUMP_IF_NOT_NONE:
                reversed_opcode = POP_JUMP_IF_NONE;
                break;
            case POP_JUMP_IF_NONE:
                reversed_opcode = POP_JUMP_IF_NOT_NONE;
                break;
            case POP_JUMP_IF_FALSE:
                reversed_opcode = POP_JUMP_IF_TRUE;
                break;
            case POP_JUMP_IF_TRUE:
                reversed_opcode = POP_JUMP_IF_FALSE;
                break;
        }
        /* transform 'conditional jump T' to
         * 'reversed_jump b_next' followed by 'jump_backwards T'
         */

        basicblock target = last.i_target;
        basicblock backwards_jump = cfg_builder_new_block(g);
        basicblock_addop(backwards_jump, NOT_TAKEN, 0, last.i_loc);
        basicblock_add_jump(backwards_jump, JUMP, target, last.i_loc);
        backwards_jump.b_startdepth = target.b_startdepth;
        last.i_opcode = reversed_opcode;
        last.i_target = b.b_next;

        backwards_jump.b_cold = b.b_cold;
        backwards_jump.b_next = b.b_next;
        b.b_next = backwards_jump;
    }

    private static void normalize_jumps(cfg_builder g) {
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            b.b_visited = false;
        }
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            b.b_visited = true;
            normalize_jumps_in_block(g, b);
        }
    }

    private static void check_cfg(cfg_builder g) {
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            /* Raise SystemError if jump or exit is not last instruction in the block. */
            for (int i = 0; i < b.b_iused; i++) {
                int opcode = b.b_instr[i].i_opcode;
                assert !IS_ASSEMBLER_OPCODE(opcode);
                if (IS_TERMINATOR_OPCODE(opcode)) {
                    if (i != b.b_iused - 1) {
                        throw new IllegalStateException("malformed control flow graph.");
                    }
                }
            }
        }
    }

    private static int get_max_label(basicblock entryblock) {
        int lbl = -1;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (b.b_label > lbl) {
                lbl = b.b_label;
            }
        }
        return lbl;
    }

    /* Calculate the actual jump target from the target_label */
    private static void translate_jump_labels_to_targets(basicblock entryblock) {
        int max_label = get_max_label(entryblock);
        basicblock[] label2block = new basicblock[max_label + 1];
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (b.b_label >= 0) {
                label2block[b.b_label] = b;
            }
        }
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                assert instr.i_target == null;
                if (HAS_TARGET(instr.i_opcode)) {
                    int lbl = instr.i_oparg;
                    assert lbl >= 0 && lbl <= max_label;
                    instr.i_target = label2block[lbl];
                    assert instr.i_target != null;
                    assert instr.i_target.b_label == lbl;
                }
            }
        }
    }

    private static void mark_except_handlers(basicblock entryblock) {
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            assert !b.b_except_handler;
        }
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (is_block_push(instr)) {
                    instr.i_target.b_except_handler = true;
                }
            }
        }
    }

    /** C: struct _PyCfgExceptStack. */
    static final class _PyCfgExceptStack {
        basicblock[] handlers = new basicblock[Compile.CO_MAXBLOCKS + 2];
        int depth;
    }

    private static basicblock push_except_block(_PyCfgExceptStack stack, cfg_instr setup) {
        assert is_block_push(setup);
        int opcode = setup.i_opcode;
        basicblock target = setup.i_target;
        if (opcode == SETUP_WITH || opcode == SETUP_CLEANUP) {
            target.b_preserve_lasti = true;
        }
        assert stack.depth <= Compile.CO_MAXBLOCKS;
        stack.handlers[++stack.depth] = target;
        return target;
    }

    private static basicblock pop_except_block(_PyCfgExceptStack stack) {
        assert stack.depth > 0;
        return stack.handlers[--stack.depth];
    }

    private static basicblock except_stack_top(_PyCfgExceptStack stack) {
        return stack.handlers[stack.depth];
    }

    private static _PyCfgExceptStack make_except_stack() {
        _PyCfgExceptStack new_ = new _PyCfgExceptStack();
        new_.depth = 0;
        new_.handlers[0] = null;
        return new_;
    }

    private static _PyCfgExceptStack copy_except_stack(_PyCfgExceptStack stack) {
        _PyCfgExceptStack copy = new _PyCfgExceptStack();
        copy.handlers = stack.handlers.clone();
        copy.depth = stack.depth;
        return copy;
    }

    private static basicblock[] make_cfg_traversal_stack(basicblock entryblock) {
        int nblocks = 0;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            b.b_visited = false;
            nblocks++;
        }
        return new basicblock[nblocks];
    }

    /**
     * Compute the stack effects of opcode with argument oparg: the net
     * effect, or Integer.MIN_VALUE if it isn't known (C: get_stack_effects
     * returning -1).
     *
     * Some opcodes have different stack effect when jump to the target and
     * when not jump. The 'jump' parameter specifies the case:
     *
     * * 0 -- when not jump
     * * 1 -- when jump
     * * -1 -- maximal
     */
    private static int get_stack_effects(int opcode, int oparg, int jump) {
        if (opcode < 0) {
            return Integer.MIN_VALUE;
        }
        if ((opcode <= MAX_REAL_OPCODE) && (_PyOpcode_Deopt[opcode] != opcode)) {
            // Specialized instructions are not supported.
            return Integer.MIN_VALUE;
        }
        int popped = _PyOpcode_num_popped(opcode, oparg);
        int pushed = _PyOpcode_num_pushed(opcode, oparg);
        if (popped < 0 || pushed < 0) {
            return Integer.MIN_VALUE;
        }
        if (IS_BLOCK_PUSH_OPCODE(opcode) && jump == 0) {
            return 0;
        }
        return pushed - popped;
    }

    /** C: stackdepth_push, with the stack pointer sp[0] an index into stack. */
    private static void stackdepth_push(basicblock[] stack, int[] sp, basicblock b, int depth) {
        if (!(b.b_startdepth < 0 || b.b_startdepth == depth)) {
            throw new IllegalStateException("Invalid CFG, inconsistent stackdepth");
        }
        if (b.b_startdepth < depth && b.b_startdepth < 100) {
            assert b.b_startdepth < 0;
            b.b_startdepth = depth;
            stack[sp[0]++] = b;
        }
    }

    /*
     * Find the flow path that needs the largest stack. We assume that
     * cycles in the flow graph have no net effect on the stack depth.
     */
    static int calculate_stackdepth(cfg_builder g) {
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            b.b_startdepth = Integer.MIN_VALUE;
        }
        basicblock[] stack = make_cfg_traversal_stack(entryblock);

        int maxdepth = 0;
        int[] sp = {0};
        stackdepth_push(stack, sp, entryblock, 0);
        while (sp[0] != 0) {
            basicblock b = stack[--sp[0]];
            int depth = b.b_startdepth;
            assert depth >= 0;
            basicblock next = b.b_next;
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                int effect = get_stack_effects(instr.i_opcode, instr.i_oparg, 0);
                if (effect == Integer.MIN_VALUE) {
                    throw new IllegalStateException(String.format(
                            "Invalid stack effect for opcode=%d, arg=%d",
                            instr.i_opcode, instr.i_oparg));
                }
                int new_depth = depth + effect;
                if (new_depth < 0) {
                    throw new IllegalStateException(String.format(
                            "Invalid CFG, stack underflow at line %d", instr.i_loc.lineno));
                }
                maxdepth = Math.max(maxdepth, depth);
                if (HAS_TARGET(instr.i_opcode) && instr.i_opcode != END_ASYNC_FOR) {
                    effect = get_stack_effects(instr.i_opcode, instr.i_oparg, 1);
                    if (effect == Integer.MIN_VALUE) {
                        throw new IllegalStateException(String.format(
                                "Invalid stack effect for opcode=%d, arg=%d",
                                instr.i_opcode, instr.i_oparg));
                    }
                    int target_depth = depth + effect;
                    assert target_depth >= 0; /* invalid code or bug in stackdepth() */
                    maxdepth = Math.max(maxdepth, depth);
                    stackdepth_push(stack, sp, instr.i_target, target_depth);
                }
                depth = new_depth;
                assert !IS_ASSEMBLER_OPCODE(instr.i_opcode);
                if (IS_UNCONDITIONAL_JUMP_OPCODE(instr.i_opcode) ||
                    IS_SCOPE_EXIT_OPCODE(instr.i_opcode))
                {
                    /* remaining code is dead */
                    next = null;
                    break;
                }
            }
            if (next != null) {
                assert BB_HAS_FALLTHROUGH(b);
                stackdepth_push(stack, sp, next, depth);
            }
        }
        return maxdepth;
    }

    private static void label_exception_targets(basicblock entryblock) {
        basicblock[] todo_stack = make_cfg_traversal_stack(entryblock);
        _PyCfgExceptStack except_stack = make_except_stack();
        except_stack.depth = 0;
        todo_stack[0] = entryblock;
        entryblock.b_visited = true;
        entryblock.b_exceptstack = except_stack;
        int todo = 1;
        basicblock handler = null;
        while (todo > 0) {
            todo--;
            basicblock b = todo_stack[todo];
            assert b.b_visited;
            except_stack = b.b_exceptstack;
            assert except_stack != null;
            b.b_exceptstack = null;
            handler = except_stack_top(except_stack);
            int last_yield_except_depth = -1;
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (is_block_push(instr)) {
                    if (!instr.i_target.b_visited) {
                        _PyCfgExceptStack copy = copy_except_stack(except_stack);
                        instr.i_target.b_exceptstack = copy;
                        todo_stack[todo] = instr.i_target;
                        instr.i_target.b_visited = true;
                        todo++;
                    }
                    handler = push_except_block(except_stack, instr);
                }
                else if (instr.i_opcode == POP_BLOCK) {
                    handler = pop_except_block(except_stack);
                    INSTR_SET_OP0(instr, NOP);
                }
                else if (is_jump(instr)) {
                    instr.i_except = handler;
                    assert i == b.b_iused - 1;
                    if (!instr.i_target.b_visited) {
                        if (BB_HAS_FALLTHROUGH(b)) {
                            _PyCfgExceptStack copy = copy_except_stack(except_stack);
                            instr.i_target.b_exceptstack = copy;
                        }
                        else {
                            instr.i_target.b_exceptstack = except_stack;
                            except_stack = null;
                        }
                        todo_stack[todo] = instr.i_target;
                        instr.i_target.b_visited = true;
                        todo++;
                    }
                }
                else if (instr.i_opcode == YIELD_VALUE) {
                    instr.i_except = handler;
                    last_yield_except_depth = except_stack.depth;
                }
                else if (instr.i_opcode == RESUME) {
                    instr.i_except = handler;
                    if (instr.i_oparg != RESUME_AT_FUNC_START
                            && instr.i_oparg != RESUME_AT_GEN_EXPR_START) {
                        assert last_yield_except_depth >= 0;
                        if (last_yield_except_depth == 1) {
                            instr.i_oparg |= RESUME_OPARG_DEPTH1_MASK;
                        }
                        last_yield_except_depth = -1;
                    }
                }
                else if (instr.i_opcode == RETURN_GENERATOR) {
                    instr.i_except = null;
                }
                else {
                    instr.i_except = handler;
                }
            }
            if (BB_HAS_FALLTHROUGH(b) && !b.b_next.b_visited) {
                assert except_stack != null;
                b.b_next.b_exceptstack = except_stack;
                todo_stack[todo] = b.b_next;
                b.b_next.b_visited = true;
                todo++;
            }
        }
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            assert b.b_exceptstack == null;
        }
    }

    /***** CFG optimizations *****/

    private static void remove_unreachable(basicblock entryblock) {
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            b.b_predecessors = 0;
        }
        basicblock[] stack = make_cfg_traversal_stack(entryblock);
        int sp = 0;
        entryblock.b_predecessors = 1;
        stack[sp++] = entryblock;
        entryblock.b_visited = true;
        while (sp > 0) {
            basicblock b = stack[--sp];
            if (b.b_next != null && BB_HAS_FALLTHROUGH(b)) {
                if (!b.b_next.b_visited) {
                    assert b.b_next.b_predecessors == 0;
                    stack[sp++] = b.b_next;
                    b.b_next.b_visited = true;
                }
                b.b_next.b_predecessors++;
            }
            for (int i = 0; i < b.b_iused; i++) {
                basicblock target;
                cfg_instr instr = b.b_instr[i];
                if (is_jump(instr) || is_block_push(instr)) {
                    target = instr.i_target;
                    if (!target.b_visited) {
                        stack[sp++] = target;
                        target.b_visited = true;
                    }
                    target.b_predecessors++;
                }
            }
        }

        /* Delete unreachable instructions */
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (b.b_predecessors == 0) {
                b.b_iused = 0;
                b.b_except_handler = false;
            }
        }
    }

    private static int basicblock_remove_redundant_nops(basicblock bb) {
        /* Remove NOPs when legal to do so. */
        int dest = 0;
        int prev_lineno = -1;
        for (int src = 0; src < bb.b_iused; src++) {
            int lineno = bb.b_instr[src].i_loc.lineno;
            if (bb.b_instr[src].i_opcode == NOP) {
                /* Eliminate no-op if it doesn't have a line number */
                if (lineno < 0) {
                    continue;
                }
                /* or, if the previous instruction had the same line number. */
                if (prev_lineno == lineno) {
                    continue;
                }
                /* or, if the next instruction has same line number or no line number */
                if (src < bb.b_iused - 1) {
                    int next_lineno = bb.b_instr[src+1].i_loc.lineno;
                    if (next_lineno == lineno) {
                        continue;
                    }
                    if (next_lineno < 0) {
                        bb.b_instr[src+1].i_loc = bb.b_instr[src].i_loc;
                        continue;
                    }
                }
                else {
                    basicblock next = next_nonempty_block(bb.b_next);
                    /* or if last instruction in BB and next BB has same line number */
                    if (next != null) {
                        SourceLocation next_loc = NO_LOCATION;
                        for (int next_i = 0; next_i < next.b_iused; next_i++) {
                            cfg_instr instr = next.b_instr[next_i];
                            if (instr.i_opcode == NOP && instr.i_loc.lineno < 0) {
                                /* Skip over NOPs without a location, they will be removed */
                                continue;
                            }
                            next_loc = instr.i_loc;
                            break;
                        }
                        if (lineno == next_loc.lineno) {
                            continue;
                        }
                    }
                }

            }
            if (dest != src) {
                bb.b_instr[dest].set(bb.b_instr[src]);
            }
            dest++;
            prev_lineno = lineno;
        }
        assert dest <= bb.b_iused;
        int num_removed = bb.b_iused - dest;
        bb.b_iused = dest;
        for (int i = dest; i < dest + num_removed; i++) {
            bb.b_instr[i].clear();
        }
        return num_removed;
    }

    private static int remove_redundant_nops(cfg_builder g) {
        int changes = 0;
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            int change = basicblock_remove_redundant_nops(b);
            changes += change;
        }
        return changes;
    }

    private static void remove_redundant_nops_and_pairs(basicblock entryblock) {
        boolean done = false;

        while (!done) {
            done = true;
            cfg_instr prev_instr = null;
            cfg_instr instr = null;
            for (basicblock b = entryblock; b != null; b = b.b_next) {
                basicblock_remove_redundant_nops(b);
                if (IS_LABEL(b.b_label)) {
                    /* this block is a jump target, forget instr */
                    instr = null;
                }
                for (int i = 0; i < b.b_iused; i++) {
                    prev_instr = instr;
                    instr = b.b_instr[i];
                    int prev_opcode = prev_instr != null ? prev_instr.i_opcode : 0;
                    int prev_oparg = prev_instr != null ? prev_instr.i_oparg : 0;
                    int opcode = instr.i_opcode;
                    boolean is_redundant_pair = false;
                    if (opcode == POP_TOP) {
                        if (loads_const(prev_opcode)) {
                            is_redundant_pair = true;
                        }
                        else if (prev_opcode == COPY && prev_oparg == 1) {
                            is_redundant_pair = true;
                        }
                    }
                    if (is_redundant_pair) {
                        INSTR_SET_OP0(prev_instr, NOP);
                        INSTR_SET_OP0(instr, NOP);
                        done = false;
                    }
                }
                if ((instr != null && is_jump(instr)) || !BB_HAS_FALLTHROUGH(b)) {
                    instr = null;
                }
            }
        }
    }

    private static int remove_redundant_jumps(cfg_builder g) {
        /* If a non-empty block ends with a jump instruction, check if the next
         * non-empty block reached through normal flow control is the target
         * of that jump. If it is, then the jump instruction is redundant and
         * can be deleted.
         *
         * Return the number of changes applied.
         */

        int changes = 0;
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            cfg_instr last = basicblock_last_instr(b);
            if (last == null) {
                continue;
            }
            assert !IS_ASSEMBLER_OPCODE(last.i_opcode);
            if (IS_UNCONDITIONAL_JUMP_OPCODE(last.i_opcode)) {
                basicblock jump_target = next_nonempty_block(last.i_target);
                if (jump_target == null) {
                    throw new IllegalStateException("jump with NULL target");
                }
                basicblock next = next_nonempty_block(b.b_next);
                if (jump_target == next) {
                    changes++;
                    INSTR_SET_OP0(last, NOP);
                }
            }
        }

        return changes;
    }

    private static boolean basicblock_has_no_lineno(basicblock b) {
        for (int i = 0; i < b.b_iused; i++) {
            if (b.b_instr[i].i_loc.lineno >= 0) {
                return false;
            }
        }
        return true;
    }

    /* Maximum size of basic block that should be copied in optimizer */
    private static final int MAX_COPY_SIZE = 4;

    /* If this block ends with an unconditional jump to a small exit block or
     * a block that has no line numbers (and no fallthrough), then
     * remove the jump and extend this block with the target.
     * Returns 1 if extended, 0 if no change.
     */
    private static int basicblock_inline_small_or_no_lineno_blocks(basicblock bb) {
        cfg_instr last = basicblock_last_instr(bb);
        if (last == null) {
            return 0;
        }
        if (!IS_UNCONDITIONAL_JUMP_OPCODE(last.i_opcode)) {
            return 0;
        }
        basicblock target = last.i_target;
        boolean small_exit_block = (basicblock_exits_scope(target) &&
                                    target.b_iused <= MAX_COPY_SIZE);
        boolean no_lineno_no_fallthrough = (basicblock_has_no_lineno(target) &&
                                            !BB_HAS_FALLTHROUGH(target));
        if (small_exit_block || no_lineno_no_fallthrough) {
            assert is_jump(last);
            int removed_jump_opcode = last.i_opcode;
            INSTR_SET_OP0(last, NOP);
            basicblock_append_instructions(bb, target);
            if (no_lineno_no_fallthrough) {
                last = basicblock_last_instr(bb);
                if (IS_UNCONDITIONAL_JUMP_OPCODE(last.i_opcode) &&
                    removed_jump_opcode == JUMP)
                {
                    /* Make sure we don't lose eval breaker checks */
                    last.i_opcode = JUMP;
                }
            }
            target.b_predecessors--;
            return 1;
        }
        return 0;
    }

    private static void inline_small_or_no_lineno_blocks(basicblock entryblock) {
        boolean changes;
        do {
            changes = false;
            for (basicblock b = entryblock; b != null; b = b.b_next) {
                int res = basicblock_inline_small_or_no_lineno_blocks(b);
                if (res != 0) {
                    changes = true;
                }
            }
        } while (changes); /* every change removes a jump, ensuring convergence */
    }

    // Attempt to eliminate jumps to jumps by updating inst to jump to
    // target->i_target using the provided opcode. Return whether or not the
    // optimization was successful (C: a bool, used as 0 or 1).
    private static int jump_thread(basicblock bb, cfg_instr inst, cfg_instr target, int opcode) {
        assert is_jump(inst);
        assert is_jump(target);
        assert inst == basicblock_last_instr(bb);
        // bpo-45773: If inst->i_target == target->i_target, then nothing actually
        // changes (and we fall into an infinite loop):
        if (inst.i_target != target.i_target) {
            /* Change inst to NOP and append a jump to target->i_target. The
             * NOP will be removed later if it's not needed for the lineno.
             */
            INSTR_SET_OP0(inst, NOP);

            basicblock_add_jump(bb, opcode, target.i_target, target.i_loc);

            return 1;
        }
        return 0;
    }

    private static boolean loads_const(int opcode) {
        return OPCODE_HAS_CONST(opcode)
            || opcode == LOAD_SMALL_INT
            || opcode == LOAD_COMMON_CONSTANT;
    }

    private static Object get_const_value(int opcode, int oparg, List<Object> co_consts) {
        Object constant = null;
        assert loads_const(opcode);
        if (opcode == LOAD_CONST) {
            int n = co_consts.size();
            if (oparg < 0 || oparg >= n) {
                throw new IllegalStateException(String.format(
                        "LOAD_CONST index %d is out of range for consts (len=%d)", oparg, n));
            }
            constant = co_consts.get(oparg);
        }
        if (opcode == LOAD_SMALL_INT) {
            return BigInteger.valueOf(oparg);
        }
        if (opcode == LOAD_COMMON_CONSTANT) {
            assert oparg < NUM_COMMON_CONSTANTS;
            return Abstract.common_consts[oparg];
        }

        if (constant == null) {
            throw new IllegalStateException("Internal error: failed to get value of a constant");
        }
        return constant;
    }

    /**
     * C: the _Py_hashtable that maps a constant (by its address) to its
     * index in consts. Addresses are compared with {@link Abstract#Py_Is}.
     */
    static final class ConstsIndex {
        private final Map<Object, Integer> map = new java.util.HashMap<>();

        private static final class Address {
            final Object op;

            Address(Object op) {
                this.op = op;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Address && Abstract.Py_Is(op, ((Address) o).op);
            }

            @Override
            public int hashCode() {
                return Abstract.Py_Is_hash(op);
            }
        }

        Integer get(Object op) {
            return map.get(new Address(op));
        }

        void set(Object op, int index) {
            map.put(new Address(op), index);
        }
    }

    private static int add_const(Object newconst, List<Object> consts,
            Map<Object, Object> const_cache, ConstsIndex consts_index) {
        newconst = Compile._PyCompile_ConstCacheMergeOne(const_cache, newconst);

        Integer entry = consts_index.get(newconst);
        if (entry != null) {
            return entry;
        }

        int index = consts.size();
        if (index >= Integer.MAX_VALUE - 1) {
            throw new IllegalStateException("too many constants");
        }
        consts.add(newconst);

        consts_index.set(newconst, index);

        return index;
    }

    /*
     * Traverse the instructions of the basic block backwards from index
     * "start", skipping over NOPs. Try to collect "size" number of
     * consecutive instructions that load constants into the array "instrs".
     * Caller must make sure that length of "instrs" is sufficient to fit in
     * at least "size" instructions.
     *
     * Return boolean indicating whether "size" such instructions were found.
     */
    private static boolean get_const_loading_instrs(basicblock bb, int start, cfg_instr[] instrs,
            int size) {
        assert start < bb.b_iused;
        assert size >= 0;
        assert size <= Compile._PY_STACK_USE_GUIDELINE;

        for (; start >= 0 && size > 0; start--) {
            cfg_instr instr = bb.b_instr[start];
            if (instr.i_opcode == NOP) {
                continue;
            }
            if (!loads_const(instr.i_opcode)) {
                return false;
            }
            instrs[--size] = instr;
        }

        return size == 0;
    }

    /*
     * Change every instruction in "instrs" NOP and set its location to
     * NO_LOCATION. Caller must make sure "instrs" has at least "size"
     * elements.
     */
    private static void nop_out(cfg_instr[] instrs, int size) {
        for (int i = 0; i < size; i++) {
            cfg_instr instr = instrs[i];
            assert instr.i_opcode != NOP;
            INSTR_SET_OP0(instr, NOP);
            INSTR_SET_LOC(instr, NO_LOCATION);
        }
    }

    /*
     * Return 1 if changed instruction to LOAD_SMALL_INT.
     * Return 0 if could not change instruction to LOAD_SMALL_INT.
     */
    private static int maybe_instr_make_load_smallint(cfg_instr instr, Object newconst,
            List<Object> consts, Map<Object, Object> const_cache) {
        if (newconst instanceof BigInteger) {
            BigInteger v = (BigInteger) newconst;
            // C: !overflow && _PY_IS_SMALL_INT(val) && 0 <= val && val <= 255
            if (v.signum() >= 0 && v.bitLength() <= 8) {
                INSTR_SET_OP1(instr, LOAD_SMALL_INT, v.intValue());
                return 1;
            }
        }
        return 0;
    }

    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    /*
     * Return 1 if changed instruction to LOAD_COMMON_CONSTANT.
     * Return 0 if could not change instruction to LOAD_COMMON_CONSTANT.
     */
    private static int maybe_instr_make_load_common_const(cfg_instr instr, Object newconst) {
        int oparg;
        if (newconst == Singleton.None) {
            oparg = CONSTANT_NONE;
        }
        else if (newconst == Singleton.True) {
            oparg = CONSTANT_TRUE;
        }
        else if (newconst == Singleton.False) {
            oparg = CONSTANT_FALSE;
        }
        else if (newconst instanceof String && ((String) newconst).isEmpty()) {
            oparg = CONSTANT_EMPTY_STR;
        }
        else if (newconst instanceof BigInteger) {
            if (!newconst.equals(MINUS_ONE)) {
                return 0;
            }
            oparg = CONSTANT_MINUS_ONE;
        }
        else {
            return 0;
        }
        INSTR_SET_OP1(instr, LOAD_COMMON_CONSTANT, oparg);
        return 1;
    }

    private static void instr_make_load_const(cfg_instr instr, Object newconst,
            List<Object> consts, Map<Object, Object> const_cache, ConstsIndex consts_index) {
        int res = maybe_instr_make_load_smallint(instr, newconst, consts, const_cache);
        if (res > 0) {
            return;
        }
        res = maybe_instr_make_load_common_const(instr, newconst);
        if (res > 0) {
            return;
        }
        int oparg = add_const(newconst, consts, const_cache, consts_index);
        INSTR_SET_OP1(instr, LOAD_CONST, oparg);
    }

    /* Replace LOAD_CONST c1, LOAD_CONST c2 ... LOAD_CONST cn, BUILD_TUPLE n
       with    LOAD_CONST (c1, c2, ... cn).
       The consts table must still be in list form so that the
       new constant (c1, c2, ... cn) can be appended.
       Called with codestr pointing to the first LOAD_CONST.
    */
    private static void fold_tuple_of_constants(basicblock bb, int i, List<Object> consts,
            Map<Object, Object> const_cache, ConstsIndex consts_index) {
        cfg_instr instr = bb.b_instr[i];
        assert instr.i_opcode == BUILD_TUPLE;

        int seq_size = instr.i_oparg;
        if (seq_size > Compile._PY_STACK_USE_GUIDELINE) {
            return;
        }

        cfg_instr[] const_instrs = new cfg_instr[Compile._PY_STACK_USE_GUIDELINE];
        if (!get_const_loading_instrs(bb, i-1, const_instrs, seq_size)) {
            /* not a const sequence */
            return;
        }

        Object[] const_tuple = new Object[seq_size];

        for (int k = 0; k < seq_size; k++) {
            cfg_instr inst = const_instrs[k];
            assert loads_const(inst.i_opcode);
            Object element = get_const_value(inst.i_opcode, inst.i_oparg, consts);
            const_tuple[k] = element;
        }

        nop_out(const_instrs, seq_size);
        instr_make_load_const(instr, Abstract.PyTuple_New(const_tuple), consts, const_cache,
                consts_index);
    }

    /* Replace:
        BUILD_LIST/BUILD_SET 0
        LOAD_CONST c1
        LIST_APPEND/SET_ADD 1
        LOAD_CONST c2
        LIST_APPEND/SET_ADD 1
        ...
        LOAD_CONST cN
        LIST_APPEND/SET_ADD 1
        [CALL_INTRINSIC_1 INTRINSIC_LIST_TO_TUPLE]   <-- optional
       with:
        LOAD_CONST (c1, c2, ... cN)
       The instruction at `i` is either the LIST_TO_TUPLE intrinsic (so the
       immediately preceding non-NOP instruction is expected to be a
       LIST_APPEND, and only the BUILD_LIST/LIST_APPEND form is considered),
       or the trailing LIST_APPEND or SET_ADD itself, in which case the
       matching BUILD_LIST/BUILD_SET start is selected from its opcode, and
       for sets the result is wrapped in a frozenset.
    */
    private static void fold_constant_seq_into_load_const(basicblock bb, int i,
            List<Object> consts, Map<Object, Object> const_cache, ConstsIndex consts_index) {
        assert i >= 0;
        assert i < bb.b_iused;

        cfg_instr target = bb.b_instr[i];
        assert target.i_opcode == LIST_APPEND || target.i_opcode == SET_ADD ||
               (target.i_opcode == CALL_INTRINSIC_1 &&
                target.i_oparg == INTRINSIC_LIST_TO_TUPLE);
        boolean expected_append = target.i_opcode == CALL_INTRINSIC_1;
        int append_op = expected_append ? LIST_APPEND : target.i_opcode;
        assert append_op == LIST_APPEND || append_op == SET_ADD;
        int build_op = append_op == LIST_APPEND ? BUILD_LIST : BUILD_SET;
        int consts_found = 0;
        /* Walking backward from `i`, we expect LIST_APPEND/SET_ADD and
           LOAD_CONST to alternate. If `i` is the trailing LIST_TO_TUPLE
           intrinsic, the next instruction back is an APPEND. If `i` is the
           trailing APPEND itself, the next instruction back is a LOAD_CONST. */
        boolean expect_append = expected_append;

        for (int pos = i - 1; pos >= 0; pos--) {
            cfg_instr instr = bb.b_instr[pos];
            int opcode = instr.i_opcode;
            int oparg = instr.i_oparg;

            if (opcode == NOP) {
                continue;
            }

            if (opcode == build_op && oparg == 0) {
                if (!expect_append) {
                    /* Not a sequence start. */
                    return;
                }

                /* Sequence start, we are done. */
                Object[] items = new Object[consts_found];

                int newpos_start = expected_append ? i - 1 : i;
                for (int newpos = newpos_start; newpos >= pos; newpos--) {
                    instr = bb.b_instr[newpos];
                    if (instr.i_opcode == NOP) {
                        continue;
                    }
                    if (loads_const(instr.i_opcode)) {
                        Object constant = get_const_value(instr.i_opcode, instr.i_oparg, consts);
                        assert consts_found > 0;
                        items[--consts_found] = constant;
                    }
                    nop_out(new cfg_instr[] {instr}, 1);
                }
                assert consts_found == 0;

                Object newconst = Abstract.PyTuple_New(items);
                if (build_op == BUILD_SET) {
                    newconst = Abstract.PyFrozenSet_New((PyTuple) newconst);
                }
                instr_make_load_const(target, newconst, consts, const_cache, consts_index);
                return;
            }

            if (expect_append) {
                if (opcode != append_op || oparg != 1) {
                    return;
                }
            }
            else {
                if (!loads_const(opcode)) {
                    return;
                }
                consts_found++;
            }

            expect_append = !expect_append;
        }

        /* Did not find sequence start. */
    }

    private static final int MIN_CONST_SEQUENCE_SIZE = 3;

    /*
     * Optimize lists and sets for:
     *     1. "for" loop, comprehension or "in"/"not in" tests:
     *            Change literal list or set of constants into constant
     *            tuple or frozenset respectively. Change list of
     *            non-constants into tuple.
     *     2. Constant literal lists/set with length >= MIN_CONST_SEQUENCE_SIZE:
     *            Replace LOAD_CONST c1, LOAD_CONST c2 ... LOAD_CONST cN, BUILD_LIST N
     *            with BUILD_LIST 0, LOAD_CONST (c1, c2, ... cN), LIST_EXTEND 1,
     *            or BUILD_SET & SET_UPDATE respectively.
     */
    private static void optimize_lists_and_sets(basicblock bb, int i, int nextop,
            List<Object> consts, Map<Object, Object> const_cache, ConstsIndex consts_index) {
        cfg_instr instr = bb.b_instr[i];
        assert instr.i_opcode == BUILD_LIST || instr.i_opcode == BUILD_SET;

        boolean contains_or_iter = nextop == GET_ITER || nextop == CONTAINS_OP;
        int seq_size = instr.i_oparg;
        if (seq_size > Compile._PY_STACK_USE_GUIDELINE ||
            (seq_size < MIN_CONST_SEQUENCE_SIZE && !contains_or_iter))
        {
            return;
        }

        cfg_instr[] const_instrs = new cfg_instr[Compile._PY_STACK_USE_GUIDELINE];
        if (!get_const_loading_instrs(bb, i-1, const_instrs, seq_size)) {  /* not a const sequence */
            if (contains_or_iter && instr.i_opcode == BUILD_LIST) {
                /* iterate over a tuple instead of list */
                INSTR_SET_OP1(instr, BUILD_TUPLE, instr.i_oparg);
            }
            return;
        }

        Object[] items = new Object[seq_size];

        for (int k = 0; k < seq_size; k++) {
            cfg_instr inst = const_instrs[k];
            assert loads_const(inst.i_opcode);
            Object element = get_const_value(inst.i_opcode, inst.i_oparg, consts);
            items[k] = element;
        }

        Object const_result = Abstract.PyTuple_New(items);
        if (instr.i_opcode == BUILD_SET) {
            const_result = Abstract.PyFrozenSet_New((PyTuple) const_result);
        }

        int index = add_const(const_result, consts, const_cache, consts_index);
        nop_out(const_instrs, seq_size);

        if (contains_or_iter) {
            INSTR_SET_OP1(instr, LOAD_CONST, index);
        }
        else {
            assert i >= 2;
            assert instr.i_opcode == BUILD_LIST || instr.i_opcode == BUILD_SET;

            INSTR_SET_LOC(bb.b_instr[i-2], instr.i_loc);

            INSTR_SET_OP1(bb.b_instr[i-2], instr.i_opcode, 0);
            INSTR_SET_OP1(bb.b_instr[i-1], LOAD_CONST, index);
            INSTR_SET_OP1(bb.b_instr[i], instr.i_opcode == BUILD_LIST ? LIST_EXTEND : SET_UPDATE, 1);
        }
    }

    /* Check whether the total number of items in the (possibly nested) collection obj exceeds
     * limit. Return a negative number if it does, and a non-negative number otherwise.
     * Used to avoid creating constants which are slow to hash.
     */
    private static long const_folding_check_complexity(Object obj, long limit) {
        if (obj instanceof PyTuple) {
            Object[] items = ((PyTuple) obj).items;
            limit -= items.length;
            for (int i = 0; limit >= 0 && i < items.length; i++) {
                limit = const_folding_check_complexity(items[i], limit);
                if (limit < 0) {
                    return limit;
                }
            }
        }
        return limit;
    }

    private static final int MAX_INT_SIZE = 128;  /* bits */
    private static final int MAX_COLLECTION_SIZE = 256;  /* items */
    private static final int MAX_STR_SIZE = 4096;  /* characters */
    private static final int MAX_TOTAL_ITEMS = 1024;  /* including nested collections */

    private static boolean PyLong_Check(Object o) {
        return Abstract.PyLong_Check(o);
    }

    private static Object const_folding_safe_multiply(Object v, Object w) {
        if (PyLong_Check(v) && PyLong_Check(w) &&
            !Abstract._PyLong_IsZero(v) && !Abstract._PyLong_IsZero(w)
        ) {
            long vbits = Abstract._PyLong_NumBits(v);
            long wbits = Abstract._PyLong_NumBits(w);
            assert vbits >= 0;
            assert wbits >= 0;
            if (vbits + wbits > MAX_INT_SIZE) {
                return null;
            }
        }
        else if (PyLong_Check(v) && w instanceof PyTuple) {
            int size = ((PyTuple) w).size();
            if (size != 0) {
                Long n = Abstract.PyLong_AsLong(v);
                if (n == null || n < 0 || n > MAX_COLLECTION_SIZE / size) {
                    return null;
                }
                if (n != 0 && const_folding_check_complexity(w, MAX_TOTAL_ITEMS / n) < 0) {
                    return null;
                }
            }
        }
        else if (PyLong_Check(v) && (w instanceof String || w instanceof Bytes)) {
            int size = w instanceof String ? Abstract.PyUnicode_GET_LENGTH((String) w) :
                                             ((Bytes) w).length();
            if (size != 0) {
                Long n = Abstract.PyLong_AsLong(v);
                if (n == null || n < 0 || n > MAX_STR_SIZE / size) {
                    return null;
                }
            }
        }
        else if (PyLong_Check(w) &&
                 (v instanceof PyTuple || v instanceof String || v instanceof Bytes))
        {
            return const_folding_safe_multiply(w, v);
        }

        return Abstract.PyNumber_Multiply(v, w);
    }

    private static Object const_folding_safe_power(Object v, Object w) {
        if (PyLong_Check(v) && PyLong_Check(w) &&
            !Abstract._PyLong_IsZero(v) && Abstract._PyLong_IsPositive(w)
        ) {
            long vbits = Abstract._PyLong_NumBits(v);
            Long wbits = Abstract.PyLong_AsSize_t(w);
            assert vbits >= 0;
            if (wbits == null) {
                return null;
            }
            // C: (uint64_t)vbits > MAX_INT_SIZE / wbits, wbits a size_t
            if (vbits > MAX_INT_SIZE / wbits) {
                return null;
            }
        }

        return Abstract.PyNumber_Power(v, w);
    }

    private static Object const_folding_safe_lshift(Object v, Object w) {
        if (PyLong_Check(v) && PyLong_Check(w) &&
            !Abstract._PyLong_IsZero(v) && !Abstract._PyLong_IsZero(w)
        ) {
            long vbits = Abstract._PyLong_NumBits(v);
            Long wbits = Abstract.PyLong_AsSize_t(w);
            assert vbits >= 0;
            if (wbits == null) {
                return null;
            }
            if (wbits > MAX_INT_SIZE || vbits > MAX_INT_SIZE - wbits) {
                return null;
            }
        }

        return Abstract.PyNumber_Lshift(v, w);
    }

    private static Object const_folding_safe_mod(Object v, Object w) {
        if (v instanceof String || v instanceof Bytes) {
            return null;
        }

        return Abstract.PyNumber_Remainder(v, w);
    }

    /** C: eval_const_binop; null where C's operation raises. */
    private static Object eval_const_binop(Object left, int op, Object right) {
        assert left != null && right != null;
        assert op >= 0 && op <= NB_SUBSCR;  // C: NB_OPARG_LAST

        Object result = null;
        switch (op) {
            case NB_ADD:
                result = Abstract.PyNumber_Add(left, right);
                break;
            case NB_SUBTRACT:
                result = Abstract.PyNumber_Subtract(left, right);
                break;
            case NB_MULTIPLY:
                result = const_folding_safe_multiply(left, right);
                break;
            case NB_TRUE_DIVIDE:
                result = Abstract.PyNumber_TrueDivide(left, right);
                break;
            case NB_FLOOR_DIVIDE:
                result = Abstract.PyNumber_FloorDivide(left, right);
                break;
            case NB_REMAINDER:
                result = const_folding_safe_mod(left, right);
                break;
            case NB_POWER:
                result = const_folding_safe_power(left, right);
                break;
            case NB_LSHIFT:
                result = const_folding_safe_lshift(left, right);
                break;
            case NB_RSHIFT:
                result = Abstract.PyNumber_Rshift(left, right);
                break;
            case NB_OR:
                result = Abstract.PyNumber_Or(left, right);
                break;
            case NB_XOR:
                result = Abstract.PyNumber_Xor(left, right);
                break;
            case NB_AND:
                result = Abstract.PyNumber_And(left, right);
                break;
            case NB_SUBSCR:
                result = Abstract.PyObject_GetItem(left, right);
                break;
            case NB_MATRIX_MULTIPLY:
                // No builtin constants implement matrix multiplication
                break;
            default:
                throw new IllegalStateException("unreachable");
        }
        return result;
    }

    private static void fold_const_binop(basicblock bb, int i, List<Object> consts,
            Map<Object, Object> const_cache, ConstsIndex consts_index) {
        final int BINOP_OPERAND_COUNT = 2;

        cfg_instr binop = bb.b_instr[i];
        assert binop.i_opcode == BINARY_OP;

        cfg_instr[] operands_instrs = new cfg_instr[BINOP_OPERAND_COUNT];
        if (!get_const_loading_instrs(bb, i-1, operands_instrs, BINOP_OPERAND_COUNT)) {
            /* not a const sequence */
            return;
        }

        cfg_instr lhs_instr = operands_instrs[0];
        assert loads_const(lhs_instr.i_opcode);
        Object lhs = get_const_value(lhs_instr.i_opcode, lhs_instr.i_oparg, consts);

        cfg_instr rhs_instr = operands_instrs[1];
        assert loads_const(rhs_instr.i_opcode);
        Object rhs = get_const_value(rhs_instr.i_opcode, rhs_instr.i_oparg, consts);

        Object newconst = eval_const_binop(lhs, binop.i_oparg, rhs);
        if (newconst == null) {
            return;
        }

        nop_out(operands_instrs, BINOP_OPERAND_COUNT);
        instr_make_load_const(binop, newconst, consts, const_cache, consts_index);
    }

    /** C: eval_const_unaryop; null where C's operation raises. */
    private static Object eval_const_unaryop(Object operand, int opcode, int oparg) {
        assert operand != null;
        assert
            opcode == UNARY_NEGATIVE ||
            opcode == UNARY_INVERT ||
            opcode == UNARY_NOT ||
            (opcode == CALL_INTRINSIC_1 && oparg == INTRINSIC_UNARY_POSITIVE);
        Object result;
        switch (opcode) {
            case UNARY_NEGATIVE:
                result = Abstract.PyNumber_Negative(operand);
                break;
            case UNARY_INVERT:
                // XXX: This should be removed once the ~bool depreciation expires.
                if (operand == Singleton.True || operand == Singleton.False) {
                    return null;
                }
                result = Abstract.PyNumber_Invert(operand);
                break;
            case UNARY_NOT: {
                int r = Abstract.PyObject_IsTrue(operand);
                if (r < 0) {
                    return null;
                }
                result = r == 0 ? Singleton.True : Singleton.False;
                break;
            }
            case CALL_INTRINSIC_1:
                if (oparg != INTRINSIC_UNARY_POSITIVE) {
                    throw new IllegalStateException("unreachable");
                }
                result = Abstract.PyNumber_Positive(operand);
                break;
            default:
                throw new IllegalStateException("unreachable");
        }
        return result;
    }

    private static void fold_const_unaryop(basicblock bb, int i, List<Object> consts,
            Map<Object, Object> const_cache, ConstsIndex consts_index) {
        final int UNARYOP_OPERAND_COUNT = 1;
        cfg_instr unaryop = bb.b_instr[i];

        cfg_instr[] operand_instr = new cfg_instr[1];
        if (!get_const_loading_instrs(bb, i-1, operand_instr, UNARYOP_OPERAND_COUNT)) {
            /* not a const */
            return;
        }

        assert loads_const(operand_instr[0].i_opcode);
        Object operand = get_const_value(
            operand_instr[0].i_opcode,
            operand_instr[0].i_oparg,
            consts
        );

        Object newconst = eval_const_unaryop(operand, unaryop.i_opcode, unaryop.i_oparg);
        if (newconst == null) {
            return;
        }

        if (unaryop.i_opcode == UNARY_NOT) {
            assert newconst == Singleton.True || newconst == Singleton.False;
        }
        nop_out(operand_instr, UNARYOP_OPERAND_COUNT);
        instr_make_load_const(unaryop, newconst, consts, const_cache, consts_index);
    }

    private static final int VISITED = -1;

    // Replace an arbitrary run of SWAPs and NOPs with an optimal one that has the
    // same effect.
    private static void swaptimize(basicblock block, int[] ix) {
        // NOTE: "./python -m test test_patma" serves as a good, quick stress test
        // for this function. Make sure to blow away cached *.pyc files first!
        assert ix[0] < block.b_iused;
        // C: instructions = &block->b_instr[*ix]; instructions[k] is b_instr[base + k]
        int base = ix[0];
        cfg_instr[] instructions = block.b_instr;
        // Find the length of the current sequence of SWAPs and NOPs, and record the
        // maximum depth of the stack manipulations:
        assert instructions[base].i_opcode == SWAP;
        int depth = instructions[base].i_oparg;
        int len = 0;
        boolean more = false;
        int limit = block.b_iused - ix[0];
        while (++len < limit) {
            int opcode = instructions[base + len].i_opcode;
            if (opcode == SWAP) {
                depth = Math.max(depth, instructions[base + len].i_oparg);
                more = true;
            }
            else if (opcode != NOP) {
                break;
            }
        }
        // It's already optimal if there's only one SWAP:
        if (!more) {
            return;
        }
        // Create an array with elements {0, 1, 2, ..., depth - 1}:
        int[] stack = new int[depth];
        for (int i = 0; i < depth; i++) {
            stack[i] = i;
        }
        // Simulate the combined effect of these instructions by "running" them on
        // our "stack":
        for (int i = 0; i < len; i++) {
            if (instructions[base + i].i_opcode == SWAP) {
                int oparg = instructions[base + i].i_oparg;
                int top = stack[0];
                // SWAPs are 1-indexed:
                stack[0] = stack[oparg - 1];
                stack[oparg - 1] = top;
            }
        }
        // Now we can begin! Our approach here is based on a solution to a closely
        // related problem (https://cs.stackexchange.com/a/13938). It's easiest to
        // think of this algorithm as determining the steps needed to efficiently
        // "un-shuffle" our stack. By performing the moves in *reverse* order,
        // though, we can efficiently *shuffle* it! For this reason, we will be
        // replacing instructions starting from the *end* of the run. Since the
        // solution is optimal, we don't need to worry about running out of space:
        int current = len - 1;
        for (int i = 0; i < depth; i++) {
            // Skip items that have already been visited, or just happen to be in
            // the correct location:
            if (stack[i] == VISITED || stack[i] == i) {
                continue;
            }
            // Okay, we've found an item that hasn't been visited. It forms a cycle
            // with other items; traversing the cycle and swapping each item with
            // the next will put them all in the correct place. The weird
            // loop-and-a-half is necessary to insert 0 into every cycle, since we
            // can only swap from that position:
            int j = i;
            while (true) {
                // Skip the actual swap if our item is zero, since swapping the top
                // item with itself is pointless:
                if (j != 0) {
                    assert 0 <= current;
                    // SWAPs are 1-indexed:
                    instructions[base + current].i_opcode = SWAP;
                    instructions[base + current--].i_oparg = j + 1;
                }
                if (stack[j] == VISITED) {
                    // Completed the cycle:
                    assert j == i;
                    break;
                }
                int next_j = stack[j];
                stack[j] = VISITED;
                j = next_j;
            }
        }
        // NOP out any unused instructions:
        while (0 <= current) {
            INSTR_SET_OP0(instructions[base + current--], NOP);
        }
        ix[0] += len - 1;
    }

    // This list is pretty small, since it's only okay to reorder opcodes that:
    // - can't affect control flow (like jumping or raising exceptions)
    // - can't invoke arbitrary code (besides finalizers)
    // - only touch the TOS (and pop it when finished)
    private static boolean SWAPPABLE(int opcode) {
        return opcode == STORE_FAST ||
               opcode == STORE_FAST_MAYBE_NULL ||
               opcode == POP_TOP;
    }

    private static int STORES_TO(cfg_instr instr) {
        return (instr.i_opcode == STORE_FAST ||
                instr.i_opcode == STORE_FAST_MAYBE_NULL)
               ? instr.i_oparg : -1;
    }

    private static int next_swappable_instruction(basicblock block, int i, int lineno) {
        while (++i < block.b_iused) {
            cfg_instr instruction = block.b_instr[i];
            if (0 <= lineno && instruction.i_loc.lineno != lineno) {
                // Optimizing across this instruction could cause user-visible
                // changes in the names bound between line tracing events!
                return -1;
            }
            if (instruction.i_opcode == NOP) {
                continue;
            }
            if (SWAPPABLE(instruction.i_opcode)) {
                return i;
            }
            return -1;
        }
        return -1;
    }

    // Attempt to apply SWAPs statically by swapping *instructions* rather than
    // stack items. For example, we can replace SWAP(2), POP_TOP, STORE_FAST(42)
    // with the more efficient NOP, STORE_FAST(42), POP_TOP.
    private static void apply_static_swaps(basicblock block, int i) {
        // SWAPs are to our left, and potential swaperands are to our right:
        for (; 0 <= i; i--) {
            assert i < block.b_iused;
            cfg_instr swap = block.b_instr[i];
            if (swap.i_opcode != SWAP) {
                if (swap.i_opcode == NOP || SWAPPABLE(swap.i_opcode)) {
                    // Nope, but we know how to handle these. Keep looking:
                    continue;
                }
                // We can't reason about what this instruction does. Bail:
                return;
            }
            int j = next_swappable_instruction(block, i, -1);
            if (j < 0) {
                return;
            }
            int k = j;
            int lineno = block.b_instr[j].i_loc.lineno;
            for (int count = swap.i_oparg - 1; 0 < count; count--) {
                k = next_swappable_instruction(block, k, lineno);
                if (k < 0) {
                    return;
                }
            }
            // The reordering is not safe if the two instructions to be swapped
            // store to the same location, or if any intervening instruction stores
            // to the same location as either of them.
            int store_j = STORES_TO(block.b_instr[j]);
            int store_k = STORES_TO(block.b_instr[k]);
            if (store_j >= 0 || store_k >= 0) {
                if (store_j == store_k) {
                    return;
                }
                for (int idx = j + 1; idx < k; idx++) {
                    int store_idx = STORES_TO(block.b_instr[idx]);
                    if (store_idx >= 0 && (store_idx == store_j || store_idx == store_k)) {
                        return;
                    }
                }
            }

            // Success!
            INSTR_SET_OP0(swap, NOP);
            cfg_instr temp = new cfg_instr();
            temp.set(block.b_instr[j]);
            block.b_instr[j].set(block.b_instr[k]);
            block.b_instr[k].set(temp);
        }
    }

    private static void basicblock_optimize_load_const(Map<Object, Object> const_cache,
            basicblock bb, List<Object> consts, ConstsIndex consts_index) {
        int opcode = 0;
        int oparg = 0;
        for (int i = 0; i < bb.b_iused; i++) {
            cfg_instr inst = bb.b_instr[i];
            if (inst.i_opcode == LOAD_CONST) {
                Object constant = get_const_value(inst.i_opcode, inst.i_oparg, consts);
                maybe_instr_make_load_smallint(inst, constant, consts, const_cache);
            }
            boolean is_copy_of_load_const = (opcode == LOAD_CONST &&
                                             inst.i_opcode == COPY &&
                                             inst.i_oparg == 1);
            if (!is_copy_of_load_const) {
                opcode = inst.i_opcode;
                oparg = inst.i_oparg;
            }
            assert !IS_ASSEMBLER_OPCODE(opcode);
            if (!loads_const(opcode)) {
                continue;
            }
            int nextop = i+1 < bb.b_iused ? bb.b_instr[i+1].i_opcode : 0;
            switch (nextop) {
                case POP_JUMP_IF_FALSE:
                case POP_JUMP_IF_TRUE:
                case JUMP_IF_FALSE:
                case JUMP_IF_TRUE:
                {
                    /* Remove LOAD_CONST const; conditional jump */
                    Object cnt = get_const_value(opcode, oparg, consts);
                    int is_true = Abstract.PyObject_IsTrue(cnt);
                    if (is_true == -1) {
                        throw new IllegalStateException("truth of a constant failed");
                    }
                    if (PyCompile_OpcodeStackEffect(nextop, 0) == -1) {
                        /* POP_JUMP_IF_FALSE or POP_JUMP_IF_TRUE */
                        INSTR_SET_OP0(inst, NOP);
                    }
                    int jump_if_true = (nextop == POP_JUMP_IF_TRUE || nextop == JUMP_IF_TRUE) ? 1 : 0;
                    if (is_true == jump_if_true) {
                        bb.b_instr[i+1].i_opcode = JUMP;
                    }
                    else {
                        INSTR_SET_OP0(bb.b_instr[i + 1], NOP);
                    }
                    break;
                }
                case IS_OP:
                {
                    // Fold to POP_JUMP_IF_NONE:
                    // - LOAD_CONST(None) IS_OP(0) POP_JUMP_IF_TRUE
                    // - LOAD_CONST(None) IS_OP(1) POP_JUMP_IF_FALSE
                    // - LOAD_CONST(None) IS_OP(0) TO_BOOL POP_JUMP_IF_TRUE
                    // - LOAD_CONST(None) IS_OP(1) TO_BOOL POP_JUMP_IF_FALSE
                    // Fold to POP_JUMP_IF_NOT_NONE:
                    // - LOAD_CONST(None) IS_OP(0) POP_JUMP_IF_FALSE
                    // - LOAD_CONST(None) IS_OP(1) POP_JUMP_IF_TRUE
                    // - LOAD_CONST(None) IS_OP(0) TO_BOOL POP_JUMP_IF_FALSE
                    // - LOAD_CONST(None) IS_OP(1) TO_BOOL POP_JUMP_IF_TRUE
                    Object cnt = get_const_value(opcode, oparg, consts);
                    if (cnt != Singleton.None) {
                        break;
                    }
                    if (bb.b_iused <= i + 2) {
                        break;
                    }
                    cfg_instr is_instr = bb.b_instr[i + 1];
                    cfg_instr jump_instr = bb.b_instr[i + 2];
                    // Get rid of TO_BOOL regardless:
                    if (jump_instr.i_opcode == TO_BOOL) {
                        INSTR_SET_OP0(jump_instr, NOP);
                        if (bb.b_iused <= i + 3) {
                            break;
                        }
                        jump_instr = bb.b_instr[i + 3];
                    }
                    boolean invert = is_instr.i_oparg != 0;
                    if (jump_instr.i_opcode == POP_JUMP_IF_FALSE) {
                        invert = !invert;
                    }
                    else if (jump_instr.i_opcode != POP_JUMP_IF_TRUE) {
                        break;
                    }
                    INSTR_SET_OP0(inst, NOP);
                    INSTR_SET_OP0(is_instr, NOP);
                    jump_instr.i_opcode = invert ? POP_JUMP_IF_NOT_NONE
                                                 : POP_JUMP_IF_NONE;
                    break;
                }
                case TO_BOOL:
                {
                    Object cnt = get_const_value(opcode, oparg, consts);
                    int is_true = Abstract.PyObject_IsTrue(cnt);
                    if (is_true == -1) {
                        throw new IllegalStateException("truth of a constant failed");
                    }
                    cnt = is_true != 0 ? Singleton.True : Singleton.False;
                    int index = add_const(cnt, consts, const_cache, consts_index);
                    INSTR_SET_OP0(inst, NOP);
                    INSTR_SET_OP1(bb.b_instr[i + 1], LOAD_CONST, index);
                    break;
                }
            }
            if (inst.i_opcode == LOAD_CONST) {
                Object constant = get_const_value(inst.i_opcode, inst.i_oparg, consts);
                maybe_instr_make_load_common_const(inst, constant);
            }
        }
    }

    private static void optimize_load_const(Map<Object, Object> const_cache, cfg_builder g,
            List<Object> consts, ConstsIndex consts_index) {
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            basicblock_optimize_load_const(const_cache, b, consts, consts_index);
        }
    }

    private static void optimize_basic_block(Map<Object, Object> const_cache, basicblock bb,
            List<Object> consts, ConstsIndex consts_index) {
        cfg_instr nop = new cfg_instr();
        INSTR_SET_OP0(nop, NOP);
        for (int i = 0; i < bb.b_iused; i++) {
            cfg_instr inst = bb.b_instr[i];
            cfg_instr target;
            int opcode = inst.i_opcode;
            int oparg = inst.i_oparg;
            if (HAS_TARGET(opcode)) {
                assert inst.i_target.b_iused > 0;
                target = inst.i_target.b_instr[0];
                assert !IS_ASSEMBLER_OPCODE(target.i_opcode);
            }
            else {
                target = nop;
            }
            int nextop = i+1 < bb.b_iused ? bb.b_instr[i+1].i_opcode : 0;
            assert !IS_ASSEMBLER_OPCODE(opcode);
            switch (opcode) {
                /* Try to fold tuples of constants.
                   Skip over BUILD_TUPLE(1) UNPACK_SEQUENCE(1).
                   Replace BUILD_TUPLE(2) UNPACK_SEQUENCE(2) with SWAP(2).
                   Replace BUILD_TUPLE(3) UNPACK_SEQUENCE(3) with SWAP(3). */
                case BUILD_TUPLE:
                    if (nextop == UNPACK_SEQUENCE && oparg == bb.b_instr[i+1].i_oparg) {
                        switch (oparg) {
                            case 1:
                                INSTR_SET_OP0(inst, NOP);
                                INSTR_SET_OP0(bb.b_instr[i + 1], NOP);
                                continue;
                            case 2:
                            case 3:
                                INSTR_SET_OP0(inst, NOP);
                                bb.b_instr[i+1].i_opcode = SWAP;
                                continue;
                        }
                    }
                    fold_tuple_of_constants(bb, i, consts, const_cache, consts_index);
                    break;
                case BUILD_LIST:
                case BUILD_SET:
                    optimize_lists_and_sets(bb, i, nextop, consts, const_cache, consts_index);
                    break;
                case POP_JUMP_IF_NOT_NONE:
                case POP_JUMP_IF_NONE:
                    switch (target.i_opcode) {
                        case JUMP:
                            i -= jump_thread(bb, inst, target, inst.i_opcode);
                    }
                    break;
                case POP_JUMP_IF_FALSE:
                    switch (target.i_opcode) {
                        case JUMP:
                            i -= jump_thread(bb, inst, target, POP_JUMP_IF_FALSE);
                    }
                    break;
                case POP_JUMP_IF_TRUE:
                    switch (target.i_opcode) {
                        case JUMP:
                            i -= jump_thread(bb, inst, target, POP_JUMP_IF_TRUE);
                    }
                    break;
                case JUMP_IF_FALSE:
                    switch (target.i_opcode) {
                        case JUMP:
                        case JUMP_IF_FALSE:
                            i -= jump_thread(bb, inst, target, JUMP_IF_FALSE);
                            continue;
                        case JUMP_IF_TRUE:
                            // No need to check for loops here, a block's b_next
                            // cannot point to itself.
                            assert inst.i_target != inst.i_target.b_next;
                            inst.i_target = inst.i_target.b_next;
                            i--;
                            continue;
                    }
                    break;
                case JUMP_IF_TRUE:
                    switch (target.i_opcode) {
                        case JUMP:
                        case JUMP_IF_TRUE:
                            i -= jump_thread(bb, inst, target, JUMP_IF_TRUE);
                            continue;
                        case JUMP_IF_FALSE:
                            // No need to check for loops here, a block's b_next
                            // cannot point to itself.
                            assert inst.i_target != inst.i_target.b_next;
                            inst.i_target = inst.i_target.b_next;
                            i--;
                            continue;
                    }
                    break;
                case JUMP:
                case JUMP_NO_INTERRUPT:
                    switch (target.i_opcode) {
                        case JUMP:
                            i -= jump_thread(bb, inst, target, JUMP);
                            continue;
                        case JUMP_NO_INTERRUPT:
                            i -= jump_thread(bb, inst, target, opcode);
                            continue;
                    }
                    break;
                case FOR_ITER:
                    if (target.i_opcode == JUMP) {
                        /* This will not work now because the jump (at target) could
                         * be forward or backward and FOR_ITER only jumps forward. We
                         * can re-enable this if ever we implement a backward version
                         * of FOR_ITER.
                         */
                        /*
                        i -= jump_thread(bb, inst, target, FOR_ITER);
                        */
                    }
                    break;
                case STORE_FAST:
                    if (opcode == nextop &&
                        oparg == bb.b_instr[i+1].i_oparg &&
                        bb.b_instr[i].i_loc.lineno == bb.b_instr[i+1].i_loc.lineno) {
                        bb.b_instr[i].i_opcode = POP_TOP;
                        bb.b_instr[i].i_oparg = 0;
                    }
                    break;
                case SWAP:
                    if (oparg == 1) {
                        INSTR_SET_OP0(inst, NOP);
                    }
                    break;
                case LOAD_GLOBAL:
                    if (nextop == PUSH_NULL && (oparg & 1) == 0) {
                        INSTR_SET_OP1(inst, LOAD_GLOBAL, oparg | 1);
                        INSTR_SET_OP0(bb.b_instr[i + 1], NOP);
                    }
                    break;
                case COMPARE_OP:
                    if (nextop == TO_BOOL) {
                        INSTR_SET_OP0(inst, NOP);
                        INSTR_SET_OP1(bb.b_instr[i + 1], COMPARE_OP, oparg | 16);
                        continue;
                    }
                    break;
                case CONTAINS_OP:
                case IS_OP:
                    if (nextop == TO_BOOL) {
                        INSTR_SET_OP0(inst, NOP);
                        INSTR_SET_OP1(bb.b_instr[i + 1], opcode, oparg);
                        continue;
                    }
                    if (nextop == UNARY_NOT) {
                        INSTR_SET_OP0(inst, NOP);
                        int inverted = oparg ^ 1;
                        assert inverted == 0 || inverted == 1;
                        INSTR_SET_OP1(bb.b_instr[i + 1], opcode, inverted);
                        continue;
                    }
                    break;
                case TO_BOOL:
                    if (nextop == TO_BOOL) {
                        INSTR_SET_OP0(inst, NOP);
                        continue;
                    }
                    break;
                case UNARY_NOT:
                    if (nextop == TO_BOOL) {
                        INSTR_SET_OP0(inst, NOP);
                        INSTR_SET_OP0(bb.b_instr[i + 1], UNARY_NOT);
                        continue;
                    }
                    if (nextop == UNARY_NOT) {
                        INSTR_SET_OP0(inst, NOP);
                        INSTR_SET_OP0(bb.b_instr[i + 1], NOP);
                        continue;
                    }
                    // fall through
                case UNARY_INVERT:
                case UNARY_NEGATIVE:
                    fold_const_unaryop(bb, i, consts, const_cache, consts_index);
                    break;
                case CALL_INTRINSIC_1:
                    if (oparg == INTRINSIC_LIST_TO_TUPLE) {
                        fold_constant_seq_into_load_const(bb, i, consts, const_cache, consts_index);
                        if (inst.i_opcode == CALL_INTRINSIC_1 && nextop == GET_ITER) {
                            INSTR_SET_OP0(inst, NOP);
                        }
                    }
                    else if (oparg == INTRINSIC_UNARY_POSITIVE) {
                        fold_const_unaryop(bb, i, consts, const_cache, consts_index);
                    }
                    break;
                case LIST_APPEND:
                case SET_ADD:
                    if (oparg == 1 && (nextop == GET_ITER || nextop == CONTAINS_OP)) {
                        fold_constant_seq_into_load_const(
                            bb, i, consts, const_cache, consts_index);
                    }
                    break;
                case BINARY_OP:
                    fold_const_binop(bb, i, consts, const_cache, consts_index);
                    break;
            }
        }

        for (int i = 0; i < bb.b_iused; i++) {
            cfg_instr inst = bb.b_instr[i];
            if (inst.i_opcode == SWAP) {
                int[] ix = {i};
                swaptimize(bb, ix);
                i = ix[0];
                apply_static_swaps(bb, i);
            }
        }
    }

    private static void remove_redundant_nops_and_jumps(cfg_builder g) {
        int removed_nops, removed_jumps;
        do {
            /* Convergence is guaranteed because the number of
             * redundant jumps and nops only decreases.
             */
            removed_nops = remove_redundant_nops(g);
            removed_jumps = remove_redundant_jumps(g);
        } while (removed_nops + removed_jumps > 0);
    }

    /* Perform optimizations on a control flow graph.
       The consts object should still be in list form to allow new constants
       to be appended.

       Code trasnformations that reduce code size initially fill the gaps with
       NOPs.  Later those NOPs are removed.
    */
    private static void optimize_cfg(cfg_builder g, List<Object> consts,
            Map<Object, Object> const_cache, ConstsIndex consts_index, int firstlineno) {
        check_cfg(g);
        inline_small_or_no_lineno_blocks(g.g_entryblock);
        remove_unreachable(g.g_entryblock);
        resolve_line_numbers(g, firstlineno);
        optimize_load_const(const_cache, g, consts, consts_index);
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            optimize_basic_block(const_cache, b, consts, consts_index);
        }
        remove_redundant_nops_and_pairs(g.g_entryblock);
        remove_unreachable(g.g_entryblock);
        remove_redundant_nops_and_jumps(g);
    }

    private static void make_super_instruction(cfg_instr inst1, cfg_instr inst2, int super_op) {
        int line1 = inst1.i_loc.lineno;
        int line2 = inst2.i_loc.lineno;
        /* Skip if instructions are on different lines */
        if (line1 >= 0 && line2 >= 0 && line1 != line2) {
            return;
        }
        if (inst1.i_oparg >= 16 || inst2.i_oparg >= 16) {
            return;
        }
        INSTR_SET_OP1(inst1, super_op, (inst1.i_oparg << 4) | inst2.i_oparg);
        INSTR_SET_OP0(inst2, NOP);
    }

    private static void insert_superinstructions(cfg_builder g) {
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {

            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr inst = b.b_instr[i];
                int nextop = i+1 < b.b_iused ? b.b_instr[i+1].i_opcode : 0;
                switch (inst.i_opcode) {
                    case LOAD_FAST:
                        if (nextop == LOAD_FAST) {
                            make_super_instruction(inst, b.b_instr[i + 1], LOAD_FAST_LOAD_FAST);
                        }
                        break;
                    case STORE_FAST:
                        switch (nextop) {
                            case LOAD_FAST:
                                make_super_instruction(inst, b.b_instr[i + 1], STORE_FAST_LOAD_FAST);
                                break;
                            case STORE_FAST:
                                make_super_instruction(inst, b.b_instr[i + 1], STORE_FAST_STORE_FAST);
                                break;
                        }
                        break;
                }
            }
        }
        remove_redundant_nops(g);
    }

    private static final int NOT_LOCAL = -1;
    private static final int DUMMY_INSTR = -1;

    /** C: ref. */
    private static final class ref {
        // Index of instruction that produced the reference or DUMMY_INSTR.
        final int instr;

        // The local to which the reference refers or NOT_LOCAL.
        final int local;

        ref(int instr, int local) {
            this.instr = instr;
            this.local = local;
        }
    }

    /** C: ref_stack. */
    private static final class ref_stack {
        ref[] refs = new ref[32];
        int size;
    }

    private static void ref_stack_push(ref_stack stack, ref r) {
        if (stack.size == stack.refs.length) {
            stack.refs = java.util.Arrays.copyOf(stack.refs, stack.refs.length * 2);
        }
        stack.refs[stack.size] = r;
        stack.size++;
    }

    private static ref ref_stack_pop(ref_stack stack) {
        assert stack.size > 0;
        stack.size--;
        ref r = stack.refs[stack.size];
        return r;
    }

    private static void ref_stack_swap_top(ref_stack stack, int off) {
        int idx = stack.size - off;
        assert idx >= 0 && idx < stack.size;
        ref tmp = stack.refs[idx];
        stack.refs[idx] = stack.refs[stack.size - 1];
        stack.refs[stack.size - 1] = tmp;
    }

    private static ref ref_stack_at(ref_stack stack, int idx) {
        assert idx >= 0 && idx < stack.size;
        return stack.refs[idx];
    }

    private static void ref_stack_clear(ref_stack stack) {
        stack.size = 0;
    }

    /* LoadFastInstrFlag */
    // The loaded reference is still on the stack when the local is killed
    private static final int SUPPORT_KILLED = 1;
    // The loaded reference is stored into a local
    private static final int STORED_AS_LOCAL = 2;
    // The loaded reference is still on the stack at the end of the basic block
    private static final int REF_UNCONSUMED = 4;

    private static void kill_local(byte[] instr_flags, ref_stack refs, int local) {
        for (int i = 0; i < refs.size; i++) {
            ref r = ref_stack_at(refs, i);
            if (r.local == local) {
                assert r.instr >= 0;
                instr_flags[r.instr] |= SUPPORT_KILLED;
            }
        }
    }

    private static void store_local(byte[] instr_flags, ref_stack refs, int local, ref r) {
        kill_local(instr_flags, refs, local);
        if (r.instr != DUMMY_INSTR) {
            instr_flags[r.instr] |= STORED_AS_LOCAL;
        }
    }

    private static void load_fast_push_block(basicblock[] stack, int[] sp, basicblock target,
            int start_depth) {
        assert target.b_startdepth >= 0 && target.b_startdepth == start_depth;
        if (!target.b_visited) {
            target.b_visited = true;
            stack[sp[0]++] = target;
        }
    }

    /*
     * Strength reduce LOAD_FAST{_LOAD_FAST} instructions into faster variants that
     * load borrowed references onto the operand stack.
     *
     * This is only safe when we can prove that the reference in the frame outlives
     * the borrowed reference produced by the instruction. We make this tractable
     * by enforcing the following lifetimes:
     *
     * 1. Borrowed references loaded onto the operand stack live until the end of
     *    the instruction that consumes them from the stack. Any borrowed
     *    references that would escape into the heap (e.g. into frame objects or
     *    generators) are converted into new, strong references.
     *
     * 2. Locals live until they are either killed by an instruction
     *    (e.g. STORE_FAST) or the frame is unwound. Any local that is overwritten
     *    via `f_locals` is added to a tuple owned by the frame object.
     *
     * To simplify the problem of detecting which supporting references in the
     * frame are killed by instructions that overwrite locals, we only allow
     * borrowed references to be stored as a local in the frame if they were passed
     * as an argument. {RETURN,YIELD}_VALUE convert borrowed references into new,
     * strong references.
     *
     * Using the above, we can optimize any LOAD_FAST{_LOAD_FAST} instructions
     * that meet the following criteria:
     *
     * 1. The produced reference must be consumed from the stack before the
     *    supporting reference in the frame is killed.
     *
     * 2. The produced reference cannot be stored as a local.
     *
     * We use abstract interpretation to identify instructions that meet these
     * criteria. For each basic block, we simulate the effect the bytecode has on a
     * stack of abstract references and note any instructions that violate the
     * criteria above. Once we've processed all the instructions in a block, any
     * non-violating LOAD_FAST{_LOAD_FAST} can be optimized.
     */
    static void optimize_load_fast(cfg_builder g) {
        ref_stack refs = new ref_stack();
        int max_instrs = 0;
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            max_instrs = Math.max(max_instrs, b.b_iused);
        }
        byte[] instr_flags = new byte[max_instrs];
        basicblock[] blocks = make_cfg_traversal_stack(entryblock);
        int[] sp = {0};
        blocks[sp[0]] = entryblock;
        sp[0]++;
        entryblock.b_startdepth = 0;
        entryblock.b_visited = true;

        while (sp[0] != 0) {
            basicblock block = blocks[--sp[0]];
            assert block.b_startdepth > -1;

            // Reset per-block state.
            java.util.Arrays.fill(instr_flags, 0, block.b_iused, (byte) 0);

            // Reset the stack of refs. We don't track references on the stack
            // across basic blocks, but the bytecode will expect their
            // presence. Add dummy references as necessary.
            ref_stack_clear(refs);
            for (int i = 0; i < block.b_startdepth; i++) {
                ref_stack_push(refs, new ref(DUMMY_INSTR, NOT_LOCAL));
            }

            for (int i = 0; i < block.b_iused; i++) {
                cfg_instr instr = block.b_instr[i];
                int opcode = instr.i_opcode;
                int oparg = instr.i_oparg;
                assert opcode != EXTENDED_ARG;
                switch (opcode) {
                    // Opcodes that load and store locals
                    case DELETE_FAST: {
                        kill_local(instr_flags, refs, oparg);
                        break;
                    }

                    case LOAD_FAST: {
                        ref_stack_push(refs, new ref(i, oparg));
                        break;
                    }

                    case LOAD_FAST_AND_CLEAR: {
                        kill_local(instr_flags, refs, oparg);
                        ref_stack_push(refs, new ref(i, oparg));
                        break;
                    }

                    case LOAD_FAST_LOAD_FAST: {
                        ref_stack_push(refs, new ref(i, oparg >> 4));
                        ref_stack_push(refs, new ref(i, oparg & 15));
                        break;
                    }

                    case STORE_FAST: {
                        ref r = ref_stack_pop(refs);
                        store_local(instr_flags, refs, oparg, r);
                        break;
                    }

                    case STORE_FAST_LOAD_FAST: {
                        // STORE_FAST
                        ref r = ref_stack_pop(refs);
                        store_local(instr_flags, refs, oparg >> 4, r);
                        // LOAD_FAST
                        ref_stack_push(refs, new ref(i, oparg & 15));
                        break;
                    }

                    case STORE_FAST_STORE_FAST: {
                        // STORE_FAST
                        ref r = ref_stack_pop(refs);
                        store_local(instr_flags, refs, oparg >> 4, r);
                        // STORE_FAST
                        r = ref_stack_pop(refs);
                        store_local(instr_flags, refs, oparg & 15, r);
                        break;
                    }

                    // Opcodes that shuffle values on the stack
                    case COPY: {
                        assert oparg > 0;
                        int idx = refs.size - oparg;
                        ref r = ref_stack_at(refs, idx);
                        ref_stack_push(refs, new ref(r.instr, r.local));
                        break;
                    }

                    case SWAP: {
                        assert oparg >= 2;
                        ref_stack_swap_top(refs, oparg);
                        break;
                    }

                    // We treat opcodes that do not consume all of their inputs on
                    // a case by case basis, as we have no generic way of knowing
                    // how many inputs should be left on the stack.

                    // Opcodes that consume no inputs
                    case FORMAT_SIMPLE:
                    case GET_ANEXT:
                    case GET_ITER:
                    case GET_LEN:
                    case IMPORT_FROM:
                    case MATCH_KEYS:
                    case MATCH_MAPPING:
                    case MATCH_SEQUENCE:
                    case WITH_EXCEPT_START: {
                        int num_popped = _PyOpcode_num_popped(opcode, oparg);
                        int num_pushed = _PyOpcode_num_pushed(opcode, oparg);
                        int net_pushed = num_pushed - num_popped;
                        assert net_pushed >= 0;
                        for (int j = 0; j < net_pushed; j++) {
                            ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        }
                        break;
                    }

                    // Opcodes that consume some inputs and push no new values
                    case DICT_MERGE:
                    case DICT_UPDATE:
                    case LIST_APPEND:
                    case LIST_EXTEND:
                    case MAP_ADD:
                    case RERAISE:
                    case SET_ADD:
                    case SET_UPDATE: {
                        int num_popped = _PyOpcode_num_popped(opcode, oparg);
                        int num_pushed = _PyOpcode_num_pushed(opcode, oparg);
                        int net_popped = num_popped - num_pushed;
                        assert net_popped > 0;
                        for (int j = 0; j < net_popped; j++) {
                            ref_stack_pop(refs);
                        }
                        break;
                    }

                    case END_SEND: {
                        assert _PyOpcode_num_popped(opcode, oparg) == 3;
                        assert _PyOpcode_num_pushed(opcode, oparg) == 1;
                        ref tos = ref_stack_pop(refs);
                        ref_stack_pop(refs);
                        ref_stack_pop(refs);
                        ref_stack_push(refs, new ref(tos.instr, tos.local));
                        break;
                    }

                    case SET_FUNCTION_ATTRIBUTE: {
                        assert _PyOpcode_num_popped(opcode, oparg) == 2;
                        assert _PyOpcode_num_pushed(opcode, oparg) == 1;
                        ref tos = ref_stack_pop(refs);
                        ref_stack_pop(refs);
                        ref_stack_push(refs, new ref(tos.instr, tos.local));
                        break;
                    }

                    // Opcodes that consume some inputs and push new values
                    case CHECK_EXC_MATCH: {
                        ref_stack_pop(refs);
                        ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        break;
                    }

                    case FOR_ITER: {
                        load_fast_push_block(blocks, sp, instr.i_target, refs.size + 1);
                        ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        break;
                    }

                    case LOAD_ATTR:
                    case LOAD_SUPER_ATTR: {
                        ref self = ref_stack_pop(refs);
                        if (opcode == LOAD_SUPER_ATTR) {
                            ref_stack_pop(refs);
                            ref_stack_pop(refs);
                        }
                        ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        if ((oparg & 1) != 0) {
                            // A method call; conservatively assume that self is pushed
                            // back onto the stack
                            ref_stack_push(refs, new ref(self.instr, self.local));
                        }
                        break;
                    }

                    case LOAD_SPECIAL:
                    case PUSH_EXC_INFO: {
                        ref tos = ref_stack_pop(refs);
                        ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        ref_stack_push(refs, new ref(tos.instr, tos.local));
                        break;
                    }

                    case SEND: {
                        load_fast_push_block(blocks, sp, instr.i_target, refs.size);
                        ref_stack_pop(refs);
                        ref_stack_push(refs, new ref(i, NOT_LOCAL));
                        break;
                    }

                    // Opcodes that consume all of their inputs
                    default: {
                        int num_popped = _PyOpcode_num_popped(opcode, oparg);
                        int num_pushed = _PyOpcode_num_pushed(opcode, oparg);
                        if (HAS_TARGET(instr.i_opcode)) {
                            load_fast_push_block(blocks, sp, instr.i_target,
                                    refs.size - num_popped + num_pushed);
                        }
                        if (!IS_BLOCK_PUSH_OPCODE(instr.i_opcode)) {
                            // Block push opcodes only affect the stack when jumping
                            // to the target.
                            for (int j = 0; j < num_popped; j++) {
                                ref_stack_pop(refs);
                            }
                            for (int j = 0; j < num_pushed; j++) {
                                ref_stack_push(refs, new ref(i, NOT_LOCAL));
                            }
                        }
                        break;
                    }
                }
            }

            // Push fallthrough block
            if (BB_HAS_FALLTHROUGH(block)) {
                assert block.b_next != null;
                load_fast_push_block(blocks, sp, block.b_next, refs.size);
            }

            // Mark instructions that produce values that are on the stack at the
            // end of the basic block
            for (int i = 0; i < refs.size; i++) {
                ref r = ref_stack_at(refs, i);
                if (r.instr != -1) {
                    instr_flags[r.instr] |= REF_UNCONSUMED;
                }
            }

            // Optimize instructions
            for (int i = 0; i < block.b_iused; i++) {
                if (instr_flags[i] == 0) {
                    cfg_instr instr = block.b_instr[i];
                    switch (instr.i_opcode) {
                        case LOAD_FAST:
                            instr.i_opcode = LOAD_FAST_BORROW;
                            break;
                        case LOAD_FAST_LOAD_FAST:
                            instr.i_opcode = LOAD_FAST_BORROW_LOAD_FAST_BORROW;
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    // helper functions for add_checks_for_loads_of_unknown_variables
    private static void maybe_push(basicblock b, long unsafe_mask, basicblock[] stack, int[] sp) {
        // Push b if the unsafe mask is giving us any new information.
        // To avoid overflowing the stack, only allow each block once.
        // Use b->b_visited=1 to mean that b is currently on the stack.
        long both = b.b_unsafe_locals_mask | unsafe_mask;
        if (b.b_unsafe_locals_mask != both) {
            b.b_unsafe_locals_mask = both;
            // More work left to do.
            if (!b.b_visited) {
                // not on the stack, so push it.
                stack[sp[0]++] = b;
                b.b_visited = true;
            }
        }
    }

    private static void scan_block_for_locals(basicblock b, basicblock[] stack, int[] sp) {
        // bit i is set if local i is potentially uninitialized
        long unsafe_mask = b.b_unsafe_locals_mask;
        for (int i = 0; i < b.b_iused; i++) {
            cfg_instr instr = b.b_instr[i];
            assert instr.i_opcode != EXTENDED_ARG;
            if (instr.i_except != null) {
                maybe_push(instr.i_except, unsafe_mask, stack, sp);
            }
            if (instr.i_oparg >= 64) {
                continue;
            }
            assert instr.i_oparg >= 0;
            long bit = 1L << instr.i_oparg;
            switch (instr.i_opcode) {
                case DELETE_FAST:
                case LOAD_FAST_AND_CLEAR:
                case STORE_FAST_MAYBE_NULL:
                    unsafe_mask |= bit;
                    break;
                case STORE_FAST:
                    unsafe_mask &= ~bit;
                    break;
                case LOAD_FAST_CHECK:
                    // If this doesn't raise, then the local is defined.
                    unsafe_mask &= ~bit;
                    break;
                case LOAD_FAST:
                    if ((unsafe_mask & bit) != 0) {
                        instr.i_opcode = LOAD_FAST_CHECK;
                    }
                    unsafe_mask &= ~bit;
                    break;
            }
        }
        if (b.b_next != null && BB_HAS_FALLTHROUGH(b)) {
            maybe_push(b.b_next, unsafe_mask, stack, sp);
        }
        cfg_instr last = basicblock_last_instr(b);
        if (last != null && is_jump(last)) {
            assert last.i_target != null;
            maybe_push(last.i_target, unsafe_mask, stack, sp);
        }
    }

    private static void fast_scan_many_locals(basicblock entryblock, int nlocals) {
        assert nlocals > 64;
        long[] states = new long[nlocals - 64];
        long blocknum = 0;
        // state[i - 64] == blocknum if local i is guaranteed to
        // be initialized, i.e., if it has had a previous LOAD_FAST or
        // STORE_FAST within that basicblock (not followed by
        // DELETE_FAST/LOAD_FAST_AND_CLEAR/STORE_FAST_MAYBE_NULL).
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            blocknum++;
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                assert instr.i_opcode != EXTENDED_ARG;
                int arg = instr.i_oparg;
                if (arg < 64) {
                    continue;
                }
                assert arg >= 0;
                switch (instr.i_opcode) {
                    case DELETE_FAST:
                    case LOAD_FAST_AND_CLEAR:
                    case STORE_FAST_MAYBE_NULL:
                        states[arg - 64] = blocknum - 1;
                        break;
                    case STORE_FAST:
                        states[arg - 64] = blocknum;
                        break;
                    case LOAD_FAST:
                        if (states[arg - 64] != blocknum) {
                            instr.i_opcode = LOAD_FAST_CHECK;
                        }
                        states[arg - 64] = blocknum;
                        break;
                }
            }
        }
    }

    private static void remove_unused_consts(basicblock entryblock, List<Object> consts) {
        int nconsts = consts.size();
        if (nconsts == 0) {
            return;  /* nothing to do */
        }

        int[] index_map = new int[nconsts];
        for (int i = 1; i < nconsts; i++) {
            index_map[i] = -1;
        }
        // The first constant may be docstring; keep it always.
        index_map[0] = 0;

        /* mark used consts */
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                int opcode = b.b_instr[i].i_opcode;
                if (OPCODE_HAS_CONST(opcode)) {
                    int index = b.b_instr[i].i_oparg;
                    index_map[index] = index;
                }
            }
        }
        /* now index_map[i] == i if consts[i] is used, -1 otherwise */
        /* condense consts */
        int n_used_consts = 0;
        for (int i = 0; i < nconsts; i++) {
            if (index_map[i] != -1) {
                assert index_map[i] == i;
                index_map[n_used_consts++] = index_map[i];
            }
        }
        if (n_used_consts == nconsts) {
            /* nothing to do */
            return;
        }

        /* move all used consts to the beginning of the consts list */
        assert n_used_consts < nconsts;
        for (int i = 0; i < n_used_consts; i++) {
            int old_index = index_map[i];
            assert i <= old_index && old_index < nconsts;
            if (i != old_index) {
                Object value = consts.get(index_map[i]);
                assert value != null;
                consts.set(i, value);
            }
        }

        /* truncate the consts list at its new size */
        consts.subList(n_used_consts, nconsts).clear();
        /* adjust const indices in the bytecode */
        int[] reverse_index_map = new int[nconsts];
        for (int i = 0; i < nconsts; i++) {
            reverse_index_map[i] = -1;
        }
        for (int i = 0; i < n_used_consts; i++) {
            assert index_map[i] != -1;
            assert reverse_index_map[index_map[i]] == -1;
            reverse_index_map[index_map[i]] = i;
        }

        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                int opcode = b.b_instr[i].i_opcode;
                if (OPCODE_HAS_CONST(opcode)) {
                    int index = b.b_instr[i].i_oparg;
                    assert reverse_index_map[index] >= 0;
                    assert reverse_index_map[index] < n_used_consts;
                    b.b_instr[i].i_oparg = reverse_index_map[index];
                }
            }
        }
    }

    private static void add_checks_for_loads_of_uninitialized_variables(basicblock entryblock,
            int nlocals, int nparams) {
        if (nlocals == 0) {
            return;
        }
        if (nlocals > 64) {
            // To avoid O(nlocals**2) compilation, locals beyond the first
            // 64 are only analyzed one basicblock at a time: initialization
            // info is not passed between basicblocks.
            fast_scan_many_locals(entryblock, nlocals);
            nlocals = 64;
        }
        basicblock[] stack = make_cfg_traversal_stack(entryblock);
        int[] sp = {0};

        // First origin of being uninitialized:
        // The non-parameter locals in the entry block.
        long start_mask = 0;
        for (int i = nparams; i < nlocals; i++) {
            start_mask |= 1L << i;
        }
        maybe_push(entryblock, start_mask, stack, sp);

        // Second origin of being uninitialized:
        // There could be DELETE_FAST somewhere, so
        // be sure to scan each basicblock at least once.
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            scan_block_for_locals(b, stack, sp);
        }
        // Now propagate the uncertainty from the origins we found: Use
        // LOAD_FAST_CHECK for any LOAD_FAST where the local could be undefined.
        while (sp[0] > 0) {
            basicblock b = stack[--sp[0]];
            // mark as no longer on stack
            b.b_visited = false;
            scan_block_for_locals(b, stack, sp);
        }
    }

    private static void mark_warm(basicblock entryblock) {
        basicblock[] stack = make_cfg_traversal_stack(entryblock);
        int sp = 0;

        stack[sp++] = entryblock;
        entryblock.b_visited = true;
        while (sp > 0) {
            basicblock b = stack[--sp];
            assert !b.b_except_handler;
            b.b_warm = true;
            basicblock next = b.b_next;
            if (next != null && BB_HAS_FALLTHROUGH(b) && !next.b_visited) {
                stack[sp++] = next;
                next.b_visited = true;
            }
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (is_jump(instr) && !instr.i_target.b_visited) {
                    stack[sp++] = instr.i_target;
                    instr.i_target.b_visited = true;
                }
            }
        }
    }

    private static void mark_cold(basicblock entryblock) {
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            assert !b.b_cold && !b.b_warm;
        }
        mark_warm(entryblock);

        basicblock[] stack = make_cfg_traversal_stack(entryblock);

        int sp = 0;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (b.b_except_handler) {
                assert !b.b_warm;
                stack[sp++] = b;
                b.b_visited = true;
            }
        }

        while (sp > 0) {
            basicblock b = stack[--sp];
            b.b_cold = true;
            basicblock next = b.b_next;
            if (next != null && BB_HAS_FALLTHROUGH(b)) {
                if (!next.b_warm && !next.b_visited) {
                    stack[sp++] = next;
                    next.b_visited = true;
                }
            }
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (is_jump(instr)) {
                    assert i == b.b_iused - 1;
                    basicblock target = b.b_instr[i].i_target;
                    if (!target.b_warm && !target.b_visited) {
                        stack[sp++] = target;
                        target.b_visited = true;
                    }
                }
            }
        }
    }

    private static void push_cold_blocks_to_end(cfg_builder g) {
        basicblock entryblock = g.g_entryblock;
        if (entryblock.b_next == null) {
            /* single basicblock, no need to reorder */
            return;
        }
        mark_cold(entryblock);

        int next_lbl = get_max_label(g.g_entryblock) + 1;

        /* If we have a cold block with fallthrough to a warm block, add */
        /* an explicit jump instead of fallthrough */
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (b.b_cold && BB_HAS_FALLTHROUGH(b) && b.b_next != null && b.b_next.b_warm) {
                basicblock explicit_jump = cfg_builder_new_block(g);
                if (!IS_LABEL(b.b_next.b_label)) {
                    b.b_next.b_label = next_lbl++;
                }
                basicblock_addop(explicit_jump, JUMP_NO_INTERRUPT, b.b_next.b_label,
                                 NO_LOCATION);
                explicit_jump.b_cold = true;
                explicit_jump.b_next = b.b_next;
                explicit_jump.b_predecessors = 1;
                b.b_next = explicit_jump;

                /* set target */
                cfg_instr last = basicblock_last_instr(explicit_jump);
                last.i_target = explicit_jump.b_next;
            }
        }

        assert !entryblock.b_cold;  /* First block can't be cold */
        basicblock cold_blocks = null;
        basicblock cold_blocks_tail = null;

        basicblock b = entryblock;
        while (b.b_next != null) {
            assert !b.b_cold;
            while (b.b_next != null && !b.b_next.b_cold) {
                b = b.b_next;
            }
            if (b.b_next == null) {
                /* no more cold blocks */
                break;
            }

            /* b->b_next is the beginning of a cold streak */
            assert !b.b_cold && b.b_next.b_cold;

            basicblock b_end = b.b_next;
            while (b_end.b_next != null && b_end.b_next.b_cold) {
                b_end = b_end.b_next;
            }

            /* b_end is the end of the cold streak */
            assert b_end != null && b_end.b_cold;
            assert b_end.b_next == null || !b_end.b_next.b_cold;

            if (cold_blocks == null) {
                cold_blocks = b.b_next;
            }
            else {
                cold_blocks_tail.b_next = b.b_next;
            }
            cold_blocks_tail = b_end;
            b.b_next = b_end.b_next;
            b_end.b_next = null;
        }
        assert b != null && b.b_next == null;
        b.b_next = cold_blocks;

        if (cold_blocks != null) {
            remove_redundant_nops_and_jumps(g);
        }
    }

    private static void convert_pseudo_conditional_jumps(cfg_builder g) {
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (instr.i_opcode == JUMP_IF_FALSE || instr.i_opcode == JUMP_IF_TRUE) {
                    assert i == b.b_iused - 1;
                    instr.i_opcode = instr.i_opcode == JUMP_IF_FALSE ?
                                             POP_JUMP_IF_FALSE : POP_JUMP_IF_TRUE;
                    SourceLocation loc = instr.i_loc;
                    basicblock except = instr.i_except;
                    cfg_instr copy = new cfg_instr(COPY, 1, loc, null, except);
                    basicblock_insert_instruction(b, i++, copy);
                    cfg_instr to_bool = new cfg_instr(TO_BOOL, 0, loc, null, except);
                    basicblock_insert_instruction(b, i++, to_bool);
                }
            }
        }
    }

    private static void convert_pseudo_ops(cfg_builder g) {
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (is_block_push(instr)) {
                    INSTR_SET_OP0(instr, NOP);
                }
                else if (instr.i_opcode == LOAD_CLOSURE) {
                    instr.i_opcode = LOAD_FAST;
                }
                else if (instr.i_opcode == STORE_FAST_MAYBE_NULL) {
                    instr.i_opcode = STORE_FAST;
                }
            }
        }
        remove_redundant_nops_and_jumps(g);
    }

    private static boolean is_exit_or_eval_check_without_lineno(basicblock b) {
        if (basicblock_exits_scope(b) || basicblock_has_eval_break(b)) {
            return basicblock_has_no_lineno(b);
        }
        else {
            return false;
        }
    }

    /* PEP 626 mandates that the f_lineno of a frame is correct
     * after a frame terminates. It would be prohibitively expensive
     * to continuously update the f_lineno field at runtime,
     * so we make sure that all exiting instruction (raises and returns)
     * have a valid line number, allowing us to compute f_lineno lazily.
     * We can do this by duplicating the exit blocks without line number
     * so that none have more than one predecessor. We can then safely
     * copy the line number from the sole predecessor block.
     */
    private static void duplicate_exits_without_lineno(cfg_builder g) {
        int next_lbl = get_max_label(g.g_entryblock) + 1;

        /* Copy all exit blocks without line number that are targets of a jump.
         */
        basicblock entryblock = g.g_entryblock;
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            cfg_instr last = basicblock_last_instr(b);
            if (last == null) {
                continue;
            }
            if (is_jump(last)) {
                basicblock target = next_nonempty_block(last.i_target);
                if (is_exit_or_eval_check_without_lineno(target) && target.b_predecessors > 1) {
                    basicblock new_target = copy_basicblock(g, target);
                    new_target.b_instr[0].i_loc = last.i_loc;
                    last.i_target = new_target;
                    target.b_predecessors--;
                    new_target.b_predecessors = 1;
                    new_target.b_next = target.b_next;
                    new_target.b_label = next_lbl++;
                    target.b_next = new_target;
                }
            }
        }

        /* Any remaining reachable exit blocks without line number can only be reached by
         * fall through, and thus can only have a single predecessor */
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            if (BB_HAS_FALLTHROUGH(b) && b.b_next != null && b.b_iused > 0) {
                if (is_exit_or_eval_check_without_lineno(b.b_next)) {
                    cfg_instr last = basicblock_last_instr(b);
                    assert last != null;
                    b.b_next.b_instr[0].i_loc = last.i_loc;
                }
            }
        }
    }

    /* If an instruction has no line number, but it's predecessor in the BB does,
     * then copy the line number. If a successor block has no line number, and only
     * one predecessor, then inherit the line number.
     * This ensures that all exit blocks (with one predecessor) receive a line number.
     * Also reduces the size of the line number table,
     * but has no impact on the generated line number events.
     */

    private static void maybe_propagate_location(basicblock b, int i, SourceLocation loc) {
        assert b.b_iused > i;
        if (b.b_instr[i].i_loc.lineno == NO_LOCATION.lineno) {
            b.b_instr[i].i_loc = loc;
        }
    }

    private static void propagate_line_numbers(basicblock entryblock) {
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            cfg_instr last = basicblock_last_instr(b);
            if (last == null) {
                continue;
            }

            SourceLocation prev_location = NO_LOCATION;
            for (int i = 0; i < b.b_iused; i++) {
                maybe_propagate_location(b, i, prev_location);
                prev_location = b.b_instr[i].i_loc;
            }
            if (BB_HAS_FALLTHROUGH(b) && b.b_next.b_predecessors == 1) {
                if (b.b_next.b_iused > 0) {
                    maybe_propagate_location(b.b_next, 0, prev_location);
                }
            }
            if (is_jump(last)) {
                basicblock target = last.i_target;
                while (target.b_iused == 0 && target.b_predecessors == 1) {
                    target = target.b_next;
                }
                if (target.b_predecessors == 1) {
                    maybe_propagate_location(target, 0, prev_location);
                }
            }
        }
    }

    private static void resolve_line_numbers(cfg_builder g, int firstlineno) {
        duplicate_exits_without_lineno(g);
        propagate_line_numbers(g.g_entryblock);
    }

    public static void _PyCfg_OptimizeCodeUnit(cfg_builder g, List<Object> consts,
            Map<Object, Object> const_cache, int nlocals, int nparams, int firstlineno) {
        assert g.g_entryblock.b_iused > 0;
        /** Preprocessing **/
        /* Map labels to targets and mark exception handlers */
        translate_jump_labels_to_targets(g.g_entryblock);
        mark_except_handlers(g.g_entryblock);
        label_exception_targets(g.g_entryblock);

        /** Optimization **/

        ConstsIndex consts_index = new ConstsIndex();

        for (int i = 0; i < consts.size(); i++) {
            Object item = consts.get(i);
            if (consts_index.get(item) != null) {
                continue;
            }
            consts_index.set(item, i);
        }

        optimize_cfg(g, consts, const_cache, consts_index, firstlineno);

        remove_unused_consts(g.g_entryblock, consts);
        add_checks_for_loads_of_uninitialized_variables(
                g.g_entryblock, nlocals, nparams);
        insert_superinstructions(g);

        push_cold_blocks_to_end(g);
        resolve_line_numbers(g, firstlineno);
        // temporarily remove assert. See https://github.com/python/cpython/issues/125845
        // assert(all_exits_have_lineno(g->g_entryblock));
    }

    private static int[] build_cellfixedoffsets(_PyCompile_CodeUnitMetadata umd) {
        int nlocals = umd.u_varnames.size();
        int ncellvars = umd.u_cellvars.size();
        int nfreevars = umd.u_freevars.size();

        int noffsets = ncellvars + nfreevars;
        int[] fixed = new int[noffsets];
        for (int i = 0; i < noffsets; i++) {
            fixed[i] = nlocals + i;
        }

        for (Map.Entry<Object, Integer> kv : umd.u_cellvars.entrySet()) {
            Integer varindex = umd.u_varnames.get(kv.getKey());
            if (varindex == null) {
                continue;
            }

            int argoffset = varindex;

            int oldindex = kv.getValue();
            fixed[oldindex] = argoffset;
        }
        return fixed;
    }

    /** C: IS_GENERATOR(CF) */
    static boolean IS_GENERATOR(int cf) {
        return (cf & (Compile.CO_GENERATOR | Compile.CO_COROUTINE
                | Compile.CO_ASYNC_GENERATOR)) != 0;
    }

    private static void insert_prefix_instructions(_PyCompile_CodeUnitMetadata umd,
            basicblock entryblock, int[] fixed, int nfreevars) {
        assert umd.u_firstlineno > 0;

        /* Set up cells for any variable that escapes, to be put in a closure. */
        final int ncellvars = umd.u_cellvars.size();
        if (ncellvars != 0) {
            // umd->u_cellvars has the cells out of order so we sort them
            // before adding the MAKE_CELL instructions.  Note that we
            // adjust for arg cells, which come first.
            final int nvars = ncellvars + umd.u_varnames.size();
            int[] sorted = new int[nvars];
            for (int i = 0; i < ncellvars; i++) {
                sorted[fixed[i]] = i + 1;
            }
            for (int i = 0, ncellsused = 0; ncellsused < ncellvars; i++) {
                int oldindex = sorted[i] - 1;
                if (oldindex == -1) {
                    continue;
                }
                cfg_instr make_cell = new cfg_instr(
                    MAKE_CELL,
                    // This will get fixed in offset_derefs().
                    oldindex,
                    NO_LOCATION,
                    null,
                    null);
                basicblock_insert_instruction(entryblock, ncellsused, make_cell);
                ncellsused += 1;
            }
        }

        if (nfreevars != 0) {
            cfg_instr copy_frees = new cfg_instr(COPY_FREE_VARS, nfreevars, NO_LOCATION,
                    null, null);
            basicblock_insert_instruction(entryblock, 0, copy_frees);
        }
    }

    private static int fix_cell_offsets(_PyCompile_CodeUnitMetadata umd, basicblock entryblock,
            int[] fixedmap) {
        int nlocals = umd.u_varnames.size();
        int ncellvars = umd.u_cellvars.size();
        int nfreevars = umd.u_freevars.size();
        int noffsets = ncellvars + nfreevars;

        // First deal with duplicates (arg cells).
        int numdropped = 0;
        for (int i = 0; i < noffsets; i++) {
            if (fixedmap[i] == i + nlocals) {
                fixedmap[i] -= numdropped;
            }
            else {
                // It was a duplicate (cell/arg).
                numdropped += 1;
            }
        }

        // Then update offsets, either relative to locals or by cell2arg.
        for (basicblock b = entryblock; b != null; b = b.b_next) {
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr inst = b.b_instr[i];
                // This is called before extended args are generated.
                assert inst.i_opcode != EXTENDED_ARG;
                int oldoffset = inst.i_oparg;
                switch (inst.i_opcode) {
                    case MAKE_CELL:
                    case LOAD_CLOSURE:
                    case LOAD_DEREF:
                    case STORE_DEREF:
                    case DELETE_DEREF:
                    case LOAD_FROM_DICT_OR_DEREF:
                        assert oldoffset >= 0;
                        assert oldoffset < noffsets;
                        assert fixedmap[oldoffset] >= 0;
                        inst.i_oparg = fixedmap[oldoffset];
                }
            }
        }

        return numdropped;
    }

    private static int prepare_localsplus(_PyCompile_CodeUnitMetadata umd, cfg_builder g) {
        int nlocals = umd.u_varnames.size();
        int ncellvars = umd.u_cellvars.size();
        int nfreevars = umd.u_freevars.size();
        int nlocalsplus = nlocals + ncellvars + nfreevars;
        int[] cellfixedoffsets = build_cellfixedoffsets(umd);

        // This must be called before fix_cell_offsets().
        insert_prefix_instructions(umd, g.g_entryblock, cellfixedoffsets, nfreevars);

        int numdropped = fix_cell_offsets(umd, g.g_entryblock, cellfixedoffsets);

        nlocalsplus -= numdropped;
        return nlocalsplus;
    }

    public static cfg_builder _PyCfg_FromInstructionSequence(InstructionSequence seq) {
        seq._PyInstructionSequence_ApplyLabelMap();
        cfg_builder g = _PyCfgBuilder_New();
        List<_PyInstruction> instrs = seq.s_instrs;
        for (int i = 0; i < seq.s_used(); i++) {
            instrs.get(i).i_target = 0;
        }
        for (int i = 0; i < seq.s_used(); i++) {
            _PyInstruction instr = instrs.get(i);
            if (HAS_TARGET(instr.i_opcode)) {
                assert instr.i_oparg >= 0 && instr.i_oparg < seq.s_used();
                instrs.get(instr.i_oparg).i_target = 1;
            }
        }
        int offset = 0;
        for (int i = 0; i < seq.s_used(); i++) {
            _PyInstruction instr = instrs.get(i);
            if (instr.i_opcode == ANNOTATIONS_PLACEHOLDER) {
                if (seq.s_annotations_code != null) {
                    InstructionSequence ann = seq.s_annotations_code;
                    assert ann.s_labelmap == null && ann.s_nested == null;
                    for (int j = 0; j < ann.s_used(); j++) {
                        _PyInstruction ann_instr = ann.s_instrs.get(j);
                        assert !HAS_TARGET(ann_instr.i_opcode);
                        _PyCfgBuilder_Addop(g, ann_instr.i_opcode, ann_instr.i_oparg,
                                ann_instr.i_loc);
                    }
                    offset += ann.s_used() - 1;
                }
                else {
                    offset -= 1;
                }
                continue;
            }
            if (instr.i_target != 0) {
                int lbl_ = i + offset;
                _PyCfgBuilder_UseLabel(g, lbl_);
            }
            int opcode = instr.i_opcode;
            int oparg = instr.i_oparg;
            if (HAS_TARGET(opcode)) {
                oparg += offset;
            }
            _PyCfgBuilder_Addop(g, opcode, oparg, instr.i_loc);
        }
        return g;
    }

    public static void _PyCfg_ToInstructionSequence(cfg_builder g, InstructionSequence seq) {
        int lbl = 0;
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            b.b_label = lbl;
            lbl += 1;
        }
        for (basicblock b = g.g_entryblock; b != null; b = b.b_next) {
            seq._PyInstructionSequence_UseLabel(b.b_label);
            for (int i = 0; i < b.b_iused; i++) {
                cfg_instr instr = b.b_instr[i];
                if (HAS_TARGET(instr.i_opcode)) {
                    /* Set oparg to the label id (it will later be mapped to an offset) */
                    instr.i_oparg = instr.i_target.b_label;
                }
                seq._PyInstructionSequence_Addop(instr.i_opcode, instr.i_oparg, instr.i_loc);

                _PyExceptHandlerInfo hi = seq.s_instrs.get(seq.s_used() - 1).i_except_handler_info;
                if (instr.i_except != null) {
                    hi.h_label = instr.i_except.b_label;
                    hi.h_startdepth = instr.i_except.b_startdepth;
                    hi.h_preserve_lasti = instr.i_except.b_preserve_lasti ? 1 : 0;
                }
                else {
                    hi.h_label = -1;
                }
            }
        }
        seq._PyInstructionSequence_ApplyLabelMap();
    }

    /**
     * C: _PyCfg_OptimizedCfgToInstructionSequence; the stack depth and
     * nlocalsplus are returned in out[0] and out[1].
     */
    public static void _PyCfg_OptimizedCfgToInstructionSequence(cfg_builder g,
            _PyCompile_CodeUnitMetadata umd, int[] out, InstructionSequence seq) {
        convert_pseudo_conditional_jumps(g);

        out[0] = calculate_stackdepth(g);

        out[1] = prepare_localsplus(umd, g);

        convert_pseudo_ops(g);

        /* Order of basic blocks must have been determined by now */

        normalize_jumps(g);

        /* Can't modify the bytecode after inserting instructions that produce
         * borrowed references.
         */
        optimize_load_fast(g);

        /* Can't modify the bytecode after computing jump offsets. */
        _PyCfg_ToInstructionSequence(g, seq);
    }

    /* This is used by _PyCompile_Assemble to fill in the jump and exception
     * targets in a synthetic CFG (which is not the output of the builtin compiler).
     */
    public static void _PyCfg_JumpLabelsToTargets(cfg_builder g) {
        translate_jump_labels_to_targets(g.g_entryblock);
        label_exception_targets(g.g_entryblock);
    }

    /* Exported API functions */

    /** PY_INVALID_STACK_EFFECT */
    public static final int PY_INVALID_STACK_EFFECT = Integer.MAX_VALUE;

    public static int PyCompile_OpcodeStackEffectWithJump(int opcode, int oparg, int jump) {
        int effect = get_stack_effects(opcode, oparg, jump);
        return effect == Integer.MIN_VALUE ? PY_INVALID_STACK_EFFECT : effect;
    }

    public static int PyCompile_OpcodeStackEffect(int opcode, int oparg) {
        int effect = get_stack_effects(opcode, oparg, -1);
        return effect == Integer.MIN_VALUE ? PY_INVALID_STACK_EFFECT : effect;
    }

    /* Access to compiler optimizations for unit tests.

     * _PyCompile_OptimizeCfg takes an instruction list, constructs
     * a CFG, optimizes it and converts back to an instruction list.
     */

    private static InstructionSequence cfg_to_instruction_sequence(cfg_builder g) {
        InstructionSequence seq = InstructionSequence._PyInstructionSequence_New();
        _PyCfg_ToInstructionSequence(g, seq);
        return seq;
    }

    /**
     * C: _PyCompile_OptimizeCfg (_testinternalcapi.optimize_cfg): optimizes
     * seq with a fresh const cache, nparams 0 and firstlineno 1. consts is
     * changed in place, as in C: afterwards it holds the unit's constants.
     */
    public static InstructionSequence _PyCompile_OptimizeCfg(InstructionSequence seq,
            List<Object> consts, int nlocals) {
        Map<Object, Object> const_cache = new java.util.HashMap<>();

        cfg_builder g = _PyCfg_FromInstructionSequence(seq);
        int nparams = 0, firstlineno = 1;
        _PyCfg_OptimizeCodeUnit(g, consts, const_cache, nlocals, nparams, firstlineno);

        calculate_stackdepth(g);

        optimize_load_fast(g);

        return cfg_to_instruction_sequence(g);
    }
}
