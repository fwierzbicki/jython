# Class blocks: methods, super and __class__, names a class binds that its
# methods use (DEF_FREE_CLASS), and class bodies inside functions that see
# the function's names.
x = 0


class A:
    x = 1
    y = x

    def f(self):
        return super().f() + x

    def g(self):
        return __class__

    def h(self):
        def inner():
            return super()
        return inner

    @staticmethod
    def s(a, /, b, *args, c, d=1, **kw):
        return a, b, args, c, d, kw


def outer():
    v = 1
    w = 2

    class B:
        v = 3
        u = v + w

        def m(self):
            return v, w

        class Inner:
            def n(self):
                return v

    return B


def outer2():
    class C:
        def m(self):
            nonlocal z
            z = 1
    z = 0
    return C


class D:
    global g
    g = 1

    def m(self):
        return g


class E:
    global G
    G = 1
    H = 2

    def m(self, a: G, b: H) -> G:
        pass


def enclosing():
    x = 1

    class F:
        x = 2
        type A = x

        def m(self, a: x):
            return x
    return F


# A class's conditional annotations make __conditional_annotations__ free in
# it, through to the class around it.
class Outer:
    class Inner:
        if x:
            a: int
