/* -*- Mode: java; tab-width: 4; indent-tabs-mode: 1; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Stack;
import org.mozilla.javascript.json.JsonParser;
import org.mozilla.javascript.xml.XMLObject;

/**
 * This class implements the JSON native object. See ECMA 15.12.
 *
 * @author Matthew Crumley, Raphael Speyer
 */
public final class NativeJSON extends IdScriptableObject {
    private static final long serialVersionUID = -4567599697595654984L;

    private static final Object JSON_TAG = "JSON";

    private static final int MAX_STRINGIFY_GAP_LENGTH = 10;

    static void init(Scriptable scope, boolean sealed) {
        NativeJSON obj = new NativeJSON();
        obj.activatePrototypeMap(MAX_ID);
        obj.setPrototype(getObjectPrototype(scope));
        obj.setParentScope(scope);
        if (sealed) {
            obj.sealObject();
        }
        ScriptableObject.defineProperty(scope, "JSON", obj, ScriptableObject.DONTENUM);
    }

    private NativeJSON() {}

    @Override
    public String getClassName() {
        return "JSON";
    }

    @Override
    protected void initPrototypeId(int id) {
        if (id <= LAST_METHOD_ID) {
            String name;
            int arity;
            switch (id) {
                case Id_toSource:
                    arity = 0;
                    name = "toSource";
                    break;
                case Id_parse:
                    arity = 2;
                    name = "parse";
                    break;
                case Id_stringify:
                    arity = 3;
                    name = "stringify";
                    break;
                default:
                    throw new IllegalStateException(String.valueOf(id));
            }
            initPrototypeMethod(JSON_TAG, id, name, arity);
        } else {
            throw new IllegalStateException(String.valueOf(id));
        }
    }

    @Override
    public Object execIdCall(
            IdFunctionObject f, Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (!f.hasTag(JSON_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args);
        }
        int methodId = f.methodId();
        switch (methodId) {
            case Id_toSource:
                return "JSON";

            case Id_parse:
                {
                    String jtext = ScriptRuntime.toString(args, 0);
                    Object reviver = null;
                    if (args.length > 1) {
                        reviver = args[1];
                    }
                    if (reviver instanceof Callable) {
                        return parse(cx, scope, jtext, (Callable) reviver);
                    }
                    return parse(cx, scope, jtext);
                }

            case Id_stringify:
                {
                    Object value = Undefined.instance, replacer = null, space = null;

                    if (args.length > 0) {
                        value = args[0];
                        if (args.length > 1) {
                            replacer = args[1];
                            if (args.length > 2) {
                                space = args[2];
                            }
                        }
                    }

                    return stringify(cx, scope, value, replacer, space);
                }

            default:
                throw new IllegalStateException(String.valueOf(methodId));
        }
    }

    private static Object parse(Context cx, Scriptable scope, String jtext) {
        try {
            return new JsonParser(cx, scope).parseValue(jtext);
        } catch (JsonParser.ParseException ex) {
            throw ScriptRuntime.constructError("SyntaxError", ex.getMessage());
        }
    }

    public static Object parse(Context cx, Scriptable scope, String jtext, Callable reviver) {
        Object unfiltered = parse(cx, scope, jtext);
        Scriptable root = cx.newObject(scope);
        root.put("", root, unfiltered);
        return walk(cx, scope, reviver, root, "");
    }

    private static Object walk(
            Context cx, Scriptable scope, Callable reviver, Scriptable holder, Object name) {
        final Object property;
        if (name instanceof Number) {
            property = holder.get(((Number) name).intValue(), holder);
        } else {
            property = holder.get(((String) name), holder);
        }

        if (property instanceof Scriptable) {
            Scriptable val = ((Scriptable) property);
            if (val instanceof NativeArray) {
                long len = ((NativeArray) val).getLength();
                for (long i = 0; i < len; i++) {
                    // indices greater than MAX_INT are represented as strings
                    if (i > Integer.MAX_VALUE) {
                        String id = Long.toString(i);
                        Object newElement = walk(cx, scope, reviver, val, id);
                        if (newElement == Undefined.instance) {
                            val.delete(id);
                        } else {
                            val.put(id, val, newElement);
                        }
                    } else {
                        int idx = (int) i;
                        Object newElement = walk(cx, scope, reviver, val, Integer.valueOf(idx));
                        if (newElement == Undefined.instance) {
                            val.delete(idx);
                        } else {
                            val.put(idx, val, newElement);
                        }
                    }
                }
            } else {
                Object[] keys = val.getIds();
                for (Object p : keys) {
                    Object newElement = walk(cx, scope, reviver, val, p);
                    if (newElement == Undefined.instance) {
                        if (p instanceof Number) val.delete(((Number) p).intValue());
                        else val.delete((String) p);
                    } else {
                        if (p instanceof Number) val.put(((Number) p).intValue(), val, newElement);
                        else val.put((String) p, val, newElement);
                    }
                }
            }
        }

        return reviver.call(cx, scope, holder, new Object[] {name, property});
    }

