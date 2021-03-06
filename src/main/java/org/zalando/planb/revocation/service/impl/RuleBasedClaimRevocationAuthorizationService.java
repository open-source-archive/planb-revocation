package org.zalando.planb.revocation.service.impl;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.zalando.planb.revocation.api.exception.RevocationUnauthorizedException;
import org.zalando.planb.revocation.config.properties.CassandraProperties;
import org.zalando.planb.revocation.config.properties.RevocationProperties;
import org.zalando.planb.revocation.domain.AuthorizationRule;
import org.zalando.planb.revocation.domain.ImmutableAuthorizationRule;
import org.zalando.planb.revocation.domain.RevokedClaimsData;
import org.zalando.planb.revocation.persistence.AuthorizationRulesStore;

import java.text.ParseException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RuleBasedClaimRevocationAuthorizationService extends AbstractAuthorizationService {

    private final AuthorizationRulesStore authorizationRulesStore;

    public RuleBasedClaimRevocationAuthorizationService(
            AuthorizationRulesStore authorizationRulesStore,
            RevocationProperties revocationProperties,
            CassandraProperties cassandraProperties) {
        super(revocationProperties, cassandraProperties);
        this.authorizationRulesStore = authorizationRulesStore;
    }

    protected void checkClaimBasedRevocation(final RevokedClaimsData claimsData) {
        final AuthorizationRule sourceRule = ImmutableAuthorizationRule
                .builder()
                .requiredUserClaims(getRequiredUserClaimsFromContext()).build();
        final AuthorizationRule targetRule = ImmutableAuthorizationRule
                .builder()
                .allowedRevocationClaims(claimsData.claims()).build();
        final Collection<AuthorizationRule> sourceRules = authorizationRulesStore.retrieveByMatchingAllowedClaims(targetRule);
        sourceRules.stream()
                .filter(sourceRule::matchesRequiredUserClaims)
                .findAny()
                .orElseThrow(() -> new RevocationUnauthorizedException(targetRule));
    }

    private Map<String, String> getRequiredUserClaimsFromContext() {
        String accessToken = Optional.of(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication)
                .map(auth -> (OAuth2Authentication) auth)
                .map(OAuth2Authentication::getUserAuthentication)
                .map(Authentication::getDetails)
                .map(theDetails -> (Map<?, ?>) theDetails)
                .map(m -> (String) m.get("access_token"))
                .orElseThrow(() -> new IllegalStateException("Could not find access_token in SecurityContext"));
        try {
            Map<String, Object> requiredUserClaims = JWTParser.parse(accessToken).getJWTClaimsSet().getClaims();
            return requiredUserClaims.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Could not parse client token, non-JWT tokens are not allowed for claim-based revocation", e);
        }
    }
}
