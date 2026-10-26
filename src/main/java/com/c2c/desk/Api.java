package com.c2c.desk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@RestController
public class Api {
    final Workflow flow; final JavaMailSender mail; final ObjectMapper mapper;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Value("${app.live-send}") boolean live;
    @Value("${app.from}") String from;
    @Value("${app.provider-url}") String provider;
    @Value("${app.provider-token}") String providerToken;
    @Value("${app.linkedin-id}") String clientId;
    @Value("${app.linkedin-secret}") String clientSecret;
    @Value("${app.redirect-uri}") String redirect;
    public Api(Workflow f,JavaMailSender m,ObjectMapper mapper) { flow=f; mail=m; this.mapper=mapper; }
    @GetMapping("/api/status") Map<String,Object> status(HttpSession session) {
        return Map.of("liveSend",live,"mailConfigured",!from.isBlank(),"providerConfigured",!provider.isBlank(),"linkedinConfigured",!clientId.isBlank()&&!clientSecret.isBlank(),"profile",Objects.requireNonNullElse(session.getAttribute("profile"),Map.of()),"keywords",Workflow.KEYWORDS);
    }
    @GetMapping("/api/stats") Map<String,Object> stats() { return flow.stats(); }
    @GetMapping("/api/posts") List<Map<String,Object>> posts() { return flow.posts(); }
    @GetMapping("/api/contacts") List<Map<String,Object>> contacts() { return flow.contacts(); }
    @GetMapping("/api/drafts") List<Map<String,Object>> drafts() { return flow.drafts(); }
    @GetMapping("/api/template") Map<String,Object> template() { return flow.template(); }
    @PutMapping("/api/template") Map<String,Boolean> template(@RequestBody Map<String,String> body) { flow.template(body); return Map.of("saved",true); }
    @PostMapping("/api/import") Map<String,Object> ingest(@RequestBody List<Workflow.PostInput> posts) { return flow.ingest(posts,false); }
    @PostMapping("/api/demo") Map<String,Object> demo() { return flow.demo(); }
    @PostMapping("/api/drafts/generate") Map<String,Integer> generate() { return flow.generate(); }
    @PutMapping("/api/drafts/{id}") Map<String,Boolean> edit(@PathVariable long id,@RequestBody Map<String,String> body) { flow.editDraft(id,body); return Map.of("saved",true); }
    @PostMapping("/api/drafts/{id}/approve") Map<String,Boolean> approve(@PathVariable long id) { flow.approve(id); return Map.of("approved",true); }
    @PostMapping("/api/contacts/{id}/suppress") Map<String,Boolean> suppress(@PathVariable long id) { flow.suppress(id); return Map.of("suppressed",true); }
    @PostMapping("/api/logout") Map<String,Boolean> logout(HttpSession session) { session.invalidate(); return Map.of("ok",true); }
    @PostMapping("/api/sync") Map<String,Object> sync() throws Exception {
        if(provider.isBlank()) throw new IllegalArgumentException("Configure POST_PROVIDER_URL for your authorized post provider, or import JSON");
        if(!provider.startsWith("https://")) throw new IllegalArgumentException("Provider endpoint must use HTTPS");
        String sep=provider.contains("?")?"&":"?";
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(provider+sep+"country=US&since="+encode(Instant.now().minusSeconds(86400).toString())+"&keywords="+encode(String.join(",",Workflow.KEYWORDS)))).timeout(Duration.ofSeconds(30)).GET();
        if(!providerToken.isBlank()) request.header("Authorization","Bearer "+providerToken);
        HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalArgumentException("Post provider returned HTTP "+response.statusCode());
        if(response.body().length()>4_000_000) throw new IllegalArgumentException("Provider response exceeds 4 MB");
        return flow.ingest(mapper.readValue(response.body(),new TypeReference<List<Workflow.PostInput>>(){}),false);
    }
    @PostMapping("/api/drafts/{id}/send") synchronized Map<String,Object> send(@PathVariable long id) {
        Map<String,Object> d=flow.drafts().stream().filter(x->((Number)x.get("ID")).longValue()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Draft not found"));
        if(!"APPROVED".equals(d.get("STATUS"))) throw new IllegalArgumentException("Approve the draft before sending");
        if(Boolean.TRUE.equals(d.get("SUPPRESSED"))) throw new IllegalArgumentException("Contact is suppressed");
        if(!live) { flow.event("Dry run for draft #"+id+" — no email sent"); return Map.of("dryRun",true,"message","Dry run completed. No email sent; draft remains approved."); }
        String email=d.get("EMAIL").toString();
        if(Boolean.TRUE.equals(d.get("DEMO")) || email.endsWith(".example")) throw new IllegalArgumentException("Demo contacts cannot receive real email");
        if(from.isBlank()) throw new IllegalArgumentException("MAIL_FROM is required");
        if(flow.database().queryForObject("SELECT COUNT(*) FROM drafts WHERE status IN ('SENT','SENDING','UNKNOWN') AND updated_at>=?",Integer.class,Instant.now().minusSeconds(86400).toString())>=25) throw new IllegalArgumentException("Daily send limit of 25 reached");
        if(flow.database().update("UPDATE drafts SET status='SENDING',updated_at=? WHERE id=? AND status='APPROVED' AND contact_id IN (SELECT id FROM contacts WHERE suppressed=FALSE)",Instant.now().toString(),id)!=1) throw new IllegalArgumentException("Draft is no longer eligible");
        try {
            SimpleMailMessage message=new SimpleMailMessage(); message.setFrom(from); message.setTo(email); message.setSubject(d.get("SUBJECT").toString()); message.setText(d.get("BODY").toString());
            mail.send(message);
            flow.database().update("UPDATE drafts SET status='SENT',error=NULL,updated_at=? WHERE id=?",Instant.now().toString(),id);
            flow.event("Sent draft #"+id); return Map.of("sent",true);
        } catch(Exception e) {
            flow.database().update("UPDATE drafts SET status='UNKNOWN',error='SMTP outcome uncertain. Check provider delivery logs before taking further action.',updated_at=? WHERE id=?",Instant.now().toString(),id);
            throw new IllegalArgumentException("SMTP delivery outcome uncertain. Check provider logs; automatic retries are disabled to avoid duplicates.");
        }
    }
    @GetMapping("/api/export") ResponseEntity<String> export() {
        StringBuilder csv=new StringBuilder("name,email,company,source_url,suppressed\r\n");
        for(Map<String,Object> c:flow.contacts()) { for(String k:List.of("NAME","EMAIL","COMPANY","SOURCE_URL","SUPPRESSED")) {
            String v=Objects.toString(c.get(k),""); if(!v.isEmpty() && "=+@-\t\r\n".indexOf(v.charAt(0))>=0) v="'"+v;
            csv.append('"').append(v.replace("\"","\"\"")).append('"').append(k.equals("SUPPRESSED")?"\r\n":",");
        }}
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=recruiters.csv").contentType(MediaType.parseMediaType("text/csv")).body(csv.toString());
    }
    static String encode(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    @GetMapping("/auth/linkedin") ResponseEntity<Void> login(HttpSession session) {
        if(clientId.isBlank() || clientSecret.isBlank()) throw new IllegalArgumentException("Set LinkedIn app credentials first; see README");
        String state=UUID.randomUUID().toString(); session.setAttribute("oauthState",state); session.setAttribute("oauthAt",Instant.now());
        String url="https://www.linkedin.com/oauth/v2/authorization?response_type=code&client_id="+encode(clientId)+"&redirect_uri="+encode(redirect)+"&scope="+encode("openid profile email")+"&state="+encode(state);
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }
    @GetMapping("/auth/linkedin/callback") ResponseEntity<Void> callback(@RequestParam(required=false) String code,@RequestParam(required=false) String state,HttpServletRequest request) throws Exception {
        HttpSession session=request.getSession(false);
        Object expected=session==null?null:session.getAttribute("oauthState");
        Instant at=session==null?null:(Instant)session.getAttribute("oauthAt");
        if(session!=null) { session.removeAttribute("oauthState"); session.removeAttribute("oauthAt"); }
        if(code==null || state==null || !state.equals(expected) || at==null || at.isBefore(Instant.now().minusSeconds(600))) throw new IllegalArgumentException("LinkedIn sign-in expired or state did not match. Start again.");
        String form="grant_type=authorization_code&code="+encode(code)+"&redirect_uri="+encode(redirect)+"&client_id="+encode(clientId)+"&client_secret="+encode(clientSecret);
        HttpResponse<String> token=http.send(HttpRequest.newBuilder(URI.create("https://www.linkedin.com/oauth/v2/accessToken")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
        if(token.statusCode()!=200) throw new IllegalArgumentException("LinkedIn token exchange failed. Check app credentials and callback URI.");
        String access=mapper.readTree(token.body()).path("access_token").asText();
        if(access.isBlank()) throw new IllegalArgumentException("LinkedIn did not return an access token");
        HttpResponse<String> profile=http.send(HttpRequest.newBuilder(URI.create("https://api.linkedin.com/v2/userinfo")).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+access).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(profile.statusCode()!=200) throw new IllegalArgumentException("LinkedIn profile lookup failed");
        var user=mapper.readTree(profile.body());
        request.changeSessionId(); session.setAttribute("profile",Map.of("name",user.path("name").asText("LinkedIn member"),"email",user.path("email").asText("")));
        return ResponseEntity.status(302).location(URI.create("/?connected=true")).build();
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) ResponseEntity<Map<String,String>> badJson() { return ResponseEntity.badRequest().body(Map.of("error","Invalid JSON or post schema. Imports must be an array of post objects.")); }
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,String>> unexpected(Exception e) { return ResponseEntity.internalServerError().body(Map.of("error","Request failed. Check configuration and input, then retry.")); }
}
