package org.python.pegen.compile;

import static org.python.pegen.ActionHelpers.asdl_seq_LEN;
import static org.python.pegen.compile.Compile.CO_FUTURE_ANNOTATIONS;
import static org.python.pegen.compile.Compile.PyCF_ALLOW_TOP_LEVEL_AWAIT;
import static org.python.pegen.compile.SourceLocation.SRC_LOCATION_FROM_AST;
import static org.python.pegen.compile.Symtable._Py_block_ty.AnnotationBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.ClassBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.FunctionBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.ModuleBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.TypeAliasBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.TypeParametersBlock;
import static org.python.pegen.compile.Symtable._Py_block_ty.TypeVariableBlock;
import static org.python.pegen.compile.Symtable._Py_comprehension_ty.DictComprehension;
import static org.python.pegen.compile.Symtable._Py_comprehension_ty.GeneratorExpression;
import static org.python.pegen.compile.Symtable._Py_comprehension_ty.ListComprehension;
import static org.python.pegen.compile.Symtable._Py_comprehension_ty.NoComprehension;
import static org.python.pegen.compile.Symtable._Py_comprehension_ty.SetComprehension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.python.pegen.LargeStack;
import org.python.pegen.PythonSyntaxError;
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
import org.python.pegen.ast.Delete;
import org.python.pegen.ast.Dict;
import org.python.pegen.ast.DictComp;
import org.python.pegen.ast.ExceptHandler;
import org.python.pegen.ast.Expr;
import org.python.pegen.ast.Expression;
import org.python.pegen.ast.For;
import org.python.pegen.ast.FormattedValue;
import org.python.pegen.ast.FunctionDef;
import org.python.pegen.ast.GeneratorExp;
import org.python.pegen.ast.Global;
import org.python.pegen.ast.If;
import org.python.pegen.ast.IfExp;
import org.python.pegen.ast.Import;
import org.python.pegen.ast.ImportFrom;
import org.python.pegen.ast.Interactive;
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
import org.python.pegen.ast.MatchStar;
import org.python.pegen.ast.MatchValue;
import org.python.pegen.ast.Module;
import org.python.pegen.ast.Name;
import org.python.pegen.ast.NamedExpr;
import org.python.pegen.ast.Nonlocal;
import org.python.pegen.ast.ParamSpec;
import org.python.pegen.ast.Raise;
import org.python.pegen.ast.Return;
import org.python.pegen.ast.SetComp;
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
import org.python.pegen.ast.comprehension;
import org.python.pegen.ast.expr_contextType;
import org.python.pegen.ast.keyword;
import org.python.pegen.ast.match_case;
import org.python.pegen.ast.withitem;
import org.python.pegen.ast.base.excepthandler;
import org.python.pegen.ast.base.expr;
import org.python.pegen.ast.base.mod;
import org.python.pegen.ast.base.pattern;
import org.python.pegen.ast.base.stmt;
import org.python.pegen.ast.base.type_param;

/**
 * A port of CPython's Python/symtable.c (and its declarations in
 * Include/internal/pycore_symtable.h): the symbol table, built from the AST
 * in two passes. The first (symtable_visit_*) records the raw facts about
 * each name in each block (DEF_* flags); the second (symtable_analyze)
 * decides each name's scope. An instance is C's struct symtable. C's names
 * and order are kept.
 *
 * <p>C keys blocks by the address of the AST node they come from, and twice
 * by an address + 1 (an AnnAssign's annotation block, a TypeVar's default);
 * see {@link BlockKey}. C's dicts become LinkedHashMaps (insertion ordered,
 * as dicts are) and its sets HashSets: no result depends on a set's order
 * except the order of the free names update_symbols adds to a block's
 * symbols, which in C follows str hashes and so changes from run to run.
 *
 * <p>Errors are thrown as PythonSyntaxError where C sets one and returns 0.
 */
public final class Symtable {

    /** C: _Py_block_ty. The ordinals are C's values (_symtable's TYPE_*). */
    public enum _Py_block_ty {
        FunctionBlock, ClassBlock, ModuleBlock,
        // Used for annotations. If 'from __future__ import annotations' is active,
        // annotation blocks cannot bind names and are not evaluated. Otherwise, they
        // are lazily evaluated (see PEP 649).
        AnnotationBlock,

        // The following blocks are used for generics and type aliases. These work
        // mostly like functions (see PEP 695 for details). The three different
        // blocks function identically; they are different enum entries only so
        // that error messages can be more precise.

        // The block to enter when processing a "type" (PEP 695) construction,
        // e.g., "type MyGeneric[T] = list[T]".
        TypeAliasBlock,
        // The block to enter when processing a "generic" (PEP 695) object,
        // e.g., "def foo[T](): pass" or "class A[T]: pass".
        TypeParametersBlock,
        // The block to enter when processing the bound, the constraint tuple
        // or the default value of a single "type variable" in the formal sense,
        // i.e., a TypeVar, a TypeVarTuple or a ParamSpec object (the latter two
        // do not support a bound or a constraint tuple).
        TypeVariableBlock
    }

    /** C: _Py_comprehension_ty. The ordinals are C's values. */
    public enum _Py_comprehension_ty {
        NoComprehension, ListComprehension, DictComprehension, SetComprehension,
        GeneratorExpression
    }

    /* Flags for def-use information */

    /** global stmt */
    public static final int DEF_GLOBAL = 1;
    /** assignment in code block */
    public static final int DEF_LOCAL = 2;
    /** formal parameter */
    public static final int DEF_PARAM = 2 << 1;
    /** nonlocal stmt */
    public static final int DEF_NONLOCAL = 2 << 2;
    /** name is used */
    public static final int USE = 2 << 3;
    /** free variable from class's method */
    public static final int DEF_FREE_CLASS = 2 << 5;
    /** assignment occurred via import */
    public static final int DEF_IMPORT = 2 << 6;
    /** this name is annotated */
    public static final int DEF_ANNOT = 2 << 7;
    /** this name is a comprehension iteration variable */
    public static final int DEF_COMP_ITER = 2 << 8;
    /** this name is a type parameter */
    public static final int DEF_TYPE_PARAM = 2 << 9;
    /** this name is a cell in an inlined comprehension */
    public static final int DEF_COMP_CELL = 2 << 10;

    public static final int DEF_BOUND = DEF_LOCAL | DEF_PARAM | DEF_IMPORT;

    /* GLOBAL_EXPLICIT and GLOBAL_IMPLICIT are used internally by the symbol
       table.  GLOBAL is returned from _PyST_GetScope() for either of them.
       It is stored in ste_symbols at bits 13-16.
    */
    public static final int SCOPE_OFFSET = 12;
    public static final int SCOPE_MASK = DEF_GLOBAL | DEF_LOCAL | DEF_PARAM | DEF_NONLOCAL;

    public static int SYMBOL_TO_SCOPE(int S) {
        return (S >> SCOPE_OFFSET) & SCOPE_MASK;
    }

    public static final int LOCAL = 1;
    public static final int GLOBAL_EXPLICIT = 2;
    public static final int GLOBAL_IMPLICIT = 3;
    public static final int FREE = 4;
    public static final int CELL = 5;

    /**
     * A block's key in st_blocks (C: PyLong_FromVoidPtr(key)): the AST node
     * (or sequence) the block comes from, compared by identity, plus an
     * offset. C twice makes a second key for a node from its address + 1
     * (offset 1 here): an AnnAssign's annotation block, keyed on the
     * enclosing block's ste_id + 1, and a TypeVar's default, keyed on the
     * TypeVar + 1 (its bound has the TypeVar's own key).
     */
    public static final class BlockKey {
        private final Object ptr;
        private final int offset;

        public BlockKey(Object ptr, int offset) {
            this.ptr = ptr;
            this.offset = offset;
        }

