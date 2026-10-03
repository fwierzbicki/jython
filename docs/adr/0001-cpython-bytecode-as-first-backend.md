# CPython bytecode is the first backend, alongside core Jython

The Python 3 front end (the PEG parser, then future, preprocess and symtable)
is ported from CPython and shared by every backend. The first backend compiles
to CPython 3.15's own bytecode, unspecialized and in the exact format `dis`
shows, and runs it on an interpreter written in Java. That makes codegen,
flowgraph and assemble mechanical ports with CPython as an exact oracle.
Jython is meant to have several ways to compile, and this is one of them, not
a replacement for generating JVM bytecode. For now core Jython is left alone:
`org.python.compiler`, the ANTLR parser and the runtime are not changed.

## Considered Options

- **JVM bytecode straight from the Python 3 AST**, as Jython 2's
  `CodeCompiler` does. Not rejected: it stays a possible later backend on the
  same front end.
- **CPython's instruction set in a Java-friendly encoding.** Rejected,
  because it can't be diffed against CPython byte for byte. An interpreter can
  decode the exact format at load time if it needs to.
