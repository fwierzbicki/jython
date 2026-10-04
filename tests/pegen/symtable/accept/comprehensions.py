# Comprehensions: inlined (list, set, dict) and not (generator expressions),
# at module, function and class level, nested, with cells, and with
# assignment expressions binding in the enclosing block.
xs = [x for x in range(3)]
ss = {x for x in xs if x}
ds = {k: v for k, v in zip(xs, xs)}
gs = (x for x in xs)
nested = [[y for y in range(x)] for x in xs]
total = [last := x for x in xs]


def f(a):
    fs = [lambda: x for x in a]
    ys = [(y := x) for x in a]
    zs = [z for z in a for z2 in [z]]
    g = ((w := x) for x in a)
    return fs, ys, zs, g, y


def g():
    x = 1
    return [x for _ in range(3)], [lambda: x for _ in range(3)]


def h():
    return [[x for x in range(3)] for x in range(3)]


def k(a):
    def inner():
        return [b := x for x in a]
    b = 0
    return inner


class C:
    y = 1
    z = [y for _ in range(3)]
    w = [x for x in range(y)]
    v = (y for _ in range(3))

    def m(self):
        return [__class__ for _ in range(3)], [super() for _ in range(3)]


async def af(a):
    r = [x async for x in a]
    s = [await x for x in a]
    t = (x async for x in a)
    u = [[x async for x in y] for y in a]
    return r, s, t, u


def gen(a):
    return (x async for x in a)


def cell_shadow():
    x = 1
    fs = [lambda: x for x in range(3)]
    return x, fs
