# Debug text of f- and t-string replacement fields (_PyPegen_interpolation,
# set_ftstring_expr): whitespace and line continuations after the "=",
# comments, and backslashes in the expression.
f'{x = }'
f'{x=!r}'
f'{x\
=}'
t'{x  }'
t'{x # c
}'
f'''{x # c
=}'''
f'{"\\"=}'
t'''{x # c
}'''
t'''{x  # '
= }'''
f'''{x \
 = }'''
f'''{'#' \
 = }'''
