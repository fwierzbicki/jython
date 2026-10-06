package org.python.pegen.compile;

import static org.python.pegen.compile.Opcode.OPCODE_HAS_ARG;
import static org.python.pegen.compile.OpcodeUtils.HAS_TARGET;
import static org.python.pegen.compile.OpcodeUtils.IS_WITHIN_OPCODE_RANGE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A port of Python/instruction_sequence.c (with its header,
 * pycore_instruction_sequence.h): the sequence of instructions codegen
 * emits, with jump targets as labels until ApplyLabelMap turns them into
 * instruction offsets. C's {@code _PyInstructionSequence}.
 */
public final class InstructionSequence {

    /** C: _PyExceptHandlerInfo. */
    public static final class _PyExceptHandlerInfo {
        public int h_label;
        public int h_startdepth;
        public int h_preserve_lasti;
    }

    /** C: _PyInstruction. */
    public static final class _PyInstruction {
        public int i_opcode;
        public int i_oparg;
        public SourceLocation i_loc;
        public final _PyExceptHandlerInfo i_except_handler_info = new _PyExceptHandlerInfo();

        /* Temporary fields, used by the assembler and in instr_sequence_to_cfg */
        public int i_target;
        public int i_offset;
    }

    /** C: _PyJumpTargetLabel, a label id. */
    public static final class _PyJumpTargetLabel {
        public final int id;

        public _PyJumpTargetLabel(int id) {
            this.id = id;
        }
    }

    public static final _PyJumpTargetLabel NO_LABEL = new _PyJumpTargetLabel(-1);

    public static boolean SAME_JUMP_TARGET_LABEL(_PyJumpTargetLabel l1, _PyJumpTargetLabel l2) {
        return l1.id == l2.id;
    }

    public static boolean IS_JUMP_TARGET_LABEL(_PyJumpTargetLabel l) {
        return !SAME_JUMP_TARGET_LABEL(l, NO_LABEL);
    }

    private static final int INITIAL_INSTR_SEQUENCE_LABELS_MAP_SIZE = 10;
    private static final int MAX_OPCODE = 511;

    public final List<_PyInstruction> s_instrs = new ArrayList<>();

    /** next free label id */
    int s_next_free_label;

    /*
     * Map of a label id to instruction offset (index into s_instrs). If
     * s_labelmap is null, then each label id is the offset itself.
     */
    int[] s_labelmap;

    /** Instruction sequences of nested functions (C: a PyList, NULL when empty) */
    public List<InstructionSequence> s_nested;

    /** Code for creating annotations, spliced into the main sequence later */
    public InstructionSequence s_annotations_code;

    /** C: s_used */
    public int s_used() {
        return s_instrs.size();
    }

    /** C: _PyInstructionSequence_New */
    public static InstructionSequence _PyInstructionSequence_New() {
        return new InstructionSequence();
    }

    public _PyJumpTargetLabel _PyInstructionSequence_NewLabel() {
        return new _PyJumpTargetLabel(++s_next_free_label);
    }

    /** C: _Py_CArray_EnsureCapacity for s_labelmap, the new entries -111. */
    public void _PyInstructionSequence_UseLabel(int lbl) {
        int old_size = s_labelmap == null ? 0 : s_labelmap.length;
        int new_size = old_size;
        if (s_labelmap == null) {
            new_size = INITIAL_INSTR_SEQUENCE_LABELS_MAP_SIZE;
            if (lbl >= new_size) {
                new_size = lbl + INITIAL_INSTR_SEQUENCE_LABELS_MAP_SIZE;
            }
        } else if (lbl >= old_size) {
            new_size = old_size << 1;
            if (lbl >= new_size) {
                new_size = lbl + INITIAL_INSTR_SEQUENCE_LABELS_MAP_SIZE;
            }
        }
        if (new_size != old_size) {
            s_labelmap = s_labelmap == null ? new int[new_size]
                    : Arrays.copyOf(s_labelmap, new_size);
            for (int i = old_size; i < new_size; i++) {
                s_labelmap[i] = -111;  /* something weird, for debugging */
            }
        }
        s_labelmap[lbl] = s_used(); /* label refers to the next instruction */
    }

    public void _PyInstructionSequence_ApplyLabelMap() {
        if (s_labelmap == null) {
            /* Already applied - nothing to do */
            return;
        }
        /* Replace labels by offsets in the code */
        for (_PyInstruction instr : s_instrs) {
            if (HAS_TARGET(instr.i_opcode)) {
                assert instr.i_oparg < s_labelmap.length;
                instr.i_oparg = s_labelmap[instr.i_oparg];
            }
            _PyExceptHandlerInfo hi = instr.i_except_handler_info;
            if (hi.h_label >= 0) {
                assert hi.h_label < s_labelmap.length;
                hi.h_label = s_labelmap[hi.h_label];
            }
        }
        /* Clear label map so it's never used again */
        s_labelmap = null;
    }

    public void _PyInstructionSequence_Addop(int opcode, int oparg, SourceLocation loc) {
        assert 0 <= opcode && opcode <= MAX_OPCODE;
        assert IS_WITHIN_OPCODE_RANGE(opcode);
        assert OPCODE_HAS_ARG(opcode) || HAS_TARGET(opcode) || oparg == 0;
        assert 0 <= oparg && oparg < (1 << 30);

        _PyInstruction ci = new _PyInstruction();
        ci.i_opcode = opcode;
        ci.i_oparg = oparg;
        ci.i_loc = loc;
        s_instrs.add(ci);
    }

    public void _PyInstructionSequence_InsertInstruction(int pos, int opcode, int oparg,
            SourceLocation loc) {
        assert pos >= 0 && pos <= s_used();
        _PyInstruction ci = new _PyInstruction();
        ci.i_opcode = opcode;
        ci.i_oparg = oparg;
        ci.i_loc = loc;
        s_instrs.add(pos, ci);

        /* fix the labels map */
        if (s_labelmap != null) {
            for (int lbl = 0; lbl < s_labelmap.length; lbl++) {
                if (s_labelmap[lbl] >= pos) {
                    s_labelmap[lbl]++;
                }
            }
        }
    }

    public _PyInstruction _PyInstructionSequence_GetInstruction(int pos) {
        assert pos >= 0 && pos < s_used();
        return s_instrs.get(pos);
    }

    public void _PyInstructionSequence_SetAnnotationsCode(InstructionSequence annotations) {
        assert s_annotations_code == null;
        s_annotations_code = annotations;
    }

    public void _PyInstructionSequence_AddNested(InstructionSequence nested) {
        if (s_nested == null) {
            s_nested = new ArrayList<>();
        }
        s_nested.add(nested);
    }
}
