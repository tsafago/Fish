package pane5250.net;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

/** RFC 4777 section 10 display startup confirmation, not presentation data or a sign-on assertion. */
public record StartupResponse(String code,String system,String device) {
    private static final Map<String,String> ERRORS=Map.ofEntries(
            Map.entry("2702","Device description not found"),Map.entry("2703","Controller description not found"),
            Map.entry("2777","Damaged device description"),Map.entry("8901","Device not varied on"),
            Map.entry("8902","Device not available; check whether another session is using it"),
            Map.entry("8903","Device not valid for this session"),Map.entry("8906","Session initiation failed"),
            Map.entry("8907","Session failure"),Map.entry("8910","Controller not valid for session"),
            Map.entry("8916","No matching device"),Map.entry("8917","Not authorized to device/object"),
            Map.entry("8918","Job canceled"),Map.entry("8920","Object partially damaged"),
            Map.entry("8921","Communications error"),Map.entry("8922","Negative response"),
            Map.entry("8923","Startup record built incorrectly"),Map.entry("8925","Device creation failed"),
            Map.entry("8928","Device change failed"),Map.entry("8929","Device vary on/off failed"),
            Map.entry("8930","Message queue does not exist"),Map.entry("8934","S/36 WSF startup unsupported"),
            Map.entry("8935","Session rejected"),Map.entry("8936","Security failure"),
            Map.entry("8937","Automatic sign-on rejected"),Map.entry("8940","Automatic configuration failed or not allowed"),
            Map.entry("I904","Incompatible system release"),Map.entry("0001","Sign-on system error"),
            Map.entry("0002","Unknown user"),Map.entry("0003","User disabled"),Map.entry("0004","Invalid password"),
            Map.entry("0005","Password expired"),Map.entry("0006","Pre-V2R2 password"),
            Map.entry("0008","Next invalid sign-on will revoke user"));
    private static final Map<String,String> KERBEROS_ERRORS=Map.ofEntries(
            Map.entry("0001","Mapped IBM i user profile is disabled"),
            Map.entry("0002","Kerberos principal maps to a system user profile; that mapping is not allowed"),
            Map.entry("0003","IBM i EIM configuration error"),
            Map.entry("0004","EIM has no IBM i user mapping for this Kerberos principal"),
            Map.entry("0005","EIM maps this Kerberos principal to multiple IBM i user profiles"),
            Map.entry("0006","EIM maps this principal to an IBM i user profile that does not exist"),
            Map.entry("1000","IBM i does not support a requested Kerberos mechanism; check Network Authentication Service"),
            Map.entry("2000","Kerberos input name is invalid or incorrectly formatted"),
            Map.entry("6000","Kerberos token signature is incorrect; check IBM i keytab and registered service identity"),
            Map.entry("7000","No usable credentials for the server security context"),
            Map.entry("9000","Kerberos token consistency checks failed"),
            Map.entry("A000","Kerberos credential structure consistency checks failed"),
            Map.entry("B000","Kerberos credentials have expired"),
            Map.entry("D000","IBM i Kerberos/GSS runtime failure; check NAS/EIM diagnostics"),
            Map.entry("I906","IBM i refused automatic sign-on. Check QRMTSIGN (*VERIFY), Network Authentication Service and EIM. No password fallback was used"));
    public static StartupResponse parse(byte[] bytes) throws IOException {
        if (bytes.length<38 || (bytes[4]&255)!=0x90 || bytes[5]!=0 || bytes[6]!=5
                || bytes[7]!=0x60 || bytes[8]!=6 || bytes[9]!=0
                || (((bytes[12]&255)<<8)|(bytes[13]&255))+12!=bytes.length)
            throw new IOException("Unsupported or malformed RFC 4777 startup confirmation.");
        return new StartupResponse(text(bytes,16,4),text(bytes,20,8),text(bytes,28,10));
    }
    private static String text(byte[] bytes,int offset,int length) {
        return new String(bytes,offset,length,Charset.forName("Cp037")).replace("\0","").strip();
    }
    public String diagnostic(String requested) throws IOException {
        return diagnostic(requested,false);
    }
    public String diagnostic(String requested,boolean kerberos) throws IOException {
        String context="Host startup "+code+(system.isEmpty() ? "" : " on "+system)
                +(device.isEmpty() ? "" : ", device "+device)+(requested.isEmpty() ? "" : " (requested "+requested+")");
        String detail=switch (code) {
            case "I901" -> "Device has less function than requested; authentication not verified";
            case "I902" -> "Device allocated; authentication not verified";
            case "I906" -> "Automatic sign-on not allowed; use the coming host sign-on screen";
            default -> null;
        };
        if (kerberos && KERBEROS_ERRORS.containsKey(code)) throw new IOException(context+": "+KERBEROS_ERRORS.get(code));
        if (detail==null) throw new IOException(context+": "+(kerberos && code.startsWith("000")
                ? "Unrecognized Kerberos startup result; session stopped"
                : ERRORS.getOrDefault(code,"Unrecognized startup result; session stopped")));
        if (!requested.isEmpty() && !device.isEmpty() && !requested.equals(device))
            throw new IOException(context+": host allocated a different device; requested name was not changed by the client.");
        return context+": "+detail;
    }
}
