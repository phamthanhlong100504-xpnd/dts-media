package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owned test keys only; never use these to mint runtime tickets. */
public final class SimulationExamTestTokens {
    public static final String SECRET=MediaTestTokens.SECRET,BANK="dts-simulation-120-card01-v1",MANIFEST="a".repeat(64),ETAG="0123456789abcdef0123456789abcdef";
    private SimulationExamTestTokens() {}
    public static byte[] bytes(){byte[]b=new byte[1024];byte[]head={0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};System.arraycopy(head,0,b,0,head.length);return b;}
    public static String sha(){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes()));}catch(Exception e){throw new IllegalStateException(e);}}
    public static SecretKey key(String kind) {
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET),"HmacSHA256"));
            return Keys.hmacShaKeyFor(mac.doFinal(("dts-media:simulation-exam-"+kind+":v1").getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Map<String,Object> asset(UUID media) {
        return new HashMap<>(Map.of("mediaId",media.toString(),"fileName","fixture.mp4","sha256",sha(),"durationMs",27000,"videoTrackDurationMs",26800));
    }
    public static Map<String,Object> claims(String kind,UUID user,UUID media,UUID session) {
        Map<String,Object> c=new HashMap<>();
        c.put("iss","dts-practice");c.put("aud",List.of("dts-media"));c.put("sub",user.toString());
        c.put("iat",new Date(System.currentTimeMillis()-1000));c.put("exp",new Date(System.currentTimeMillis()+44000));c.put("jti",UUID.randomUUID().toString());
        c.put("purpose","SIMULATION_EXAM_"+kind.toUpperCase(Locale.ROOT));c.put("bankVersion",BANK);c.put("manifestSha256",MANIFEST);c.put("targetType","SIMULATION_VIDEO");
        if(kind.equals("eligibility"))c.put("candidates",List.of(asset(media)));
        else{c.put("sessionId",session.toString());c.put("position",1);c.put("playbackEventId",UUID.randomUUID().toString());c.put("asset",asset(media));c.put("objectEtag",ETAG);}
        return c;
    }
    public static String ticket(String kind,Map<String,Object> c) {
        return Jwts.builder().header().type("dts-simulation-exam-"+kind+"+jwt").and().claims(c).signWith(key(kind),Jwts.SIG.HS256).compact();
    }
    public static String access(UUID user,boolean admin,String...rights){return MediaTestTokens.access(user,List.of(admin?"ROLE_ADMIN":"ROLE_STUDENT"),rights);}
}
