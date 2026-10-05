package org.python.pegen.compile;

import static org.python.pegen.compile.InstructionSequence.SAME_JUMP_TARGET_LABEL;
import static org.python.pegen.compile.Symtable.CELL;
import static org.python.pegen.compile.Symtable.DEF_COMP_CELL;
import static org.python.pegen.compile.Symtable.DEF_FREE_CLASS;
import static org.python.pegen.compile.Symtable.DEF_LOCAL;
import static org.python.pegen.compile.Symtable.DEF_NONLOCAL;
import static org.python.pegen.compile.Symtable.FREE;
import static org.python.pegen.compile.Symtable.GLOBAL_EXPLICIT;
import static org.python.pegen.compile.Symtable.GLOBAL_IMPLICIT;
import static org.python.pegen.compile.Symtable.LOCAL;
import static org.python.pegen.compile.Symtable.SYMBOL_TO_SCOPE;
import static org.python.pegen.compile.Symtable._PyST_GetScope;
import static org.python.pegen.compile.Symtable._PyST_GetSymbol;
import static org.python.pegen.compile.Symtable._PyST_IsFunctionLike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.python.pegen.Parser;
import org.python.pegen.ast.Attribute;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.Interactive;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.Singleton;
import org.python.pegen.ast.expr_contextType;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.stmt;
import org.python.pegen.compile.InstructionSequence._PyJumpTargetLabel;
import org.python.pegen.compile.Symtable.BlockKey;
import org.python.pegen.compile.Symtable.PySTEntryObject;

/**
 * A port of Python/compile.c: compiler_setup (future, then preprocess, then
 * symtable), the compiler units codegen works in, the const cache, and
 * _PyCompile_AstPreprocess, which is what compile(..., PyCF_ONLY_AST) runs
 * after parsing. An instance is C's compiler struct.
 *
 * <p>C's names are kept. Errors are thrown as PythonSyntaxError where C sets
 * one and returns ERROR (or NULL); a C function whose only result is
 * SUCCESS or ERROR returns void.
 *
 * <p>Until flowgraph and assemble are ported (Phases E and F),
 * _PyCompile_OptimizeAndAssemble returns a placeholder code object.
 */
public final class Compile {

    /* Code flags (Include/cpython/code.h) */
    public static final int CO_OPTIMIZED = 0x0001;
    public static final int CO_NEWLOCALS = 0x0002;
    public static final int CO_VARARGS = 0x0004;
    public static final int CO_VARKEYWORDS = 0x0008;
    public static final int CO_NESTED = 0x0010;
    public static final int CO_GENERATOR = 0x0020;
    public static final int CO_COROUTINE = 0x0080;
    public static final int CO_ITERABLE_COROUTINE = 0x0100;
    public static final int CO_ASYNC_GENERATOR = 0x0200;
    /* CO_FUTURE_ constants use bits starting at 0x20000, PyCF_ ones bits 0x0100 to 0x10000. */
    public static final int CO_FUTURE_DIVISION = 0x20000;
    /** do absolute imports by default */
    public static final int CO_FUTURE_ABSOLUTE_IMPORT = 0x40000;
    public static final int CO_FUTURE_WITH_STATEMENT = 0x80000;
    public static final int CO_FUTURE_PRINT_FUNCTION = 0x100000;
    public static final int CO_FUTURE_UNICODE_LITERALS = 0x200000;
    public static final int CO_FUTURE_BARRY_AS_BDFL = 0x400000;
    public static final int CO_FUTURE_GENERATOR_STOP = 0x800000;
    public static final int CO_FUTURE_ANNOTATIONS = 0x1000000;
    public static final int CO_NO_MONITORING_EVENTS = 0x2000000;
    public static final int CO_HAS_DOCSTRING = 0x4000000;
    public static final int CO_METHOD = 0x8000000;

    /** Max static block nesting within a function */
    public static final int CO_MAXBLOCKS = 21;

    /* Compiler flags (Include/cpython/compile.h) */
    public static final int PyCF_MASK = CO_FUTURE_DIVISION | CO_FUTURE_ABSOLUTE_IMPORT
            | CO_FUTURE_WITH_STATEMENT | CO_FUTURE_PRINT_FUNCTION
            | CO_FUTURE_UNICODE_LITERALS | CO_FUTURE_BARRY_AS_BDFL
            | CO_FUTURE_GENERATOR_STOP | CO_FUTURE_ANNOTATIONS;
    public static final int PyCF_MASK_OBSOLETE = CO_NESTED;
    public static final int PyCF_SOURCE_IS_UTF8 = 0x0100;
    public static final int PyCF_DONT_IMPLY_DEDENT = 0x0200;
    public static final int PyCF_ONLY_AST = 0x0400;
    public static final int PyCF_IGNORE_COOKIE = 0x0800;
    public static final int PyCF_TYPE_COMMENTS = 0x1000;
    public static final int PyCF_ALLOW_TOP_LEVEL_AWAIT = 0x2000;
    public static final int PyCF_ALLOW_INCOMPLETE_INPUT = 0x4000;
    public static final int PyCF_OPTIMIZED_AST = 0x8000 | PyCF_ONLY_AST;
    public static final int PyCF_COMPILE_MASK = PyCF_ONLY_AST | PyCF_ALLOW_TOP_LEVEL_AWAIT
            | PyCF_TYPE_COMMENTS | PyCF_DONT_IMPLY_DEDENT | PyCF_ALLOW_INCOMPLETE_INPUT
            | PyCF_OPTIMIZED_AST;

    /** C: PY_MINOR_VERSION, of the Python whose grammar the parser follows. */
    public static final int PY_MINOR_VERSION = 15;

    /**
     * A soft limit for stack use, to avoid excessive memory use for large
     * constants, etc. (pycore_compile.h)
     */
    public static final int _PY_STACK_USE_GUIDELINE = 30;

    /** C: PyCompilerFlags. A new one is C's _PyCompilerFlags_INIT. */
    public static final class PyCompilerFlags {
        /** bitmask of CO_xxx flags relevant to future */
        public int cf_flags;
        /** minor Python version (PyCF_ONLY_AST) */
        public int cf_feature_version = PY_MINOR_VERSION;

        public PyCompilerFlags() {}

        public PyCompilerFlags(int cf_flags) {
            this.cf_flags = cf_flags;
        }
    }

    /**
     * C: _Py_GetConfig()->optimization_level, the -O level used when the
     * caller passes optimize -1.
     */
    public static int optimization_level = 0;

