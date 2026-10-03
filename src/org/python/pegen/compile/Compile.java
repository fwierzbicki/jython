package org.python.pegen.compile;

import org.python.pegen.Parser;
import org.python.pegen.ast.base.mod;

/**
 * The front half of CPython's Python/compile.c: what compiler_setup does
 * before codegen (future, then preprocess, then symtable), and
 * _PyCompile_AstPreprocess, which is what compile(..., PyCF_ONLY_AST) runs
 * after parsing. An instance is C's compiler struct, with only the fields
 * these stages fill in; compiler units, the const cache and the rest of
 * compile.c wait for a backend.
 *
 * <p>C's names are kept. Errors are thrown as PythonSyntaxError where C sets
 * one and returns ERROR (or NULL).
 *
 * <p>Not ported yet: _PySymtable_Build (Phase C of
 * plan-cpython-bytecode-compiler.md), so for now future and preprocess run.
 */
public final class Compile {

    /* Code flags (Include/cpython/code.h) */
    public static final int CO_NESTED = 0x0010;
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

    /* The fields of C's compiler struct that the front end fills in. */
    public String c_filename;
    /** module's __future__ */
    public final Future.FutureFeatures c_future = new Future.FutureFeatures();
    public PyCompilerFlags c_flags;
    /** optimization level */
    public int c_optimize;
    /** module name, for warnings; may be null */
    public String c_module;

    private Compile() {}

    private void compiler_setup(mod mod, String filename, PyCompilerFlags flags, int optimize,
            String module, Parser.WarningHandler warnings) {
        PyCompilerFlags local_flags = new PyCompilerFlags();

        c_filename = filename;
        Future._PyFuture_FromAST(mod, filename, c_future);
        c_module = module;
        if (flags == null) {
            flags = local_flags;
        }
        int merged = c_future.ff_features | flags.cf_flags;
        c_future.ff_features = merged;
        flags.cf_flags = merged;
        c_flags = flags;
        c_optimize = (optimize == -1) ? optimization_level : optimize;

        AstPreprocess._PyAST_Preprocess(mod, filename, c_optimize, merged, false, true, module,
                warnings);
        // Not ported yet (Phase C):
        // c_st = _PySymtable_Build(mod, filename, &c_future)
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
}
