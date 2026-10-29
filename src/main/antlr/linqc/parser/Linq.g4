grammar Linq;

@header {
package linqc.parser;
}

// Phase 0 placeholder: just enough grammar to prove the build works.
// Phase 2 grows this into the real query grammar (where, orderby, ...).
query
  : FROM ID IN ID SELECT ID EOF
  ;

// Keywords come before ID: when two rules match the same text,
// ANTLR picks the one defined first, so "from" is FROM, not ID.
FROM   : 'from' ;
IN     : 'in' ;
SELECT : 'select' ;

ID     : [a-zA-Z_] [a-zA-Z_0-9]* ;
SPACE  : [ \t\r\n] -> skip ;