    /* The kinds of compiler unit (C's anonymous enum COMPILE_SCOPE_*). */
    public static final int COMPILE_SCOPE_MODULE = 0;
    public static final int COMPILE_SCOPE_CLASS = 1;
    public static final int COMPILE_SCOPE_FUNCTION = 2;
    public static final int COMPILE_SCOPE_ASYNC_FUNCTION = 3;
    public static final int COMPILE_SCOPE_LAMBDA = 4;
    public static final int COMPILE_SCOPE_COMPREHENSION = 5;
    public static final int COMPILE_SCOPE_ANNOTATIONS = 6;

    /* _PyCompile_optype */
    public static final int COMPILE_OP_FAST = 0;
    public static final int COMPILE_OP_GLOBAL = 1;
    public static final int COMPILE_OP_DEREF = 2;
    public static final int COMPILE_OP_NAME = 3;

    /* enum _PyCompile_FBlockType */
    public static final int COMPILE_FBLOCK_WHILE_LOOP = 0;
    public static final int COMPILE_FBLOCK_FOR_LOOP = 1;
    public static final int COMPILE_FBLOCK_ASYNC_FOR_LOOP = 2;
    public static final int COMPILE_FBLOCK_TRY_EXCEPT = 3;
    public static final int COMPILE_FBLOCK_FINALLY_TRY = 4;
    public static final int COMPILE_FBLOCK_FINALLY_END = 5;
    public static final int COMPILE_FBLOCK_WITH = 6;
    public static final int COMPILE_FBLOCK_ASYNC_WITH = 7;
    public static final int COMPILE_FBLOCK_HANDLER_CLEANUP = 8;
    public static final int COMPILE_FBLOCK_POP_VALUE = 9;
    public static final int COMPILE_FBLOCK_EXCEPTION_HANDLER = 10;
    public static final int COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER = 11;
    public static final int COMPILE_FBLOCK_ASYNC_COMPREHENSION_GENERATOR = 12;
    public static final int COMPILE_FBLOCK_STOP_ITERATION = 13;

    /**
     * C: _PyCompile_FBlockInfo, the current frame block. A frame block is
     * used to handle loops, try/except, and try/finally. It's called a frame
     * block to distinguish it from a basic block in the compiler IR.
     */
    public static final class _PyCompile_FBlockInfo {
        public int fb_type;
        public _PyJumpTargetLabel fb_block;
        public SourceLocation fb_loc;
        /** (optional) type-specific exit or cleanup block */
        public _PyJumpTargetLabel fb_exit;
        /** (optional) additional information required for unwinding */
        public Object fb_datum;

        _PyCompile_FBlockInfo copy() {
            _PyCompile_FBlockInfo f = new _PyCompile_FBlockInfo();
            f.fb_type = fb_type;
            f.fb_block = fb_block;
            f.fb_loc = fb_loc;
            f.fb_exit = fb_exit;
            f.fb_datum = fb_datum;
            return f;
        }
    }

    /**
     * C: _PyCompile_CodeUnitMetadata. The dicts map objects to their index
     * in co_XXX, which is the argument of the opcodes that refer to those
     * collections; they keep C's insertion order.
     */
    public static final class _PyCompile_CodeUnitMetadata {
        public String u_name;
        /** dot-separated qualified name (lazy) */
        public String u_qualname;
        /** all constants (keyed by _PyCode_ConstantKey) */
        public Map<Object, Integer> u_consts;
        /** all names */
        public Map<Object, Integer> u_names;
        /** local variables */
        public Map<Object, Integer> u_varnames;
        /** cell variables */
        public Map<Object, Integer> u_cellvars;
        /** free variables */
        public Map<Object, Integer> u_freevars;
        /**
         * keys are names that are fast-locals only temporarily within an
         * inlined comprehension. When value is True, treat as fast-local.
         */
        public Map<String, Boolean> u_fasthidden;
        /** number of arguments for block */
        public int u_argcount;
        /** number of positional only arguments for block */
        public int u_posonlyargcount;
        /** number of keyword only arguments for block */
        public int u_kwonlyargcount;
        /** the first lineno of the block */
        public int u_firstlineno;
    }

    /**
     * C: struct compiler_unit, the items that change on entry and exit of
     * code blocks. They must be saved and restored when returning to a
     * block.
     */
    static final class compiler_unit {
        PySTEntryObject u_ste;

        int u_scope_type;

        /** for private name mangling */
        String u_private;
        /** for class: attributes accessed via self.X */
        Set<String> u_static_attributes;
        /** AnnAssign nodes deferred to the end of compilation */
        List<stmt> u_deferred_annotations;
        /**
         * indices of annotations that are conditionally executed (or -1 for
         * unconditional annotations)
         */
        List<Integer> u_conditional_annotation_indices;
        /** index of the next conditional annotation */
        int u_next_conditional_annotation_index;

        /** codegen output */
        InstructionSequence u_instr_sequence;
        /** temporarily stashed parent instruction sequence */
        InstructionSequence u_stashed_instr_sequence;

        int u_nfblocks;
        int u_in_inlined_comp;
        int u_in_conditional_block;

        final _PyCompile_FBlockInfo[] u_fblock = new _PyCompile_FBlockInfo[CO_MAXBLOCKS];

        _PyCompile_CodeUnitMetadata u_metadata = new _PyCompile_CodeUnitMetadata();
    }

    /** C: _PyCompile_InlinedComprehensionState */
    public static final class _PyCompile_InlinedComprehensionState {
        public List<String> pushed_locals;
        public Map<String, Integer> temp_symbols;
        public Set<String> fast_hidden;
        public _PyJumpTargetLabel cleanup;
    }

    /* The fields of C's compiler struct. */
    public String c_filename;
    public Symtable c_st;
    /** module's __future__ */
    public final Future.FutureFeatures c_future = new Future.FutureFeatures();
    public PyCompilerFlags c_flags;

    /** optimization level */
    public int c_optimize;
    /** true if in interactive mode */
    boolean c_interactive;
    /** all constants, including names tuple (C: a dict, key to key) */
    final Map<Object, Object> c_const_cache = new HashMap<>();
    /** compiler state for current block */
    compiler_unit u;
    /** compiler_unit ptrs (C: a Python list of capsules) */
    final List<compiler_unit> c_stack = new ArrayList<>();

    /**
     * if true, construct recursive instruction sequences (including
     * instructions for nested code objects)
     */
    boolean c_save_nested_seqs;
    int c_disable_warning;
    /** module name, for warnings; may be null */
    public String c_module;
    /** Where warnings go (C: Python's warnings machinery). */
    Parser.WarningHandler c_warnings;

    private Compile() {}

