# Flowgraph's checks for loads of uninitialized locals, past the 64 locals
# it tracks across blocks (fast_scan_many_locals), and superinstructions
# whose opargs need more than 4 bits.
def many(cond, v0, v1, v2):
    v3 = cond
    v4 = cond
    v5 = cond
    v6 = cond
    v7 = cond
    v8 = cond
    v9 = cond
    v10 = cond
    v11 = cond
    v12 = cond
    v13 = cond
    v14 = cond
    v15 = cond
    v16 = cond
    v17 = cond
    v18 = cond
    v19 = cond
    v20 = cond
    v21 = cond
    v22 = cond
    v23 = cond
    v24 = cond
    v25 = cond
    v26 = cond
    v27 = cond
    v28 = cond
    v29 = cond
    v30 = cond
    v31 = cond
    v32 = cond
    v33 = cond
    v34 = cond
    v35 = cond
    v36 = cond
    v37 = cond
    v38 = cond
    v39 = cond
    v40 = cond
    v41 = cond
    v42 = cond
    v43 = cond
    v44 = cond
    v45 = cond
    v46 = cond
    v47 = cond
    v48 = cond
    v49 = cond
    v50 = cond
    v51 = cond
    v52 = cond
    v53 = cond
    v54 = cond
    v55 = cond
    v56 = cond
    v57 = cond
    v58 = cond
    v59 = cond
    v60 = cond
    v61 = cond
    v62 = cond
    v63 = cond
    v64 = cond
    v65 = cond
    v66 = cond
    v67 = cond
    v68 = cond
    v69 = cond
    if cond:
        del v65
        del v5
    print(v65, v66, v5, v6)
    v67 = v68 = v69
    for v1 in cond:
        v64 = v1
    print(v64, v1, v2)
    v66, v67 = v67, v66
    v3, v68, v4 = v4, v3, v68
    return v69, v0

def maybe(cond):
    if cond:
        x = 1
    print(x)
    del x
    try:
        y = 1
    finally:
        print(y)
    while cond:
        z = 2
    return z
