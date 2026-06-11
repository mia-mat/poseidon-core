package ws.mia.poseidon.core.controller;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ws.mia.poseidon.core.env.EnvironmentService;

import java.io.IOException;

@Component
public class APIRoutingFilter extends OncePerRequestFilter {

	private final EnvironmentService environmentService;

	public APIRoutingFilter(EnvironmentService environmentService) {
		this.environmentService = environmentService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String expectedToken = environmentService.getPoseidonAPIToken();

		// If no token is configured, allow all requests through
		if (expectedToken == null) {
			filterChain.doFilter(request, response);
			return;
		}

		String authHeader = request.getHeader("Authorization");

		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed Authorization header");
			return;
		}

		String token = authHeader.substring("Bearer ".length());

		if (!expectedToken.equals(token)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API token");
			return;
		}

		filterChain.doFilter(request, response);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}
}
