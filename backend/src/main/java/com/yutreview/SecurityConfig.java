package com.yutreview;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean SecurityFilterChain chain(HttpSecurity http,JwtFilter jwt) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/public/**","/api/admin/auth/login","/api/admin/auth/signup","/actuator/health").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->writeError(res,401,"AUTH_REQUIRED","로그인이 필요합니다.")).accessDeniedHandler((req,res,x)->writeError(res,403,"FORBIDDEN","접근 권한이 없습니다.")))
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void writeError(HttpServletResponse res,int status,String code,String message)throws IOException{res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\"}}");}
}
@Component class JwtService {
    private final Algorithm algorithm;
    JwtService(@Value("${app.jwt-key}") String key){if(key.length()<32)throw new IllegalArgumentException("JWT_SECRET must contain at least 32 characters");algorithm=Algorithm.HMAC256(key);}
    String issue(AdminUser u){return JWT.create().withSubject(u.id.toString()).withClaim("role",u.role.name()).withExpiresAt(Instant.now().plusSeconds(28800)).sign(algorithm);}
    Long verify(String token){try{return Long.valueOf(JWT.require(algorithm).build().verify(token).getSubject());}catch(Exception e){return null;}}
}
@Component class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt; JwtFilter(JwtService jwt){this.jwt=jwt;}
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        String h=req.getHeader(HttpHeaders.AUTHORIZATION);
        if(h!=null&&h.startsWith("Bearer ")){Long id=jwt.verify(h.substring(7));if(id!=null)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id,null,java.util.List.of()));}
        chain.doFilter(req,res);
    }
}
