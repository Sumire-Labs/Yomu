package com.sumirelabs.yomu;

import java.math.*;
import java.util.*;

/** Typed access only; does not mutate or recursively copy values. */
final class Json5Values {
    private Json5Values() { }
    static Object unwrap(Object value) {
        while(value instanceof CommentedValue)value=((CommentedValue)value).getValue();
        return value;
    }
    static String child(String path,String key) {
        StringBuilder out=new StringBuilder(path.length()+key.length()+4).append(path).append('[');
        StringEscapes.quoted(key,out,'"');return out.append(']').toString();
    }
    static String index(String path,int index) { return path+'['+Integer.toString(index)+']'; }
    static Json5AccessException expected(String path,String kind,Object value) {
        return new Json5AccessException(path,"Expected "+kind+", got "+(value==null?"null":value.getClass().getName()));
    }
    static String string(Object value,String path) {
        value=unwrap(value);if(!(value instanceof String))throw expected(path,"string",value);return (String)value;
    }
    static boolean bool(Object value,String path) {
        value=unwrap(value);if(!(value instanceof Boolean))throw expected(path,"boolean",value);return (Boolean)value;
    }
    static Number number(Object value,String path) {
        value=unwrap(value);if(!(value instanceof Number))throw expected(path,"number",value);return (Number)value;
    }
    static double floating(Object value,String path) {
        Number n=number(value,path);double result=n.doubleValue();
        if(Double.isInfinite(result) && !(n instanceof Double || n instanceof Float))
            throw new Json5AccessException(path,"Finite number overflows double");
        return result;
    }
    static BigDecimal decimal(Object value,String path) {
        Number n=number(value,path);
        if(n instanceof BigDecimal)return (BigDecimal)n;
        if(n instanceof BigInteger)return new BigDecimal((BigInteger)n);
        try { return new BigDecimal(n.toString()); }
        catch(NumberFormatException e){throw new Json5AccessException(path,"Number cannot be represented as BigDecimal",e);}
    }
    static BigDecimal integralDecimal(Number n) {
        return n instanceof BigDecimal ? (BigDecimal)n : n instanceof BigInteger ? new BigDecimal((BigInteger)n)
                : n instanceof Double || n instanceof Float ? new BigDecimal(n.doubleValue()) : new BigDecimal(n.toString());
    }
    static long integral(Object value,long min,long max,String path) {
        Number n=number(value,path);
        try {
            if(n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
                long result=n.longValue();
                if(result<min || result>max)throw new ArithmeticException("Overflow");
                return result;
            }
            BigDecimal decimal=integralDecimal(n);
            if(decimal.compareTo(BigDecimal.valueOf(min))<0 || decimal.compareTo(BigDecimal.valueOf(max))>0)
                throw new ArithmeticException("Overflow");
            if(decimal.signum()!=0 && decimal.stripTrailingZeros().scale()>0)throw new ArithmeticException("Fractional value");
            return decimal.longValueExact();
        } catch(ArithmeticException | NumberFormatException e) {
            throw new Json5AccessException(path,"Number is fractional, non-finite or outside ["+min+", "+max+"]",e);
        }
    }
    static BigInteger integer(Object value,String path) {
        Number n=number(value,path);
        if(n instanceof BigInteger)return (BigInteger)n;
        try {
            BigDecimal decimal=integralDecimal(n);
            if(decimal.signum()!=0 && decimal.stripTrailingZeros().scale()>0)throw new ArithmeticException("Fractional value");
            return decimal.toBigIntegerExact();
        }
        catch(ArithmeticException | NumberFormatException e){throw new Json5AccessException(path,"Expected an exact finite integer",e);}
    }
    @SuppressWarnings("unchecked")
    static Json5Object object(Object value,String path) {
        value=unwrap(value);
        if(!(value instanceof Map))throw expected(path,"object",value);
        if(value instanceof Json5Object)return new Json5Object(((Json5Object)value).asMap(),path);
        return new Json5Object((Map<String,Object>)value,path);
    }
    @SuppressWarnings("unchecked")
    static Json5Array array(Object value,String path) {
        value=unwrap(value);
        if(!(value instanceof List))throw expected(path,"array (List)",value);
        if(value instanceof Json5Array)return new Json5Array(((Json5Array)value).asList(),path);
        return new Json5Array((List<Object>)value,path);
    }
}
