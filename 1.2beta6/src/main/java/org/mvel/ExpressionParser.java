/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.mvel;

import static org.mvel.DataConversion.canConvert;
import static org.mvel.Operator.*;
import static org.mvel.PropertyAccessor.get;
import org.mvel.integration.VariableResolverFactory;
import org.mvel.integration.impl.MapVariableResolverFactory;
import org.mvel.util.ExecutionStack;
import static org.mvel.util.ParseTools.*;
import static org.mvel.util.PropertyTools.*;
import org.mvel.util.Stack;
import org.mvel.util.StringAppender;

import static java.lang.Character.isWhitespace;
import static java.lang.Class.forName;
import static java.lang.String.valueOf;
import java.math.BigDecimal;
import java.util.*;
import static java.util.Collections.synchronizedMap;
import static java.util.regex.Pattern.compile;


public class ExpressionParser extends AbstractParser {
    private boolean returnBigDecimal = false;
    private int roundingMode = BigDecimal.ROUND_HALF_DOWN;

    private Object ctx;
    private VariableResolverFactory variableFactory;
    private final Stack stk = new ExecutionStack();

    private static Map<String, char[]> EX_PRECACHE;

    static {
        configureFactory();
    }

    static void configureFactory() {
        if (MVEL.THREAD_SAFE) {
            EX_PRECACHE = synchronizedMap(new WeakHashMap<String, char[]>(10));
        }
        else {
            EX_PRECACHE = new WeakHashMap<String, char[]>(10);
        }
    }


    Object parse() {
        stk.clear();
        fields = (Token.BOOLEAN_MODE & fields);
        cursor = 0;

        parseAndExecuteInterpreted();

        return handleParserEgress(stk.peek(), (fields & Token.BOOLEAN_MODE) != 0, returnBigDecimal);
    }

    private void parseAndExecuteInterpreted() {
        Token tk;
        Integer operator;

        while ((tk = nextToken()) != null) {
            if (stk.size() == 0) {
                stk.push(tk.getReducedValue(ctx, ctx, variableFactory));
            }

            if (!tk.isOperator()) {
                continue;
            }

            switch (reduceBinary(operator = tk.getOperator())) {
                case-1:
                    return;
                case 0:
                    break;
                case 1:
                    continue;
            }

            if ((tk = nextToken()) == null) continue;

            stk.push(tk.getReducedValue(ctx, ctx, variableFactory), operator);

            reduceTrinary();
        }
    }


    public TokenIterator compileTokens() {
        Token tk;
        TokenMap tokenMap = null;

        while ((tk = nextToken()) != null) {
            if (tk.isSubeval()) {
                tk.setAccessor((ExecutableStatement) MVEL.compileExpression(tk.getNameAsArray()));
            }

            if (tokenMap == null) {
                tokenMap = new TokenMap(tk);
            }
            else {
                tokenMap.addTokenNode(tk);
            }
        }

        if (tokenMap == null)
            throw new CompileException("Nothing to do.");

        return new FastTokenIterator(tokenMap);
    }


    /**
     * This method is called to subEval a binary statement (or junction).  The difference between a binary and
     * trinary statement, as far as the parser is concerned is that a binary statement has an entrant state,
     * where-as a trinary statement does not.  Consider: (x && y): in this case, x will be reduced first, and
     * therefore will have a value on the stack, so the parser will then process the next statement as a binary,
     * which is (&& y).
     * <p/>
     * You can also think of a binary statement in terms of: ({stackvalue} op value)
     *
     * @param o - operator
     * @return int - behaviour code
     */
    private int reduceBinary(int o) {
        assert debug("BINARY_OP " + o + " PEEK=<<" + stk.peek() + ">>");
        switch (o) {
            case AND:
                if (stk.peek() instanceof Boolean && !((Boolean) stk.peek())) {
                    assert debug("STMT_UNWIND");
                    if (unwindStatement()) {
                        return -1;
                    }
                    else {
                        stk.clear();
                        return 1;
                    }
                }
                else {
                    stk.discard();
                    return 1;
                }
            case OR:
                if (stk.peek() instanceof Boolean && ((Boolean) stk.peek())) {
                    assert debug("STMT_UNWIND");
                    if (unwindStatement()) {
                        return -1;
                    }
                    else {
                        stk.clear();
                        return 1;
                    }
                }
                else {
                    stk.discard();
                    return 1;
                }

            case TERNARY:
                Token tk;
                if ((Boolean) stk.pop()) {
                    return 1;
                }
                else {
                    stk.clear();

                    while ((tk = nextToken()) != null && !tk.isOperator(Operator.TERNARY_ELSE)) {
                        //nothing
                    }

                    return 1;
                }


            case TERNARY_ELSE:
                return -1;

            case END_OF_STMT:
                /**
                 * Assignments are a special scenario for dealing with the stack.  Assignments are basically like
                 * held-over failures that basically kickstart the parser when an assignment operator is is
                 * encountered.  The originating token is captured, and the the parser is told to march on.  The
                 * resultant value on the stack is then used to populate the target variable.
                 *
                 * The other scenario in which we don't want to wipe the stack, is when we hit the end of the
                 * statement, because that top stack value is the value we want back from the parser.
                 */

                if ((fields & Token.ASSIGN) != 0) {
                    return -1;
                }
                else if (!hasNoMore()) {
                    stk.clear();
                }

                return 1;
        }
        return 0;
    }

