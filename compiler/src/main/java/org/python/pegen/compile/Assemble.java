package org.python.pegen.compile;

import static org.python.pegen.compile.Opcode.*;
import static org.python.pegen.compile.OpcodeUtils.*;
import static org.python.pegen.compile.PyCodeObject.*;
import static org.python.pegen.compile.SourceLocation.NEXT_LOCATION;
import static org.python.pegen.compile.SourceLocation.NO_LOCATION;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.python.pegen.compile.Compile._PyCompile_CodeUnitMetadata;
import org.python.pegen.compile.InstructionSequence._PyExceptHandlerInfo;
import org.python.pegen.compile.InstructionSequence._PyInstruction;
import org.python.pegen.compile.PyCodeObject._PyCodeConstructor;

/**
 * A port of Python/assemble.c: the optimized instruction sequence of a code
 * unit turned into its code object (the bytecode, the location and
 * exception tables, the locals), with the varint writers of pycore_code.h.
 *
 * <p>C's bytes objects, grown with _PyBytes_Resize, are byte arrays grown
 * as needed; their final contents are C's. _PyCompile_ConstCacheMergeOne on
 * the bytecode and the two tables is left out: it only shares equal bytes
 * objects, which a byte array doesn't need.
 */
public final class Assemble {

    private Assemble() {}

    private static final int DEFAULT_CODE_SIZE = 128;
    private static final int DEFAULT_LNOTAB_SIZE = 16;
    private static final int DEFAULT_CNOTAB_SIZE = 32;

    /* Kinds of location table entries (C: _PyCodeLocationInfoKind, cpython/code.h). */
    /* short forms are 0 to 9 */
    static final int PY_CODE_LOCATION_INFO_SHORT0 = 0;
    /* one lineforms are 10 to 12 */
    static final int PY_CODE_LOCATION_INFO_ONE_LINE0 = 10;
    static final int PY_CODE_LOCATION_INFO_ONE_LINE1 = 11;
    static final int PY_CODE_LOCATION_INFO_ONE_LINE2 = 12;

    static final int PY_CODE_LOCATION_INFO_NO_COLUMNS = 13;
    static final int PY_CODE_LOCATION_INFO_LONG = 14;
    static final int PY_CODE_LOCATION_INFO_NONE = 15;

    private static boolean same_location(SourceLocation a, SourceLocation b) {
        return a.lineno == b.lineno &&
               a.end_lineno == b.end_lineno &&
               a.col_offset == b.col_offset &&
               a.end_col_offset == b.end_col_offset;
    }

    private static int instr_size(_PyInstruction instr) {
        int opcode = instr.i_opcode;
        int oparg = instr.i_oparg;
        assert !IS_PSEUDO_INSTR(opcode);
        assert OPCODE_HAS_ARG(opcode) || oparg == 0;
        int extended_args = (0xFFFFFF < oparg ? 1 : 0) + (0xFFFF < oparg ? 1 : 0)
                + (0xFF < oparg ? 1 : 0);
        int caches = _PyOpcode_Caches[opcode];
        return extended_args + 1 + caches;
    }

    /** C: struct assembler. */
    private static final class assembler {
        byte[] a_bytecode;          /* bytes containing bytecode */
        int a_offset;               /* offset into bytecode (in code units) */
        byte[] a_except_table;      /* bytes containing exception table */
        int a_except_table_off;     /* offset into exception table */
        /* Location Info */
        int a_lineno;               /* lineno of last emitted instruction */
        byte[] a_linetable;         /* bytes containing location info */
        int a_location_off;         /* offset of last written location info frame */
    }

    private static void assemble_init(assembler a, int firstlineno) {
        a.a_lineno = firstlineno;
        a.a_location_off = 0;
        a.a_bytecode = new byte[DEFAULT_CODE_SIZE];
        a.a_linetable = new byte[DEFAULT_CNOTAB_SIZE];
        a.a_except_table = new byte[DEFAULT_LNOTAB_SIZE];
    }

    private static void write_except_byte(assembler a, int b) {
        a.a_except_table[a.a_except_table_off++] = (byte) b;
    }

    private static final int CONTINUATION_BIT = 64;

