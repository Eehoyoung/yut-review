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

/**
 * `/api/operator/**`(운영자 API 전부)에 이메일 OTP로 발급된 고정 만료 세션만 허용한다.
 * 운영자 API는 매장 관리자 API(`/api/admin/**`)와 주소가 겹치지 않는다. 운영자 전용 동작을 `/api/admin` 아래에 두지 말 것 —
 * 이 필터를 지나지 않아 일반 비밀번호 세션으로도 닿게 된다(요금제 변경이 실제로 그랬다).
 */
@Component class OperatorAccessFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    OperatorAccessFilter(JwtService jwt){this.jwt=jwt;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String uri=request.getRequestURI();
        return !(uri.equals("/api/operator")||uri.startsWith("/api/operator/"));
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
