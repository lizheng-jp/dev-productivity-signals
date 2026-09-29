package dev.productivity.signals.security;

import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
@Configuration
public class WebSecurityConfig {

    @Value("${setting.security:true}")
    private boolean isSecurityCheck;

    /**
     * Spring Security における、認証・認可のフィルタ設定を行う。
     *
     * @param http
     * @return
     * @throws Exception
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // CSRF対策無効
                .csrf(csrf -> csrf.disable())
                // BASIC認証無効
                .httpBasic(basic -> basic.disable())
                // FORMログイン無効
                .formLogin(login -> login.disable())
                // ログアウト処理無効
                .logout(logout -> logout.disable())
                // セッション使用不可
                .sessionManagement((session) -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 認可処理
                .authorizeHttpRequests(authz -> {
                    if (isSecurityCheck) {
                        // 静的リソース/Swagger/特定パスはpermitAll
                        authz
                            .requestMatchers(
                                // swagger用
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs**", "/v3/api-docs/**", "/api-docs/**",
                                // monitoring用
                                "/actuator/health", "/actuator/prometheus"
                            ).permitAll()
                            .anyRequest().authenticated();
                    } else {
                        // LOCALや検証用: 全てpermitAll
                        authz.anyRequest().permitAll();
                    }
                });
        return http.build();
    }

}
