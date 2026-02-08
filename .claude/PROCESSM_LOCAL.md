# ProcessM Local Repository Reference

## Location
```
C:\Users\andre\AntigravityProjects\processm
```

## Original Test Files (Query Language)
```
C:\Users\andre\AntigravityProjects\processm\processm.core\src\test\kotlin\processm\core\querylanguage\
├── AttributeTests.kt
├── FunctionTests.kt
├── LiteralTests.kt
├── OrderDirectionTests.kt
├── QueryTests.kt
└── ScopeTests.kt
```

## Original Source Files (Query Language Model)
```
C:\Users\andre\AntigravityProjects\processm\processm.core\src\main\kotlin\processm\core\querylanguage\
├── Query.kt
├── Attribute.kt
├── Expression.kt
├── Function.kt
├── Literal.kt
├── Operator.kt
├── Scope.kt
├── OrderDirection.kt
└── ...
```

## ANTLR Grammar
```
C:\Users\andre\AntigravityProjects\processm\processm.core\src\main\antlr4\processm\core\querylanguage\
├── QLLexer.g4
└── QLParser.g4
```

## Notes
- This is the reference implementation for pql-graph-db
- Tests from ProcessM are the SPECIFICATION (TDD approach)
- pql-graph-db must pass all ProcessM tests 1:1
