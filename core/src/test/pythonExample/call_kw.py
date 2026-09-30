# call_kw.py
#
# In CPython 3.15, calls with keyword arguments compile to CALL_KW,
# taking the tuple of names from the stack.

m1 = max(3, 5, key=None)
m2 = min(7, 4, 9, key=None)
m3 = max([], default=42)
p = "a,b,c".split(sep=",")
q = "a,b,c".split(",", maxsplit=1)
