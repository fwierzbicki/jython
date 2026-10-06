def g():
    x = 0
    def f():
        (x := 1)
        nonlocal x
