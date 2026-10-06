# Annotations (PEP 649 annotation blocks): module, class and function, in
# conditional blocks, and annotation targets that aren't names.
import sys

x: int
y: list[int] = []
(z): int = 1
a.b: int
a[0]: int = 1

if sys:
    w: str


class C:
    p: int
    q: "C" = None

    if sys:
        r: int
    try:
        s: int
    except ImportError:
        pass

    def m(self, a: int, *args: str, b: C = None, **kw: float) -> C:
        local: unevaluated_name = 1
        return local


def f(a: int) -> None:
    v: int
    (u): int = 1


def nested():
    class D:
        t: int

        def m(self, x: D):
            pass
    return D


for i in range(3):
    k: int
