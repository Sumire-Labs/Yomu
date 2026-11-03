package com.sumirelabs.yomu;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Precision paths linked only when a literal exceeds long or exact decimals are requested. */
final class ParsedNumbers {
    private ParsedNumbers() { }

    static Number integer(String source,int start,int end,int radix,boolean negative) {
        BigInteger value=new BigInteger(source.substring(start,end),radix);
        return negative ? value.negate() : value;
    }

    static Number decimal(String source,int start,int end,int exponentMark,int fractionDigits,boolean negative) {
        try {
            BigDecimal exact=exactDecimal(source,start,end,exponentMark,fractionDigits);
            // BigDecimal has no signed zero; keep the observable IEEE negative zero.
            return negative && exact.signum()==0 ? Double.valueOf(-0.0) : exact;
        } catch(NumberFormatException cause) {
            Json5Exception failure=new Json5Exception("Decimal literal exceeds BigDecimal exponent/scale range",source,start);
            failure.initCause(cause);throw failure;
        }
    }

    private static BigDecimal exactDecimal(String source,int start,int end,int exponentMark,int fractionDigits) {
        if(exponentMark==end)return new BigDecimal(source.substring(start,end));
        int cursor=exponentMark+1;
        boolean negativeExponent=source.charAt(cursor)=='-';
        if(negativeExponent||source.charAt(cursor)=='+')cursor++;
        long exponent=0;
        while(cursor<end) {
            exponent=exponent*10+source.charAt(cursor++)-'0';
            if(exponent>4294967295L)throw new NumberFormatException("Exponent exceeds BigDecimal scale range");
        }
        long scale=(long)fractionDigits-(negativeExponent ? -exponent : exponent);
        if(scale<Integer.MIN_VALUE||scale>Integer.MAX_VALUE)throw new NumberFormatException("BigDecimal scale overflow");
        BigDecimal significand=new BigDecimal(source.substring(start,exponentMark));
        return scale==significand.scale() ? significand : new BigDecimal(significand.unscaledValue(),(int)scale);
    }
}
