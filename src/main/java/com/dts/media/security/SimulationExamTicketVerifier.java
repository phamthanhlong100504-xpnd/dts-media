package com.dts.media.security;

import com.dts.media.api.response.SimulationExamMediaModel.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Separate signing domains for eligibility, active-position playback and completed-session review. */
@Component
public class SimulationExamTicketVerifier {
    public static final int MAX_TICKET=16384,MAX_CANDIDATES=16;
    private static final Set<String> BASE=Set.of("iss","aud","sub","iat","exp","jti","purpose","bankVersion","manifestSha256","targetType");
    private final Map<String,SecretKey> keys;
    public SimulationExamTicketVerifier(@Value("${jwt.access-token.secret:${JWT_ACCESS_SECRET:}}")String secret) {
        if(secret==null||secret.isBlank())throw new IllegalStateException("Configure Identity JWT access secret before starting Media");
        try {
            byte[] root;
            try {root=Decoders.BASE64.decode(secret);}catch(Exception e){try{root=Decoders.BASE64URL.decode(secret);}catch(Exception ignored){root=secret.getBytes(StandardCharsets.UTF_8);}}
            if(root.length<32)root=MessageDigest.getInstance("SHA-256").digest(root);
            Map<String,SecretKey> derived=new HashMap<>();
            for(String kind:List.of("eligibility","playback","review")) {
                Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(root,"HmacSHA256"));
                derived.put(kind,Keys.hmacShaKeyFor(mac.doFinal(("dts-media:simulation-exam-"+kind+":v1").getBytes(StandardCharsets.UTF_8))));
            }
            keys=Map.copyOf(derived);
        }catch(Exception e){throw new IllegalStateException("Invalid simulation exam ticket configuration");}
    }
    public EligibilityScope eligibility(String ticket,UUID user) {
        try {
            Claims c=parse(ticket,user,"eligibility",Set.of("candidates"));
            if(!(c.get("candidates")instanceof List<?> list)||list.isEmpty()||list.size()>MAX_CANDIDATES)throw new IllegalArgumentException();
            List<Asset> assets=new ArrayList<>();Set<UUID> unique=new HashSet<>();
            for(Object raw:list){Asset a=asset(raw);if(!unique.add(a.mediaId()))throw new IllegalArgumentException();assets.add(a);}
            return new EligibilityScope(c.get("bankVersion",String.class),c.get("manifestSha256",String.class),List.copyOf(assets));
        }catch(Exception e){throw denied();}
    }
    public PlaybackScope playback(String ticket,UUID user,boolean review) {
        try {
            Claims c=parse(ticket,user,review?"review":"playback",Set.of("sessionId","position","playbackEventId","asset","objectEtag"));
            return new PlaybackScope(c.get("bankVersion",String.class),c.get("manifestSha256",String.class),
                    MediaJwtVerifier.canonicalUuid(c.get("sessionId",String.class)),number(c.get("position"),1,10),
                    MediaJwtVerifier.canonicalUuid(c.get("playbackEventId",String.class)),asset(c.get("asset")),etag(c.get("objectEtag")),review);
        }catch(Exception e){throw denied();}
    }
    private Claims parse(String ticket,UUID user,String kind,Set<String> extras) {
        if(ticket==null||ticket.isBlank()||ticket.length()>MAX_TICKET||user==null)throw new IllegalArgumentException();
        var signed=Jwts.parser().verifyWith(keys.get(kind)).requireIssuer("dts-practice")
                .require("purpose","SIMULATION_EXAM_"+kind.toUpperCase(Locale.ROOT)).build().parseSignedClaims(ticket);
        Claims c=signed.getPayload();Instant now=Instant.now();Set<String> fields=new HashSet<>(BASE);fields.addAll(extras);
        if(!fields.equals(c.keySet())||!"HS256".equals(signed.getHeader().getAlgorithm())
                ||!("dts-simulation-exam-"+kind+"+jwt").equals(signed.getHeader().getType())
                ||signed.getHeader().containsKey("zip")||!Set.of("dts-media").equals(c.getAudience())
                ||c.getIssuedAt()==null||c.getExpiration()==null||c.getIssuedAt().toInstant().isAfter(now)
                ||!c.getExpiration().toInstant().isAfter(now)||!c.getExpiration().after(c.getIssuedAt())
                ||c.getExpiration().getTime()-c.getIssuedAt().getTime()>60000
                ||!user.equals(MediaJwtVerifier.canonicalUuid(c.getSubject()))
                ||!"SIMULATION_VIDEO".equals(c.get("targetType",String.class)))throw new IllegalArgumentException();
        MediaJwtVerifier.canonicalUuid(c.getId());String bank=c.get("bankVersion",String.class),manifest=c.get("manifestSha256",String.class);
        if(bank==null||!bank.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")||manifest==null||!manifest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        return c;
    }
    private Asset asset(Object raw) {
        if(!(raw instanceof Map<?,?>m)||!m.keySet().equals(Set.of("mediaId","fileName","sha256","durationMs","videoTrackDurationMs")))throw new IllegalArgumentException();
        UUID media=MediaJwtVerifier.canonicalUuid((String)m.get("mediaId"));String file=(String)m.get("fileName"),sha=(String)m.get("sha256");
        if(file==null||file.isBlank()||file.length()>500||file.chars().anyMatch(c->c<32||c=='/'||c=='\\')
                ||!file.toLowerCase(Locale.ROOT).endsWith(".mp4")||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        return new Asset(media,file,sha,number(m.get("durationMs"),1,3600000),number(m.get("videoTrackDurationMs"),1,3600000));
    }
    private int number(Object raw,int min,int max) {
        if(!(raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException();
        long n=((Number)raw).longValue();if(n<min||n>max)throw new IllegalArgumentException();return(int)n;
    }
    private String etag(Object raw) {
        if(!(raw instanceof String s)||!s.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException();return s;
    }
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid or expired simulation exam ticket");}
}