    private void compiler_setup(mod mod, String filename, PyCompilerFlags flags, int optimize,
            String module, Parser.WarningHandler warnings) {
        PyCompilerFlags local_flags = new PyCompilerFlags();

        c_filename = filename;
        Future._PyFuture_FromAST(mod, filename, c_future);
        c_module = module;
        c_warnings = warnings;
        if (flags == null) {
            flags = local_flags;
        }
        int merged = c_future.ff_features | flags.cf_flags;
        c_future.ff_features = merged;
        flags.cf_flags = merged;
        c_flags = flags;
        c_optimize = (optimize == -1) ? optimization_level : optimize;
        c_save_nested_seqs = false;

        AstPreprocess._PyAST_Preprocess(mod, filename, c_optimize, merged, false, true, module,
                warnings);
        c_st = Symtable._PySymtable_Build(mod, filename, c_future);
    }

    /**
     * new_compiler: a compiler set up for mod (compiler_setup), which
     * changes mod in place. Like C's, it writes the merged future flags back
     * into flags. Warnings go to warnings (C: Python's warnings machinery).
     * Throws the SyntaxError a stage raises.
     */
    public static Compile new_compiler(mod mod, String filename, PyCompilerFlags pflags,
            int optimize, String module, Parser.WarningHandler warnings) {
        Compile c = new Compile();
        c.compiler_setup(mod, filename, pflags, optimize, module, warnings);
        return c;
    }

    void _PyCompile_MaybeAddStaticAttributeToClass(Attribute e) {
        expr attr_value = e.value;
        if (!(attr_value instanceof Name) ||
            e.ctx != expr_contextType.Store ||
            !((Name) attr_value).id.equals("self"))
        {
            return;
        }
        for (int i = c_stack.size() - 1; i >= 0; i--) {
            compiler_unit u = c_stack.get(i);
            if (u.u_scope_type == COMPILE_SCOPE_CLASS) {
                assert u.u_static_attributes != null;
                u.u_static_attributes.add(e.attr);
                break;
            }
        }
    }

    private void compiler_set_qualname() {
        compiler_unit u = this.u;
        String name, base;

        base = null;
        int stack_size = c_stack.size();
        assert stack_size >= 1;
        if (stack_size > 1) {
            boolean force_global = false;
            compiler_unit parent;

            parent = c_stack.get(stack_size - 1);
            if (parent.u_scope_type == COMPILE_SCOPE_ANNOTATIONS) {
                /* The parent is an annotation scope, so we need to
                   look at the grandparent. */
                if (stack_size == 2) {
                    // If we're immediately within the module, we can skip
                    // the rest and just set the qualname to be the same as name.
                    u.u_metadata.u_qualname = u.u_metadata.u_name;
                    return;
                }
                parent = c_stack.get(stack_size - 2);
            }

            if (u.u_scope_type == COMPILE_SCOPE_FUNCTION
                || u.u_scope_type == COMPILE_SCOPE_ASYNC_FUNCTION
                || u.u_scope_type == COMPILE_SCOPE_CLASS) {
                assert u.u_metadata.u_name != null;
                String mangled = Symtable._Py_Mangle(parent.u_private, u.u_metadata.u_name);

                int scope = _PyST_GetScope(parent.u_ste, mangled);
                assert scope != GLOBAL_IMPLICIT;
                if (scope == GLOBAL_EXPLICIT)
                    force_global = true;
            }

            if (!force_global) {
                if (parent.u_scope_type == COMPILE_SCOPE_FUNCTION
                    || parent.u_scope_type == COMPILE_SCOPE_ASYNC_FUNCTION
                    || parent.u_scope_type == COMPILE_SCOPE_LAMBDA)
                {
                    base = parent.u_metadata.u_qualname + ".<locals>";
                }
                else {
                    base = parent.u_metadata.u_qualname;
                }
            }
            if (u.u_ste.ste_function_name != null) {
                base = base + "." + u.u_ste.ste_function_name;
            }
        }
        else if (u.u_ste.ste_function_name != null) {
            base = u.u_ste.ste_function_name;
        }

        if (base != null) {
            name = base + "." + u.u_metadata.u_name;
        }
        else {
            name = u.u_metadata.u_name;
        }
        u.u_metadata.u_qualname = name;
    }

    /*
     * Merge const o and return constant key object. If recursive, insert all
     * elements if o is a tuple or frozen set.
     */
    private static Object const_cache_insert(Map<Object, Object> const_cache, Object o,
            boolean recursive) {
        // None and Ellipsis are immortal objects, and key is the singleton.
        // No need to merge object and key.
        if (o == Singleton.None || o == Singleton.Ellipsis) {
            return o;
        }

        Object key = PyCodeObject._PyCode_ConstantKey(o);

        Object t = const_cache.putIfAbsent(key, key);
        if (t != null) {
            // o was not inserted into const_cache. t is the existing value.
            return t;
        }

        if (!recursive) {
            return key;
        }

        // We registered o in const_cache.
        // When o is a tuple or frozenset, we want to merge its
        // items too.
        if (o instanceof PyTuple) {
            Object[] items = ((PyTuple) o).items;
            for (int i = 0; i < items.length; i++) {
                Object item = items[i];
                Object u = const_cache_insert(const_cache, item, recursive);

                // See _PyCode_ConstantKey()
                Object v;
                if (u instanceof PyCodeObject.ConstantKey) {
                    v = ((PyCodeObject.ConstantKey) u).op;
                }
                else {
                    v = u;
                }
                if (v != item) {
                    items[i] = v;
                }
            }
        }
        else if (o instanceof PyFrozenSet) {
            // key is a ConstantKey whose op is the frozenset of the merged
            // items. See _PyCode_ConstantKey() for detail.
            PyFrozenSet set = (PyFrozenSet) o;
            if (set.size() == 0) {  // empty frozenset should not be re-created.
                return key;
            }
            List<Object> tuple = new ArrayList<>();
            for (Object item : set.items) {
                Object k = const_cache_insert(const_cache, item, recursive);
                Object u;
                if (k instanceof PyCodeObject.ConstantKey) {
                    u = ((PyCodeObject.ConstantKey) k).op;
                }
                else {
                    u = k;
                }
                tuple.add(u);
            }

            // Instead of rewriting o, we create new frozenset and embed in the
            // key tuple.  Caller should get merged frozenset from the key tuple.
            ((PyCodeObject.ConstantKey) key).op = new PyFrozenSet(tuple);
        }

        return key;
    }

    private static Object merge_consts_recursive(Map<Object, Object> const_cache, Object o) {
        return const_cache_insert(const_cache, o, true);
    }

