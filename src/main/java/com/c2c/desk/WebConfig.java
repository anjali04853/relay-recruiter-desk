package com.c2c.desk;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
                res.setHeader("X-Content-Type-Options","nosniff");
                res.setHeader("X-Frame-Options","DENY");
                res.setHeader("Referrer-Policy","no-referrer");
                res.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
                if(req.getRequestURI().startsWith("/api/")) {
                    res.setHeader("Cache-Control","no-store");
                    if(!req.getMethod().equals("GET") && !"1".equals(req.getHeader("X-Desk-Request"))) {
                        res.setStatus(403); res.setContentType("application/json"); res.getWriter().write("{\"error\":\"X-Desk-Request: 1 header required\"}"); return false;
                    }
                }
                return true;
            }
        });
    }
}
