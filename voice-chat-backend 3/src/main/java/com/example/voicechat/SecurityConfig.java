package com.example.voicechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService unusedUserDetailsService() {
        return username -> { throw new UsernameNotFoundException("Bearer authentication only"); };
    }
    @Bean SecurityFilterChain security(HttpSecurity http, AuthService auth, ObjectMapper json,
                                       @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        CorsFilter corsFilter = new CorsFilter(cors);
        corsFilter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws IOException {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                String id = response instanceof ServletServerHttpResponse servlet
                    ? servlet.getServletResponse().getHeader("X-Request-ID") : "unknown";
                json.writeValue(response.getBody(), new Api.ErrorResponse("CORS_ORIGIN_DENIED", "网页来源未被允许", id));
                response.flush();
            }
        });
        return http.csrf(c -> c.disable()).cors(c -> c.disable())
            .httpBasic(c -> c.disable()).formLogin(c -> c.disable()).logout(c -> c.disable())
            .requestCache(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c -> c
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/styles.css", "/app.js", "/service-worker.js", "/manifest.webmanifest", "/api/capabilities", "/api/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(c -> c
                .authenticationEntryPoint((req, res, e) -> ApiErrors.write(json, req, res, 401, "UNAUTHORIZED", "请先登录"))
                .accessDeniedHandler((req, res, e) -> ApiErrors.write(json, req, res, 403, "FORBIDDEN", "无权执行此操作")))
            .addFilterBefore(corsFilter, org.springframework.security.web.csrf.CsrfFilter.class)
            .addFilterBefore(new TokenFilter(auth, json), UsernamePasswordAuthenticationFilter.class).build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("X-Request-ID"));
        cors.setAllowCredentials(false); cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors); return source;
    }
    @Bean OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("网页智能语音对话后端").version("1.0.0")
            .description("Bearer登录；语音识别和朗读默认由浏览器完成。"))
            .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
    private static class TokenFilter extends OncePerRequestFilter {
        private final AuthService auth; private final ObjectMapper json;
        TokenFilter(AuthService auth, ObjectMapper json) { this.auth = auth; this.json = json; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                 FilterChain chain) throws ServletException, IOException {
            String header = req.getHeader("Authorization");
            if (header != null && !req.getMethod().equals("OPTIONS")) {
                var principal = header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? auth.authenticate(header.substring(7)) : java.util.Optional.<AccountPrincipal>empty();
                if (principal.isEmpty()) {
                    ApiErrors.write(json, req, res, 401, "UNAUTHORIZED", "登录凭证无效或已过期"); return;
                }
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal.get(), null, List.of()));
            }
            chain.doFilter(req, res);
        }
    }
}
