package com.c2c.desk;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1","app.live-send=false"})
@AutoConfigureMockMvc
class WorkflowTest {
    @Autowired Workflow flow;
    @Autowired MockMvc mvc;
    @Autowired Api controller;
    @MockBean JavaMailSender mail;
    @BeforeEach void clear() { controller.live=false; controller.from="sender@example.com"; for(String table:List.of("drafts","contacts","posts","events","settings")) flow.database().update("DELETE FROM "+table); }
    Workflow.PostInput sample(String text,String country,String at,String url) { return new Workflow.PostInput("Recruiter","Agency",text,"Austin, TX",country,at,url); }
    @Test void filtersAgeCountryFutureAndKeywordBoundaries() {
        String now=Instant.now().minusSeconds(10).toString();
        var result=flow.ingest(List.of(sample("JAVA DEVELOPER C2C hi@agency.example","US",now,""),sample("Java Developer","IN",now,""),sample("Java Developer","US",Instant.now().minusSeconds(86401).toString(),""),sample("Java Developer","US",Instant.now().plusSeconds(3600).toString(),""),sample("Wholesale unrelated","US",now,"")),false);
        assertThat(result).containsEntry("added",1).containsEntry("filtered",4);
        assertThat(flow.contacts()).hasSize(1);
        assertThat(Workflow.matches("full time / full-time / W2")).contains("Full-Time","W2");
    }
    @Test void importsAndDraftsAreIdempotentAndSuppressionBlocksApproval() {
        var p=sample("Java Developer C2C TEST@agency.example test@agency.example","US",Instant.now().minusSeconds(10).toString(),"https://www.linkedin.com/posts/example");
        flow.ingest(List.of(p,p),false); flow.ingest(List.of(p),false);
        assertThat(flow.posts()).hasSize(1); assertThat(flow.contacts()).hasSize(1);
        flow.template(Map.of("subject","Hi {{name}}","body","About {{company}}: {{keywords}} {{email}}"));
        assertThat(flow.generate()).containsEntry("created",1); assertThat(flow.generate()).containsEntry("created",0);
        var d=flow.drafts().get(0); long id=((Number)d.get("ID")).longValue();
        assertThat(d.get("SUBJECT")).isEqualTo("Hi Recruiter");
        assertThat(d.get("BODY").toString()).contains("Agency","test@agency.example");
        flow.approve(id); flow.editDraft(id,Map.of("subject","Updated","body","Reviewed content"));
        assertThat(flow.drafts().get(0).get("STATUS")).isEqualTo("DRAFT");
        flow.suppress(((Number)flow.contacts().get(0).get("ID")).longValue());
        assertThatThrownBy(()->flow.approve(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(flow.drafts().get(0).get("STATUS")).isEqualTo("SUPPRESSED");
    }
    @Test void malformedBatchRollsBackAllRows() {
        assertThatThrownBy(()->flow.ingest(List.of(sample("Java Developer","US",Instant.now().minusSeconds(10).toString(),""),sample("C2C","US","invalid","")),false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(flow.posts()).isEmpty();
    }
    @Test void apiDemoToDryRunAndCsrfProtection() throws Exception {
        mvc.perform(post("/api/demo")).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo").header("X-Desk-Request","1")).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(4)).andExpect(jsonPath("$.filtered").value(2));
        mvc.perform(get("/api/posts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].BODY").isString());
        mvc.perform(post("/api/drafts/generate").header("X-Desk-Request","1")).andExpect(jsonPath("$.created").value(4));
        long id=((Number)flow.drafts().get(0).get("ID")).longValue();
        mvc.perform(post("/api/drafts/"+id+"/send").header("X-Desk-Request","1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/drafts/"+id+"/approve").header("X-Desk-Request","1")).andExpect(status().isOk());
        mvc.perform(post("/api/drafts/"+id+"/send").header("X-Desk-Request","1")).andExpect(jsonPath("$.dryRun").value(true));
        assertThat(flow.drafts().get(0).get("STATUS")).isEqualTo("APPROVED");
        mvc.perform(get("/api/export")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=recruiters.csv"));
        mvc.perform(get("/auth/linkedin/callback").param("code","fake").param("state","fake")).andExpect(status().isBadRequest());
    }
    long approvedRealDraft() {
        flow.ingest(List.of(sample("Java Developer C2C recruiter@agency.test","US",Instant.now().minusSeconds(10).toString(),"")),false);
        flow.generate(); long id=((Number)flow.drafts().get(0).get("ID")).longValue(); flow.approve(id); return id;
    }
    @Test void smtpSuccessCannotBeSentTwice() {
        long id=approvedRealDraft(); controller.live=true;
        assertThat(controller.send(id)).containsEntry("sent",true);
        verify(mail,times(1)).send(any(SimpleMailMessage.class));
        assertThat(flow.drafts().get(0).get("STATUS")).isEqualTo("SENT");
        assertThatThrownBy(()->controller.send(id)).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(mail);
    }
    @Test void smtpUncertainOutcomeIsNotRetried() {
        long id=approvedRealDraft(); controller.live=true;
        doThrow(new MailSendException("connection lost")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(()->controller.send(id)).hasMessageContaining("uncertain");
        assertThat(flow.drafts().get(0).get("STATUS")).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->flow.approve(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.send(id)).isInstanceOf(IllegalArgumentException.class);
        verify(mail,times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void sampleContactsCannotReceiveLiveMail() {
        flow.demo(); flow.generate(); long id=((Number)flow.drafts().get(0).get("ID")).longValue(); flow.approve(id); controller.live=true;
        assertThatThrownBy(()->controller.send(id)).hasMessageContaining("Demo");
        verifyNoInteractions(mail);
    }
}
