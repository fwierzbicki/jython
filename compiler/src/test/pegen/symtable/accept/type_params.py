# PEP 695 type parameters and type aliases, with bounds, constraints and
# defaults, at module and class level, and private names in generic classes.
def f[T, *Ts, **P](a: T, *args: *Ts) -> T:
    return a


def g[T: int, U: (str, bytes) = str, *Ts = tuple[int], **P = [int]](x=1, *, y=2):
    pass


def h[T](*, y=2):
    pass


class C[T, U: list[T]](list[T], metaclass=type):
    def m[V](self, v: V) -> T | V:
        return v

    type A[W] = dict[T, W]
    type B = list[C]


class Private[__T, _Private__U, __V: __Bound = __T]:
    x: __T

    def m(self) -> __T:
        return self.__a


type Alias = int
type Generic[T: int = bool] = list[T]


class Outer:
    class Inner[T]:
        pass

    def method[T: Outer](self):
        return [T for _ in range(3)]


def outer():
    x = 1

    def inner[T: x]():
        return x
    return inner
