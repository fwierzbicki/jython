def g():
    x = 1
    def f():
        nonlocal x
        x: int
