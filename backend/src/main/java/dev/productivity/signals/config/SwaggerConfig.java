package dev.productivity.signals.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Open API ドキュメント生成用の設定クラスです。 Swagger UI を通じて API ドキュメントを確認できます。
 *
 * @since 2025/12/15
 * @deprecated SwaggerConfig is deprecated and will be removed in future releases. Use Springdoc OpenAPI instead.
 * @see <a href="https://springdoc.org/">Springdoc OpenAPI</a> for more information.
 *
 */
@Configuration
public class SwaggerConfig {
    @Bean
    OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("開発生産性評価システム")
            .version("1.0.0")
            .description("開発生産性評価システムAPI仕様")
            );
    }
}
