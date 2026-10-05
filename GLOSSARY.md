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

**Code object**:
What a backend that compiles to **Bytecode** produces for each **Block**
that has code: the instructions plus their constants, names, line table and
exception table, with the fields of CPython's `PyCodeObject`.
_Avoid_: PyCode (that's Jython 2's class), compiled code

## Runtimes

**Jython 2**:
The released Jython: its runtime, its ANTLR parser and its compiler to
**JVM bytecode**. This work leaves it unchanged.
_Avoid_: core Jython, core

**Jython 3 runtime**:
The new runtime on the `main` branch (rt3): its object model, with
invokedynamic call sites. Code from every **Backend** runs on it.
_Avoid_: core, rt3 (except as the branch's nickname), invokedynamic runtime

**Interpreter**:
The part of the **Jython 3 runtime** that executes **Bytecode**, one frame
at a time.
_Avoid_: VM, eval loop, cpython bytecode runtime