        /** The key for node itself (C: (void *)node). */
        public BlockKey(Object ptr) {
            this(ptr, 0);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BlockKey && ((BlockKey) o).ptr == ptr
                    && ((BlockKey) o).offset == offset;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ptr) + offset;
        }
    }

    /** An entry of ste_directives: C's (name, lineno, col_offset, end_lineno, end_col_offset). */
    private static final class Directive {
        final String name;
        final SourceLocation loc;

        Directive(String name, SourceLocation loc) {
            this.name = name;
            this.loc = loc;
        }
    }

    /** C: PySTEntryObject, one block of the symbol table. */
    public static final class PySTEntryObject {
        /** key in ste_table.st_blocks */
        public final BlockKey ste_id;
        /** variable names to flags */
        public final Map<String, Integer> ste_symbols = new LinkedHashMap<>();
        /** name of current block */
        public final String ste_name;
        /** for annotation blocks: name of the corresponding functions; may be null */
        public String ste_function_name;
        /** list of function parameters */
        public final List<String> ste_varnames = new ArrayList<>();
        /** list of child blocks */
        public final List<PySTEntryObject> ste_children = new ArrayList<>();
        /** locations of global and nonlocal statements; null until there is one */
        private List<Directive> ste_directives;
        /** set of names for which mangling should be applied; may be null */
        public Set<String> ste_mangled_names;

        public final _Py_block_ty ste_type;
        /**
         * Optional string set by symtable.c and used when reporting errors.
         * The content of that string is a description of the current
         * "context". For instance, if we are processing the default value of
         * the type variable "T" in "def foo[T = int](): pass",
         * ste_scope_info is set to "a TypeVar default".
         */
        public String ste_scope_info;

        /** true if block is nested */
        public boolean ste_nested;
        /** true if namespace is a generator */
        public boolean ste_generator;
        /** true if namespace is a coroutine */
        public boolean ste_coroutine;
        /** true if there are any annotations in this scope */
        public boolean ste_annotations_used;
        /** Kind of comprehension (if any) */
        public _Py_comprehension_ty ste_comprehension = NoComprehension;
        /** true if block has varargs */
        public boolean ste_varargs;
        /** true if block has varkeywords */
        public boolean ste_varkeywords;
        /** true if namespace uses return with an argument */
        public boolean ste_returns_value;
        /** for class scopes, true if a closure over __class__ should be created */
        public boolean ste_needs_class_closure;
        /** for class scopes, true if a closure over the class dict should be created */
        public boolean ste_needs_classdict;
        /** true if this comprehension is inlined */
        public boolean ste_comp_inlined;
        /** true if visiting comprehension target */
        public boolean ste_comp_iter_target;
        /** true if this block can see names bound in an enclosing class scope */
        public boolean ste_can_see_class_scope;
        /** true if docstring present */
        public boolean ste_has_docstring;
        /** true if block is a function block defined in class scope */
        public boolean ste_method;
        /** true if block has conditionally executed annotations */
        public boolean ste_has_conditional_annotations;
        /** set while we are inside a conditionally executed block */
        public boolean ste_in_conditional_block;
        /** set while we are inside a try/except block */
        public boolean ste_in_try_block;
        /** set while we are processing an annotation that will not be evaluated */
        public boolean ste_in_unevaluated_annotation;
        /** non-zero if visiting a comprehension range expression */
        public int ste_comp_iter_expr;
        /** source location of block */
        public final SourceLocation ste_loc;
        /** symbol table entry for this entry's annotations */
        public PySTEntryObject ste_annotation_block;
        public final Symtable ste_table;

        private PySTEntryObject(Symtable ste_table, BlockKey ste_id, String ste_name,
                _Py_block_ty ste_type, SourceLocation ste_loc) {
            this.ste_table = ste_table;
            this.ste_id = ste_id;
            this.ste_name = ste_name;
            this.ste_type = ste_type;
            this.ste_loc = ste_loc;
        }

        /** C: ste_repr */
        @Override
        public String toString() {
            return "<symtable entry " + ste_name + ", line " + ste_loc.lineno + ">";
        }
    }

    /* error strings used for warnings */
    private static final String GLOBAL_PARAM = "name '%s' is parameter and global";

    private static final String NONLOCAL_PARAM = "name '%s' is parameter and nonlocal";

    private static final String GLOBAL_AFTER_ASSIGN =
            "name '%s' is assigned to before global declaration";

    private static final String NONLOCAL_AFTER_ASSIGN =
            "name '%s' is assigned to before nonlocal declaration";

    private static final String GLOBAL_AFTER_USE = "name '%s' is used prior to global declaration";

    private static final String NONLOCAL_AFTER_USE =
            "name '%s' is used prior to nonlocal declaration";

    private static final String GLOBAL_ANNOT = "annotated name '%s' can't be global";

    private static final String NONLOCAL_ANNOT = "annotated name '%s' can't be nonlocal";

    private static final String IMPORT_STAR_WARNING = "import * only allowed at module level";

    private static final String NAMED_EXPR_COMP_IN_CLASS =
            "assignment expression within a comprehension cannot be used in a class body";

    private static final String NAMED_EXPR_COMP_IN_TYPEVAR_BOUND =
            "assignment expression within a comprehension cannot be used in a TypeVar bound";

    private static final String NAMED_EXPR_COMP_IN_TYPEALIAS =
            "assignment expression within a comprehension cannot be used in a type alias";

    private static final String NAMED_EXPR_COMP_IN_TYPEPARAM =
            "assignment expression within a comprehension cannot be used within the definition of a generic";

    private static final String NAMED_EXPR_COMP_CONFLICT =
            "assignment expression cannot rebind comprehension iteration variable '%s'";

    private static final String NAMED_EXPR_COMP_INNER_LOOP_CONFLICT =
            "comprehension inner loop cannot rebind assignment expression target '%s'";

    private static final String NAMED_EXPR_COMP_ITER_EXPR =
            "assignment expression cannot be used in a comprehension iterable expression";

    private static final String ANNOTATION_NOT_ALLOWED = "%s cannot be used within an annotation";

    private static final String EXPR_NOT_ALLOWED_IN_TYPE_VARIABLE = "%s cannot be used within %s";

    private static final String EXPR_NOT_ALLOWED_IN_TYPE_ALIAS =
            "%s cannot be used within a type alias";

    private static final String EXPR_NOT_ALLOWED_IN_TYPE_PARAMETERS =
            "%s cannot be used within the definition of a generic";

    private static final String DUPLICATE_TYPE_PARAM = "duplicate type parameter '%s'";

    private static final String ASYNC_WITH_OUTSIDE_ASYNC_FUNC =
            "'async with' outside async function";

    private static final String ASYNC_FOR_OUTSIDE_ASYNC_FUNC =
            "'async for' outside async function";

    private static final String DUPLICATE_PARAMETER =
            "duplicate parameter '%s' in function definition";

    private static SourceLocation LOCATION(org.python.pegen.ast.Located x) {
        return SRC_LOCATION_FROM_AST(x);
    }

    /**
     * SET_ERROR_LOCATION, with the PyErr_Format before it: the SyntaxError
     * msg at L (C's offsets: 1-based UTF-8 byte offsets), for the caller to
     * throw.
     */
    private static PythonSyntaxError SET_ERROR_LOCATION(String FNAME, SourceLocation L,
            String msg) {
        return Errors._PyErr_RaiseSyntaxError(msg, FNAME, L.lineno, L.col_offset + 1,
                L.end_lineno, L.end_col_offset + 1);
    }

    private boolean IS_ASYNC_DEF() {
        return st_cur.ste_type == FunctionBlock && st_cur.ste_coroutine;
    }

    /* C: struct symtable */
    /** name of file being compiled */
    public String st_filename;
    /** current symbol table entry */
    public PySTEntryObject st_cur;
    /** symbol table entry for module */
    public PySTEntryObject st_top;
    /** map AST node addresses to symbol table entries */
    public final Map<BlockKey, PySTEntryObject> st_blocks = new HashMap<>();
    /** stack of namespace info */
    private final List<PySTEntryObject> st_stack = new ArrayList<>();
    /** st_top.ste_symbols */
    private Map<String, Integer> st_global;
    /** name of current class or null */
    private String st_private;
    /** module's future features that affect the symbol table */
    public Future.FutureFeatures st_future;

    private PySTEntryObject ste_new(String name, _Py_block_ty block, BlockKey key,
            SourceLocation loc) {
        PySTEntryObject ste = new PySTEntryObject(this, key, name, block, loc);

        if (st_cur != null && (st_cur.ste_nested || _PyST_IsFunctionLike(st_cur))) {
            ste.ste_nested = true;
        }

        if (st_cur != null && st_cur.ste_type == ClassBlock && block == FunctionBlock) {
            ste.ste_method = true;
        }

        st_blocks.put(ste.ste_id, ste);
        return ste;
    }

    /** symtable_new */
    private Symtable() {}

    /**
     * _PySymtable_Build: the symbol table of mod. Throws the SyntaxError
     * symtable raises.
     *
     * <p>It runs on a thread with a large stack (LargeStack), as the parser
     * does, since the visit recurses as deep as the tree. A
     * StackOverflowError becomes the RecursionError C raises from
     * Py_EnterRecursiveCall.
     */
    public static Symtable _PySymtable_Build(mod mod, String filename,
            Future.FutureFeatures future) {
        Symtable st = new Symtable();
        if (filename == null) {
            throw new NullPointerException("filename");
        }
        st.st_filename = filename;
        st.st_future = future;
        return LargeStack.call(() -> {
            try {
                st.build(mod);
            } catch (StackOverflowError e) {
                throw new PythonSyntaxError("RecursionError",
                        "Stack overflow during compilation");
            }
            return st;
        });
    }

    /** The body of _PySymtable_Build. */
    private void build(mod mod) {
        List<stmt> seq;

        /* Make the initial symbol information gathering pass */

        SourceLocation loc0 = new SourceLocation(0, 0, 0, 0);
        symtable_enter_block("top", ModuleBlock, new BlockKey(mod), loc0);

        st_top = st_cur;
        if (mod instanceof Module) {
            seq = ((Module) mod).body;
            if (Ast._PyAST_GetDocString(seq) != null) {
                st_cur.ste_has_docstring = true;
            }
            for (int i = 0; i < asdl_seq_LEN(seq); i++) {
                symtable_visit_stmt(seq.get(i));
            }
        } else if (mod instanceof Expression) {
            symtable_visit_expr(((Expression) mod).body);
        } else if (mod instanceof Interactive) {
            seq = ((Interactive) mod).body;
            for (int i = 0; i < asdl_seq_LEN(seq); i++) {
                symtable_visit_stmt(seq.get(i));
            }
        } else {
            // FunctionType
            throw new PythonSyntaxError("RuntimeError",
                    "this compiler does not handle FunctionTypes");
        }
        symtable_exit_block();
        /* Make the second symbol analysis pass */
        symtable_analyze();
    }

    /** _PySymtable_Lookup: the block for key; KeyError (here IllegalStateException) if none. */
    public PySTEntryObject _PySymtable_Lookup(BlockKey key) {
        PySTEntryObject v = st_blocks.get(key);
        if (v == null) {
            throw new IllegalStateException("unknown symbol table entry");
        }
        return v;
    }

    /** _PySymtable_LookupOptional: the block for key, or null. */
    public PySTEntryObject _PySymtable_LookupOptional(BlockKey key) {
        return st_blocks.get(key);
    }

    /** _PyST_GetSymbol: name's flags in ste, 0 if it has none. */
    public static int _PyST_GetSymbol(PySTEntryObject ste, String name) {
        Integer v = ste.ste_symbols.get(name);
        return v == null ? 0 : v;
    }

    /** _PyST_GetScope */
    public static int _PyST_GetScope(PySTEntryObject ste, String name) {
        int symbol = _PyST_GetSymbol(ste, name);
        return SYMBOL_TO_SCOPE(symbol);
    }

    /** _PyST_IsFunctionLike */
    public static boolean _PyST_IsFunctionLike(PySTEntryObject ste) {
        return ste.ste_type == FunctionBlock
                || ste.ste_type == AnnotationBlock
                || ste.ste_type == TypeVariableBlock
                || ste.ste_type == TypeAliasBlock
                || ste.ste_type == TypeParametersBlock;
    }

    /** error_at_directive: msg, located at the directive for name in ste. */
    private static PythonSyntaxError error_at_directive(PySTEntryObject ste, String name,
            String msg) {
        assert ste.ste_directives != null;
        for (Directive data : ste.ste_directives) {
            if (data.name.equals(name)) {
                return SET_ERROR_LOCATION(ste.ste_table.st_filename, data.loc, msg);
            }
        }
        return new PythonSyntaxError("RuntimeError",
                "BUG: internal directive bookkeeping broken");
    }

    /* Analyze raw symbol information to determine scope of each name.

       The next several functions are helpers for symtable_analyze(),
       which determines whether a name is local, global, or free.  In addition,
       it determines which local variables are cell variables; they provide
       bindings that are used for free variables in enclosed blocks.

       There are also two kinds of global variables, implicit and explicit.  An
       explicit global is declared with the global statement.  An implicit
       global is a free variable for which the compiler has found no binding
       in an enclosing function scope.  The implicit global is either a global
       or a builtin.  Python's module and class blocks use the xxx_NAME opcodes
       to handle these names to implement slightly odd semantics.  In such a
       block, the name is treated as global until it is assigned to; then it
       is treated as a local.

       The symbol table requires two passes to determine the scope of each name.
       The first pass collects raw facts from the AST via the symtable_visit_*
       functions: the name is a parameter here, the name is used but not defined
       here, etc.  The second pass analyzes these facts during a pass over the
       PySTEntryObjects created during pass 1.

       When a function is entered during the second pass, the parent passes
       the set of all name bindings visible to its children.  These bindings
       are used to determine if non-local variables are free or implicit globals.
       Names which are explicitly declared nonlocal must exist in this set of
       visible names - if they do not, a syntax error is raised. After doing
       the local analysis, it analyzes each of its child blocks using an
       updated set of name bindings.

       The children update the free variable set.  If a local variable is added to
       the free variable set by the child, the variable is marked as a cell.  The
       function object being defined must provide runtime storage for the variable
       that may outlive the function's frame.  Cell variables are removed from the
       free set before the analyze function returns to its parent.

       During analysis, the names are:
          symbols: dict mapping from symbol names to flag values (including offset scope values)
          scopes: dict mapping from symbol names to scope values (no offset)
          local: set of all symbol names local to the current scope
          bound: set of all symbol names local to a containing function scope
          free: set of all symbol names referenced but not bound in child scopes
          global: set of all symbol names explicitly declared as global
    */

    /* Decide on scope of name, given flags.

       The namespace dictionaries may be modified to record information
       about the new name.  For example, a new global will add an entry to
       global.  A name that was global can be changed to local.
    */
    private static void analyze_name(PySTEntryObject ste, Map<String, Integer> scopes,
            String name, int flags, Set<String> bound, Set<String> local, Set<String> free,
            Set<String> global, Set<String> type_params, PySTEntryObject class_entry) {
        if ((flags & DEF_GLOBAL) != 0) {
            if ((flags & DEF_NONLOCAL) != 0) {
                throw error_at_directive(ste, name,
                        String.format("name '%s' is nonlocal and global", name));
            }
            scopes.put(name, GLOBAL_EXPLICIT);
            global.add(name);
            if (bound != null) {
                bound.remove(name);
            }
            return;
        }
        if ((flags & DEF_NONLOCAL) != 0) {
            if (bound == null) {
                throw error_at_directive(ste, name,
                        "nonlocal declaration not allowed at module level");
            }
            if (!bound.contains(name)) {
                throw error_at_directive(ste, name,
                        String.format("no binding for nonlocal '%s' found", name));
            }
            if (type_params.contains(name)) {
                throw error_at_directive(ste, name, String.format(
                        "nonlocal binding not allowed for type parameter '%s'", name));
            }
            scopes.put(name, FREE);
            free.add(name);
            return;
        }
        if ((flags & DEF_BOUND) != 0) {
            scopes.put(name, LOCAL);
            local.add(name);
            global.remove(name);
            if ((flags & DEF_TYPE_PARAM) != 0) {
                type_params.add(name);
            } else {
                type_params.remove(name);
            }
            return;
        }
        // If we were passed class_entry (i.e., we're in an ste_can_see_class_scope scope)
        // and the bound name is in that set, then the name is potentially bound both by
        // the immediately enclosing class namespace, and also by an outer function namespace.
        // In that case, we want the runtime name resolution to look at only the class
        // namespace and the globals (not the namespace providing the bound).
        // Similarly, if the name is explicitly global in the class namespace (through the
        // global statement), we want to also treat it as a global in this scope.
        if (class_entry != null) {
            int class_flags = _PyST_GetSymbol(class_entry, name);
            if ((class_flags & DEF_GLOBAL) != 0) {
                scopes.put(name, GLOBAL_EXPLICIT);
                return;
            } else if ((class_flags & DEF_BOUND) != 0 && (class_flags & DEF_NONLOCAL) == 0) {
                scopes.put(name, GLOBAL_IMPLICIT);
                return;
            }
        }
        /* If an enclosing block has a binding for this name, it
           is a free variable rather than a global variable.
           Note that having a non-NULL bound implies that the block
           is nested.
        */
        if (bound != null && bound.contains(name)) {
            scopes.put(name, FREE);
            free.add(name);
            return;
        }
        /* If a parent has a global statement, then call it global
           explicit?  It could also be global implicit.
         */
        if (global != null && global.contains(name)) {
            scopes.put(name, GLOBAL_IMPLICIT);
            return;
        }
        scopes.put(name, GLOBAL_IMPLICIT);
    }

    private static boolean is_free_in_any_child(PySTEntryObject entry, String key) {
        for (PySTEntryObject child_ste : entry.ste_children) {
            int scope = _PyST_GetScope(child_ste, key);
            if (scope == FREE) {
                return true;
            }
        }
        return false;
    }

    private static void inline_comprehension(PySTEntryObject ste, PySTEntryObject comp,
            Map<String, Integer> scopes, Set<String> comp_free, Set<String> inlined_cells) {
        boolean remove_dunder_class = false;
        boolean remove_dunder_classdict = false;
        boolean remove_dunder_cond_annotations = false;

        for (Map.Entry<String, Integer> item : comp.ste_symbols.entrySet()) {
            String k = item.getKey();
            // skip comprehension parameter
            int comp_flags = item.getValue();
            if ((comp_flags & DEF_PARAM) != 0) {
                assert k.equals(".0");
                continue;
            }
            int scope = SYMBOL_TO_SCOPE(comp_flags);
            int only_flags = comp_flags & ((1 << SCOPE_OFFSET) - 1);
            if (scope == CELL || (only_flags & DEF_COMP_CELL) != 0) {
                inlined_cells.add(k);
            }
            Integer existing = ste.ste_symbols.get(k);
            // __class__, __classdict__ and __conditional_annotations__ are
            // not allowed to be free through a class scope (see
            // drop_class_free) unless children scopes need it
            if (scope == FREE && ste.ste_type == ClassBlock
                    && (k.equals("__class__") || k.equals("__classdict__")
                            || k.equals("__conditional_annotations__"))) {
                scope = GLOBAL_IMPLICIT;
                if (!is_free_in_any_child(comp, k)) {
                    comp_free.remove(k);
                }
                if (k.equals("__class__")) {
                    remove_dunder_class = true;
                } else if (k.equals("__conditional_annotations__")) {
                    remove_dunder_cond_annotations = true;
                } else {
                    remove_dunder_classdict = true;
                }
            }
            if (existing == null) {
                // name does not exist in scope, copy from comprehension
                assert scope != FREE || comp_free.contains(k);
                ste.ste_symbols.put(k, only_flags);
                scopes.put(k, scope);
            } else {
                int flags = existing;
                if ((flags & DEF_BOUND) != 0 && ste.ste_type != ClassBlock) {
                    // free vars in comprehension that are locals in outer scope can
                    // now simply be locals, unless they are free in comp children,
                    // or if the outer scope is a class block
                    if (!is_free_in_any_child(comp, k)) {
                        comp_free.remove(k);
                    }
                }
            }
        }
        if (remove_dunder_class) {
            comp.ste_symbols.remove("__class__");
        }
        if (remove_dunder_classdict) {
            comp.ste_symbols.remove("__classdict__");
        }
        if (remove_dunder_cond_annotations) {
            comp.ste_symbols.remove("__conditional_annotations__");
        }
    }

    /* If a name is defined in free and also in locals, then this block
       provides the binding for the free variable.  The name should be
       marked CELL in this block and removed from the free list.

       Note that the current block's free variables are included in free.
       That's safe because no name can be free and local in the same scope.
    */
    private static void analyze_cells(Map<String, Integer> scopes, Set<String> free,
            Set<String> inlined_cells) {
        for (Map.Entry<String, Integer> item : scopes.entrySet()) {
            String name = item.getKey();
            int scope = item.getValue();
            if (scope != LOCAL) {
                continue;
            }
            if (!free.contains(name) && !inlined_cells.contains(name)) {
                continue;
            }
            /* Replace LOCAL with CELL for this name, and remove
               from free. It is safe to replace the value of name
               in the dict, because it will not cause a resize.
             */
            item.setValue(CELL);
            free.remove(name);
        }
    }

    private static void drop_class_free(PySTEntryObject ste, Set<String> free) {
        if (free.remove("__class__")) {
            ste.ste_needs_class_closure = true;
        }
        if (free.remove("__classdict__")) {
            ste.ste_needs_classdict = true;
        }
        if (free.remove("__conditional_annotations__")) {
            ste.ste_has_conditional_annotations = true;
        }
    }

    /* Enter the final scope information into the ste_symbols dict.
     *
     * All arguments are dicts.  Modifies symbols, others are read-only.
    */
    private static void update_symbols(Map<String, Integer> symbols, Map<String, Integer> scopes,
            Set<String> bound, Set<String> free, Set<String> inlined_cells, boolean classflag) {
        /* Update scope information for all symbols in this scope */
        for (Map.Entry<String, Integer> item : symbols.entrySet()) {
            String name = item.getKey();
            int flags = item.getValue();
            if (inlined_cells.contains(name)) {
                flags |= DEF_COMP_CELL;
            }
            Integer v_scope = scopes.get(name);
            if (v_scope == null) {
                throw new IllegalStateException("KeyError: " + name);
            }
            flags |= (v_scope << SCOPE_OFFSET);
            item.setValue(flags);
        }

        /* Record not yet resolved free variables from children (if any) */
        int v_free = FREE << SCOPE_OFFSET;

        for (String name : free) {
            Integer v = symbols.get(name);

            /* Handle symbol that already exists in this scope */
            if (v != null) {
                /* Handle a free variable in a method of
                   the class that has the same name as a local
                   or global in the class scope.
                */
                if (classflag) {
                    symbols.put(name, v | DEF_FREE_CLASS);
                }
                /* It's a cell, or already free in this scope */
                continue;
            }
            /* Handle global symbol */
            if (bound != null && !bound.contains(name)) {
                continue;       /* it's a global */
            }
            /* Propagate new free symbol up the lexical stack */
            symbols.put(name, v_free);
        }
    }

    /* Make final symbol table decisions for block of ste.

       Arguments:
       ste -- current symtable entry (input/output)
       bound -- set of variables bound in enclosing scopes (input).  bound
           is NULL for module blocks.
       free -- set of free variables in enclosed scopes (output)
       globals -- set of declared global variables in enclosing scopes (input)

       The implementation uses two mutually recursive functions,
       analyze_block() and analyze_child_block().  analyze_block() is
       responsible for analyzing the individual names defined in a block.
       analyze_child_block() prepares temporary namespace dictionaries
       used to evaluated nested blocks.

       The two functions exist because a child block should see the name
       bindings of its enclosing blocks, but those bindings should not
       propagate back to a parent block.
    */
    private static void analyze_block(PySTEntryObject ste, Set<String> bound, Set<String> free,
            Set<String> global, Set<String> type_params, PySTEntryObject class_entry) {
        /* collect new names bound in block */
        Set<String> local = new HashSet<>();
        /* collect scopes defined for each name */
        Map<String, Integer> scopes = new LinkedHashMap<>();

        /* Allocate new global, bound and free variable sets.  These
           sets hold the names visible in nested blocks.  For
           ClassBlocks, the bound and global names are initialized
           before analyzing names, because class bindings aren't
           visible in methods.  For other blocks, they are initialized
           after names are analyzed.
         */
        Set<String> newglobal = new HashSet<>();
        Set<String> newfree = new HashSet<>();
        Set<String> newbound = new HashSet<>();
        Set<String> inlined_cells = new HashSet<>();

        /* Class namespace has no effect on names visible in
           nested functions, so populate the global and bound
           sets to be passed to child blocks before analyzing
           this one.
         */
        if (ste.ste_type == ClassBlock) {
            /* Pass down known globals */
            newglobal.addAll(global);
            /* Pass down previously bound symbols */
            if (bound != null) {
                newbound.addAll(bound);
            }
        }

        for (Map.Entry<String, Integer> item : ste.ste_symbols.entrySet()) {
            analyze_name(ste, scopes, item.getKey(), item.getValue(), bound, local, free, global,
                    type_params, class_entry);
        }

        /* Populate global and bound sets to be passed to children. */
        if (ste.ste_type != ClassBlock) {
            /* Add function locals to bound set */
            if (_PyST_IsFunctionLike(ste)) {
                newbound.addAll(local);
            }
            /* Pass down previously bound symbols */
            if (bound != null) {
                newbound.addAll(bound);
            }
            /* Pass down known globals */
            newglobal.addAll(global);
        } else {
            /* Special-case __class__ and __classdict__ */
            newbound.add("__class__");
            newbound.add("__classdict__");
            newbound.add("__conditional_annotations__");
        }

        /* Recursively call analyze_child_block() on each child block.

           newbound, newglobal now contain the names visible in
           nested blocks.  The free variables in the children will
           be added to newfree.
        */
        for (int i = 0; i < ste.ste_children.size(); ++i) {
            PySTEntryObject entry = ste.ste_children.get(i);

            PySTEntryObject new_class_entry = null;
            if (entry.ste_can_see_class_scope) {
                if (ste.ste_type == ClassBlock) {
                    new_class_entry = ste;
                } else if (class_entry != null) {
                    new_class_entry = class_entry;
                }
            }

            // we inline all non-generator-expression comprehensions,
            // except those in annotation scopes that are nested in classes
            boolean inline_comp = entry.ste_comprehension != NoComprehension
                    && !entry.ste_generator
                    && !ste.ste_can_see_class_scope;

            Set<String> child_free = analyze_child_block(entry, newbound, newfree, newglobal,
                    type_params, new_class_entry);
            if (inline_comp) {
                inline_comprehension(ste, entry, scopes, child_free, inlined_cells);
                entry.ste_comp_inlined = true;
            }
            newfree.addAll(child_free);
        }

        /* Splice children of inlined comprehensions into our children list */
        for (int i = ste.ste_children.size() - 1; i >= 0; --i) {
            PySTEntryObject entry = ste.ste_children.get(i);
            if (entry.ste_comp_inlined) {
                ste.ste_children.remove(i);
                ste.ste_children.addAll(i, entry.ste_children);
            }
        }

        /* Check if any local variables must be converted to cell variables */
        if (_PyST_IsFunctionLike(ste)) {
            analyze_cells(scopes, newfree, inlined_cells);
        } else if (ste.ste_type == ClassBlock) {
            drop_class_free(ste, newfree);
        }
        /* Records the results of the analysis in the symbol table entry */
        update_symbols(ste.ste_symbols, scopes, bound, newfree, inlined_cells,
                (ste.ste_type == ClassBlock) || ste.ste_can_see_class_scope);

        free.addAll(newfree);
    }

    /** analyze_child_block: returns C's *child_free. */
    private static Set<String> analyze_child_block(PySTEntryObject entry, Set<String> bound,
            Set<String> free, Set<String> global, Set<String> type_params,
            PySTEntryObject class_entry) {
        /* Copy the bound/global/free sets.

           These sets are used by all blocks enclosed by the
           current block.  The analyze_block() call modifies these
           sets.

        */
        Set<String> temp_bound = new HashSet<>(bound);
        Set<String> temp_free = new HashSet<>(free);
        Set<String> temp_global = new HashSet<>(global);
        Set<String> temp_type_params = new HashSet<>(type_params);

        analyze_block(entry, temp_bound, temp_free, temp_global, temp_type_params, class_entry);
        return temp_free;
    }

    private void symtable_analyze() {
        Set<String> free = new HashSet<>();
        Set<String> global = new HashSet<>();
        Set<String> type_params = new HashSet<>();
        analyze_block(st_top, null, free, global, type_params, null);
    }

    private void symtable_exit_block() {
        st_cur = null;
        int size = st_stack.size();
        if (size != 0) {
            st_stack.remove(size - 1);
            if (--size != 0) {
                st_cur = st_stack.get(size - 1);
            }
        }
    }

    private void symtable_enter_existing_block(PySTEntryObject ste, boolean add_to_children) {
        st_stack.add(ste);
        PySTEntryObject prev = st_cur;
        /* bpo-37757: For now, disallow *all* assignment expressions in the
         * outermost iterator expression of a comprehension, even those inside
         * a nested comprehension or a lambda expression.
         */
        if (prev != null) {
            ste.ste_comp_iter_expr = prev.ste_comp_iter_expr;
        }
        /* No need to inherit ste_mangled_names in classes, where all names
         * are mangled. */
        if (prev != null && prev.ste_mangled_names != null && ste.ste_type != ClassBlock) {
            ste.ste_mangled_names = prev.ste_mangled_names;
        }
        st_cur = ste;

        /* If "from __future__ import annotations" is active,
         * annotation blocks shouldn't have any affect on the symbol table since in
         * the compilation stage, they will all be transformed to strings. */
        if ((st_future.ff_features & CO_FUTURE_ANNOTATIONS) != 0
                && ste.ste_type == AnnotationBlock) {
            return;
        }

        if (ste.ste_type == ModuleBlock) {
            st_global = st_cur.ste_symbols;
        }

        if (add_to_children && prev != null) {
            prev.ste_children.add(ste);
        }
    }

    private void symtable_enter_block(String name, _Py_block_ty block, BlockKey ast,
            SourceLocation loc) {
        PySTEntryObject ste = ste_new(name, block, ast, loc);
        symtable_enter_existing_block(ste, /* add_to_children */true);
        if (block == AnnotationBlock || block == TypeVariableBlock || block == TypeAliasBlock) {
            // We need to insert code that reads this "parameter" to the function.
            symtable_add_def(".format", DEF_PARAM, loc);
            symtable_add_def(".format", USE, loc);
        }
    }

    private int symtable_lookup_entry(PySTEntryObject ste, String name) {
        String mangled = _Py_MaybeMangle(st_private, ste, name);
        return _PyST_GetSymbol(ste, mangled);
    }

    private int symtable_lookup(String name) {
        return symtable_lookup_entry(st_cur, name);
    }

    private void symtable_add_def_helper(String name, int flag, PySTEntryObject ste,
            SourceLocation loc) {
        String mangled = _Py_MaybeMangle(st_private, st_cur, name);
        Map<String, Integer> dict = ste.ste_symbols;
        int val;
        Integer o = dict.get(mangled);
        if (o != null) {
            val = o;
            if ((flag & DEF_PARAM) != 0 && (val & DEF_PARAM) != 0) {
                /* Is it better to use 'mangled' or 'name' here? */
                throw SET_ERROR_LOCATION(st_filename, loc,
                        String.format(DUPLICATE_PARAMETER, name));
            }
            if ((flag & DEF_TYPE_PARAM) != 0 && (val & DEF_TYPE_PARAM) != 0) {
                throw SET_ERROR_LOCATION(st_filename, loc,
                        String.format(DUPLICATE_TYPE_PARAM, name));
            }
            val |= flag;
        } else {
            val = flag;
        }
        if (ste.ste_comp_iter_target) {
            /* This name is an iteration variable in a comprehension,
             * so check for a binding conflict with any named expressions.
             * Otherwise, mark it as an iteration variable so subsequent
             * named expressions can check for conflicts.
             */
            if ((val & (DEF_GLOBAL | DEF_NONLOCAL)) != 0) {
                throw SET_ERROR_LOCATION(st_filename, loc,
                        String.format(NAMED_EXPR_COMP_INNER_LOOP_CONFLICT, name));
            }
            val |= DEF_COMP_ITER;
        }
        dict.put(mangled, val);

        if ((flag & DEF_PARAM) != 0) {
            ste.ste_varnames.add(mangled);
        } else if ((flag & DEF_GLOBAL) != 0) {
            /* XXX need to update DEF_GLOBAL for other flags too;
               perhaps only DEF_FREE_GLOBAL */
            val = 0;
            if ((o = st_global.get(mangled)) != null) {
                val = o;
            }
            val |= flag;
            st_global.put(mangled, val);
        }
    }

    private void check_name(String name, SourceLocation loc, expr_contextType ctx) {
        if (ctx == expr_contextType.Store && name.equals("__debug__")) {
            throw SET_ERROR_LOCATION(st_filename, loc, "cannot assign to __debug__");
        }
        if (ctx == expr_contextType.Del && name.equals("__debug__")) {
            throw SET_ERROR_LOCATION(st_filename, loc, "cannot delete __debug__");
        }
    }

    private void check_keywords(List<keyword> keywords) {
        for (int i = 0; i < asdl_seq_LEN(keywords); i++) {
            keyword key = keywords.get(i);
            if (key.arg != null) {
                check_name(key.arg, LOCATION(key), expr_contextType.Store);
            }
        }
    }

    private void check_kwd_patterns(MatchClass p) {
        List<String> kwd_attrs = p.kwd_attrs;
        List<pattern> kwd_patterns = p.kwd_patterns;
        for (int i = 0; i < asdl_seq_LEN(kwd_attrs); i++) {
            SourceLocation loc = LOCATION(kwd_patterns.get(i));
            check_name(kwd_attrs.get(i), loc, expr_contextType.Store);
        }
    }

    private void symtable_add_def_ctx(String name, int flag, SourceLocation loc,
            expr_contextType ctx) {
        int write_mask = DEF_PARAM | DEF_LOCAL | DEF_IMPORT;
        if ((flag & write_mask) != 0) {
            check_name(name, loc, ctx);
        }
        if ((flag & DEF_TYPE_PARAM) != 0 && st_cur.ste_mangled_names != null) {
            st_cur.ste_mangled_names.add(name);
        }
        symtable_add_def_helper(name, flag, st_cur, loc);
    }

    private void symtable_add_def(String name, int flag, SourceLocation loc) {
        symtable_add_def_ctx(name, flag, loc,
                flag == USE ? expr_contextType.Load : expr_contextType.Store);
    }

    private void symtable_enter_type_param_block(String name, Object ast, boolean has_defaults,
            boolean has_kwdefaults, stmt.Kind kind, SourceLocation loc) {
        _Py_block_ty current_type = st_cur.ste_type;
        symtable_enter_block(name, TypeParametersBlock, new BlockKey(ast), loc);
        if (current_type == ClassBlock) {
            st_cur.ste_can_see_class_scope = true;
            symtable_add_def("__classdict__", USE, loc);
        }
        if (kind == stmt.Kind.ClassDef) {
            // It gets "set" when we create the type params tuple and
            // "used" when we build up the bases.
            symtable_add_def(".type_params", DEF_LOCAL, loc);
            symtable_add_def(".type_params", USE, loc);
            // This is used for setting the generic base
            symtable_add_def(".generic_base", DEF_LOCAL, loc);
            symtable_add_def(".generic_base", USE, loc);
        }
        if (has_defaults) {
            symtable_add_def(".defaults", DEF_PARAM, loc);
        }
        if (has_kwdefaults) {
            symtable_add_def(".kwdefaults", DEF_PARAM, loc);
        }
    }

    /*
     * VISIT, VISIT_SEQ and VISIT_SEQ_TAIL become calls and loops.
     * VISIT_SEQ_WITH_NULL skips null elements.
     */

    private void symtable_visit_stmt_seq(List<stmt> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_stmt(seq.get(i));
        }
    }

    private void symtable_visit_expr_seq(List<expr> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_expr(seq.get(i));
        }
    }

    /** VISIT_SEQ_WITH_NULL(st, expr, seq) */
    private void symtable_visit_expr_seq_with_null(List<expr> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            expr elt = seq.get(i);
            if (elt == null) {
                continue; /* can be NULL */
            }
            symtable_visit_expr(elt);
        }
    }

    /*
     * ENTER_CONDITIONAL_BLOCK and LEAVE_CONDITIONAL_BLOCK, ENTER_TRY_BLOCK and
     * LEAVE_TRY_BLOCK are written out where they are used. ENTER_RECURSIVE
     * and LEAVE_RECURSIVE have no counterpart: running out of stack is
     * caught in _PySymtable_Build.
     */

    private void symtable_record_directive(String name, SourceLocation loc) {
        if (st_cur.ste_directives == null) {
            st_cur.ste_directives = new ArrayList<>();
        }
        String mangled = _Py_MaybeMangle(st_private, st_cur, name);
        st_cur.ste_directives.add(new Directive(mangled, loc));
    }

    private static boolean has_kwonlydefaults(List<arg> kwonlyargs, List<expr> kw_defaults) {
        for (int i = 0; i < asdl_seq_LEN(kwonlyargs); i++) {
            expr default_ = kw_defaults.get(i);
            if (default_ != null) {
                return true;
            }
        }
        return false;
    }

    private void check_import_from(ImportFrom s) {
        SourceLocation fut = st_future.ff_location;
        if (s.module != null && s.level == 0 && s.module.equals("__future__")
                && ((s.lineno > fut.lineno)
                        || ((s.lineno == fut.end_lineno) && (s.col_offset > fut.end_col_offset)))) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                    "from __future__ imports must occur at the beginning of the file");
        }
    }

    private void check_lazy_import_context(stmt s, String import_type) {
        // Check if inside try/except block.
        if (st_cur.ste_in_try_block) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                    String.format("lazy %s not allowed inside try/except blocks", import_type));
        }

        // Check if inside function scope.
        if (st_cur.ste_type == FunctionBlock) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                    String.format("lazy %s not allowed inside functions", import_type));
        }

        // Check if inside class scope.
        if (st_cur.ste_type == ClassBlock) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                    String.format("lazy %s not allowed inside classes", import_type));
        }
    }

    private boolean allows_top_level_await() {
        return (st_future.ff_features & PyCF_ALLOW_TOP_LEVEL_AWAIT) != 0
                && st_cur.ste_type == ModuleBlock;
    }

    private void maybe_set_ste_coroutine_for_module(stmt s) {
        if (allows_top_level_await()) {
            st_cur.ste_coroutine = true;
        }
    }

    private void symtable_visit_stmt(stmt s) {
        switch (s.kind()) {
            case FunctionDef: {
                FunctionDef f = (FunctionDef) s;
                symtable_add_def(f.name, DEF_LOCAL, LOCATION(s));
                if (f.args.defaults != null) {
                    symtable_visit_expr_seq(f.args.defaults);
                }
                if (f.args.kw_defaults != null) {
                    symtable_visit_expr_seq_with_null(f.args.kw_defaults);
                }
                if (f.decorator_list != null) {
                    symtable_visit_expr_seq(f.decorator_list);
                }
                if (asdl_seq_LEN(f.type_params) > 0) {
                    symtable_enter_type_param_block(f.name, f.type_params,
                            f.args.defaults != null,
                            has_kwonlydefaults(f.args.kwonlyargs, f.args.kw_defaults),
                            s.kind(), LOCATION(s));
                    symtable_visit_type_param_seq(f.type_params);
                }
                PySTEntryObject new_ste = ste_new(f.name, FunctionBlock, new BlockKey(s),
                        LOCATION(s));

                if (Ast._PyAST_GetDocString(f.body) != null) {
                    new_ste.ste_has_docstring = true;
                }

                symtable_visit_annotations(s, f.args, f.returns, new_ste);
                symtable_enter_existing_block(new_ste, /* add_to_children */true);
                symtable_visit_arguments(f.args);
                symtable_visit_stmt_seq(f.body);
                symtable_exit_block();
                if (asdl_seq_LEN(f.type_params) > 0) {
                    symtable_exit_block();
                }
                break;
            }
            case ClassDef: {
                ClassDef c = (ClassDef) s;
                String tmp;
                symtable_add_def(c.name, DEF_LOCAL, LOCATION(s));
                if (c.decorator_list != null) {
                    symtable_visit_expr_seq(c.decorator_list);
                }
                tmp = st_private;
                if (asdl_seq_LEN(c.type_params) > 0) {
                    symtable_enter_type_param_block(c.name, c.type_params, false, false,
                            s.kind(), LOCATION(s));
                    st_private = c.name;
                    st_cur.ste_mangled_names = new HashSet<>();
                    symtable_visit_type_param_seq(c.type_params);
                }
                symtable_visit_expr_seq(c.bases);
                check_keywords(c.keywords);
                symtable_visit_keyword_seq(c.keywords);
                symtable_enter_block(c.name, ClassBlock, new BlockKey(s), LOCATION(s));
                st_private = c.name;
                if (asdl_seq_LEN(c.type_params) > 0) {
                    symtable_add_def("__type_params__", DEF_LOCAL, LOCATION(s));
                    symtable_add_def(".type_params", USE, LOCATION(s));
                }

                if (Ast._PyAST_GetDocString(c.body) != null) {
                    st_cur.ste_has_docstring = true;
                }

                symtable_visit_stmt_seq(c.body);
                symtable_exit_block();
                if (asdl_seq_LEN(c.type_params) > 0) {
                    symtable_exit_block();
                }
                st_private = tmp;
                break;
            }
            case TypeAlias: {
                TypeAlias t = (TypeAlias) s;
                symtable_visit_expr(t.name);
                assert t.name.kind() == expr.Kind.Name;
                String name = ((Name) t.name).id;
                boolean is_in_class = st_cur.ste_type == ClassBlock;
                boolean is_generic = asdl_seq_LEN(t.type_params) > 0;
                if (is_generic) {
                    symtable_enter_type_param_block(name, t.type_params, false, false, s.kind(),
                            LOCATION(s));
                    symtable_visit_type_param_seq(t.type_params);
                }
                symtable_enter_block(name, TypeAliasBlock, new BlockKey(s), LOCATION(s));
                st_cur.ste_can_see_class_scope = is_in_class;
                if (is_in_class) {
                    symtable_add_def("__classdict__", USE, LOCATION(t.value));
                }
                symtable_visit_expr(t.value);
                symtable_exit_block();
                if (is_generic) {
                    symtable_exit_block();
                }
                break;
            }
            case Return: {
                Return r = (Return) s;
                if (r.value != null) {
                    symtable_visit_expr(r.value);
                    st_cur.ste_returns_value = true;
                }
                break;
            }
            case Delete:
                symtable_visit_expr_seq(((Delete) s).targets);
                break;
            case Assign:
                symtable_visit_expr_seq(((Assign) s).targets);
                symtable_visit_expr(((Assign) s).value);
                break;
            case AnnAssign: {
                AnnAssign a = (AnnAssign) s;
                st_cur.ste_annotations_used = true;
                if (a.target.kind() == expr.Kind.Name) {
                    Name e_name = (Name) a.target;
                    int cur = symtable_lookup(e_name.id);
                    if ((cur & (DEF_GLOBAL | DEF_NONLOCAL)) != 0
                            && (st_cur.ste_symbols != st_global)
                            && a.simple != 0) {
                        throw SET_ERROR_LOCATION(st_filename, LOCATION(s), String.format(
                                (cur & DEF_GLOBAL) != 0 ? GLOBAL_ANNOT : NONLOCAL_ANNOT,
                                e_name.id));
                    }
                    if (a.simple != 0) {
                        symtable_add_def(e_name.id, DEF_ANNOT | DEF_LOCAL, LOCATION(e_name));
                    } else {
                        if (a.value != null) {
                            symtable_add_def(e_name.id, DEF_LOCAL, LOCATION(e_name));
                        }
                    }
                } else {
                    symtable_visit_expr(a.target);
                }
                symtable_visit_annotation(a.annotation, new BlockKey(st_cur.ste_id, 1));

                if (a.value != null) {
                    symtable_visit_expr(a.value);
                }
                break;
            }
            case AugAssign: {
                symtable_visit_expr(((AugAssign) s).target);
                symtable_visit_expr(((AugAssign) s).value);
                break;
            }
            case For: {
                For f = (For) s;
                symtable_visit_expr(f.target);
                symtable_visit_expr(f.iter);
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_stmt_seq(f.body);
                if (f.orelse != null) {
                    symtable_visit_stmt_seq(f.orelse);
                }
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case While: {
                While w = (While) s;
                symtable_visit_expr(w.test);
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_stmt_seq(w.body);
                if (w.orelse != null) {
                    symtable_visit_stmt_seq(w.orelse);
                }
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case If: {
                If i = (If) s;
                /* XXX if 0: and lookup_yield() hacks */
                symtable_visit_expr(i.test);
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_stmt_seq(i.body);
                if (i.orelse != null) {
                    symtable_visit_stmt_seq(i.orelse);
                }
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case Match: {
                Match m = (Match) s;
                symtable_visit_expr(m.subject);
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                for (int i = 0; i < asdl_seq_LEN(m.cases); i++) {
                    symtable_visit_match_case(m.cases.get(i));
                }
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case Raise: {
                Raise r = (Raise) s;
                if (r.exc != null) {
                    symtable_visit_expr(r.exc);
                    if (r.cause != null) {
                        symtable_visit_expr(r.cause);
                    }
                }
                break;
            }
            case Try: {
                Try t = (Try) s;
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                boolean in_try_block = st_cur.ste_in_try_block;
                st_cur.ste_in_try_block = true;
                symtable_visit_stmt_seq(t.body);
                symtable_visit_excepthandler_seq(t.handlers);
                symtable_visit_stmt_seq(t.orelse);
                symtable_visit_stmt_seq(t.finalbody);
                st_cur.ste_in_try_block = in_try_block;
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case TryStar: {
                TryStar t = (TryStar) s;
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                boolean in_try_block = st_cur.ste_in_try_block;
                st_cur.ste_in_try_block = true;
                symtable_visit_stmt_seq(t.body);
                symtable_visit_excepthandler_seq(t.handlers);
                symtable_visit_stmt_seq(t.orelse);
                symtable_visit_stmt_seq(t.finalbody);
                st_cur.ste_in_try_block = in_try_block;
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case Assert: {
                Assert a = (Assert) s;
                symtable_visit_expr(a.test);
                if (a.msg != null) {
                    symtable_visit_expr(a.msg);
                }
                break;
            }
            case Import: {
                Import i = (Import) s;
                if (i.is_lazy != 0) {
                    check_lazy_import_context(s, "import");
                }
                symtable_visit_alias_seq(i.names);
                break;
            }
            case ImportFrom: {
                ImportFrom i = (ImportFrom) s;
                if (i.is_lazy != 0) {
                    check_lazy_import_context(s, "from ... import");

                    // Check for import *
                    for (int j = 0; j < asdl_seq_LEN(i.names); j++) {
                        alias alias = i.names.get(j);
                        if (alias.name != null && alias.name.equals("*")) {
                            throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                                    "lazy from ... import * is not allowed");
                        }
                    }
                }
                symtable_visit_alias_seq(i.names);
                check_import_from(i);
                break;
            }
            case Global: {
                List<String> seq = ((Global) s).names;
                for (int i = 0; i < asdl_seq_LEN(seq); i++) {
                    String name = seq.get(i);
                    int cur = symtable_lookup(name);
                    if ((cur & (DEF_PARAM | DEF_LOCAL | USE | DEF_ANNOT)) != 0) {
                        String msg;
                        if ((cur & DEF_PARAM) != 0) {
                            msg = GLOBAL_PARAM;
                        } else if ((cur & USE) != 0) {
                            msg = GLOBAL_AFTER_USE;
                        } else if ((cur & DEF_ANNOT) != 0) {
                            msg = GLOBAL_ANNOT;
                        } else {  /* DEF_LOCAL */
                            msg = GLOBAL_AFTER_ASSIGN;
                        }
                        throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                                String.format(msg, name));
                    }
                    symtable_add_def(name, DEF_GLOBAL, LOCATION(s));
                    symtable_record_directive(name, LOCATION(s));
                }
                break;
            }
            case Nonlocal: {
                List<String> seq = ((Nonlocal) s).names;
                for (int i = 0; i < asdl_seq_LEN(seq); i++) {
                    String name = seq.get(i);
                    int cur = symtable_lookup(name);
                    if ((cur & (DEF_PARAM | DEF_LOCAL | USE | DEF_ANNOT)) != 0) {
                        String msg;
                        if ((cur & DEF_PARAM) != 0) {
                            msg = NONLOCAL_PARAM;
                        } else if ((cur & USE) != 0) {
                            msg = NONLOCAL_AFTER_USE;
                        } else if ((cur & DEF_ANNOT) != 0) {
                            msg = NONLOCAL_ANNOT;
                        } else {  /* DEF_LOCAL */
                            msg = NONLOCAL_AFTER_ASSIGN;
                        }
                        throw SET_ERROR_LOCATION(st_filename, LOCATION(s),
                                String.format(msg, name));
                    }
                    symtable_add_def(name, DEF_NONLOCAL, LOCATION(s));
                    symtable_record_directive(name, LOCATION(s));
                }
                break;
            }
            case Expr:
                symtable_visit_expr(((Expr) s).value);
                break;
            case Pass:
            case Break:
            case Continue:
                /* nothing to do here */
                break;
            case With: {
                With w = (With) s;
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_withitem_seq(w.items);
                symtable_visit_stmt_seq(w.body);
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case AsyncFunctionDef: {
                AsyncFunctionDef f = (AsyncFunctionDef) s;
                symtable_add_def(f.name, DEF_LOCAL, LOCATION(s));
                if (f.args.defaults != null) {
                    symtable_visit_expr_seq(f.args.defaults);
                }
                if (f.args.kw_defaults != null) {
                    symtable_visit_expr_seq_with_null(f.args.kw_defaults);
                }
                if (f.decorator_list != null) {
                    symtable_visit_expr_seq(f.decorator_list);
                }
                if (asdl_seq_LEN(f.type_params) > 0) {
                    symtable_enter_type_param_block(f.name, f.type_params,
                            f.args.defaults != null,
                            has_kwonlydefaults(f.args.kwonlyargs, f.args.kw_defaults),
                            s.kind(), LOCATION(s));
                    symtable_visit_type_param_seq(f.type_params);
                }
                PySTEntryObject new_ste = ste_new(f.name, FunctionBlock, new BlockKey(s),
                        LOCATION(s));

                if (Ast._PyAST_GetDocString(f.body) != null) {
                    new_ste.ste_has_docstring = true;
                }

                symtable_visit_annotations(s, f.args, f.returns, new_ste);
                symtable_enter_existing_block(new_ste, /* add_to_children */true);

                st_cur.ste_coroutine = true;
                symtable_visit_arguments(f.args);
                symtable_visit_stmt_seq(f.body);
                symtable_exit_block();
                if (asdl_seq_LEN(f.type_params) > 0) {
                    symtable_exit_block();
                }
                break;
            }
            case AsyncWith: {
                AsyncWith w = (AsyncWith) s;
                maybe_set_ste_coroutine_for_module(s);
                symtable_raise_if_not_coroutine(ASYNC_WITH_OUTSIDE_ASYNC_FUNC, LOCATION(s));
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_withitem_seq(w.items);
                symtable_visit_stmt_seq(w.body);
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
            case AsyncFor: {
                AsyncFor f = (AsyncFor) s;
                maybe_set_ste_coroutine_for_module(s);
                symtable_raise_if_not_coroutine(ASYNC_FOR_OUTSIDE_ASYNC_FUNC, LOCATION(s));
                symtable_visit_expr(f.target);
                symtable_visit_expr(f.iter);
                boolean in_conditional_block = st_cur.ste_in_conditional_block;
                st_cur.ste_in_conditional_block = true;
                symtable_visit_stmt_seq(f.body);
                if (f.orelse != null) {
                    symtable_visit_stmt_seq(f.orelse);
                }
                st_cur.ste_in_conditional_block = in_conditional_block;
                break;
            }
        }
    }

    private void symtable_extend_namedexpr_scope(expr e) {
        assert e.kind() == expr.Kind.Name;

        String target_name = ((Name) e).id;
        int size = st_stack.size();
        assert size != 0;

        /* Iterate over the stack in reverse and add to the nearest adequate scope */
        for (int i = size - 1; i >= 0; i--) {
            PySTEntryObject ste = st_stack.get(i);

            /* If we find a comprehension scope, check for a target
             * binding conflict with iteration variables, otherwise skip it
             */
            if (ste.ste_comprehension != NoComprehension) {
                int target_in_scope = symtable_lookup_entry(ste, target_name);
                if ((target_in_scope & DEF_COMP_ITER) != 0
                        && (target_in_scope & DEF_LOCAL) != 0) {
                    throw SET_ERROR_LOCATION(st_filename, LOCATION(e),
                            String.format(NAMED_EXPR_COMP_CONFLICT, target_name));
                }
                continue;
            }

            /* If we find a FunctionBlock entry, add as GLOBAL/LOCAL or NONLOCAL/LOCAL */
            if (ste.ste_type == FunctionBlock) {
                int target_in_scope = symtable_lookup_entry(ste, target_name);
                if ((target_in_scope & DEF_GLOBAL) != 0) {
                    symtable_add_def(target_name, DEF_GLOBAL, LOCATION(e));
                } else {
                    symtable_add_def(target_name, DEF_NONLOCAL, LOCATION(e));
                }
                symtable_record_directive(target_name, LOCATION(e));

                symtable_add_def_helper(target_name, DEF_LOCAL, ste, LOCATION(e));
                return;
            }
            /* If we find a ModuleBlock entry, add as GLOBAL */
            if (ste.ste_type == ModuleBlock) {
                symtable_add_def(target_name, DEF_GLOBAL, LOCATION(e));
                symtable_record_directive(target_name, LOCATION(e));

                symtable_add_def_helper(target_name, DEF_GLOBAL, ste, LOCATION(e));
                return;
            }
            /* Disallow usage in ClassBlock and type scopes */
            if (ste.ste_type == ClassBlock
                    || ste.ste_type == TypeParametersBlock
                    || ste.ste_type == TypeAliasBlock
                    || ste.ste_type == TypeVariableBlock) {
                String msg;
                switch (ste.ste_type) {
                    case ClassBlock:
                        msg = NAMED_EXPR_COMP_IN_CLASS;
                        break;
                    case TypeParametersBlock:
                        msg = NAMED_EXPR_COMP_IN_TYPEPARAM;
                        break;
                    case TypeAliasBlock:
                        msg = NAMED_EXPR_COMP_IN_TYPEALIAS;
                        break;
                    case TypeVariableBlock:
                        msg = NAMED_EXPR_COMP_IN_TYPEVAR_BOUND;
                        break;
                    default:
                        throw new IllegalStateException("unreachable");
                }
                throw SET_ERROR_LOCATION(st_filename, LOCATION(e), msg);
            }
        }

        /* We should always find either a function-like block, ModuleBlock or ClassBlock
           and should never fall to this case
        */
        throw new IllegalStateException("unreachable");
    }

    private void symtable_handle_namedexpr(NamedExpr e) {
        if (st_cur.ste_comp_iter_expr > 0) {
            /* Assignment isn't allowed in a comprehension iterable expression */
            throw SET_ERROR_LOCATION(st_filename, LOCATION(e), NAMED_EXPR_COMP_ITER_EXPR);
        }
        if (st_cur.ste_comprehension != NoComprehension) {
            /* Inside a comprehension body, so find the right target scope */
            symtable_extend_namedexpr_scope(e.target);
        }
        symtable_visit_expr(e.value);
        symtable_visit_expr(e.target);
    }

    private void symtable_visit_expr(expr e) {
        switch (e.kind()) {
            case NamedExpr:
                symtable_raise_if_annotation_block("named expression", e);
                symtable_handle_namedexpr((NamedExpr) e);
                break;
            case BoolOp:
                symtable_visit_expr_seq(((BoolOp) e).values);
                break;
            case BinOp:
                symtable_visit_expr(((BinOp) e).left);
                symtable_visit_expr(((BinOp) e).right);
                break;
            case UnaryOp:
                symtable_visit_expr(((UnaryOp) e).operand);
                break;
            case Lambda: {
                Lambda l = (Lambda) e;
                if (l.args.defaults != null) {
                    symtable_visit_expr_seq(l.args.defaults);
                }
                if (l.args.kw_defaults != null) {
                    symtable_visit_expr_seq_with_null(l.args.kw_defaults);
                }
                symtable_enter_block("<lambda>", FunctionBlock, new BlockKey(e), LOCATION(e));
                symtable_visit_arguments(l.args);
                symtable_visit_expr(l.body);
                symtable_exit_block();
                break;
            }
            case IfExp:
                symtable_visit_expr(((IfExp) e).test);
                symtable_visit_expr(((IfExp) e).body);
                symtable_visit_expr(((IfExp) e).orelse);
                break;
            case Dict:
                symtable_visit_expr_seq_with_null(((Dict) e).keys);
                symtable_visit_expr_seq(((Dict) e).values);
                break;
            case Set:
                symtable_visit_expr_seq(((org.python.pegen.ast.Set) e).elts);
                break;
            case GeneratorExp:
                symtable_visit_genexp((GeneratorExp) e);
                break;
            case ListComp:
                symtable_visit_listcomp((ListComp) e);
                break;
            case SetComp:
                symtable_visit_setcomp((SetComp) e);
                break;
            case DictComp:
                symtable_visit_dictcomp((DictComp) e);
                break;
            case Yield: {
                Yield y = (Yield) e;
                symtable_raise_if_annotation_block("yield expression", e);
                if (y.value != null) {
                    symtable_visit_expr(y.value);
                }
                st_cur.ste_generator = true;
                if (st_cur.ste_comprehension != NoComprehension) {
                    symtable_raise_if_comprehension_block(e);
                }
                break;
            }
            case YieldFrom:
                symtable_raise_if_annotation_block("yield expression", e);
                symtable_visit_expr(((YieldFrom) e).value);
                st_cur.ste_generator = true;
                if (st_cur.ste_comprehension != NoComprehension) {
                    symtable_raise_if_comprehension_block(e);
                }
                break;
            case Await:
                symtable_raise_if_annotation_block("await expression", e);
                if (!allows_top_level_await()) {
                    if (!_PyST_IsFunctionLike(st_cur)) {
                        throw SET_ERROR_LOCATION(st_filename, LOCATION(e),
                                "'await' outside function");
                    }
                    if (!IS_ASYNC_DEF() && st_cur.ste_comprehension == NoComprehension) {
                        throw SET_ERROR_LOCATION(st_filename, LOCATION(e),
                                "'await' outside async function");
                    }
                }
                symtable_visit_expr(((Await) e).value);
                st_cur.ste_coroutine = true;
                break;
            case Compare:
                symtable_visit_expr(((Compare) e).left);
                symtable_visit_expr_seq(((Compare) e).comparators);
                break;
            case Call: {
                Call c = (Call) e;
                symtable_visit_expr(c.func);
                symtable_visit_expr_seq(c.args);
                check_keywords(c.keywords);
                symtable_visit_keyword_seq(c.keywords);
                break;
            }
            case FormattedValue: {
                FormattedValue f = (FormattedValue) e;
                symtable_visit_expr(f.value);
                if (f.format_spec != null) {
                    symtable_visit_expr(f.format_spec);
                }
                break;
            }
            case Interpolation: {
                Interpolation i = (Interpolation) e;
                symtable_visit_expr(i.value);
                if (i.format_spec != null) {
                    symtable_visit_expr(i.format_spec);
                }
                break;
            }
            case JoinedStr:
                symtable_visit_expr_seq(((JoinedStr) e).values);
                break;
            case TemplateStr:
                symtable_visit_expr_seq(((TemplateStr) e).values);
                break;
            case Constant:
                /* Nothing to do here. */
                break;
            /* The following exprs can be assignment targets. */
            case Attribute: {
                Attribute a = (Attribute) e;
                check_name(a.attr, LOCATION(e), a.ctx);
                symtable_visit_expr(a.value);
                break;
            }
            case Subscript:
                symtable_visit_expr(((Subscript) e).value);
                symtable_visit_expr(((Subscript) e).slice);
                break;
            case Starred:
                symtable_visit_expr(((Starred) e).value);
                break;
            case Slice: {
                Slice s = (Slice) e;
                if (s.lower != null) {
                    symtable_visit_expr(s.lower);
                }
                if (s.upper != null) {
                    symtable_visit_expr(s.upper);
                }
                if (s.step != null) {
                    symtable_visit_expr(s.step);
                }
                break;
            }
            case Name: {
                Name n = (Name) e;
                if (!st_cur.ste_in_unevaluated_annotation) {
                    symtable_add_def_ctx(n.id, n.ctx == expr_contextType.Load ? USE : DEF_LOCAL,
                            LOCATION(e), n.ctx);
                    /* Special-case super: it counts as a use of __class__ */
                    if (n.ctx == expr_contextType.Load
                            && _PyST_IsFunctionLike(st_cur)
                            && n.id.equals("super")) {
                        symtable_add_def("__class__", USE, LOCATION(e));
                    }
                }
                break;
            }
            /* child nodes of List and Tuple will have expr_context set */
            case List:
                symtable_visit_expr_seq(((org.python.pegen.ast.List) e).elts);
                break;
            case Tuple:
                symtable_visit_expr_seq(((Tuple) e).elts);
                break;
        }
    }

    /**
     * symtable_visit_type_param_bound_or_default. C passes the block's key
     * as tp (the type parameter, or its address + 1); here key is the
     * offset from tp.
     */
    private void symtable_visit_type_param_bound_or_default(expr e, String name, type_param tp,
            int key, String ste_scope_info) {
        if (name.equals("__classdict__")) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(tp), String.format(
                    "reserved name '%s' cannot be used for type parameter", name));
        }

        if (e != null) {
            boolean is_in_class = st_cur.ste_can_see_class_scope;
            symtable_enter_block(name, TypeVariableBlock, new BlockKey(tp, key), LOCATION(e));

            st_cur.ste_can_see_class_scope = is_in_class;
            if (is_in_class) {
                symtable_add_def("__classdict__", USE, LOCATION(e));
            }

            assert ste_scope_info != null;
            st_cur.ste_scope_info = ste_scope_info;
            symtable_visit_expr(e);

            symtable_exit_block();
        }
    }

    private void symtable_visit_type_param_seq(List<type_param> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_type_param(seq.get(i));
        }
    }

    private void symtable_visit_type_param(type_param tp) {
        switch (tp.kind()) {
            case TypeVar: {
                TypeVar t = (TypeVar) tp;
                symtable_add_def(t.name, DEF_TYPE_PARAM | DEF_LOCAL, LOCATION(tp));

                String ste_scope_info = null;
                final expr bound = t.bound;
                if (bound != null) {
                    ste_scope_info = bound.kind() == expr.Kind.Tuple ? "a TypeVar constraint"
                            : "a TypeVar bound";
                }

                // We must use a different key for the bound and default. The obvious choice would be to
                // use the .bound and .default_value pointers, but that fails when the expression immediately
                // inside the bound or default is a comprehension: we would reuse the same key for
                // the comprehension scope. Therefore, use the address + 1 as the second key.
                // The only requirement for the key is that it is unique and it matches the logic in
                // compile.c where the scope is retrieved.
                symtable_visit_type_param_bound_or_default(t.bound, t.name, tp, 0,
                        ste_scope_info);

                symtable_visit_type_param_bound_or_default(t.default_value, t.name, tp, 1,
                        "a TypeVar default");
                break;
            }
            case TypeVarTuple: {
                TypeVarTuple t = (TypeVarTuple) tp;
                symtable_add_def(t.name, DEF_TYPE_PARAM | DEF_LOCAL, LOCATION(tp));

                symtable_visit_type_param_bound_or_default(t.default_value, t.name, tp, 0,
                        "a TypeVarTuple default");
                break;
            }
            case ParamSpec: {
                ParamSpec t = (ParamSpec) tp;
                symtable_add_def(t.name, DEF_TYPE_PARAM | DEF_LOCAL, LOCATION(tp));

                symtable_visit_type_param_bound_or_default(t.default_value, t.name, tp, 0,
                        "a ParamSpec default");
                break;
            }
        }
    }

    private void symtable_visit_pattern_seq(List<pattern> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_pattern(seq.get(i));
        }
    }

    private void symtable_visit_pattern(pattern p) {
        switch (p.kind()) {
            case MatchValue:
                symtable_visit_expr(((MatchValue) p).value);
                break;
            case MatchSingleton:
                /* Nothing to do here. */
                break;
            case MatchSequence:
                symtable_visit_pattern_seq(((MatchSequence) p).patterns);
                break;
            case MatchStar: {
                MatchStar m = (MatchStar) p;
                if (m.name != null) {
                    symtable_add_def(m.name, DEF_LOCAL, LOCATION(p));
                }
                break;
            }
            case MatchMapping: {
                MatchMapping m = (MatchMapping) p;
                symtable_visit_expr_seq(m.keys);
                symtable_visit_pattern_seq(m.patterns);
                if (m.rest != null) {
                    symtable_add_def(m.rest, DEF_LOCAL, LOCATION(p));
                }
                break;
            }
            case MatchClass: {
                MatchClass m = (MatchClass) p;
                symtable_visit_expr(m.cls);
                symtable_visit_pattern_seq(m.patterns);
                check_kwd_patterns(m);
                symtable_visit_pattern_seq(m.kwd_patterns);
                break;
            }
            case MatchAs: {
                MatchAs m = (MatchAs) p;
                if (m.pattern != null) {
                    symtable_visit_pattern(m.pattern);
                }
                if (m.name != null) {
                    symtable_add_def(m.name, DEF_LOCAL, LOCATION(p));
                }
                break;
            }
            case MatchOr:
                symtable_visit_pattern_seq(((MatchOr) p).patterns);
                break;
        }
    }

    private void symtable_implicit_arg(int pos) {
        String id = "." + pos;
        symtable_add_def(id, DEF_PARAM, st_cur.ste_loc);
    }

    private void symtable_visit_params(List<arg> args) {
        for (int i = 0; i < asdl_seq_LEN(args); i++) {
            arg arg = args.get(i);
            symtable_add_def(arg.arg, DEF_PARAM, LOCATION(arg));
        }
    }

    private void symtable_visit_annotation(expr annotation, BlockKey key) {
        // Annotations in local scopes are not executed and should not affect the symtable
        boolean is_unevaluated = st_cur.ste_type == FunctionBlock;

        // Module-level annotations are always considered conditional because the module
        // may be partially executed.
        if (((st_cur.ste_type == ClassBlock && st_cur.ste_in_conditional_block)
                || st_cur.ste_type == ModuleBlock)
                && !st_cur.ste_has_conditional_annotations) {
            st_cur.ste_has_conditional_annotations = true;
            symtable_add_def("__conditional_annotations__", USE, LOCATION(annotation));
        }
        PySTEntryObject parent_ste = st_cur;
        if (parent_ste.ste_annotation_block == null) {
            _Py_block_ty current_type = parent_ste.ste_type;
            symtable_enter_block("__annotate__", AnnotationBlock, key, LOCATION(annotation));
            parent_ste.ste_annotation_block = st_cur;
            boolean future_annotations = (st_future.ff_features & CO_FUTURE_ANNOTATIONS) != 0;
            if (current_type == ClassBlock && !future_annotations) {
                st_cur.ste_can_see_class_scope = true;
                parent_ste.ste_needs_classdict = true;
                symtable_add_def("__classdict__", USE, LOCATION(annotation));
            }
        } else {
            symtable_enter_existing_block(parent_ste.ste_annotation_block,
                    /* add_to_children */false);
        }
        if (is_unevaluated) {
            st_cur.ste_in_unevaluated_annotation = true;
        }
        symtable_visit_expr(annotation);
        if (is_unevaluated) {
            st_cur.ste_in_unevaluated_annotation = false;
        }
        symtable_exit_block();
    }

    private void symtable_visit_argannotations(List<arg> args) {
        for (int i = 0; i < asdl_seq_LEN(args); i++) {
            arg arg = args.get(i);
            if (arg.annotation != null) {
                st_cur.ste_annotations_used = true;
                symtable_visit_expr(arg.annotation);
            }
        }
    }

    private void symtable_visit_annotations(stmt o, arguments a, expr returns,
            PySTEntryObject function_ste) {
        boolean is_in_class = st_cur.ste_can_see_class_scope;
        _Py_block_ty current_type = st_cur.ste_type;
        symtable_enter_block("__annotate__", AnnotationBlock, new BlockKey(a), LOCATION(o));
        st_cur.ste_function_name = function_ste.ste_name;
        if (is_in_class || current_type == ClassBlock) {
            st_cur.ste_can_see_class_scope = true;
            symtable_add_def("__classdict__", USE, LOCATION(o));
        }
        if (a.posonlyargs != null) {
            symtable_visit_argannotations(a.posonlyargs);
        }
        if (a.args != null) {
            symtable_visit_argannotations(a.args);
        }
        if (a.vararg != null && a.vararg.annotation != null) {
            st_cur.ste_annotations_used = true;
            symtable_visit_expr(a.vararg.annotation);
        }
        if (a.kwarg != null && a.kwarg.annotation != null) {
            st_cur.ste_annotations_used = true;
            symtable_visit_expr(a.kwarg.annotation);
        }
        if (a.kwonlyargs != null) {
            symtable_visit_argannotations(a.kwonlyargs);
        }
        if (returns != null) {
            st_cur.ste_annotations_used = true;
            symtable_visit_expr(returns);
        }
        symtable_exit_block();
    }

    private void symtable_visit_arguments(arguments a) {
        /* skip default arguments inside function block
           XXX should ast be different?
        */
        if (a.posonlyargs != null) {
            symtable_visit_params(a.posonlyargs);
        }
        if (a.args != null) {
            symtable_visit_params(a.args);
        }
        if (a.kwonlyargs != null) {
            symtable_visit_params(a.kwonlyargs);
        }
        if (a.vararg != null) {
            symtable_add_def(a.vararg.arg, DEF_PARAM, LOCATION(a.vararg));
            st_cur.ste_varargs = true;
        }
        if (a.kwarg != null) {
            symtable_add_def(a.kwarg.arg, DEF_PARAM, LOCATION(a.kwarg));
            st_cur.ste_varkeywords = true;
        }
    }

    private void symtable_visit_excepthandler_seq(List<excepthandler> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_excepthandler(seq.get(i));
        }
    }

    private void symtable_visit_excepthandler(excepthandler eh_) {
        ExceptHandler eh = (ExceptHandler) eh_;
        if (eh.type != null) {
            symtable_visit_expr(eh.type);
        }
        if (eh.name != null) {
            symtable_add_def(eh.name, DEF_LOCAL, LOCATION(eh));
        }
        symtable_visit_stmt_seq(eh.body);
    }

    private void symtable_visit_withitem_seq(List<withitem> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_withitem(seq.get(i));
        }
    }

    private void symtable_visit_withitem(withitem item) {
        symtable_visit_expr(item.context_expr);
        if (item.optional_vars != null) {
            symtable_visit_expr(item.optional_vars);
        }
    }

    private void symtable_visit_match_case(match_case m) {
        symtable_visit_pattern(m.pattern);
        if (m.guard != null) {
            symtable_visit_expr(m.guard);
        }
        symtable_visit_stmt_seq(m.body);
    }

    private void symtable_visit_alias_seq(List<alias> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_alias(seq.get(i));
        }
    }

    private void symtable_visit_alias(alias a) {
        /* Compute store_name, the name actually bound by the import
           operation.  It is different than a->name when a->name is a
           dotted package name (e.g. spam.eggs)
        */
        String store_name;
        String name = (a.asname == null) ? a.name : a.asname;
        int dot = name.indexOf('.');
        if (dot != -1) {
            store_name = name.substring(0, dot);
        } else {
            store_name = name;
        }
        if (!name.equals("*")) {
            symtable_add_def(store_name, DEF_IMPORT, LOCATION(a));
        } else {
            if (st_cur.ste_type != ModuleBlock) {
                throw SET_ERROR_LOCATION(st_filename, LOCATION(a), IMPORT_STAR_WARNING);
            }
        }
    }

    private void symtable_visit_comprehension_seq(List<comprehension> seq, int start) {
        for (int i = start; i < asdl_seq_LEN(seq); i++) {
            symtable_visit_comprehension(seq.get(i));
        }
    }

    private void symtable_visit_comprehension(comprehension lc) {
        st_cur.ste_comp_iter_target = true;
        symtable_visit_expr(lc.target);
        st_cur.ste_comp_iter_target = false;
        st_cur.ste_comp_iter_expr++;
        symtable_visit_expr(lc.iter);
        st_cur.ste_comp_iter_expr--;
        symtable_visit_expr_seq(lc.ifs);
        if (lc.is_async != 0) {
            st_cur.ste_coroutine = true;
        }
    }

    private void symtable_visit_keyword_seq(List<keyword> seq) {
        for (int i = 0; i < asdl_seq_LEN(seq); i++) {
            keyword k = seq.get(i);
            if (k == null) {
                continue; /* can be NULL */
            }
            symtable_visit_keyword(k);
        }
    }

    private void symtable_visit_keyword(keyword k) {
        symtable_visit_expr(k.value);
    }

    private void symtable_handle_comprehension(expr e, String scope_name,
            List<comprehension> generators, expr elt, expr value) {
        boolean is_generator = (e.kind() == expr.Kind.GeneratorExp);
        comprehension outermost = generators.get(0);
        /* Outermost iterator is evaluated in current scope */
        st_cur.ste_comp_iter_expr++;
        symtable_visit_expr(outermost.iter);
        st_cur.ste_comp_iter_expr--;
        /* Create comprehension scope for the rest */
        symtable_enter_block(scope_name, FunctionBlock, new BlockKey(e), LOCATION(e));
        switch (e.kind()) {
            case ListComp:
                st_cur.ste_comprehension = ListComprehension;
                break;
            case SetComp:
                st_cur.ste_comprehension = SetComprehension;
                break;
            case DictComp:
                st_cur.ste_comprehension = DictComprehension;
                break;
            default:
                st_cur.ste_comprehension = GeneratorExpression;
                break;
        }
        if (outermost.is_async != 0) {
            st_cur.ste_coroutine = true;
        }

        /* Outermost iter is received as an argument */
        symtable_implicit_arg(0);
        /* Visit iteration variable target, and mark them as such */
        st_cur.ste_comp_iter_target = true;
        symtable_visit_expr(outermost.target);
        st_cur.ste_comp_iter_target = false;
        /* Visit the rest of the comprehension body */
        symtable_visit_expr_seq(outermost.ifs);
        symtable_visit_comprehension_seq(generators, 1);
        if (value != null) {
            symtable_visit_expr(value);
        }
        symtable_visit_expr(elt);
        st_cur.ste_generator = is_generator;
        boolean is_async = st_cur.ste_coroutine && !is_generator;
        symtable_exit_block();
        if (is_async
                && !IS_ASYNC_DEF()
                && st_cur.ste_comprehension == NoComprehension
                && !allows_top_level_await()) {
            throw SET_ERROR_LOCATION(st_filename, LOCATION(e),
                    "asynchronous comprehension outside of an asynchronous function");
        }
        if (is_async) {
            st_cur.ste_coroutine = true;
        }
    }

    private void symtable_visit_genexp(GeneratorExp e) {
        symtable_handle_comprehension(e, "<genexpr>", e.generators, e.elt, null);
    }

    private void symtable_visit_listcomp(ListComp e) {
        symtable_handle_comprehension(e, "<listcomp>", e.generators, e.elt, null);
    }

    private void symtable_visit_setcomp(SetComp e) {
        symtable_handle_comprehension(e, "<setcomp>", e.generators, e.elt, null);
    }

    private void symtable_visit_dictcomp(DictComp e) {
        symtable_handle_comprehension(e, "<dictcomp>", e.generators, e.key, e.value);
    }

    private void symtable_raise_if_annotation_block(String name, expr e) {
        _Py_block_ty type = st_cur.ste_type;
        String msg;
        if (type == AnnotationBlock) {
            msg = String.format(ANNOTATION_NOT_ALLOWED, name);
        } else if (type == TypeVariableBlock) {
            String info = st_cur.ste_scope_info;
            assert info != null; // e.g., info == "a ParamSpec default"
            msg = String.format(EXPR_NOT_ALLOWED_IN_TYPE_VARIABLE, name, info);
        } else if (type == TypeAliasBlock) {
            // for now, we do not have any extra information
            assert st_cur.ste_scope_info == null;
            msg = String.format(EXPR_NOT_ALLOWED_IN_TYPE_ALIAS, name);
        } else if (type == TypeParametersBlock) {
            // for now, we do not have any extra information
            assert st_cur.ste_scope_info == null;
            msg = String.format(EXPR_NOT_ALLOWED_IN_TYPE_PARAMETERS, name);
        } else {
            return;
        }

        throw SET_ERROR_LOCATION(st_filename, LOCATION(e), msg);
    }

    private void symtable_raise_if_comprehension_block(expr e) {
        _Py_comprehension_ty type = st_cur.ste_comprehension;
        throw SET_ERROR_LOCATION(st_filename, LOCATION(e),
                (type == ListComprehension) ? "'yield' inside list comprehension"
                        : (type == SetComprehension) ? "'yield' inside set comprehension"
                        : (type == DictComprehension) ? "'yield' inside dict comprehension"
                        : "'yield' inside generator expression");
    }

    private void symtable_raise_if_not_coroutine(String msg, SourceLocation loc) {
        if (!st_cur.ste_coroutine) {
            throw SET_ERROR_LOCATION(st_filename, loc, msg);
        }
    }

    /**
     * _Py_SymtableStringObjectFlags, which the _symtable module calls, after
     * its parse: the symbol table of mod, a tree the parser just returned
     * (C parses str here; there is no Java tokenizer yet, so the caller
     * parses). Runs future, then _PySymtable_Build, with no preprocess in
     * between. Throws the SyntaxError a stage raises.
     */
    public static Symtable _Py_SymtableStringObjectFlags(mod mod, String filename,
            Compile.PyCompilerFlags flags) {
        Future.FutureFeatures future = new Future.FutureFeatures();
        Future._PyFuture_FromAST(mod, filename, future);
        future.ff_features |= flags.cf_flags;
        return _PySymtable_Build(mod, filename, future);
    }

    /** _Py_MaybeMangle */
    public static String _Py_MaybeMangle(String privateobj, PySTEntryObject ste, String name) {
        /* Special case for type parameter blocks around generic classes:
         * we want to mangle type parameter names (so a type param with a private
         * name can be used inside the class body), but we don't want to mangle
         * any other names that appear within the type parameter scope.
         */
        if (ste.ste_mangled_names != null) {
            if (!ste.ste_mangled_names.contains(name)) {
                return name;
            }
        }
        return _Py_Mangle(privateobj, name);
    }

    /** _Py_IsPrivateName */
    public static boolean _Py_IsPrivateName(String ident) {
        int nlen = ident.length();
        if (nlen < 3 || ident.charAt(0) != '_' || ident.charAt(1) != '_') {
            return false;
        }
        if (ident.charAt(nlen - 1) == '_' && ident.charAt(nlen - 2) == '_') {
            return false; /* Don't mangle __whatever__ */
        }
        return true;
    }

    /**
     * PyUnicode_READ_CHAR, where C may read a str's terminating NUL: a
     * one-character name's second character, a class name of underscores'
     * end.
     */
    private static char READ_CHAR(String s, int i) {
        return i < s.length() ? s.charAt(i) : 0;
    }

    /**
     * _Py_Mangle. C indexes code points where this indexes UTF-16 code
     * units, which comes to the same: the characters looked for, '_' and
     * '.', are not surrogates.
     */
    public static String _Py_Mangle(String privateobj, String ident) {
        /* Name mangling: __private becomes _classname__private.
           This is independent from how the name is used. */
        if (privateobj == null || READ_CHAR(ident, 0) != '_' || READ_CHAR(ident, 1) != '_') {
            return ident;
        }
        int nlen = ident.length();
        int plen = privateobj.length();
        /* Don't mangle __id__ or names with dots.

           The only time a name with a dot can occur is when
           we are compiling an import statement that has a
           package name.

           TODO(jhylton): Decide whether we want to support
           mangling of the module name, e.g. __M.X.
        */
        if ((ident.charAt(nlen - 1) == '_' && ident.charAt(nlen - 2) == '_')
                || ident.indexOf('.') != -1) {
            return ident; /* Don't mangle __whatever__ */
        }
        /* Strip leading underscores from class name */
        int ipriv = 0;
        while (READ_CHAR(privateobj, ipriv) == '_') {
            ipriv++;
        }
        if (ipriv == plen) {
            return ident; /* Don't mangle if class is just underscores */
        }

        // ident = "_" + priv[ipriv:] + ident
        return "_" + privateobj.substring(ipriv) + ident;
    }
}
