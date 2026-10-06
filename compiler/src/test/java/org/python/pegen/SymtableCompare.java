package org.python.pegen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.python.pegen.ast.base.mod;
import org.python.pegen.compile.Compile;
import org.python.pegen.compile.Symtable;
import org.python.pegen.compile.Symtable.PySTEntryObject;

/**
 * The Java half of tests/pegen/compare_symtable.py: parses each file (with
 * the Java tokenizer, as for source bytes), builds its symbol table as the
 * _symtable module does (Symtable._Py_SymtableStringObjectFlags: future,
 * then symtable, with no preprocess), and writes the result in a canonical
 * text form that compare_symtable.py also produces from CPython's
 * _symtable.symtable(), so the two can be compared exactly.
 *
 * <p>Per file: "#FILE path", then either one "#ERROR" line (as AstCompare
 * writes it) or the block tree: a "BLOCK type name lineno nested" line per
 * block, then a "SYMBOL name flags" line per symbol (sorted by the name's
 * written form; flags are the raw ste_symbols value, scope bits included),
 * a "VARNAMES" line, then the children, each level indented one more space.
 * These are the fields _symtable's entries show; names are written as
 * AstCompare.str writes strings.
 *
 * <p>Usage: SymtableCompare [--mode file|single|eval] LIST OUT, where LIST
 * names the files, one per line.
 */
public class SymtableCompare {

    public static void main(String[] args) throws IOException {
        int startRule = Parser.FILE_INPUT;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--mode")) {
                String mode = args[++i];
                startRule = mode.equals("single") ? Parser.SINGLE_INPUT
                        : mode.equals("eval") ? Parser.EVAL_INPUT : Parser.FILE_INPUT;
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() != 2) {
            System.err.println("usage: SymtableCompare [--mode file|single|eval] LIST OUT");
            System.exit(2);
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(files.get(1))), StandardCharsets.UTF_8))) {
            for (String path : Files.readAllLines(Paths.get(files.get(0)),
                    StandardCharsets.UTF_8)) {
                StringBuilder b = new StringBuilder();
                b.append("#FILE ").append(path).append('\n');
                try {
                    // _symtable.symtable(source, ...) with source bytes.
                    Parser p = AstCompare.parser(Files.readAllBytes(Paths.get(path)), startRule,
                            Compile.PyCF_SOURCE_IS_UTF8);
                    Object result = p.runParser(new GeneratedParser(p));
                    if (result != null) {
                        Symtable st = Symtable._Py_SymtableStringObjectFlags((mod) result,
                                "<unknown>",
                                new Compile.PyCompilerFlags(Compile.PyCF_SOURCE_IS_UTF8));
                        dump(st.st_top, b, 0);
                    } else if (p.getError() != null) {
                        AstCompare.error(p.getError(), b);
                    } else {
                        b.append("#CRASH no result and no error\n");
                    }
                } catch (PythonSyntaxError e) {
                    AstCompare.error(e, b);
                } catch (RuntimeException | StackOverflowError e) {
                    b.append("#CRASH ").append(AstCompare.str(e.toString())).append('\n');
                }
                out.write(b.toString());
            }
        }
    }

    private static void dump(PySTEntryObject ste, StringBuilder b, int indent) {
        pad(b, indent).append("BLOCK ").append(ste.ste_type.ordinal()).append(' ')
                .append(AstCompare.str(ste.ste_name)).append(' ').append(ste.ste_loc.lineno)
                .append(' ').append(ste.ste_nested ? 1 : 0).append('\n');
        List<String[]> symbols = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ste.ste_symbols.entrySet()) {
            symbols.add(new String[] {AstCompare.str(e.getKey()), e.getValue().toString()});
        }
        symbols.sort((x, y) -> x[0].compareTo(y[0]));
        for (String[] s : symbols) {
            pad(b, indent + 1).append("SYMBOL ").append(s[0]).append(' ').append(s[1])
                    .append('\n');
        }
        pad(b, indent + 1).append("VARNAMES");
        for (String name : ste.ste_varnames) {
            b.append(' ').append(AstCompare.str(name));
        }
        b.append('\n');
        for (PySTEntryObject child : ste.ste_children) {
            dump(child, b, indent + 1);
        }
    }

    private static StringBuilder pad(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) {
            b.append(' ');
        }
        return b;
    }
}
