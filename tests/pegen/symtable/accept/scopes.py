# global and nonlocal, names free through several blocks, cells, and the
# names import binds.
import os.path
import os.path as p
from os import path as q, sep
from . import r

g = 1


def outer():
    a = 1
    b = 2

    def middle():
        nonlocal b
        b = 3

        def inner():
            global g
            g = a + b
            return c
        return inner
    c = 3
    return middle


def uses_global():
    global undefined_yet
    undefined_yet = 1


def shadow(a):
    def inner(a):
        return a
    return inner


def free_through_class():
    v = 1

    class K:
        def m(self):
            return v
    return K


def walrus_global():
    global n
    return [n := i for i in range(3)]


def deletes():
    d = 1
    del d
    (e, [f, *g2]) = 1, [2, 3]
    with open("x") as (h, i):
        pass
    for j, k in []:
        pass
    try:
        pass
    except Exception as exc:
        pass
    match d:
        case [m1, *rest] | {"k": m1, **rest}:
            pass
        case C(attr=m2) as whole:
            pass
    lambda q=d, *, r=e: q + r
    return locals()


lazy import json
lazy from os import sep as sep2
