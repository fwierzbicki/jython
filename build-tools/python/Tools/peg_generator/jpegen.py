#!/usr/bin/env python3.8

import argparse
import sys
from typing import Tuple

from pegen import __main__, build
from pegen.build import Grammar, Parser, ParserGenerator, Tokenizer

from java_generator import JavaParserGenerator

def generate_java_code(
    args: argparse.Namespace,
) -> Tuple[Grammar, Parser, Tokenizer, ParserGenerator]:

    verbose = args.verbose
    verbose_tokenizer = verbose >= 3
    verbose_parser = verbose == 2 or verbose >= 4
    try:
        grammar, parser, tokenizer, gen = build_java_parser_and_generator(
            args.grammar_filename,
            args.tokens_filename,
            args.output,
            args.compile_extension,
            verbose_tokenizer,
            verbose_parser,
            args.verbose,
            keep_asserts_in_extension=False if args.optimized else True,
            skip_actions=args.skip_actions,
        )
        return grammar, parser, tokenizer, gen
    except Exception as err:
        if args.verbose:
            raise  # Show traceback
        traceback.print_exception(err.__class__, err, None)
        sys.stderr.write("For full traceback, use -v\n")
        sys.exit(1)

def build_java_parser_and_generator(
    grammar_file: str,
    tokens_file: str,
    output_file: str,
    compile_extension: bool = False,
    verbose_tokenizer: bool = False,
    verbose_parser: bool = False,
    verbose_c_extension: bool = False,
    keep_asserts_in_extension: bool = True,
    skip_actions: bool = False,
) -> Tuple[Grammar, Parser, Tokenizer, ParserGenerator]:
    """Generate rules, Java parser, tokenizer, parser generator for a given grammar

    Args:
        grammar_file (string): Path for the grammar file
        tokens_file (string): Path for the tokens file
        output_file (string): Path for the output file
        compile_extension (bool, optional): XXX: Probably does not have meaning for Java
          Defaults to False.
        verbose_tokenizer (bool, optional): Whether to display additional output
          when generating the tokenizer. Defaults to False.
        verbose_parser (bool, optional): Whether to display additional output
          when generating the parser. Defaults to False.
        verbose_c_extension (bool, optional): XXX: Probably does not have meaning for Java
        keep_asserts_in_extension (bool, optional): XXX: Probably does not have meaning for Java
        skip_actions (bool, optional): Whether to pretend no rule has any actions.
    """
    grammar, parser, tokenizer = build.build_parser(grammar_file, verbose_tokenizer, verbose_parser)
    gen = build_java_generator(
        grammar,
        grammar_file,
        tokens_file,
        output_file,
        compile_extension,
        verbose_c_extension,
        keep_asserts_in_extension,
        skip_actions=skip_actions,
    )
    return grammar, parser, tokenizer, gen


def build_java_generator(
    grammar: Grammar,
    grammar_file: str,
    tokens_file: str,
    output_file: str,
    compile_extension: bool = False,
    verbose_c_extension: bool = False,
    keep_asserts_in_extension: bool = True,
    skip_actions: bool = False,
) -> ParserGenerator:
    with open(tokens_file, "r") as tok_file:
        all_tokens, exact_tok, non_exact_tok = build.generate_token_definitions(tok_file)
    with open(output_file, "w") as file:
        gen: ParserGenerator = JavaParserGenerator(
            grammar, all_tokens, exact_tok, non_exact_tok, file, skip_actions=skip_actions
        )
        gen.generate(grammar_file)
    return gen

java_parser = __main__.subparsers.add_parser("java", help="Generate Java code for inclusion into Jython")
java_parser.set_defaults(func=generate_java_code)
java_parser.add_argument("grammar_filename", help="Grammar description")
java_parser.add_argument("tokens_filename", help="Tokens description")
java_parser.add_argument(
    "-o", "--output", metavar="OUT", default="Parser.java", help="Where to write the generated parser"
)
java_parser.add_argument(
    "--compile-extension",
    action="store_true",
    help="Compile generated C code into an extension module",
)
java_parser.add_argument(
    "--optimized", action="store_true", help="Compile the extension in optimized mode"
)
java_parser.add_argument(
    "--skip-actions",
    action="store_true",
    help="Suppress code emission for rule actions",
)

if __name__ == "__main__":
    if sys.version_info < (3, 8):
        print("ERROR: using jpegen requires at least Python 3.8!", file=sys.stderr)
        sys.exit(1)
    __main__.main()
