package com.example.voicechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res,
                      int status, String code, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getOutputStream(), new Api.ErrorResponse(code, message, RequestLogFilter.requestId(req)));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Api.ErrorResponse> api(ApiException e, HttpServletRequest req) {
        return error(e.status(), e.code(), e.getMessage(), req);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Api.ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).orElse("请求参数无效");
        return error(400, "VALIDATION_ERROR", message, req);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Api.ErrorResponse> malformed(Exception e, HttpServletRequest req) {
        return error(400, "VALIDATION_ERROR", "请求参数或JSON格式不正确", req);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Api.ErrorResponse> tooLarge(Exception e, HttpServletRequest req) {
        return error(413, "UPLOAD_TOO_LARGE", "音频文件不能超过10MB", req);
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Api.ErrorResponse> unsupported(Exception e, HttpServletRequest req) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持", req);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Api.ErrorResponse> method(Exception e, HttpServletRequest req) {
        return error(405, "METHOD_NOT_ALLOWED", "请求方法不受支持", req);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Api.ErrorResponse> notFound(Exception e, HttpServletRequest req) {
        return error(404, "NOT_FOUND", "接口不存在", req);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Api.ErrorResponse> denied(Exception e, HttpServletRequest req) {
        return error(403, "FORBIDDEN", "无权执行此操作", req);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Api.ErrorResponse> unexpected(Exception e, HttpServletRequest req) {
        log.error("requestId={} errorType={}", RequestLogFilter.requestId(req), e.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试", req);
    }
    private ResponseEntity<Api.ErrorResponse> error(int status, String code, String message, HttpServletRequest req) {
        log.warn("requestId={} errorCode={}", RequestLogFilter.requestId(req), code);
        return ResponseEntity.status(status).body(new Api.ErrorResponse(code, message, RequestLogFilter.requestId(req)));
    }
}