    private static String repeat(char c, int count) {
        char chars[] = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static class StringifyState {
        StringifyState(
                Context cx,
                Scriptable scope,
                String indent,
                String gap,
                Callable replacer,
                Object[] propertyList) {
            this.cx = cx;
            this.scope = scope;

            this.indent = indent;
            this.gap = gap;
            this.replacer = replacer;
            this.propertyList = propertyList;
        }

        Stack<Object> stack = new Stack<Object>();
        String indent;
        String gap;
        Callable replacer;
        Object[] propertyList;

        Context cx;
        Scriptable scope;
    }

    public static Object stringify(
            Context cx, Scriptable scope, Object value, Object replacer, Object space) {
        String indent = "";
        String gap = "";

        Object[] propertyList = null;
        Callable replacerFunction = null;

        if (replacer instanceof Callable) {
            replacerFunction = (Callable) replacer;
        } else if (replacer instanceof NativeArray) {
            LinkedHashSet<Object> propertySet = new LinkedHashSet<Object>();
            NativeArray replacerArray = (NativeArray) replacer;
            for (int i : replacerArray.getIndexIds()) {
                Object v = replacerArray.get(i, replacerArray);
                if (v instanceof String) {
                    propertySet.add(v);
                } else if (v instanceof Number
                        || v instanceof NativeString
                        || v instanceof NativeNumber) {
                    // TODO: This should also apply to subclasses of NativeString and NativeNumber
                    // once the class, extends, and super keywords are implemented
                    propertySet.add(ScriptRuntime.toString(v));
                }
            }
            // After items have been converted to strings and duplicates removed, transform to an
            // array and convert indexed keys back to Integers as required for later processing
            propertyList = new Object[propertySet.size()];
            int i = 0;
            for (Object prop : propertySet) {
                ScriptRuntime.StringIdOrIndex idOrIndex = ScriptRuntime.toStringIdOrIndex(cx, prop);
                // This will always be a String or Integer
                propertyList[i++] =
                        (idOrIndex.stringId == null) ? idOrIndex.index : idOrIndex.stringId;
            }
        }

        if (space instanceof NativeNumber) {
            space = Double.valueOf(ScriptRuntime.toNumber(space));
        } else if (space instanceof NativeString) {
            space = ScriptRuntime.toString(space);
        }

        if (space instanceof Number) {
            int gapLength = (int) ScriptRuntime.toInteger(space);
            gapLength = Math.min(MAX_STRINGIFY_GAP_LENGTH, gapLength);
            gap = (gapLength > 0) ? repeat(' ', gapLength) : "";
        } else if (space instanceof String) {
            gap = (String) space;
            if (gap.length() > MAX_STRINGIFY_GAP_LENGTH) {
                gap = gap.substring(0, MAX_STRINGIFY_GAP_LENGTH);
            }
        }

        StringifyState state =
                new StringifyState(cx, scope, indent, gap, replacerFunction, propertyList);

        ScriptableObject wrapper = new NativeObject();
        wrapper.setParentScope(scope);
        wrapper.setPrototype(ScriptableObject.getObjectPrototype(scope));
        wrapper.defineProperty("", value, 0);
        return str("", wrapper, state);
    }

    private static Object str(Object key, Scriptable holder, StringifyState state) {
        Object value = null;
        Object unwrappedJavaValue = null;

        String keyString = null;
        int keyInt = 0;
        if (key instanceof String) {
            keyString = (String) key;
            value = getProperty(holder, keyString);
        } else {
            keyInt = ((Number) key).intValue();
            value = getProperty(holder, keyInt);
        }

        if (value instanceof Scriptable && hasProperty((Scriptable) value, "toJSON")) {
            Object toJSON = getProperty((Scriptable) value, "toJSON");
            if (toJSON instanceof Callable) {
                value =
                        callMethod(
                                state.cx,
                                (Scriptable) value,
                                "toJSON",
                                new Object[] {
                                    keyString == null ? Integer.toString(keyInt) : keyString
                                });
            }
        } else if (value instanceof BigInteger) {
            Scriptable bigInt = ScriptRuntime.toObject(state.cx, state.scope, value);
            if (hasProperty(bigInt, "toJSON")) {
                Object toJSON = getProperty(bigInt, "toJSON");
                if (toJSON instanceof Callable) {
                    value =
                            callMethod(
                                    state.cx,
                                    bigInt,
                                    "toJSON",
                                    new Object[] {
                                        keyString == null ? Integer.toString(keyInt) : keyString
                                    });
                }
            }
        }

        if (state.replacer != null) {
            value = state.replacer.call(state.cx, state.scope, holder, new Object[] {key, value});
        }

        if (ScriptRuntime.isSymbol(value)) return Undefined.instance;

        if (value instanceof NativeNumber) {
            value = Double.valueOf(ScriptRuntime.toNumber(value));
        } else if (value instanceof NativeString) {
            value = ScriptRuntime.toString(value);
        } else if (value instanceof NativeBoolean) {
            value = ((NativeBoolean) value).getDefaultValue(ScriptRuntime.BooleanClass);
        } else if (state.cx.getLanguageVersion() >= Context.VERSION_ES6
                && value instanceof NativeBigInt) {
            value = ((NativeBigInt) value).getDefaultValue(ScriptRuntime.BigIntegerClass);
        } else if (value instanceof NativeJavaObject) {
            unwrappedJavaValue = ((NativeJavaObject) value).unwrap();
            if (!(unwrappedJavaValue instanceof Map
                    || unwrappedJavaValue instanceof Collection
                    || unwrappedJavaValue.getClass().isArray())) {
                value = unwrappedJavaValue;
            } else {
                // Don't unwrap Java objects to be processed by jo() or ja()
                unwrappedJavaValue = null;
            }
        } else if (value instanceof XMLObject) {
            value = ((XMLObject) value).toString();
        }

        if (value == null) return "null";
        if (value.equals(Boolean.TRUE)) return "true";
        if (value.equals(Boolean.FALSE)) return "false";

        if (value instanceof CharSequence) {
            return quote(value.toString());
        }

        if (value instanceof Number) {
            if (value instanceof BigInteger) {
                throw ScriptRuntime.typeErrorById("msg.json.cant.serialize", "BigInt");
            }
            double d = ((Number) value).doubleValue();
            if (!Double.isNaN(d)
                    && d != Double.POSITIVE_INFINITY
                    && d != Double.NEGATIVE_INFINITY) {
                return ScriptRuntime.toString(value);
            }
            return "null";
        }

        if (unwrappedJavaValue != null) {
            return javaToJSON(value, state);
        }

        if ((value instanceof Scriptable) && !(value instanceof Callable)) {
            if (isObjectArrayLike(value)) {
                return ja((Scriptable) value, state);
            }
            return jo((Scriptable) value, state);
        }
        return Undefined.instance;
    }

    private static String join(Collection<Object> objs, String delimiter) {
        if (objs == null || objs.isEmpty()) {
            return "";
        }
        Iterator<Object> iter = objs.iterator();
        if (!iter.hasNext()) return "";
        StringBuilder builder = new StringBuilder(iter.next().toString());
        while (iter.hasNext()) {
            builder.append(delimiter).append(iter.next());
        }
        return builder.toString();
    }

    private static String jo(Scriptable value, StringifyState state) {
        Object trackValue = value, unwrapped = null;
        if (value instanceof Wrapper) {
            trackValue = unwrapped = ((Wrapper) value).unwrap();
        }

        if (state.stack.search(trackValue) != -1) {
            throw ScriptRuntime.typeErrorById("msg.cyclic.value", trackValue.getClass().getName());
        }
        state.stack.push(trackValue);

        if (unwrapped instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) unwrapped;
            Scriptable nObj = state.cx.newObject(state.scope);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Symbol) continue;
                Object wrappedValue = Context.javaToJS(entry.getValue(), state.scope, state.cx);
                int attributes;
                String key;
                if (entry.getKey() instanceof String) {
                    // Keys that are actually Strings are permanent and will not be
                    // overridden by other objects having the same toString value.
                    key = (String) entry.getKey();
                    attributes = ScriptableObject.READONLY | ScriptableObject.PERMANENT;
                } else {
                    // To avoid duplicate keys in JSON, replace previous key having the same
                    // toString value as the current object.
                    key = entry.getKey().toString();
                    attributes = ScriptableObject.EMPTY;
                }
                try {
                    ScriptableObject.defineProperty(nObj, key, wrappedValue, attributes);
                } catch (EcmaError error) {
                    // ignore TypeErrors if we tried to rewrite the property for a
                    // String key.
                }
            }
            value = nObj;
        }

        String stepback = state.indent;
        state.indent = state.indent + state.gap;
        Object[] k = null;
        if (state.propertyList != null) {
            k = state.propertyList;
        } else {
            k = value.getIds();
        }

        Collection<Object> partial = new LinkedList<Object>();

        for (Object p : k) {
            Object strP = str(p, value, state);
            if (strP != Undefined.instance) {
                String member = quote(p.toString()) + ":";
                if (state.gap.length() > 0) {
                    member = member + " ";
                }
                member = member + strP;
                partial.add(member);
            }
        }

        final String finalValue;

        if (partial.isEmpty()) {
            finalValue = "{}";
        } else {
            if (state.gap.length() == 0) {
                finalValue = '{' + join(partial, ",") + '}';
            } else {
                String separator = ",\n" + state.indent;
                String properties = join(partial, separator);
                finalValue = "{\n" + state.indent + properties + '\n' + stepback + '}';
            }
        }

        state.stack.pop();
        state.indent = stepback;
        return finalValue;
    }

    private static String ja(Scriptable value, StringifyState state) {
        Object trackValue = value, unwrapped = null;
        if (value instanceof Wrapper) {
            trackValue = unwrapped = ((Wrapper) value).unwrap();
        }
        if (state.stack.search(trackValue) != -1) {
            throw ScriptRuntime.typeErrorById("msg.cyclic.value", trackValue.getClass().getName());
        }
        state.stack.push(trackValue);

        String stepback = state.indent;
        state.indent = state.indent + state.gap;
        Collection<Object> partial = new LinkedList<Object>();

        if (unwrapped != null) {
            Object[] elements = null;
            if (unwrapped.getClass().isArray()) {
                int length = Array.getLength(unwrapped);
                elements = new Object[length];
                for (int i = 0; i < length; i++) {
                    elements[i] = Context.javaToJS(Array.get(unwrapped, i), state.scope, state.cx);
                }
            } else if (unwrapped instanceof Collection) {
                Collection<?> collection = (Collection<?>) unwrapped;
                elements = new Object[collection.size()];
                int i = 0;
                for (Object o : collection) {
                    elements[i++] = Context.javaToJS(o, state.scope, state.cx);
                }
            }
            if (elements != null) {
                value = state.cx.newArray(state.scope, elements);
            }
        }

        long len = ((NativeArray) value).getLength();

        for (long index = 0; index < len; index++) {
            Object strP;
            if (index > Integer.MAX_VALUE) {
                strP = str(Long.toString(index), value, state);
            } else {
                strP = str(Integer.valueOf((int) index), value, state);
            }
            if (strP == Undefined.instance) {
                partial.add("null");
            } else {
                partial.add(strP);
            }
        }

        final String finalValue;

        if (partial.isEmpty()) {
            finalValue = "[]";
        } else {
            if (state.gap.length() == 0) {
                finalValue = '[' + join(partial, ",") + ']';
            } else {
                String separator = ",\n" + state.indent;
                String properties = join(partial, separator);
                finalValue = "[\n" + state.indent + properties + '\n' + stepback + ']';
            }
        }

        state.stack.pop();
        state.indent = stepback;
        return finalValue;
    }

    private static String quote(String string) {
        StringBuilder product =
                new StringBuilder(string.length() + 2); // two extra chars for " on either side
        product.append('"');
        int length = string.length();
        for (int i = 0; i < length; i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"':
                    product.append("\\\"");
                    break;
                case '\\':
                    product.append("\\\\");
                    break;
                case '\b':
                    product.append("\\b");
                    break;
                case '\f':
                    product.append("\\f");
                    break;
                case '\n':
                    product.append("\\n");
                    break;
                case '\r':
                    product.append("\\r");
                    break;
                case '\t':
                    product.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        product.append("\\u");
                        String hex = String.format("%04x", Integer.valueOf(c));
                        product.append(hex);
                    } else {
                        product.append(c);
                    }
                    break;
            }
        }
        product.append('"');
        return product.toString();
    }

    private static Object javaToJSON(Object value, StringifyState state) {
        value = state.cx.getJavaToJSONConverter().apply(value);
        value = Context.javaToJS(value, state.scope, state.cx);

        ScriptableObject wrapper = new NativeObject();
        wrapper.setParentScope(state.scope);
        wrapper.setPrototype(ScriptableObject.getObjectPrototype(state.scope));
        wrapper.defineProperty("", value, 0);
        return str("", wrapper, state);
    }

    private static boolean isObjectArrayLike(Object o) {
        if (o instanceof NativeArray) {
            return true;
        }
        if (o instanceof NativeJavaObject) {
            Object unwrapped = ((NativeJavaObject) o).unwrap();
            return (unwrapped instanceof Collection) || (unwrapped.getClass().isArray());
        }
        return false;
    }

    @Override
    protected int findPrototypeId(String s) {
        int id;
        switch (s) {
            case "toSource":
                id = Id_toSource;
                break;
            case "parse":
                id = Id_parse;
                break;
            case "stringify":
                id = Id_stringify;
                break;
            default:
                id = 0;
                break;
        }
        return id;
    }

    private static final int Id_toSource = 1,
            Id_parse = 2,
            Id_stringify = 3,
            LAST_METHOD_ID = 3,
            MAX_ID = 3;
}