    private static void assemble_emit_exception_table_item(assembler a, int value, int msb) {
        assert (msb | 128) == 128;
        assert value >= 0 && value < (1 << 30);
        if (value >= 1 << 24) {
            write_except_byte(a, (value >> 24) | CONTINUATION_BIT | msb);
            msb = 0;
        }
        if (value >= 1 << 18) {
            write_except_byte(a, ((value >> 18)&0x3f) | CONTINUATION_BIT | msb);
            msb = 0;
        }
        if (value >= 1 << 12) {
            write_except_byte(a, ((value >> 12)&0x3f) | CONTINUATION_BIT | msb);
            msb = 0;
        }
        if (value >= 1 << 6) {
            write_except_byte(a, ((value >> 6)&0x3f) | CONTINUATION_BIT | msb);
            msb = 0;
        }
        write_except_byte(a, (value&0x3f) | msb);
    }

    /* See InternalDocs/exception_handling.md for details of layout */
    private static final int MAX_SIZE_OF_ENTRY = 20;

    private static void assemble_emit_exception_table_entry(assembler a, int start, int end,
            int handler_offset, _PyExceptHandlerInfo handler) {
        int len = a.a_except_table.length;
        if (a.a_except_table_off + MAX_SIZE_OF_ENTRY >= len) {
            a.a_except_table = Arrays.copyOf(a.a_except_table, len * 2);
        }
        int size = end-start;
        assert end > start;
        int target = handler_offset;
        int depth = handler.h_startdepth - 1;
        if (handler.h_preserve_lasti > 0) {
            depth -= 1;
        }
        assert depth >= 0;
        int depth_lasti = (depth<<1) | handler.h_preserve_lasti;
        assemble_emit_exception_table_item(a, start, (1<<7));
        assemble_emit_exception_table_item(a, size, 0);
        assemble_emit_exception_table_item(a, target, 0);
        assemble_emit_exception_table_item(a, depth_lasti, 0);
    }

