/*----------------------
 | SecurityConfig.kt
 | Description: Admits only a Google identity and disables every password-based credential.
 | Author: suinevere
 | Dependencies: spring-boot-starter-oauth2-client
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

	/*----------------------
	 | authCheckFilterChain
	 | Description: Answers nginx's auth_request subrequest with 401 rather than the redirect oauth2Login would otherwise send.
	 | Author: suinevere
	 | Dependencies: ServerHttpSecurity
	 | Globals: N/A
	 | Params: http -- the chain builder supplied by Spring Security
	 | Returns: the SecurityWebFilterChain guarding the auth-check endpoint alone
	 ----------------------*/
	@Bean
	@Order(1)
	fun authCheckFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		http
			.securityMatcher(PathPatternParserServerWebExchangeMatcher("/_authcheck"))
			.authorizeExchange { it.anyExchange().authenticated() }
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.csrf { it.disable() }
			.build()

	/*----------------------
	 | securityWebFilterChain
	 | Description: Requires a Google sign-in for every other exchange and refuses form and basic credentials outright.
	 | Author: suinevere
	 | Dependencies: ServerHttpSecurity
	 | Globals: N/A
	 | Params: http -- the chain builder supplied by Spring Security
	 | Returns: the configured SecurityWebFilterChain
	 ----------------------*/
	@Bean
	@Order(2)
	fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		http
			.authorizeExchange { it.anyExchange().authenticated() }
			.oauth2Login { it.loginPage("/oauth2/authorization/google") }
			.formLogin { it.disable() }
			.httpBasic { it.disable() }
			.csrf { it.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()) }
			.logout { }
			.build()
}
