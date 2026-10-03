# Jython 3 compilation

How Python 3 source becomes something Jython can run, on the peg-parser and
cpython-bytecode-compiler branches, modelled stage for stage on CPython. Terms follow CPython's, so that the C
sources and our Java read alike.

## Language

**Front end**:
The stages every backend shares: parsing, then future, preprocess and
symtable.
_Avoid_: compiler (too broad)

**Backend**:
One way of turning the front end's output into something Jython runs. There
can be several.
_Avoid_: target, compile mode

**Bytecode**:
CPython's instruction set, the one `dis` shows: what the first backend
compiles to and runs on an interpreter written in Java.
_Avoid_: using it unqualified for JVM bytecode (say **JVM bytecode**)

**Symbol table**:
The result of scope analysis over a module's AST: a tree of blocks, each
recording how every name used in it is bound.
_Avoid_: scopes, scope info

**Block**:
One node of the symbol table: a module, class, function, lambda,
comprehension, annotation or type-parameter body that has its own namespace.
_Avoid_: scope (that word means something else here), entry, ScopeInfo

**Scope**:
Where a name in a block resolves: local, global (explicit or implicit), free
or cell.
_Avoid_: binding kind, using it for a block
