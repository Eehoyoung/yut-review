package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.slf4j.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.*;

record ApiResponse<T>(boolean success,T data,ApiError error) {
    static <T> ApiResponse<T> ok(T data){ return new ApiResponse<>(true,data,null); }
    static ApiResponse<Void> fail(String code,String message){ return new ApiResponse<>(false,null,new ApiError(code,message)); }
}
record ApiError(String code,String message) {}
class AppException extends RuntimeException {
    final String code; final HttpStatus status;
    AppException(String code,String message){this(code,message,HttpStatus.BAD_REQUEST);}
    AppException(String code,String message,HttpStatus status){super(message);this.code=code;this.status=status;}
}
@RestControllerAdvice class ErrorHandler {
    @ExceptionHandler(AppException.class) ResponseEntity<ApiResponse<Void>> app(AppException e){return ResponseEntity.status(e.status).body(ApiResponse.fail(e.code,e.getMessage()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class}) ResponseEntity<ApiResponse<Void>> validation(){return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST","입력값을 확인해 주세요."));}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<ApiResponse<Void>> missing(){return ResponseEntity.status(404).body(ApiResponse.fail("NOT_FOUND","요청하신 경로를 찾을 수 없습니다."));}
    private static final Logger log=LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(Exception.class) ResponseEntity<ApiResponse<Void>> unknown(Exception e){log.error("Unhandled request failure",e);return ResponseEntity.status(500).body(ApiResponse.fail("INTERNAL_ERROR","요청을 처리하지 못했습니다."));}
}
