package com.yutreview;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean SecurityFilterChain chain(HttpSecurity http,JwtFilter jwt,SystemConsoleGateFilter gate) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/public/**","/api/admin/auth/login","/api/admin/auth/signup","/actuator/health").permitAll()
                // 로그인과 2단계 인증 등록은 토큰 없이 들어와야 한다. 대신 앞단의 게이트 필터가 IP 허용 목록을,
                // 컨트롤러가 비밀번호·OTP·시도 제한을 본다.
                .requestMatchers("/api/system/auth/**").permitAll()
                // 나머지 운영자 API는 "SYSTEM_ADMIN이 2단계 인증까지 마친 토큰"만 통과한다.
                // 매장 콘솔 토큰(scope=STORE)은 운영자 계정의 것이라도 이 권한을 받지 못한다.
                .requestMatchers("/api/system/**").hasAuthority(JwtService.CONSOLE_AUTHORITY)
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->writeError(res,401,"AUTH_REQUIRED","로그인이 필요합니다.")).accessDeniedHandler((req,res,x)->writeError(res,403,"FORBIDDEN","접근 권한이 없습니다.")))
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).addFilterBefore(gate,JwtFilter.class).build();
    }
    static void writeError(HttpServletResponse res,int status,String code,String message)throws IOException{res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\"}}");}
}
@Component class JwtService {
    /** 2단계 인증까지 통과한 운영자 토큰에만 붙는 권한. 매장 콘솔 토큰에는 절대 붙이지 않는다. */
    static final String CONSOLE_AUTHORITY="OPERATOR_CONSOLE";
    static final String SCOPE_STORE="STORE",SCOPE_OPERATOR="OPERATOR",SCOPE_ENROLL="OPERATOR_ENROLL";
    /** 토큰이 실어 나르는 것: 누구인지, 어떤 등급인지, 어느 문으로 들어왔는지. */
    record Claims(Long adminId,AdminRole role,String scope){}
    private final Algorithm algorithm; private final int operatorTtlSeconds;
    JwtService(@Value("${app.jwt-key}") String key,@Value("${app.system-console.session-minutes:60}") int operatorMinutes){if(key.length()<32)throw new IllegalArgumentException("JWT_SECRET must contain at least 32 characters");algorithm=Algorithm.HMAC256(key);operatorTtlSeconds=Math.max(5,operatorMinutes)*60;}
    int operatorTtlSeconds(){return operatorTtlSeconds;}
    String issue(AdminUser u){return sign(u,JwtService.SCOPE_STORE,28800);}
    /** 운영자 콘솔 토큰은 짧게 준다. 플랫폼 전체를 여는 토큰이 하루 종일 살아 있을 이유가 없다. */
    String issueOperator(AdminUser u){return sign(u,JwtService.SCOPE_OPERATOR,operatorTtlSeconds);}
    /** 2단계 인증 등록 중에만 쓰는 임시 토큰. 콘솔 API는 이 토큰으로 열리지 않는다. */
    String issueEnrollment(AdminUser u){return sign(u,JwtService.SCOPE_ENROLL,300);}
    private String sign(AdminUser u,String scope,int ttlSeconds){return JWT.create().withSubject(u.id.toString()).withClaim("role",u.role.name()).withClaim("scope",scope).withExpiresAt(Instant.now().plusSeconds(ttlSeconds)).sign(algorithm);}
    Claims verify(String token){
        try{
            DecodedJWT decoded=JWT.require(algorithm).build().verify(token);
            String scope=decoded.getClaim("scope").asString(),role=decoded.getClaim("role").asString();
            // scope가 없는 토큰은 이 기능 이전에 발급된 매장 콘솔 토큰이다. 운영자 권한은 주지 않는다.
            return new Claims(Long.valueOf(decoded.getSubject()),role==null?null:AdminRole.valueOf(role),scope==null?SCOPE_STORE:scope);
        }catch(Exception e){return null;}
    }
}
@Component class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt; JwtFilter(JwtService jwt){this.jwt=jwt;}
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        String h=req.getHeader(HttpHeaders.AUTHORIZATION);
        if(h!=null&&h.startsWith("Bearer ")){
            JwtService.Claims c=jwt.verify(h.substring(7));
            // 운영자 권한은 토큰의 역할과 발급 경로가 둘 다 맞을 때만 붙는다. DB의 현재 역할은
            // 컨트롤러가 요청마다 다시 확인한다(토큰을 발급한 뒤 역할을 회수할 수 있어야 한다).
            if(c!=null)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(c.adminId(),null,
                c.role()==AdminRole.SYSTEM_ADMIN&&JwtService.SCOPE_OPERATOR.equals(c.scope())?List.of(new SimpleGrantedAuthority(JwtService.CONSOLE_AUTHORITY)):List.of()));
        }
        chain.doFilter(req,res);
    }
}
