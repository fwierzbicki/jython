# With annotations postponed, annotations aren't folded, everything else is.
from __future__ import annotations

def f(p: "%s" % (x,) = "%s" % (y,), *a: "%s" % (x,), **k: "%r" % (x,)) -> "%s" % (z,):
    v: "%s" % (x,) = "%s" % (y,)
    w: __debug__
    return __debug__

class C:
    a: "%s" % (x,) = __debug__
