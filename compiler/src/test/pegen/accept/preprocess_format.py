# "..." % (...) folds into an f-string (fold_binop, optimize_format) when
# the format units are simple ones: s, r or a, with flags, and width and
# precision under 100.
a = "%s" % (x,)
a = "%r and %a" % (x, y)
a = "<%s>" % (x,)
a = "%5s|%-5s|%05s|%+s|% s|%#s|%-05s" % (x, y, z, x, y, z, x)
a = "%.2s|%5.2r|%-5.2a|%.s|%5.s|%99s|%.99s" % (x, y, z, x, y, z, x)
a = "100%% %s%%%%" % (x,)
a = "%%" % ()
a = "plain" % ()
a = "" % ()
a = "café \U0001f600 %s \U0001f600" % (x,)
a = "%s%s%s" % (f(x), y.z, [w])
a = ("%s" "-" "%r") % (x, y)
a = f"{x}" % (y,)
a = "%s" % ((x, y),)

# Not folded.
a = "%100s" % (x,)
a = "%.100s" % (x,)
a = "%d" % (x,)
a = "%s %d" % (x, y)
a = "%(name)s" % (x,)
a = "%*s" % (x, y)
a = "%s" % (x, y)
a = "%s %s" % (x,)
a = "%s" % (*x,)
a = "%s %s" % (x, *y)
a = "abc%" % (x,)
a = "abc%5" % (x,)
a = "abc%5." % (x,)
a = "%s" % x
a = "%s" % [x]
a = b"%s" % (x,)
a = x % (y,)
a = "%s" + (x,)
a = ("%s" % (x,)) % (y,)

# Folded wherever an expression is.
def f(p="%s" % (x,), *, q="%r" % (y,)) -> "%a" % (z,):
    return "%s" % ("%s" % (x,),)

lambda p="%s" % (x,): "%r" % (p,)
print("%s" % (x,), sep="%s" % (y,))
[("%s" % (i,)) for i in "%s" % (x,) if "%r" % (i,)]
{"%s" % (k,): "%s" % (v,) for k, v in x}
{**x, "%s" % (k,): 1}
y = x["%s" % (k,):"%r" % (k,)]
assert "%s" % (x,), "%r" % (y,)
