# not_op.py
#
# In CPython 3.15, "not" compiles to TO_BOOL and UNARY_NOT.

u = 0
v = 3
w = ""
x = "a"

nu = not u
nv = not v
nw = not w
nx = not x
nnv = not not v
nl = not [0]
