package com.yutreview;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** `/api/admin/operator/**`에 이메일 OTP로 발급된 고정 만료 세션만 허용한다. */
@Component class OperatorAccessFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    OperatorAccessFilter(JwtService jwt){this.jwt=jwt;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !request.getRequestURI().startsWith("/api/admin/operator/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
            FilterChain chain) throws ServletException,IOException {
        Object principal=org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()==null?null
            :org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long adminId=principal instanceof Long id?id:null;
        String authorization=request.getHeader(HttpHeaders.AUTHORIZATION);
        String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):"";
        if(jwt.isOperatorSession(token,adminId)){chain.doFilter(request,response);return;}
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"OPERATOR_SESSION_REQUIRED\"," 
            +"\"message\":\"운영자 이메일 인증이 필요하거나 세션이 만료되었습니다.\"}}");
    }
}
