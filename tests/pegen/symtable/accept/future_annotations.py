# Under "from __future__ import annotations" annotation blocks are not
# children of the block they annotate.
from __future__ import annotations

x: int


class C:
    p: int

    def m(self, a: C) -> C:
        b: int = 1
        return b


def f[T](a: T) -> T:
    return a