    private boolean hasNoMore() {
        return cursor >= length;
    }

    /**
     * This method is called when we reach the point where we must subEval a trinary operation in the expression.
     * (ie. val1 op val2).  This is not the same as a binary operation, although binary operations would appear
     * to have 3 structures as well.  A binary structure (or also a junction in the expression) compares the
     * current state against 2 downrange structures (usually an op and a val).
     */
    private void reduceTrinary() {
        Object v1 = null, v2;
        Integer operator;
        try {
            while (stk.size() > 1) {
                if ((v1 = stk.pop()) instanceof Boolean) {
                    /**
                     * There is a boolean value at the top of the stk, so we
                     * are at a boolean junction.
                     */
                    operator = (Integer) stk.pop();
                    v2 = processToken(stk.pop());
                }
                else {
                    operator = (Integer) v1;
                    v1 = processToken(stk.pop());
                    v2 = processToken(stk.pop());
                }

                assert debug("DO_TRINARY <<OPCODE_" + operator + ">> register1=" + v1 + "; register2=" + v2);

                switch (operator) {
                    case ADD:
                        if (v1 instanceof BigDecimal && v2 instanceof BigDecimal) {
                            stk.push(((BigDecimal) v1).add((BigDecimal) v2));
                        }
                        else {
                            stk.push(valueOf(v2) + valueOf(v1));
                        }
                        break;

                    case SUB:
                        stk.push(((BigDecimal) v2).subtract(((BigDecimal) v1)));
                        break;

                    case DIV:
                        stk.push(((BigDecimal) v2).divide(((BigDecimal) v1), 20, roundingMode));
                        break;

                    case MULT:
                        stk.push(((BigDecimal) v2).multiply((BigDecimal) v1));
                        break;

                    case MOD:
                        stk.push(((BigDecimal) v2).remainder((BigDecimal) v1));
                        break;

                    case EQUAL:

                        if (v1 instanceof BigDecimal && v2 instanceof BigDecimal) {
                            stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) == 0);
                        }
                        else if (v1 != null)
                            stk.push(v1.equals(v2));
                        else if (v2 != null)
                            stk.push(v2.equals(v1));
                        else
                            stk.push(v1 == v2);
                        break;

                    case NEQUAL:

                        if (v1 instanceof BigDecimal && v2 instanceof BigDecimal) {
                            stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) != 0);
                        }
                        else if (v1 != null)
                            stk.push(!v1.equals(v2));
                        else if (v2 != null)
                            stk.push(!v2.equals(v1));
                        else
                            stk.push(v1 != v2);
                        break;
                    case GTHAN:
                        stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) == 1);
                        break;
                    case LTHAN:
                        stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) == -1);
                        break;
                    case GETHAN:
                        stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) >= 0);
                        break;
                    case LETHAN:
                        stk.push(((BigDecimal) v2).compareTo((BigDecimal) v1) <= 0);
                        break;

                    case AND:
                        if (v2 instanceof Boolean && v1 instanceof Boolean) {
                            stk.push(((Boolean) v2) && ((Boolean) v1));
                            break;
                        }
                        else if (((Boolean) v2)) {
                            stk.push(v2, Operator.AND, v1);
                        }
                        return;

                    case OR:
                        if (v2 instanceof Boolean && v1 instanceof Boolean) {
                            stk.push(((Boolean) v2) || ((Boolean) v1));
                            break;
                        }
                        else {
                            stk.push(v2, Operator.OR, v1);
                            return;
                        }

                    case CHOR:
                        if (!isEmpty(v2) || !isEmpty(v1)) {
                            stk.clear();
                            stk.push(!isEmpty(v2) ? v2 : v1);
                            return;
                        }
                        else stk.push(null);
                        break;

                    case REGEX:
                        stk.push(compile(valueOf(v1)).matcher(valueOf(v2)).matches());
                        break;

                    case INSTANCEOF:
                        if (v1 instanceof Class)
                            stk.push(((Class) v1).isInstance(v2));
                        else
                            stk.push(forName(valueOf(v1)).isInstance(v2));

                        break;

                    case CONVERTABLE_TO:
                        if (v1 instanceof Class)
                            stk.push(canConvert(v2.getClass(), (Class) v1));
                        else
                            stk.push(canConvert(v2.getClass(), forName(valueOf(v1))));
                        break;

                    case CONTAINS:
                        stk.push(containsCheck(v2, v1));
                        break;

                    case BW_AND:
                        stk.push(asInt(v2) & asInt(v1));
                        break;

                    case BW_OR:
                        stk.push(asInt(v2) | asInt(v1));
                        break;

                    case BW_XOR:
                        stk.push(asInt(v2) ^ asInt(v1));
                        break;

                    case BW_SHIFT_LEFT:
                        stk.push(asInt(v2) << asInt(v1));
                        break;

                    case BW_USHIFT_LEFT:
                        int iv2 = asInt(v2);
                        if (iv2 < 0) iv2 *= -1;
                        stk.push(iv2 << asInt(v1));
                        break;

                    case BW_SHIFT_RIGHT:
                        stk.push(asInt(v2) >> asInt(v1));
                        break;

                    case BW_USHIFT_RIGHT:
                        stk.push(asInt(v2) >>> asInt(v1));
                        break;

                    case STR_APPEND:
                        stk.push(new StringAppender(valueOf(v2)).append(valueOf(v1)).toString());
                        break;

                    case PROJECTION:
                        try {
                            List<Object> list = new ArrayList<Object>(((Collection) v1).size());
                            for (Object o : (Collection) v1) {
                                list.add(get(valueOf(v2), o));
                            }
                            stk.push(list);
                        }
                        catch (ClassCastException e) {
                            throw new ParseException("projections can only be peformed on collections");
                        }
                        break;

                    case SOUNDEX:
                        stk.push(Soundex.soundex(valueOf(v1)).equals(Soundex.soundex(valueOf(v2))));
                        break;

                    case SIMILARITY:
                        stk.push(similarity(valueOf(v1), valueOf(v2)));
                        break;

                }
            }
        }
        catch (ClassCastException e) {
            if ((fields & Token.LOOKAHEAD) == 0) {
                /**
                 * This will allow for some developers who like messy expressions to compileAccessor
                 * away with some messy constructs like: a + b < c && e + f > g + q instead
                 * of using brackets like (a + b < c) && (e + f > g + q)
                 */

                fields |= Token.LOOKAHEAD;

                Token tk = nextToken();
                if (tk != null) {
                    stk.push(v1, nextToken(), tk.getOperator());

                    reduceTrinary();
                    return;
                }
            }
            throw new CompileException("syntax error or incomptable types", expr, cursor, e);

        }
        catch (Exception e) {
            throw new CompileException("failed to subEval expression", e);
        }

    }

    private static int asInt(final Object o) {
        return ((BigDecimal) o).intValue();
    }

    private Object processToken(Object operand) {
        setFieldFalse(Token.EVAL_RIGHT);

        if (operand instanceof BigDecimal) {
            return operand;
        }
        else if (isNumber(operand)) {
            return new BigDecimal(valueOf(operand));
        }
        else {
            return operand;
        }
    }


    /**
     * This method is called to unwind the current statement without any reduction or further parsing.
     *
     * @return -
     */
    private boolean unwindStatement() {
        Token tk;
        while ((tk = nextToken()) != null && !tk.isOperator(Operator.END_OF_STMT)) {
            //nothing
        }
        return tk == null;
    }


    private void setExpression(String expression) {
        if (expression != null && !"".equals(expression)) {
            if (!EX_PRECACHE.containsKey(expression)) {
                length = (this.expr = expression.toCharArray()).length;

                // trim any whitespace.
                while (isWhitespace(this.expr[length - 1])) length--;

                char[] e = new char[length];
                System.arraycopy(this.expr, 0, e, 0, length);

                EX_PRECACHE.put(expression, e);
            }
            else {
                length = (expr = EX_PRECACHE.get(expression)).length;
            }
        }
    }


    public ExpressionParser setExpressionArray(char[] expressionArray) {
        this.length = (this.expr = expressionArray).length;
        return this;
    }

    public int getRoundingMode() {
        return roundingMode;
    }

    public void setRoundingMode(int roundingMode) {
        this.roundingMode = roundingMode;
    }

    public boolean isReturnBigDecimal() {
        return returnBigDecimal;
    }

    public void setReturnBigDecimal(boolean returnBigDecimal) {
        this.returnBigDecimal = returnBigDecimal;
    }


    public boolean isBooleanModeOnly() {
        return (fields & Token.BOOLEAN_MODE) != 0;
    }

    /**
     * <p>Sets the compiler into boolean mode.  When operating in boolean-mode, the
     * parser ALWAYS returns a Boolean value based on the Boolean-only rules.</p>
     * <p/>
     * The returned boolean value will be returned based on the following rules, in this order:
     * <p/>
     * 1. Is the terminal value on the stack a Boolean? If so, return it directly.<br/>
     * 2. Is the value on the stack null? If so, return false.<br/>
     * 3. Is the value on the stack empty (0, zero-length, or an empty collections? If so, return false.<br/>
     * 4. Otherwise return true.<br/>
     *
     * @param booleanModeOnly - boolean denoting mode.
     */
    public void setBooleanModeOnly(boolean booleanModeOnly) {
        if (booleanModeOnly)
            fields |= Token.BOOLEAN_MODE;
        else
            setFieldFalse(Token.BOOLEAN_MODE);
    }


    ExpressionParser(char[] expression, Object ctx, Map<String, Object> variables) {
        this.expr = expression;
        this.length = expr.length;
        this.ctx = ctx;
        this.variableFactory = new MapVariableResolverFactory(variables);
    }

    ExpressionParser(String expression, Object ctx, Map<String, Object> variables) {
        setExpression(expression);
        this.ctx = ctx;
        this.variableFactory = new MapVariableResolverFactory(variables);
    }

    ExpressionParser(String expression) {
        setExpression(expression);
    }

    ExpressionParser(char[] expression) {
        this.length = (this.expr = expression).length;
    }


    /**
     * Lots of messy constructors beyond here.  Most exist for performance considerations (code inlinability, etc.)
     */
    public ExpressionParser() {
    }


    ExpressionParser(char[] expr, Object ctx, VariableResolverFactory resolverFactory) {
        this.length = (this.expr = expr).length;
        this.ctx = ctx;
        this.variableFactory = resolverFactory;
    }


    ExpressionParser(String expression, Object ctx, Map<String, Object> variables, boolean booleanMode) {
        setExpression(expression);
        this.ctx = ctx;
        this.variableFactory = new MapVariableResolverFactory(variables);
        this.fields = booleanMode ? fields | Token.BOOLEAN_MODE : fields;
    }

    ExpressionParser(String expression, Object ctx, boolean booleanMode) {
        setExpression(expression);
        this.ctx = ctx;
        this.fields = booleanMode ? fields | Token.BOOLEAN_MODE : fields;
    }


    ExpressionParser(String expression, Object ctx, VariableResolverFactory resolverFactory, boolean booleanMode) {
        setExpression(expression);
        this.ctx = ctx;
        this.variableFactory = resolverFactory;
        this.fields = booleanMode ? fields | Token.BOOLEAN_MODE : fields;
    }

    ExpressionParser(Object ctx, Map<String, Object> variables) {
        this.ctx = ctx;
        this.variableFactory = new MapVariableResolverFactory(variables);
    }

    ExpressionParser(String expression, Object ctx, VariableResolverFactory resolverFactory) {
        setExpression(expression);
        this.ctx = ctx;
        this.variableFactory = resolverFactory;
    }

    ExpressionParser(String expression, VariableResolverFactory resolverFactory) {
        setExpression(expression);
        this.variableFactory = resolverFactory;
    }


    ExpressionParser(String expression, Object ctx) {
        setExpression(expression);
        this.ctx = ctx;
    }


}
