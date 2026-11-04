package pane5250.update;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

final class Json {
    private final String text;
    private int index,nodes;
    private Json(String text) { this.text=text; }
    static Object parse(String text) throws IOException {
        Json parser=new Json(text);
        Object value=parser.value(0); parser.space();
        if (parser.index!=text.length()) throw new IOException("Unexpected trailing JSON data.");
        return value;
    }
    private void space() { while (index<text.length() && " \r\n\t".indexOf(text.charAt(index))>=0) index++; }
    private IOException invalid() { return new IOException("Invalid or oversized GitHub release JSON."); }
    private char next() throws IOException { if (index>=text.length()) throw invalid(); return text.charAt(index++); }
    private Object value(int depth) throws IOException {
        if (depth>32 || ++nodes>100_000) throw invalid();
        space(); char c=next();
        if (c=='"') return string();
        if (c=='[') {
            List<Object> values=new ArrayList<>(); space();
            if (index<text.length() && text.charAt(index)==']') { index++; return values; }
            do { values.add(value(depth+1)); space(); c=next(); if (c==']') return values; } while (c==',');
            throw invalid();
        }
        if (c=='{') {
            Map<String,Object> values=new LinkedHashMap<>(); space();
            if (index<text.length() && text.charAt(index)=='}') { index++; return values; }
            do {
                space(); if (next()!='"') throw invalid(); String key=string(); space();
                if (next()!=':' || values.containsKey(key)) throw invalid();
                values.put(key,value(depth+1)); space(); c=next(); if (c=='}') return values;
            } while (c==',');
            throw invalid();
        }
        if (c=='t' && take("rue")) return Boolean.TRUE;
        if (c=='f' && take("alse")) return Boolean.FALSE;
        if (c=='n' && take("ull")) return null;
        int start=index-1;
        while (index<text.length() && "-+0123456789.eE".indexOf(text.charAt(index))>=0) index++;
        String number=text.substring(start,index);
        if (number.length()>32 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
        try { return new BigDecimal(number); } catch (NumberFormatException e) { throw invalid(); }
    }
    private boolean take(String suffix) {
        if (!text.startsWith(suffix,index)) return false;
        index+=suffix.length(); return true;
    }
    private String string() throws IOException {
        StringBuilder result=new StringBuilder();
        while (true) {
            char c=next();
            if (c=='"') break;
            if (c<32 || result.length()>=262_144) throw invalid();
            if (c=='\\') {
                c=next();
                switch (c) {
                    case '"','\\','/' -> result.append(c);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        int value=0;
                        for (int i=0;i<4;i++) { int digit=Character.digit(next(),16); if (digit<0) throw invalid(); value=value*16+digit; }
                        result.append((char)value);
                    }
                    default -> throw invalid();
                }
            } else result.append(c);
        }
        for (int i=0;i<result.length();i++) {
            char c=result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return result.toString();
    }
}