    /** C: _PyCompile_DictAddObj: o's index in dict, adding it at the end if new. */
    public static int _PyCompile_DictAddObj(Map<Object, Integer> dict, Object o) {
        Integer v = dict.get(o);
        int arg;
        if (v == null) {
            arg = dict.size();
            dict.put(o, arg);
        }
        else
            arg = v;
        return arg;
    }

    public int _PyCompile_AddConst(Object o) {
        Object key = merge_consts_recursive(c_const_cache, o);
        return _PyCompile_DictAddObj(u.u_metadata.u_consts, key);
    }

    private static Map<Object, Integer> list2dict(List<String> list) {
        Map<Object, Integer> dict = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            dict.put(list.get(i), i);
        }
        return dict;
    }

    /**
     * Sorts names as Python sorts str (PyList_Sort): by code point, which
     * differs from String.compareTo's UTF-16 order above the BMP.
     */
    static void sortByCodePoint(List<String> names) {
        Collections.sort(names, (a, b) -> {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                int ca = a.codePointAt(i), cb = b.codePointAt(j);
                if (ca != cb) {
                    return Integer.compare(ca, cb);
                }
                i += Character.charCount(ca);
                j += Character.charCount(cb);
            }
            return Integer.compare(a.length() - i, b.length() - j);
        });
    }

    /*
     * Return new dict containing names from src that match scope(s).
     *
     * src is a symbol table dictionary. If the scope of a name matches
     * either scope_type or flag is set, insert it into the new dict. The
     * values are integers, starting at offset and increasing by one for each
     * key.
     */
    private static Map<Object, Integer> dictbytype(Map<String, Integer> src, int scope_type,
            int flag, int offset) {
        int i = offset;
        Map<Object, Integer> dest = new LinkedHashMap<>();

        /* Sort the keys so that we have a deterministic order on the indexes
           saved in the returned dictionary.  These indexes are used as indexes
           into the free and cell var storage.  Therefore if they aren't
           deterministic, then the generated bytecode is not deterministic.
        */
        List<String> sorted_keys = new ArrayList<>(src.keySet());
        sortByCodePoint(sorted_keys);

        for (String k : sorted_keys) {
            int vi = src.get(k);
            if (SYMBOL_TO_SCOPE(vi) == scope_type || (vi & flag) != 0) {
                dest.put(k, i);
                i++;
            }
        }
        return dest;
    }

    public void _PyCompile_EnterScope(String name, int scope_type, BlockKey key, int lineno,
            String privateobj, _PyCompile_CodeUnitMetadata umd) {
        compiler_unit u = new compiler_unit();
        u.u_scope_type = scope_type;
        if (umd != null) {
            u.u_metadata = umd;
        }
        else {
            u.u_metadata.u_argcount = 0;
            u.u_metadata.u_posonlyargcount = 0;
            u.u_metadata.u_kwonlyargcount = 0;
        }
        u.u_ste = c_st._PySymtable_Lookup(key);
        u.u_metadata.u_name = name;
        u.u_metadata.u_varnames = list2dict(u.u_ste.ste_varnames);
        u.u_metadata.u_cellvars = dictbytype(u.u_ste.ste_symbols, CELL, DEF_COMP_CELL, 0);
        if (u.u_ste.ste_needs_class_closure) {
            /* Cook up an implicit __class__ cell. */
            assert u.u_scope_type == COMPILE_SCOPE_CLASS;
            _PyCompile_DictAddObj(u.u_metadata.u_cellvars, "__class__");
        }
        if (u.u_ste.ste_needs_classdict) {
            /* Cook up an implicit __classdict__ cell. */
            assert u.u_scope_type == COMPILE_SCOPE_CLASS;
            _PyCompile_DictAddObj(u.u_metadata.u_cellvars, "__classdict__");
        }
        if (u.u_ste.ste_has_conditional_annotations) {
            /* Cook up an implicit __conditional_annotations__ cell */
            assert u.u_scope_type == COMPILE_SCOPE_CLASS
                    || u.u_scope_type == COMPILE_SCOPE_MODULE;
            _PyCompile_DictAddObj(u.u_metadata.u_cellvars, "__conditional_annotations__");
        }

        u.u_metadata.u_freevars = dictbytype(u.u_ste.ste_symbols, FREE, DEF_FREE_CLASS,
                                   u.u_metadata.u_cellvars.size());

        u.u_metadata.u_fasthidden = new LinkedHashMap<>();

        u.u_nfblocks = 0;
        u.u_in_inlined_comp = 0;
        u.u_metadata.u_firstlineno = lineno;
        u.u_metadata.u_consts = new LinkedHashMap<>();
        u.u_metadata.u_names = new LinkedHashMap<>();

        u.u_deferred_annotations = null;
        u.u_conditional_annotation_indices = null;
        u.u_next_conditional_annotation_index = 0;
        if (scope_type == COMPILE_SCOPE_CLASS) {
            u.u_static_attributes = new HashSet<>();
        }
        else {
            u.u_static_attributes = null;
        }

        u.u_instr_sequence = InstructionSequence._PyInstructionSequence_New();
        u.u_stashed_instr_sequence = null;

        /* Push the old compiler_unit on the stack. */
        if (this.u != null) {
            c_stack.add(this.u);
            if (privateobj == null) {
                privateobj = this.u.u_private;
            }
        }

        u.u_private = privateobj;

        this.u = u;
        if (scope_type != COMPILE_SCOPE_MODULE) {
            compiler_set_qualname();
        }
    }

    public void _PyCompile_ExitScope() {
        InstructionSequence nested_seq = null;
        if (c_save_nested_seqs) {
            nested_seq = u.u_instr_sequence;
        }
        /* Restore c->u to the parent unit. */
        int n = c_stack.size() - 1;
        if (n >= 0) {
            u = c_stack.remove(n);
            if (nested_seq != null) {
                u.u_instr_sequence._PyInstructionSequence_AddNested(nested_seq);
            }
        }
        else {
            u = null;
        }
    }

    /*
     * Frame block handling functions
     */

    public void _PyCompile_PushFBlock(SourceLocation loc, int t, _PyJumpTargetLabel block_label,
            _PyJumpTargetLabel exit, Object datum) {
        if (u.u_nfblocks >= CO_MAXBLOCKS) {
            throw _PyCompile_Error(loc, "too many statically nested blocks");
        }
        _PyCompile_FBlockInfo f = new _PyCompile_FBlockInfo();
        u.u_fblock[u.u_nfblocks++] = f;
        f.fb_type = t;
        f.fb_block = block_label;
        f.fb_loc = loc;
        f.fb_exit = exit;
        f.fb_datum = datum;
        if (t == COMPILE_FBLOCK_FINALLY_END) {
            c_disable_warning++;
        }
    }

    public void _PyCompile_PopFBlock(int t, _PyJumpTargetLabel block_label) {
        compiler_unit u = this.u;
        assert u.u_nfblocks > 0;
        u.u_nfblocks--;
        assert u.u_fblock[u.u_nfblocks].fb_type == t;
        assert SAME_JUMP_TARGET_LABEL(u.u_fblock[u.u_nfblocks].fb_block, block_label);
        if (t == COMPILE_FBLOCK_FINALLY_END) {
            c_disable_warning--;
        }
    }

    public _PyCompile_FBlockInfo _PyCompile_TopFBlock() {
        if (u.u_nfblocks == 0) {
            return null;
        }
        return u.u_fblock[u.u_nfblocks - 1];
    }

    public boolean _PyCompile_InExceptionHandler() {
        for (int i = 0; i < u.u_nfblocks; i++) {
            _PyCompile_FBlockInfo block = u.u_fblock[i];
            switch (block.fb_type) {
                case COMPILE_FBLOCK_TRY_EXCEPT:
                case COMPILE_FBLOCK_FINALLY_TRY:
                case COMPILE_FBLOCK_FINALLY_END:
                case COMPILE_FBLOCK_EXCEPTION_HANDLER:
                case COMPILE_FBLOCK_EXCEPTION_GROUP_HANDLER:
                case COMPILE_FBLOCK_HANDLER_CLEANUP:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** C: _PyCompile_DeferredAnnotations: the unit's deferred annotations, or null. */
    public List<stmt> _PyCompile_DeferredAnnotations() {
        return u.u_deferred_annotations;
    }

    /** The conditional annotation indices that go with them (the other out-parameter). */
    public List<Integer> _PyCompile_ConditionalAnnotationIndices() {
        return u.u_conditional_annotation_indices;
    }

    private static SourceLocation start_location(List<stmt> stmts) {
        if (stmts != null && stmts.size() > 0) {
            /* Set current line number to the line number of first statement.
             * This way line number for SETUP_ANNOTATIONS will always
             * coincide with the line number of first "real" statement in module.
             * If body is empty, then lineno will be set later in the assembly stage.
             */
            stmt st = stmts.get(0);
            return SourceLocation.SRC_LOCATION_FROM_AST(st);
        }
        return new SourceLocation(1, 1, 0, 0);
    }

    private void compiler_codegen(mod mod) {
        Codegen._PyCodegen_EnterAnonymousScope(this, mod);
        assert u.u_scope_type == COMPILE_SCOPE_MODULE;
        if (mod instanceof Module) {
            List<stmt> stmts = ((Module) mod).body;
            Codegen._PyCodegen_Module(this, start_location(stmts), stmts, false);
        }
        else if (mod instanceof Interactive) {
            c_interactive = true;
            List<stmt> stmts = ((Interactive) mod).body;
            Codegen._PyCodegen_Module(this, start_location(stmts), stmts, true);
        }
        else if (mod instanceof Expression) {
            Codegen._PyCodegen_Expression(this, ((Expression) mod).body);
        }
        else {
            throw new IllegalStateException("module kind " + mod + " should not be possible");
        }
    }

    private PyCodeObject compiler_mod(mod mod) {
        PyCodeObject co = null;
        boolean addNone = !(mod instanceof Expression);
        assert u == null;
        try {
            compiler_codegen(mod);
            co = _PyCompile_OptimizeAndAssemble(addNone);
        } finally {
            if (u != null) {
                _PyCompile_ExitScope();
            }
        }
        return co;
    }

    public int _PyCompile_GetRefType(String name) {
        if (u.u_scope_type == COMPILE_SCOPE_CLASS &&
            (name.equals("__class__") ||
             name.equals("__classdict__") ||
             name.equals("__conditional_annotations__"))) {
            return CELL;
        }
        PySTEntryObject ste = u.u_ste;
        int scope = _PyST_GetScope(ste, name);
        if (scope == 0) {
            throw new IllegalStateException("_PyST_GetScope(name='" + name
                    + "') failed: unknown scope in unit " + u.u_metadata.u_name);
        }
        return scope;
    }

    /** C: dict_lookup_arg, -1 if name isn't in dict. */
    private static int dict_lookup_arg(Map<Object, Integer> dict, String name) {
        Integer v = dict.get(name);
        return v == null ? -1 : v;
    }

    public int _PyCompile_LookupCellvar(String name) {
        assert u.u_metadata.u_cellvars != null;
        return dict_lookup_arg(u.u_metadata.u_cellvars, name);
    }

    public int _PyCompile_LookupArg(PyCodeObject co, String name) {
        /* Special case: If a class contains a method with a
         * free variable that has the same name as a method,
         * the name will be considered free *and* local in the
         * class.  It should be handled by the closure, as
         * well as by the normal name lookup logic.
         */
        int reftype = _PyCompile_GetRefType(name);
        int arg;
        if (reftype == CELL) {
            arg = dict_lookup_arg(u.u_metadata.u_cellvars, name);
        }
        else {
            arg = dict_lookup_arg(u.u_metadata.u_freevars, name);
        }
        if (arg == -1) {
            throw new IllegalStateException("compiler_lookup_arg(name='" + name
                    + "') with reftype=" + reftype + " failed in " + u.u_metadata.u_name
                    + "; freevars of code " + co.co_name + ": " + co.co_freevars);
        }
        return arg;
    }

    public PyTuple _PyCompile_StaticAttributesAsTuple() {
        assert u.u_static_attributes != null;
        List<String> static_attributes_unsorted = new ArrayList<>(u.u_static_attributes);
        sortByCodePoint(static_attributes_unsorted);
        return new PyTuple(static_attributes_unsorted.toArray());
    }

    /**
     * C: _PyCompile_ResolveNameop: the kind of op for the mangled name in
     * the current unit (a COMPILE_OP_*), and for all but COMPILE_OP_FAST its
     * index in the names, cellvars or freevars, returned as {optype, arg}.
     */
    public int[] _PyCompile_ResolveNameop(String mangled, int scope) {
        Map<Object, Integer> dict = u.u_metadata.u_names;
        int optype = COMPILE_OP_NAME;
        int arg = 0;

        assert scope >= 0;
        switch (scope) {
        case FREE:
            dict = u.u_metadata.u_freevars;
            optype = COMPILE_OP_DEREF;
            break;
        case CELL:
            dict = u.u_metadata.u_cellvars;
            optype = COMPILE_OP_DEREF;
            break;
        case LOCAL:
            if (_PyST_IsFunctionLike(u.u_ste)) {
                optype = COMPILE_OP_FAST;
            }
            else {
                Boolean item = u.u_metadata.u_fasthidden.get(mangled);
                if (item != null && item) {
                    optype = COMPILE_OP_FAST;
                }
            }
            break;
        case GLOBAL_IMPLICIT:
            if (_PyST_IsFunctionLike(u.u_ste)) {
                optype = COMPILE_OP_GLOBAL;
            }
            break;
        case GLOBAL_EXPLICIT:
            optype = COMPILE_OP_GLOBAL;
            break;
        default:
            /* scope can be 0 */
            break;
        }
        if (optype != COMPILE_OP_FAST) {
            arg = _PyCompile_DictAddObj(dict, mangled);
        }
        return new int[] {optype, arg};
    }

    public void _PyCompile_TweakInlinedComprehensionScopes(SourceLocation loc,
            PySTEntryObject entry, _PyCompile_InlinedComprehensionState state) {
        boolean in_class_block = (u.u_ste.ste_type == Symtable._Py_block_ty.ClassBlock)
                && u.u_in_inlined_comp == 0;
        u.u_in_inlined_comp++;

        for (Map.Entry<String, Integer> kv : entry.ste_symbols.entrySet()) {
            String k = kv.getKey();
            int symbol = kv.getValue();
            int scope = SYMBOL_TO_SCOPE(symbol);

            int outsymbol = _PyST_GetSymbol(u.u_ste, k);
            int outsc = SYMBOL_TO_SCOPE(outsymbol);

            // If a name has different scope inside than outside the comprehension,
            // we need to temporarily handle it with the right scope while
            // compiling the comprehension. If it's free in the comprehension
            // scope, no special handling; it should be handled the same as the
            // enclosing scope. (If it's free in outer scope and cell in inner
            // scope, we can't treat it as both cell and free in the same function,
            // but treating it as free throughout is fine; it's *_DEREF
            // either way.)
            if ((scope != outsc && scope != FREE && !(scope == CELL && outsc == FREE))
                    || in_class_block) {
                if (state.temp_symbols == null) {
                    state.temp_symbols = new LinkedHashMap<>();
                }
                // update the symbol to the in-comprehension version and save
                // the outer version; we'll restore it after running the
                // comprehension
                u.u_ste.ste_symbols.put(k, symbol);
                state.temp_symbols.put(k, outsymbol);
            }
            // locals handling for names bound in comprehension (DEF_LOCAL |
            // DEF_NONLOCAL occurs in assignment expression to nonlocal)
            if (((symbol & DEF_LOCAL) != 0 && (symbol & DEF_NONLOCAL) == 0) || in_class_block) {
                if (!_PyST_IsFunctionLike(u.u_ste)) {
                    // non-function scope: override this name to use fast locals
                    Boolean orig = u.u_metadata.u_fasthidden.get(k);
                    if (orig == null || !orig) {
                        u.u_metadata.u_fasthidden.put(k, Boolean.TRUE);
                        if (state.fast_hidden == null) {
                            state.fast_hidden = new LinkedHashSet<>();
                        }
                        state.fast_hidden.add(k);
                    }
                }
            }
        }
    }

    public void _PyCompile_RevertInlinedComprehensionScopes(SourceLocation loc,
            _PyCompile_InlinedComprehensionState state) {
        u.u_in_inlined_comp--;
        if (state.temp_symbols != null) {
            for (Map.Entry<String, Integer> kv : state.temp_symbols.entrySet()) {
                u.u_ste.ste_symbols.put(kv.getKey(), kv.getValue());
            }
            state.temp_symbols = null;
        }
        if (state.fast_hidden != null) {
            for (String k : state.fast_hidden) {
                // we set to False instead of clearing, so we can track which names
                // were temporarily fast-locals and should use CO_FAST_HIDDEN
                u.u_metadata.u_fasthidden.put(k, Boolean.FALSE);
            }
            state.fast_hidden = null;
        }
    }

    public void _PyCompile_EnterConditionalBlock() {
        u.u_in_conditional_block++;
    }

    public void _PyCompile_LeaveConditionalBlock() {
        assert u.u_in_conditional_block > 0;
        u.u_in_conditional_block--;
    }

    /**
     * C: _PyCompile_AddDeferredAnnotation: records s, returning its
     * conditional annotation index (C's out-parameter), or null if it's
     * unconditional.
     */
    public Integer _PyCompile_AddDeferredAnnotation(stmt s) {
        if (u.u_deferred_annotations == null) {
            u.u_deferred_annotations = new ArrayList<>();
        }
        if (u.u_conditional_annotation_indices == null) {
            u.u_conditional_annotation_indices = new ArrayList<>();
        }
        u.u_deferred_annotations.add(s);
        Integer conditional_annotation_index = null;
        int index;
        if (u.u_scope_type == COMPILE_SCOPE_MODULE || u.u_in_conditional_block != 0) {
            index = u.u_next_conditional_annotation_index;
            conditional_annotation_index = index;
            u.u_next_conditional_annotation_index++;
        }
        else {
            index = -1;
        }
        u.u_conditional_annotation_indices.add(index);
        return conditional_annotation_index;
    }

    /**
     * Makes the SyntaxError for msg at loc, for the caller to throw (C:
     * raises it and returns ERROR). msg is a Java format string.
     */
    public org.python.pegen.PythonSyntaxError _PyCompile_Error(SourceLocation loc,
            String format, Object... args) {
        String msg = String.format(format, args);
        return Errors._PyErr_RaiseSyntaxError(msg, c_filename, loc.lineno, loc.col_offset + 1,
                                loc.end_lineno, loc.end_col_offset + 1);
    }

    /*
     * Emits a SyntaxWarning. If a SyntaxWarning raised as error, throws a
     * SyntaxError instead.
     */
    public void _PyCompile_Warn(SourceLocation loc, String format, Object... args) {
        if (c_disable_warning != 0) {
            return;
        }
        String msg = String.format(format, args);
        Errors._PyErr_EmitSyntaxWarning(c_warnings, msg, c_filename, loc.lineno,
                loc.col_offset + 1, loc.end_lineno, loc.end_col_offset + 1, c_module);
    }

    public String _PyCompile_Mangle(String name) {
        return Symtable._Py_Mangle(u.u_private, name);
    }

    public String _PyCompile_MaybeMangle(String name) {
        return Symtable._Py_MaybeMangle(u.u_private, u.u_ste, name);
    }

    public InstructionSequence _PyCompile_InstrSequence() {
        return u.u_instr_sequence;
    }

    public void _PyCompile_StartAnnotationSetup() {
        InstructionSequence new_seq = InstructionSequence._PyInstructionSequence_New();
        assert u.u_stashed_instr_sequence == null;
        u.u_stashed_instr_sequence = u.u_instr_sequence;
        u.u_instr_sequence = new_seq;
    }

    public void _PyCompile_EndAnnotationSetup() {
        assert u.u_stashed_instr_sequence != null;
        InstructionSequence parent_seq = u.u_stashed_instr_sequence;
        InstructionSequence anno_seq = u.u_instr_sequence;
        u.u_stashed_instr_sequence = null;
        u.u_instr_sequence = parent_seq;
        parent_seq._PyInstructionSequence_SetAnnotationsCode(anno_seq);
    }

    public int _PyCompile_FutureFeatures() {
        return c_future.ff_features;
    }

    public Symtable _PyCompile_Symtable() {
        return c_st;
    }

    public PySTEntryObject _PyCompile_SymtableEntry() {
        return u.u_ste;
    }

    public int _PyCompile_OptimizationLevel() {
        return c_optimize;
    }

    public boolean _PyCompile_IsInteractiveTopLevel() {
        boolean is_nested_scope = c_stack.size() > 0;
        return c_interactive && !is_nested_scope;
    }

    public int _PyCompile_ScopeType() {
        return u.u_scope_type;
    }

    public int _PyCompile_IsInInlinedComp() {
        return u.u_in_inlined_comp;
    }

    public String _PyCompile_Qualname() {
        assert u.u_metadata.u_qualname != null;
        return u.u_metadata.u_qualname;
    }

    public _PyCompile_CodeUnitMetadata _PyCompile_Metadata() {
        return u.u_metadata;
    }

    /** Merge obj with constant cache, without recursion: the merged object. */
    public static Object _PyCompile_ConstCacheMergeOne(Map<Object, Object> const_cache,
            Object obj) {
        Object key = const_cache_insert(const_cache, obj, false);
        if (key instanceof PyCodeObject.ConstantKey) {
            return ((PyCodeObject.ConstantKey) key).op;
        }
        else {
            return key;
        }
    }

    /** C: consts_dict_keys_inorder: the constants, by index. */
    static List<Object> consts_dict_keys_inorder(Map<Object, Integer> dict) {
        Object[] consts = new Object[dict.size()];
        for (Map.Entry<Object, Integer> kv : dict.entrySet()) {
            int i = kv.getValue();
            Object k = kv.getKey();
            /* The keys of the dictionary can be tuples wrapping a constant.
             * (see _PyCompile_DictAddObj and _PyCode_ConstantKey). In that case
             * the object we want is always second. */
            if (k instanceof PyCodeObject.ConstantKey) {
                k = ((PyCodeObject.ConstantKey) k).op;
            }
            assert i < consts.length;
            assert i >= 0;
            consts[i] = k;
        }
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, consts);
        return list;
    }

    private int compute_code_flags() {
        PySTEntryObject ste = u.u_ste;
        int flags = 0;
        if (_PyST_IsFunctionLike(ste)) {
            flags |= CO_NEWLOCALS | CO_OPTIMIZED;
            if (ste.ste_nested)
                flags |= CO_NESTED;
            if (ste.ste_generator && !ste.ste_coroutine)
                flags |= CO_GENERATOR;
            if (ste.ste_generator && ste.ste_coroutine)
                flags |= CO_ASYNC_GENERATOR;
            if (ste.ste_varargs)
                flags |= CO_VARARGS;
            if (ste.ste_varkeywords)
                flags |= CO_VARKEYWORDS;
            if (ste.ste_has_docstring)
                flags |= CO_HAS_DOCSTRING;
            if (ste.ste_method)
                flags |= CO_METHOD;
        }

        if (ste.ste_coroutine && !ste.ste_generator) {
            flags |= CO_COROUTINE;
        }

        /* (Only) inherit compilerflags in PyCF_MASK */
        flags |= (c_flags.cf_flags & PyCF_MASK);

        return flags;
    }

    /**
     * C: optimize_and_assemble_code_unit. Phase D: no flowgraph or
     * assembler yet, so this makes the placeholder code object (see
     * {@link PyCodeObject}): its free variables are u_freevars' keys in
     * index order, where compute_localsplus_info puts them.
     */
    private static PyCodeObject optimize_and_assemble_code_unit(compiler_unit u,
            int code_flags, String filename) {
        String[] freevars = new String[u.u_metadata.u_freevars.size()];
        int offset = u.u_metadata.u_cellvars.size();
        for (Map.Entry<Object, Integer> kv : u.u_metadata.u_freevars.entrySet()) {
            freevars[kv.getValue() - offset] = (String) kv.getKey();
        }
        List<String> co_freevars = new ArrayList<>();
        Collections.addAll(co_freevars, freevars);

        // What code_richcompare compares (see PyCodeObject).
        u.u_instr_sequence._PyInstructionSequence_ApplyLabelMap();
        List<Object> identity = new ArrayList<>();
        _PyCompile_CodeUnitMetadata umd = u.u_metadata;
        Collections.addAll(identity, umd.u_name, umd.u_argcount, umd.u_posonlyargcount,
                umd.u_kwonlyargcount, code_flags, umd.u_firstlineno);
        for (InstructionSequence._PyInstruction instr : u.u_instr_sequence.s_instrs) {
            SourceLocation loc = instr.i_loc;
            Collections.addAll(identity, instr.i_opcode, instr.i_oparg, loc.lineno,
                    loc.end_lineno, loc.col_offset, loc.end_col_offset);
        }
        identity.add(new ArrayList<>(umd.u_consts.keySet()));
        identity.add(new ArrayList<>(umd.u_names.keySet()));
        identity.add(new ArrayList<>(umd.u_varnames.keySet()));
        identity.add(new ArrayList<>(umd.u_cellvars.keySet()));
        identity.add(co_freevars);
        return new PyCodeObject(u.u_metadata.u_name, u.u_metadata.u_qualname,
                u.u_metadata.u_firstlineno, co_freevars, identity);
    }

    public PyCodeObject _PyCompile_OptimizeAndAssemble(boolean addNone) {
        compiler_unit u = this.u;
        String filename = c_filename;

        int code_flags = compute_code_flags();

        Codegen._PyCodegen_AddReturnAtEnd(this, addNone);

        return optimize_and_assemble_code_unit(u, code_flags, filename);
    }

    /**
     * _PyAST_Compile: the code object for mod. Throws the SyntaxError a
     * stage raises. (Phase D: a placeholder code object; see
     * {@link PyCodeObject}.)
     */
    public static PyCodeObject _PyAST_Compile(mod mod, String filename, PyCompilerFlags pflags,
            int optimize, String module, Parser.WarningHandler warnings) {
        Compile c = new_compiler(mod, filename, pflags, optimize, module, warnings);
        return onLargeStack(() -> c.compiler_mod(mod));
    }

    /**
     * Runs a codegen stage on a thread with a large stack (LargeStack), as
     * the parser and the other stages run, so that any tree the parser makes
     * fits. C's Py_EnterRecursiveCall guards codegen_visit_expr: here a
     * StackOverflowError becomes the RecursionError C raises.
     */
    private static <T> T onLargeStack(java.util.function.Supplier<T> stage) {
        return org.python.pegen.LargeStack.call(() -> {
            try {
                return stage.get();
            } catch (StackOverflowError e) {
                throw new org.python.pegen.PythonSyntaxError("RecursionError",
                        "maximum recursion depth exceeded during compilation");
            }
        });
    }

    /**
     * _PyCompile_AstPreprocess: future, then preprocess, on the tree
     * compile(..., PyCF_ONLY_AST) returns. no_const_folding is true unless
     * cf asks for an optimized AST (pythonrun.c's syntax_check_only), and
     * the tree is changed in place. Throws the SyntaxError a stage raises.
     */
    public static void _PyCompile_AstPreprocess(mod mod, String filename, PyCompilerFlags cf,
            int optimize, boolean no_const_folding) {
        Future.FutureFeatures future = new Future.FutureFeatures();
        Future._PyFuture_FromAST(mod, filename, future);
        int flags = future.ff_features | cf.cf_flags;
        if (optimize == -1) {
            optimize = optimization_level;
        }
        // No warnings are enabled, so none need a handler.
        AstPreprocess._PyAST_Preprocess(mod, filename, optimize, flags, no_const_folding, false,
                null, null);
    }

    /**
     * C implementation of inspect.cleandoc(). Difference from
     * inspect.cleandoc(): do not remove leading and trailing blank lines to
     * keep lineno. (C works on the UTF-8 bytes; only ' ' and '\n', which are
     * ASCII, are looked at, so chars do as well.)
     */
    public static String _PyCompile_CleanDoc(String doc) {
        doc = expandtabs(doc);

        int p = 0;
        int pend = doc.length();

        // First pass: find minimum indentation of any non-blank lines
        // after first line.
        while (p < pend && doc.charAt(p++) != '\n') {
        }

        int margin = Integer.MAX_VALUE;
        while (p < pend) {
            int s = p;
            while (p < pend && doc.charAt(p) == ' ') p++;
            if (p < pend && doc.charAt(p) != '\n') {
                margin = Math.min(margin, p - s);
            }
            while (p < pend && doc.charAt(p++) != '\n') {
            }
        }
        if (margin == Integer.MAX_VALUE) {
            margin = 0;
        }

        // Second pass: write cleandoc into buff.

        // copy first line without leading spaces.
        p = 0;
        while (p < pend && doc.charAt(p) == ' ') {
            p++;
        }
        if (p == 0 && margin == 0) {
            // doc is already clean.
            return doc;
        }

        StringBuilder buff = new StringBuilder(pend);

        while (p < pend) {
            char ch = doc.charAt(p++);
            buff.append(ch);
            if (ch == '\n') {
                break;
            }
        }

        // copy subsequent lines without margin.
        while (p < pend) {
            for (int i = 0; i < margin; i++, p++) {
                if (p >= pend || doc.charAt(p) != ' ') {
                    break;
                }
            }
            while (p < pend) {
                char ch = doc.charAt(p++);
                buff.append(ch);
                if (ch == '\n') {
                    break;
                }
            }
        }

        return buff.toString();
    }

    /**
     * str.expandtabs() with the default tab size 8: the column resets at
     * '\n' and '\r', and is counted in code points.
     */
    static String expandtabs(String s) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (int i = 0; i < s.length();) {
            int ch = s.codePointAt(i);
            i += Character.charCount(ch);
            if (ch == '\t') {
                int n = 8 - col % 8;
                for (int k = 0; k < n; k++) {
                    out.append(' ');
                }
                col += n;
            } else {
                out.appendCodePoint(ch);
                col++;
                if (ch == '\n' || ch == '\r') {
                    col = 0;
                }
            }
        }
        return out.toString();
    }

    /**
     * The result of _PyCompile_CodeGen (C: a tuple of the instruction
     * sequence and a metadata dict).
     */
    public static final class CodeGenResult {
        public final InstructionSequence seq;
        public final int argcount;
        public final int posonlyargcount;
        public final int kwonlyargcount;
        /** The constants in LOAD_CONST index order. */
        public final List<Object> consts;

        CodeGenResult(InstructionSequence seq, _PyCompile_CodeUnitMetadata umd,
                List<Object> consts) {
            this.seq = seq;
            this.argcount = umd.u_argcount;
            this.posonlyargcount = umd.u_posonlyargcount;
            this.kwonlyargcount = umd.u_kwonlyargcount;
            this.consts = consts;
        }
    }

    /**
     * Access to compiler optimizations for unit tests: _PyCompile_CodeGen
     * applies code-gen to mod (after preprocess and symtable, as
     * new_compiler sets up) and returns the unoptimized instruction
     * sequence, with the nested units' sequences in it. C takes the AST as a
     * Python object (PyAST_obj2mod); this takes the tree, which it changes.
     */
    public static CodeGenResult _PyCompile_CodeGen(mod mod, String filename,
            PyCompilerFlags pflags, int optimize, Parser.WarningHandler warnings) {
        Compile c = new_compiler(mod, filename, pflags, optimize, null, warnings);
        c.c_save_nested_seqs = true;
        return onLargeStack(() -> c.compiler_codegen_result(mod));
    }

    private CodeGenResult compiler_codegen_result(mod mod) {
        Compile c = this;
        try {
            c.compiler_codegen(mod);

            _PyCompile_CodeUnitMetadata umd = c.u.u_metadata;

            boolean addNone = !(mod instanceof Expression);
            Codegen._PyCodegen_AddReturnAtEnd(c, addNone);

            c._PyCompile_InstrSequence()._PyInstructionSequence_ApplyLabelMap();

            /* After AddReturnAtEnd: co_consts indices match the final instruction stream. */
            List<Object> consts_list = consts_dict_keys_inorder(umd.u_consts);
            return new CodeGenResult(c._PyCompile_InstrSequence(), umd, consts_list);
        } finally {
            if (c.u != null) {
                c._PyCompile_ExitScope();
            }
        }
    }
}