    private static void assemble_exception_table(assembler a, InstructionSequence instrs) {
        int ioffset = 0;
        // C's handler is a struct copied by value (see copy).
        _PyExceptHandlerInfo handler = new _PyExceptHandlerInfo();
        handler.h_label = -1;
        handler.h_startdepth = -1;
        handler.h_preserve_lasti = -1;
        int start = -1;
        for (int i = 0; i < instrs.s_used(); i++) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            if (instr.i_except_handler_info.h_label != handler.h_label) {
                if (handler.h_label >= 0) {
                    int handler_offset = instrs.s_instrs.get(handler.h_label).i_offset;
                    assemble_emit_exception_table_entry(a, start, ioffset,
                                                        handler_offset,
                                                        handler);
                }
                start = ioffset;
                handler = copy(instr.i_except_handler_info);
            }
            ioffset += instr_size(instr);
        }
        if (handler.h_label >= 0) {
            int handler_offset = instrs.s_instrs.get(handler.h_label).i_offset;
            assemble_emit_exception_table_entry(a, start, ioffset,
                                                handler_offset,
                                                handler);
        }
    }

    private static _PyExceptHandlerInfo copy(_PyExceptHandlerInfo h) {
        _PyExceptHandlerInfo c = new _PyExceptHandlerInfo();
        c.h_label = h.h_label;
        c.h_startdepth = h.h_startdepth;
        c.h_preserve_lasti = h.h_preserve_lasti;
        return c;
    }

    /* Code location emitting code. See locations.md for a description of the format. */

    private static void write_location_byte(assembler a, int val) {
        a.a_linetable[a.a_location_off] = (byte) (val&255);
        a.a_location_off++;
    }

    private static void write_location_first_byte(assembler a, int code, int length) {
        a.a_location_off += write_location_entry_start(
            a.a_linetable, a.a_location_off, code, length);
    }

    private static void write_location_varint(assembler a, int val) {
        a.a_location_off += write_varint(a.a_linetable, a.a_location_off, val);
    }

    private static void write_location_signed_varint(assembler a, int val) {
        a.a_location_off += write_signed_varint(a.a_linetable, a.a_location_off, val);
    }

    private static void write_location_info_short_form(assembler a, int length, int column,
            int end_column) {
        assert length > 0 &&  length <= 8;
        int column_low_bits = column & 7;
        int column_group = column >> 3;
        assert column < 80;
        assert end_column >= column;
        assert end_column - column < 16;
        write_location_first_byte(a, PY_CODE_LOCATION_INFO_SHORT0 + column_group, length);
        write_location_byte(a, (column_low_bits << 4) | (end_column - column));
    }

    private static void write_location_info_oneline_form(assembler a, int length,
            int line_delta, int column, int end_column) {
        assert length > 0 &&  length <= 8;
        assert line_delta >= 0 && line_delta < 3;
        assert column < 128;
        assert end_column < 128;
        write_location_first_byte(a, PY_CODE_LOCATION_INFO_ONE_LINE0 + line_delta, length);
        write_location_byte(a, column);
        write_location_byte(a, end_column);
    }

    private static void write_location_info_long_form(assembler a, SourceLocation loc,
            int length) {
        assert length > 0 &&  length <= 8;
        write_location_first_byte(a, PY_CODE_LOCATION_INFO_LONG, length);
        write_location_signed_varint(a, loc.lineno - a.a_lineno);
        assert loc.end_lineno >= loc.lineno;
        write_location_varint(a, loc.end_lineno - loc.lineno);
        write_location_varint(a, loc.col_offset + 1);
        write_location_varint(a, loc.end_col_offset + 1);
    }

    private static void write_location_info_none(assembler a, int length) {
        write_location_first_byte(a, PY_CODE_LOCATION_INFO_NONE, length);
    }

    private static void write_location_info_no_column(assembler a, int length,
            int line_delta) {
        write_location_first_byte(a, PY_CODE_LOCATION_INFO_NO_COLUMNS, length);
        write_location_signed_varint(a, line_delta);
    }

    private static final int THEORETICAL_MAX_ENTRY_SIZE = 25; /* 1 + 6 + 6 + 6 + 6 */

    private static void write_location_info_entry(assembler a, SourceLocation loc,
            int isize) {
        int len = a.a_linetable.length;
        if (a.a_location_off + THEORETICAL_MAX_ENTRY_SIZE >= len) {
            assert len > THEORETICAL_MAX_ENTRY_SIZE;
            a.a_linetable = Arrays.copyOf(a.a_linetable, len*2);
        }
        if (loc.lineno == NO_LOCATION.lineno) {
            write_location_info_none(a, isize);
            return;
        }
        int line_delta = loc.lineno - a.a_lineno;
        int column = loc.col_offset;
        int end_column = loc.end_col_offset;
        if (column < 0 || end_column < 0) {
            if (loc.end_lineno == loc.lineno || loc.end_lineno < 0) {
                write_location_info_no_column(a, isize, line_delta);
                a.a_lineno = loc.lineno;
                return;
            }
        }
        else if (loc.end_lineno == loc.lineno) {
            if (line_delta == 0 && column < 80 && end_column - column < 16 && end_column >= column) {
                write_location_info_short_form(a, isize, column, end_column);
                return;
            }
            if (line_delta >= 0 && line_delta < 3 && column < 128 && end_column < 128) {
                write_location_info_oneline_form(a, isize, line_delta, column, end_column);
                a.a_lineno = loc.lineno;
                return;
            }
        }
        write_location_info_long_form(a, loc, isize);
        a.a_lineno = loc.lineno;
    }

    private static void assemble_emit_location(assembler a, SourceLocation loc, int isize) {
        if (isize == 0) {
            return;
        }
        while (isize > 8) {
            write_location_info_entry(a, loc, 8);
            isize -= 8;
        }
        write_location_info_entry(a, loc, isize);
    }

    private static void assemble_location_info(assembler a, InstructionSequence instrs,
            int firstlineno) {
        a.a_lineno = firstlineno;
        SourceLocation loc = NO_LOCATION;
        for (int i = instrs.s_used()-1; i >= 0; i--) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            if (same_location(instr.i_loc, NEXT_LOCATION)) {
                if (IS_TERMINATOR_OPCODE(instr.i_opcode)) {
                    instr.i_loc = NO_LOCATION;
                }
                else {
                    assert i < instrs.s_used()-1;
                    instr.i_loc = instrs.s_instrs.get(i + 1).i_loc;
                }
            }
        }
        int size = 0;
        for (int i = 0; i < instrs.s_used(); i++) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            if (!same_location(loc, instr.i_loc)) {
                    assemble_emit_location(a, loc, size);
                    loc = instr.i_loc;
                    size = 0;
            }
            size += instr_size(instr);
        }
        assemble_emit_location(a, loc, size);
    }

    /** C: write_instr, codestr being the code unit at index p of code. */
    private static void write_instr(byte[] code, int p, _PyInstruction instr, int ilen) {
        int opcode = instr.i_opcode;
        assert !IS_PSEUDO_INSTR(opcode);
        int oparg = instr.i_oparg;
        assert OPCODE_HAS_ARG(opcode) || oparg == 0;
        int caches = _PyOpcode_Caches[opcode];
        switch (ilen - caches) {
            case 4:
                p = write_code_unit(code, p, EXTENDED_ARG, (oparg >> 24) & 0xFF);
                // fall through
            case 3:
                p = write_code_unit(code, p, EXTENDED_ARG, (oparg >> 16) & 0xFF);
                // fall through
            case 2:
                p = write_code_unit(code, p, EXTENDED_ARG, (oparg >> 8) & 0xFF);
                // fall through
            case 1:
                p = write_code_unit(code, p, opcode, oparg & 0xFF);
                break;
            default:
                throw new IllegalStateException("unreachable");
        }
        while (caches-- > 0) {
            p = write_code_unit(code, p, CACHE, 0);
        }
    }

    /** codestr->op.code = op; codestr->op.arg = arg; codestr++ */
    private static int write_code_unit(byte[] code, int p, int op, int arg) {
        code[2 * p] = (byte) op;
        code[2 * p + 1] = (byte) arg;
        return p + 1;
    }

    /* assemble_emit_instr()
       Extend the bytecode with a new instruction.
       Update lnotab if necessary.
    */

    private static void assemble_emit_instr(assembler a, _PyInstruction instr) {
        int len = a.a_bytecode.length;

        int size = instr_size(instr);
        if (a.a_offset + size >= len / 2) {
            a.a_bytecode = Arrays.copyOf(a.a_bytecode, len * 2);
        }
        int code = a.a_offset;
        a.a_offset += size;
        write_instr(a.a_bytecode, code, instr, size);
    }

    private static void assemble_emit(assembler a, InstructionSequence instrs,
            int first_lineno) {
        assemble_init(a, first_lineno);

        for (int i = 0; i < instrs.s_used(); i++) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            assemble_emit_instr(a, instr);
        }

        assemble_location_info(a, instrs, a.a_lineno);

        assemble_exception_table(a, instrs);

        a.a_except_table = Arrays.copyOf(a.a_except_table, a.a_except_table_off);
        a.a_linetable = Arrays.copyOf(a.a_linetable, a.a_location_off);
        a.a_bytecode = Arrays.copyOf(a.a_bytecode, a.a_offset * 2);
    }

    private static PyTuple dict_keys_inorder(Map<Object, Integer> dict, int offset) {
        int size = dict.size();
        Object[] tuple = new Object[size];
        for (Map.Entry<Object, Integer> kv : dict.entrySet()) {
            int i = kv.getValue();
            assert (i - offset) < size;
            assert (i - offset) >= 0;
            tuple[i - offset] = kv.getKey();
        }
        return new PyTuple(tuple);
    }

    private static void compute_localsplus_info(_PyCompile_CodeUnitMetadata umd,
            int nlocalsplus, int flags, PyTuple names, byte[] kinds) {
        // Set the locals kinds.  Arg vars fill the first portion of the list.
        int[][] argvarkinds = {
            {umd.u_posonlyargcount, CO_FAST_ARG_POS},
            {umd.u_argcount, CO_FAST_ARG_POS | CO_FAST_ARG_KW},
            {umd.u_kwonlyargcount, CO_FAST_ARG_KW},
            {(flags & Compile.CO_VARARGS) != 0 ? 1 : 0, CO_FAST_ARG_VAR | CO_FAST_ARG_POS},
            {(flags & Compile.CO_VARKEYWORDS) != 0 ? 1 : 0, CO_FAST_ARG_VAR | CO_FAST_ARG_KW},
            {-1, 0},  // the remaining local vars
        };
        // C: PyDict_Next over u_varnames, pos carried across the six groups.
        Object[] varnames = umd.u_varnames.keySet().toArray();
        int pos = 0;
        int max = 0;
        for (int i = 0; i < 6; i++) {
            max = argvarkinds[i][0] < 0
                ? Integer.MAX_VALUE
                : max + argvarkinds[i][0];
            while (pos < max && pos < varnames.length) {
                Object k = varnames[pos++];
                int offset = umd.u_varnames.get(k);
                assert offset >= 0;
                assert offset < nlocalsplus;

                int kind = CO_FAST_LOCAL | argvarkinds[i][1];

                if (umd.u_fasthidden.containsKey(k)) {
                    kind |= CO_FAST_HIDDEN;
                }

                if (umd.u_cellvars.containsKey(k)) {
                    kind |= CO_FAST_CELL;
                }

                _Py_set_localsplus_info(offset, (String) k, kind, names, kinds);
            }
        }
        int nlocals = umd.u_varnames.size();

        // This counter mirrors the fix done in fix_cell_offsets().
        int numdropped = 0, cellvar_offset = -1;
        for (Map.Entry<Object, Integer> kv : umd.u_cellvars.entrySet()) {
            Object k = kv.getKey();
            if (umd.u_varnames.containsKey(k)) {
                // Skip cells that are already covered by locals.
                numdropped += 1;
                continue;
            }

            cellvar_offset = kv.getValue();
            assert cellvar_offset >= 0;
            cellvar_offset += nlocals - numdropped;
            assert cellvar_offset < nlocalsplus;
            _Py_set_localsplus_info(cellvar_offset, (String) k, CO_FAST_CELL, names, kinds);
        }

        for (Map.Entry<Object, Integer> kv : umd.u_freevars.entrySet()) {
            int offset = kv.getValue();
            assert offset >= 0;
            offset += nlocals - numdropped;
            assert offset < nlocalsplus;
            /* XXX If the assertion below fails it is most likely because a freevar
               was added to u_freevars with the wrong index due to not taking into
               account cellvars already present, see gh-128632. */
            assert offset > cellvar_offset;
            _Py_set_localsplus_info(offset, (String) kv.getKey(), CO_FAST_FREE, names, kinds);
        }
    }

    private static PyCodeObject makecode(_PyCompile_CodeUnitMetadata umd, assembler a,
            Map<Object, Object> const_cache, List<Object> constslist, int maxdepth,
            int nlocalsplus, int code_flags, String filename) {
        PyTuple names = dict_keys_inorder(umd.u_names, 0);
        names = (PyTuple) Compile._PyCompile_ConstCacheMergeOne(const_cache, names);

        PyTuple consts = new PyTuple(constslist.toArray()); /* PyCode_New requires a tuple */
        consts = (PyTuple) Compile._PyCompile_ConstCacheMergeOne(const_cache, consts);

        assert umd.u_posonlyargcount < Integer.MAX_VALUE;
        assert umd.u_argcount < Integer.MAX_VALUE;
        assert umd.u_kwonlyargcount < Integer.MAX_VALUE;
        int posonlyargcount = umd.u_posonlyargcount;
        int posorkwargcount = umd.u_argcount;
        int kwonlyargcount = umd.u_kwonlyargcount;

        PyTuple localsplusnames = new PyTuple(new Object[nlocalsplus]);
        byte[] localspluskinds = new byte[nlocalsplus];
        compute_localsplus_info(
                umd, nlocalsplus, code_flags,
                localsplusnames, localspluskinds);

        _PyCodeConstructor con = new _PyCodeConstructor();
        con.filename = filename;
        con.name = umd.u_name;
        con.qualname = umd.u_qualname != null ? umd.u_qualname : umd.u_name;
        con.flags = code_flags;

        con.code = a.a_bytecode;
        con.firstlineno = umd.u_firstlineno;
        con.linetable = a.a_linetable;

        con.consts = consts;
        con.names = names;

        con.localsplusnames = localsplusnames;
        con.localspluskinds = localspluskinds;

        con.argcount = posonlyargcount + posorkwargcount;
        con.posonlyargcount = posonlyargcount;
        con.kwonlyargcount = kwonlyargcount;

        con.stacksize = maxdepth;

        con.exceptiontable = a.a_except_table;

        PyCodeObject._PyCode_Validate(con);

        localsplusnames = (PyTuple) Compile._PyCompile_ConstCacheMergeOne(const_cache,
                localsplusnames);
        con.localsplusnames = localsplusnames;

        return new PyCodeObject(con);
    }

    // The offset (in code units) of the END_SEND from the SEND in the `yield from` sequence.
    private static final int END_SEND_OFFSET = 6;

    private static void resolve_jump_offsets(InstructionSequence instrs) {
        /* Compute the size of each instruction and fixup jump args.
         * Replace instruction index with position in bytecode.
         */

        for (int i = 0; i < instrs.s_used(); i++) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            if (OPCODE_HAS_JUMP(instr.i_opcode)) {
                instr.i_target = instr.i_oparg;
            }
        }

        boolean extended_arg_recompile;

        do {
            int totsize = 0;
            for (int i = 0; i < instrs.s_used(); i++) {
                _PyInstruction instr = instrs.s_instrs.get(i);
                instr.i_offset = totsize;
                int isize = instr_size(instr);
                totsize += isize;
            }
            extended_arg_recompile = false;

            int offset = 0;
            for (int i = 0; i < instrs.s_used(); i++) {
                _PyInstruction instr = instrs.s_instrs.get(i);
                int isize = instr_size(instr);
                /* jump offsets are computed relative to
                 * the instruction pointer after fetching
                 * the jump instruction.
                 */
                offset += isize;
                if (OPCODE_HAS_JUMP(instr.i_opcode)) {
                    _PyInstruction target = instrs.s_instrs.get(instr.i_target);
                    instr.i_oparg = target.i_offset;
                    if (instr.i_opcode == END_ASYNC_FOR) {
                        // sys.monitoring needs to be able to find the matching END_SEND
                        // but the target is the SEND, so we adjust it here.
                        instr.i_oparg = offset - instr.i_oparg - END_SEND_OFFSET;
                    }
                    else if (instr.i_oparg < offset) {
                        assert IS_BACKWARDS_JUMP_OPCODE(instr.i_opcode);
                        instr.i_oparg = offset - instr.i_oparg;
                    }
                    else {
                        assert !IS_BACKWARDS_JUMP_OPCODE(instr.i_opcode);
                        instr.i_oparg = instr.i_oparg - offset;
                    }
                    if (instr_size(instr) != isize) {
                        extended_arg_recompile = true;
                    }
                }
            }
        /* XXX: This is an awful hack that could hurt performance, but
            on the bright side it should work until we come up
            with a better solution.

            The issue is that in the first loop instr_size() is
            called, and it requires i_oparg be set appropriately.
            There is a bootstrap problem because i_oparg is
            calculated in the second loop above.

            So we loop until we stop seeing new EXTENDED_ARGs.
            The only EXTENDED_ARGs that could be popping up are
            ones in jump instructions.  So this should converge
            fairly quickly.
        */
        } while (extended_arg_recompile);
    }

    private static void resolve_unconditional_jumps(InstructionSequence instrs) {
        /* Resolve directions of unconditional jumps */

        for (int i = 0; i < instrs.s_used(); i++) {
            _PyInstruction instr = instrs.s_instrs.get(i);
            boolean is_forward = (instr.i_oparg > i);
            switch(instr.i_opcode) {
                case JUMP:
                    instr.i_opcode = is_forward ? JUMP_FORWARD : JUMP_BACKWARD;
                    break;
                case JUMP_NO_INTERRUPT:
                    instr.i_opcode = is_forward ?
                        JUMP_FORWARD : JUMP_BACKWARD_NO_INTERRUPT;
                    break;
                default:
                    if (OPCODE_HAS_JUMP(instr.i_opcode) &&
                        IS_PSEUDO_INSTR(instr.i_opcode)) {
                        throw new IllegalStateException("unreachable");
                    }
            }
        }
    }

    public static PyCodeObject _PyAssemble_MakeCodeObject(_PyCompile_CodeUnitMetadata umd,
            Map<Object, Object> const_cache, List<Object> consts, int maxdepth,
            InstructionSequence instrs, int nlocalsplus, int code_flags, String filename) {
        instrs._PyInstructionSequence_ApplyLabelMap();
        resolve_unconditional_jumps(instrs);
        resolve_jump_offsets(instrs);

        assembler a = new assembler();
        assemble_emit(a, instrs, umd.u_firstlineno);
        return makecode(umd, a, const_cache, consts, maxdepth, nlocalsplus,
                        code_flags, filename);
    }

    /* The varint writers of pycore_code.h, at index p of a byte array. */

    /** C: write_varint */
    static int write_varint(byte[] a, int p, int val) {
        int written = 1;
        while (Integer.compareUnsigned(val, 64) >= 0) {
            a[p++] = (byte) (64 | (val & 63));
            val >>>= 6;
            written++;
        }
        a[p] = (byte) val;
        return written;
    }

    /** C: write_signed_varint */
    static int write_signed_varint(byte[] a, int p, int val) {
        int uval;
        if (val < 0) {
            // (unsigned int)(-val) has an undefined behavior for INT_MIN
            uval = ((0 - val) << 1) | 1;
        }
        else {
            uval = val << 1;
        }
        return write_varint(a, p, uval);
    }

    /** C: write_location_entry_start */
    static int write_location_entry_start(byte[] a, int p, int code, int length) {
        assert (code & 15) == code;
        a[p] = (byte) (128 | (code << 3) | (length - 1));
        return 1;
    }
}
