# PEP 765: return, break or continue that leave a finally block warn, when
# compiling to code only (compare_ast.py's #COMPILE-WARNING lines).
def f():
    try:
        pass
    finally:
        return 1

def g():
    for x in y:
        try:
            pass
        finally:
            break
    while x:
        try:
            pass
        finally:
            continue

def h():
    try:
        pass
    finally:
        # Inside a loop or function within the finally block: no warning.
        for x in y:
            break
            continue
        else:
            # The else block isn't the loop body: warns.
            return 2
        while x:
            if x:
                break
        def inner():
            return 3
        async def ainner():
            return 4
        lambda: 5
        class C:
            def m(self):
                return 6
        if x:
            with y:
                return 7

def nested():
    try:
        pass
    finally:
        try:
            pass
        finally:
            return 8
        return 9

def in_handlers():
    try:
        pass
    except E:
        return 10
    else:
        return 11
    finally:
        pass

def star():
    try:
        pass
    except* E:
        pass
    finally:
        return 12

async def async_loops():
    async for x in y:
        try:
            pass
        finally:
            continue
    try:
        pass
    finally:
        async with x:
            return 13

def loop_in_finally_in_loop():
    for x in y:
        try:
            pass
        finally:
            for z in x:
                try:
                    pass
                finally:
                    break
            break

def match_in_finally():
    try:
        pass
    finally:
        match x:
            case 1:
                return 14
