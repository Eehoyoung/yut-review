package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Uses a canonical origin in production and the current request origin in local/field-test environments. */
@Component
class PublicOriginResolver {
    private final String configuredOrigin;

    PublicOriginResolver(@Value("${app.public-origin:}") String configuredOrigin) {
        this.configuredOrigin = normalize(configuredOrigin);
    }

    String resolve(HttpServletRequest request) {
        return configuredOrigin.isEmpty()
            ? normalize(ServletUriComponentsBuilder.fromRequestUri(request).replacePath(request.getContextPath()).replaceQuery(null).build().toUriString())
            : configuredOrigin;
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("APP_PUBLIC_ORIGIN must be an absolute HTTP(S) origin", exception);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("APP_PUBLIC_ORIGIN must be an absolute HTTP(S) origin without a path");
        }
        int port = uri.getPort();
        return scheme.toLowerCase() + "://" + uri.getHost().toLowerCase() + (port < 0 ? "" : ":" + port);
    }
}
