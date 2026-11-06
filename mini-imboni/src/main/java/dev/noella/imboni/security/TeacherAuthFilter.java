package dev.noella.imboni.security;

import dev.noella.imboni.config.ImboniProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Prototype authentication: an API key per teacher (X-API-Key header), mapped to a teacher id.
 * Everything downstream gets the teacher id from here, never from the request body, so a client
 * can't ask for another teacher's documents. A real deployment would use OAuth/OIDC instead.
 */
@Component
public class TeacherAuthFilter extends OncePerRequestFilter {

    public static final String TEACHER_ATTR = "teacherId";

    private final ImboniProperties props;

    public TeacherAuthFilter(ImboniProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        String teacher = key == null ? null : props.apiKeys().get(key);
        if (teacher == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"missing or invalid X-API-Key\"}");
            return;
        }
        req.setAttribute(TEACHER_ATTR, teacher);
        chain.doFilter(req, res);
    }
}
