# subscr_slice.py
#
# In CPython 3.15, subscripts compile to BINARY_OP with NB_SUBSCR,
# and constant slices are constants of type slice (marshal ':').

a = [1, 2, 3, 4, 5]
t = (10, 20, 30, 40, 50)
s = "hello"

a1 = a[1]
t2 = t[2]
sm1 = s[-1]
a13 = a[1:3]
t_to2 = t[:2]
s2_ = s[2:]
a_step = a[::2]
