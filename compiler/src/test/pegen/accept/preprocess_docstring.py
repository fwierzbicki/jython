"""Module docstring: removed at optimize 2."""

def only_doc():
    """Replaced by pass, which keeps the docstring's start."""

def multiline_doc():
    """A docstring
    over lines."""

def doc_and_body():
    "removed"
    return 1

async def async_doc():
    'removed'
    await x

class C:
    """removed"""
    def m(self):
        """removed"""

class D:
    """only"""

def not_docstrings():
    f"not {a} docstring"
    "a string after it"

def bytes_not_docstring():
    b"not a docstring"

def second():
    x = 1
    "not a docstring"

def paren_doc():
    ("parenthesized"
     "docstring")
    pass

if x:
    "not a docstring: not a body that has one"
lambda: "nor this"
