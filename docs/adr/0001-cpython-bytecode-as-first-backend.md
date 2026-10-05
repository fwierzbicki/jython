# CPython bytecode is the first backend, alongside Jython 2

The Python 3 front end (the PEG parser, then future, preprocess and symtable)
is ported from CPython and shared by every backend. The first backend compiles
to CPython 3.15's own bytecode, unspecialized and in the exact format `dis`
shows, to be run by the Jython 3 runtime's interpreter. That makes codegen,
flowgraph and assemble mechanical ports with CPython as an exact oracle.
Jython is meant to have several ways to compile, and this is one of them, not
a replacement for generating JVM bytecode. Jython 2 is left alone:
`org.python.compiler`, the ANTLR parser and the runtime are not changed.

This is a learning spike, not a final decision. The work is done on the
Jython 2 tree (the cpython-bytecode-compiler branch), although the interpreter
lives on `main`. The backend stops at the code object, which is checked
against CPython stage by stage and, at the end, as marshal output. Nothing is
ported to `main` until the backend is done, and only after checking with the
user. Whether a port happens, and whether the backend should go on to run
code, gets decided then.

## Considered Options

- **JVM bytecode straight from the Python 3 AST**, as Jython 2's
  `CodeCompiler` does. Not rejected: it stays a possible later backend on the
  same front end.
- **CPython's instruction set in a Java-friendly encoding.** Rejected,
  because it can't be diffed against CPython byte for byte. An interpreter can
  decode the exact format at load time if it needs to.
