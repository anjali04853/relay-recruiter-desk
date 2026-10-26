package com.c2c.desk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;

@Service
public class Workflow {
    static final List<String> KEYWORDS = List.of("Java Developer", "C2C", "Bench", "Sales", "W2", "Full-Time", "Hotlist");
    static final Pattern EMAIL = Pattern.compile("(?i)(?<![a-z0-9._%+\\-])[a-z0-9._%+\\-]+@[a-z0-9.-]+\\.[a-z]{2,63}");
    private final JdbcTemplate db;
    final ObjectMapper mapper;
    public Workflow(JdbcTemplate db, ObjectMapper mapper) { this.db=db; this.mapper=mapper; }
    public JdbcTemplate database() { return db; }
    static String str(Map<String,?> m, String k) { Object v=m.get(k); return v==null ? "" : v.toString().trim(); }
    static String bounded(String s, int n) { if(s.length()>n) throw new IllegalArgumentException("Field exceeds "+n+" characters"); return s; }
    public void event(String msg) { db.update("INSERT INTO events(message,created_at) VALUES(?,?)",msg,Instant.now().toString()); }
    public String setting(String key, String fallback) { return db.query("SELECT setting_value FROM settings WHERE setting_key=?",(rs,i)->rs.getString(1),key).stream().findFirst().orElse(fallback); }
    public static List<String> matches(String text) {
        return KEYWORDS.stream().filter(k->Pattern.compile("(?i)(?<![a-z0-9])"+Pattern.quote(k).replace("-","\\E[- ]?\\Q")+"(?![a-z0-9])").matcher(text).find()).toList();
    }
    public record PostInput(String author,String company,String text,String location,String country,String publishedAt,String url) {}
    @Transactional
    public Map<String,Object> ingest(List<PostInput> input, boolean demo) {
        if(input==null || input.size()>500) throw new IllegalArgumentException("Provide an array of at most 500 posts");
        int added=0, skipped=0, duplicates=0;
        Instant now=Instant.now();
        for(PostInput p:input) {
            if(p==null || p.text()==null || p.publishedAt()==null) throw new IllegalArgumentException("Each post needs text and an ISO-8601 publishedAt timestamp");
            Instant at;
            try { at=Instant.parse(p.publishedAt()); } catch(Exception e) { throw new IllegalArgumentException("Invalid publishedAt; use an ISO-8601 timestamp with Z"); }
            List<String> words=matches(p.text());
            if(!"US".equalsIgnoreCase(p.country()) || at.isBefore(now.minus(Duration.ofHours(24))) || at.isAfter(now) || words.isEmpty()) { skipped++; continue; }
            String author=bounded(Objects.toString(p.author(),"Unknown recruiter"),200), company=bounded(Objects.toString(p.company(),""),200);
            if(author.matches("(?s).*[\\r\\n].*") || company.matches("(?s).*[\\r\\n].*")) throw new IllegalArgumentException("Author and company must be single-line values");
            String body=bounded(p.text(),30000), url=bounded(Objects.toString(p.url(),""),2000);
            if(!url.isBlank() && !url.matches("https://[^\\s]+")) throw new IllegalArgumentException("Post URLs must use HTTPS");
            String key;
            try { key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((demo+"|"+(!url.isBlank()?url:author+"|"+body+(demo?"":"|"+at))).getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); }
            if(db.queryForObject("SELECT COUNT(*) FROM posts WHERE source_key=?",Integer.class,key)>0) { duplicates++; continue; }
            db.update("INSERT INTO posts(source_key,author,company,body,location,country,published_at,source_url,keywords,demo,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",key,author,company,body,bounded(Objects.toString(p.location(),"United States"),200),"US",at.toString(),url,String.join(", ",words),demo,now.toString());
            Long id=db.queryForObject("SELECT id FROM posts WHERE source_key=?",Long.class,key);
            Matcher emails=EMAIL.matcher(body);
            while(emails.find()) {
                String email=emails.group().toLowerCase(Locale.ROOT);
                if(email.length()>320) continue;
                if(db.queryForObject("SELECT COUNT(*) FROM contacts WHERE email=?",Integer.class,email)==0)
                    db.update("INSERT INTO contacts(post_id,email,name,company) VALUES(?,?,?,?)",id,email,author,company);
            }
            added++;
        }
        event("Imported "+added+" posts; "+skipped+" filtered; "+duplicates+" duplicates");
        return Map.of("added",added,"filtered",skipped,"duplicates",duplicates);
    }
    List<Map<String,Object>> rows(String sql) {
        return db.query(sql,(rs,i)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            for(int n=1;n<=rs.getMetaData().getColumnCount();n++) {
                Object v=rs.getObject(n);
                if(v instanceof java.sql.Clob c) v=c.getSubString(1,(int)c.length());
                row.put(rs.getMetaData().getColumnLabel(n),v);
            }
            return row;
        });
    }
    public List<Map<String,Object>> posts() { return rows("SELECT * FROM posts ORDER BY published_at DESC,id DESC"); }
    public List<Map<String,Object>> contacts() { return rows("SELECT c.*,p.source_url,p.body AS post_text,p.demo,p.published_at FROM contacts c JOIN posts p ON p.id=c.post_id ORDER BY c.id DESC"); }
    public List<Map<String,Object>> drafts() { return rows("SELECT d.*,c.email,c.name,c.company,c.suppressed,p.demo FROM drafts d JOIN contacts c ON c.id=d.contact_id JOIN posts p ON p.id=c.post_id ORDER BY d.id DESC"); }
    public Map<String,Object> template() { return Map.of("subject",setting("subject","Java Developer opportunity — {{company}}"),"body",setting("body","Hi {{name}},\n\nI saw your post about {{keywords}} and would like to discuss the opportunity.\n\n[Add your experience, availability, work authorization, and contact details.]\n\nBest regards,\n[Your name]\n\nIf this is not relevant, please reply and I will not follow up.")); }
    @Transactional
    public void template(Map<String,String> t) {
        String subject=bounded(str(t,"subject"),500),body=bounded(str(t,"body"),20000);
        if(subject.isBlank() || body.isBlank() || subject.contains("\n") || subject.contains("\r")) throw new IllegalArgumentException("Provide a single-line subject and a message body");
        db.update("MERGE INTO settings KEY(setting_key) VALUES('subject',?)",subject);
        db.update("MERGE INTO settings KEY(setting_key) VALUES('body',?)",body);
        event("Email template updated");
    }
    @Transactional
    public Map<String,Integer> generate() {
        Map<String,Object> t=template(); int count=0;
        for(Map<String,Object> c:db.queryForList("SELECT c.*,p.keywords,p.source_url FROM contacts c JOIN posts p ON p.id=c.post_id WHERE c.suppressed=FALSE AND NOT EXISTS (SELECT 1 FROM drafts d WHERE d.contact_id=c.id)")) {
            db.update("INSERT INTO drafts(contact_id,subject,body,status,updated_at) VALUES(?,?,?,'DRAFT',?)",c.get("ID"),render(t.get("subject").toString(),c),render(t.get("body").toString(),c),Instant.now().toString()); count++;
        }
        event("Created "+count+" email drafts"); return Map.of("created",count);
    }
    static String render(String t, Map<String,Object> c) { for(String k:List.of("name","company","email","keywords","source_url")) t=t.replace("{{"+k+"}}",Objects.toString(c.get(k.toUpperCase(Locale.ROOT)),"")); return t; }
    @Transactional
    public void editDraft(long id,Map<String,String> m) {
        String subject=bounded(str(m,"subject"),500), body=bounded(str(m,"body"),20000);
        if(subject.isBlank() || body.isBlank() || subject.matches("(?s).*[\\r\\n].*")) throw new IllegalArgumentException("A single-line subject and body are required");
        if(db.update("UPDATE drafts SET subject=?,body=?,status='DRAFT',error=NULL,updated_at=? WHERE id=? AND status IN ('DRAFT','APPROVED','FAILED')",subject,body,Instant.now().toString(),id)!=1) throw new IllegalArgumentException("Draft cannot be edited in its current state");
    }
    public void approve(long id) {
        if(db.update("UPDATE drafts SET status='APPROVED',updated_at=? WHERE id=? AND status IN ('DRAFT','FAILED') AND contact_id IN (SELECT id FROM contacts WHERE suppressed=FALSE)",Instant.now().toString(),id)!=1) throw new IllegalArgumentException("Draft cannot be approved");
        event("Approved draft #"+id);
    }
    @Transactional
    public void suppress(long id) {
        if(db.update("UPDATE contacts SET suppressed=TRUE WHERE id=?",id)!=1) throw new IllegalArgumentException("Contact not found");
        db.update("UPDATE drafts SET status='SUPPRESSED' WHERE contact_id=? AND status IN ('DRAFT','APPROVED','FAILED')",id);
        event("Suppressed contact #"+id);
    }
    public Map<String,Object> stats() {
        Map<String,Object> m=new LinkedHashMap<>();
        for(String table:List.of("posts","contacts","drafts")) m.put(table,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        m.put("sent",db.queryForObject("SELECT COUNT(*) FROM drafts WHERE status='SENT'",Integer.class));
        m.put("approved",db.queryForObject("SELECT COUNT(*) FROM drafts WHERE status='APPROVED'",Integer.class));
        m.put("events",db.queryForList("SELECT * FROM events ORDER BY id DESC LIMIT 12")); return m;
    }
    @Transactional
    public Map<String,Object> demo() {
        String at=Instant.now().minusSeconds(3600).toString();
        return ingest(List.of(
            new PostInput("Alex Morgan","Northstar Talent","Hiring: Java Developer C2C. Spring Boot, AWS and microservices. US remote. Send profiles to alex@northstar.example.","Austin, TX · Remote","US",at,""),
            new PostInput("Priya Shah","Vertex Staffing","W2 / Full-Time Java Developer opportunity in New York. 5+ years with Java and APIs. Contact priya@vertex.example.","New York, NY","US",at,""),
            new PostInput("Jordan Lee","Apex Consulting","Hotlist — Bench Sales team: Java Developer consultants available for C2C projects across the US. jordan@apex.example","Dallas, TX","US",at,""),
            new PostInput("Sam Taylor","Cloudbridge","Java Developer C2C in Seattle. Kubernetes, Java 17, REST APIs. Reach sam@cloudbridge.example","Seattle, WA","US",at,""),
            new PostInput("Outside window","Example","Java Developer C2C old@sample.example","US","US",Instant.now().minusSeconds(172800).toString(),""),
            new PostInput("Outside region","Example","Java Developer C2C india@sample.example","India","IN",at,"")
        ),true);
    }
}
