# and_or.py
#
# In CPython 3.15, "and" and "or" compile to COPY, TO_BOOL,
# POP_JUMP_IF_FALSE/TRUE, NOT_TAKEN and POP_TOP.

t = (0, 1, 2)
a = t[0] and t[1]
b = t[1] and t[2]
c = t[0] or t[1]
d = t[1] or t[2]
e = t[0] or t[0] or t[2]
f = t[1] and t[2] and t[0]
g = t[1] and t[0] or t[2]
