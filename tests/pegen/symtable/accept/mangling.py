# Private name mangling: in classes, nested classes, class names of
# underscores, dotted imports, dunders, and lambdas and comprehensions in
# classes.
class Spam:
    __a = 1
    __b__ = 2

    def __m(self, __p):
        __local = __p
        import __mod.sub
        import __mod2
        return self.__a, __local, Spam.__b__

    class __Inner:
        __c = 1

        def f(self):
            return __c

    f = lambda self, __q=__a: __q
    g = [__a for __i in range(3)]


class _Under:
    __x = 1


class ___:
    __y = 1


class C:
    def f(self):
        global __z
        __z = 1
        return __zz
