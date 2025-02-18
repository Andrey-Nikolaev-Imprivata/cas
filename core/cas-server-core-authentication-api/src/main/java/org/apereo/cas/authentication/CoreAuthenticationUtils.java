package org.apereo.cas.authentication;

import org.apereo.cas.authentication.adaptive.intel.DefaultIPAddressIntelligenceService;
import org.apereo.cas.authentication.adaptive.intel.GroovyIPAddressIntelligenceService;
import org.apereo.cas.authentication.adaptive.intel.IPAddressIntelligenceService;
import org.apereo.cas.authentication.adaptive.intel.RestfulIPAddressIntelligenceService;
import org.apereo.cas.authentication.policy.AllAuthenticationHandlersSucceededAuthenticationPolicy;
import org.apereo.cas.authentication.policy.AllCredentialsValidatedAuthenticationPolicy;
import org.apereo.cas.authentication.policy.AtLeastOneCredentialValidatedAuthenticationPolicy;
import org.apereo.cas.authentication.policy.GroovyScriptAuthenticationPolicy;
import org.apereo.cas.authentication.policy.NotPreventedAuthenticationPolicy;
import org.apereo.cas.authentication.policy.RequiredAuthenticationHandlerAuthenticationPolicy;
import org.apereo.cas.authentication.policy.RestfulAuthenticationPolicy;
import org.apereo.cas.authentication.support.password.DefaultPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.support.password.GroovyPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.support.password.RejectResultCodePasswordPolicyHandlingStrategy;
import org.apereo.cas.configuration.model.core.authentication.AdaptiveAuthenticationProperties;
import org.apereo.cas.configuration.model.core.authentication.AuthenticationPolicyProperties;
import org.apereo.cas.configuration.model.core.authentication.PasswordPolicyProperties;
import org.apereo.cas.configuration.model.core.authentication.PersonDirectoryPrincipalResolverProperties;
import org.apereo.cas.configuration.model.core.authentication.PrincipalAttributesCoreProperties;
import org.apereo.cas.configuration.support.Beans;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.nativex.CasRuntimeHintsRegistrar;
import org.apereo.cas.validation.Assertion;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import groovy.lang.GroovyClassLoader;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apereo.services.persondir.support.merger.IAttributeMerger;
import org.apereo.services.persondir.support.merger.MultivaluedAttributeMerger;
import org.apereo.services.persondir.support.merger.NoncollidingAttributeAdder;
import org.apereo.services.persondir.support.merger.ReplacingAttributeAdder;
import org.apereo.services.persondir.support.merger.ReturnChangesAdditiveAttributeMerger;
import org.apereo.services.persondir.support.merger.ReturnOriginalAdditiveAttributeMerger;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This is {@link CoreAuthenticationUtils}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */

@Slf4j
@UtilityClass
public class CoreAuthenticationUtils {

    /**
     * Convert attribute values to objects.
     *
     * @param attributes the attributes
     * @return the map
     */
    public static Map<String, Object> convertAttributeValuesToObjects(final Map<String, ? extends Object> attributes) {
        val entries = attributes.entrySet();
        return entries
            .stream()
            .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                val value = CollectionUtils.toCollection(entry.getValue());
                return value.size() == 1 ? value.iterator().next() : value;
            }));
    }

    /**
     * Gets attribute merger.
     *
     * @param mergingPolicy the merging policy
     * @return the attribute merger
     */
    public static IAttributeMerger getAttributeMerger(final PrincipalAttributesCoreProperties.MergingStrategyTypes mergingPolicy) {
        switch (mergingPolicy) {
            case MULTIVALUED -> {
                val merger = new MultivaluedAttributeMerger();
                merger.setDistinctValues(true);
                return merger;
            }
            case ADD -> {
                return new NoncollidingAttributeAdder();
            }
            case SOURCE -> {
                return new ReturnOriginalAdditiveAttributeMerger();
            }
            case DESTINATION -> {
                return new ReturnChangesAdditiveAttributeMerger();
            }
            default -> {
                return new ReplacingAttributeAdder();
            }
        }
    }

    /**
     * Is remember me authentication?
     * looks at the authentication object to find {@link RememberMeCredential#AUTHENTICATION_ATTRIBUTE_REMEMBER_ME}
     * and expects the assertion to also note a new login session.
     *
     * @param model     the model
     * @param assertion the assertion
     * @return true if remember-me, false if otherwise.
     */
    public static boolean isRememberMeAuthentication(final Authentication model, final Assertion assertion) {
        val authnAttributes = model.getAttributes();
        val authnMethod = authnAttributes.get(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME);
        return authnMethod != null && authnMethod.contains(Boolean.TRUE) && assertion.isFromNewLogin();
    }

    /**
     * Is remember me recorded in authentication.
     *
     * @param authentication the authentication
     * @return true/false
     */
    public static Boolean isRememberMeAuthentication(final Authentication authentication) {
        if (authentication == null) {
            return Boolean.FALSE;
        }
        val attributes = authentication.getAttributes();
        LOGGER.trace("Located authentication attributes [{}]", attributes);

        if (attributes.containsKey(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME)) {
            val rememberMeValue = attributes.get(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME);
            LOGGER.debug("Located remember-me authentication attribute [{}]", rememberMeValue);
            return rememberMeValue.contains(Boolean.TRUE);
        }
        return Boolean.FALSE;
    }

    /**
     * Merge attributes map.
     *
     * @param currentAttributes the current attributes
     * @param attributesToMerge the attributes to merge
     * @param merger            the merger
     * @return the map
     */
    public static Map<String, List<Object>> mergeAttributes(final Map<String, List<Object>> currentAttributes,
                                                            final Map<String, List<Object>> attributesToMerge,
                                                            final IAttributeMerger merger) {
        val toModify = currentAttributes.entrySet()
            .stream()
            .map(entry -> Pair.of(entry.getKey(), CollectionUtils.toCollection(entry.getValue(), ArrayList.class)))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

        val toMerge = attributesToMerge.entrySet()
            .stream()
            .map(entry -> Pair.of(entry.getKey(), CollectionUtils.toCollection(entry.getValue(), ArrayList.class)))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

        LOGGER.trace("Merging current attributes [{}] with [{}]", toModify, toMerge);
        val results = merger.mergeAttributes((Map) toModify, (Map) toMerge);
        LOGGER.debug("Merged attributes with the final result as [{}]", results);
        return results;
    }

    /**
     * Merge attributes map.
     *
     * @param currentAttributes the current attributes
     * @param attributesToMerge the attributes to merge
     * @return the map
     */
    public static Map<String, List<Object>> mergeAttributes(final Map<String, List<Object>> currentAttributes,
                                                            final Map<String, List<Object>> attributesToMerge) {
        val merger = new MultivaluedAttributeMerger();
        merger.setDistinctValues(true);
        return mergeAttributes(currentAttributes, attributesToMerge, merger);
    }

    /**
     * Transform principal attributes list into map map.
     *
     * @param list the list
     * @return the map
     */
    public static Map<String, Object> transformPrincipalAttributesListIntoMap(final List<String> list) {
        val map = transformPrincipalAttributesListIntoMultiMap(list);
        return CollectionUtils.wrap(map);
    }

    /**
     * Transform principal attributes into map.
     * Items in the list are defined in the syntax of "cn", or "cn:commonName" for virtual renaming and maps.
     *
     * @param list the list
     * @return the map
     */
    public static Multimap<String, Object> transformPrincipalAttributesListIntoMultiMap(final List<String> list) {
        val attributes = ArrayListMultimap.<String, Object>create();
        if (list.isEmpty()) {
            LOGGER.debug("No principal attributes are defined");
        } else {
            list.forEach(a -> {
                val attributeName = a.trim();
                if (attributeName.contains(":")) {
                    val attrCombo = Splitter.on(":").splitToList(attributeName);
                    val name = attrCombo.getFirst().trim();
                    val value = attrCombo.get(1).trim();
                    LOGGER.debug("Mapped principal attribute name [{}] to [{}]", name, value);
                    attributes.put(name, value);
                } else {
                    LOGGER.debug("Mapped principal attribute name [{}]", attributeName);
                    attributes.put(attributeName, attributeName);
                }
            });
        }
        return attributes;
    }


    /**
     * Gets credential selection predicate.
     *
     * @param selectionCriteria the selection criteria
     * @return the credential selection predicate
     */
    public static Predicate<Credential> newCredentialSelectionPredicate(final String selectionCriteria) {
        try {
            if (StringUtils.isBlank(selectionCriteria)) {
                return credential -> true;
            }
            if (selectionCriteria.endsWith(".groovy") && CasRuntimeHintsRegistrar.notInNativeImage()) {
                val loader = new DefaultResourceLoader();
                val resource = loader.getResource(selectionCriteria);
                val script = IOUtils.toString(resource.getInputStream(), StandardCharsets.UTF_8);
                val classLoader = new GroovyClassLoader(Beans.class.getClassLoader(),
                    new CompilerConfiguration(), true);
                val clz = classLoader.parseClass(script);
                return (Predicate<Credential>) clz.getDeclaredConstructor().newInstance();
            }
            val predicateClazz = ClassUtils.getClass(selectionCriteria);
            return (Predicate<Credential>) predicateClazz.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            val predicate = Pattern.compile(selectionCriteria).asPredicate();
            return credential -> predicate.test(credential.getId());
        }
    }

    /**
     * New password policy handling strategy.
     *
     * @param properties         the properties
     * @param applicationContext the application context
     * @return the authentication password policy handling strategy
     */
    public static AuthenticationPasswordPolicyHandlingStrategy newPasswordPolicyHandlingStrategy(final PasswordPolicyProperties properties,
                                                                                                 final ApplicationContext applicationContext) {
        if (properties.getStrategy() == PasswordPolicyProperties.PasswordPolicyHandlingOptions.REJECT_RESULT_CODE) {
            LOGGER.debug("Created password policy handling strategy based on blocked authentication result codes");
            return new RejectResultCodePasswordPolicyHandlingStrategy<>();
        }

        val location = properties.getGroovy().getLocation();
        if (properties.getStrategy() == PasswordPolicyProperties.PasswordPolicyHandlingOptions.GROOVY
            && location != null && CasRuntimeHintsRegistrar.notInNativeImage()) {
            LOGGER.debug("Created password policy handling strategy based on Groovy script [{}]", location);
            return new GroovyPasswordPolicyHandlingStrategy<>(location, applicationContext);
        }

        LOGGER.trace("Created default password policy handling strategy");
        return new DefaultPasswordPolicyHandlingStrategy<>();
    }


    /**
     * New authentication policy collection.
     *
     * @param policyProps the policy props
     * @return the collection
     */
    public static Collection<AuthenticationPolicy> newAuthenticationPolicy(final AuthenticationPolicyProperties policyProps) {
        if (policyProps.getReq().isEnabled()) {
            LOGGER.trace("Activating authentication policy [{}]", RequiredAuthenticationHandlerAuthenticationPolicy.class.getSimpleName());
            val requiredHandlerNames = org.springframework.util.StringUtils.commaDelimitedListToSet(policyProps.getReq().getHandlerName());
            var policy = new RequiredAuthenticationHandlerAuthenticationPolicy(requiredHandlerNames, policyProps.getReq().isTryAll());
            return CollectionUtils.wrapList(policy);
        }

        if (policyProps.getAllHandlers().isEnabled()) {
            LOGGER.trace("Activating authentication policy [{}]", AllAuthenticationHandlersSucceededAuthenticationPolicy.class.getSimpleName());
            return CollectionUtils.wrapList(new AllAuthenticationHandlersSucceededAuthenticationPolicy());
        }

        if (policyProps.getAll().isEnabled()) {
            LOGGER.trace("Activating authentication policy [{}]", AllCredentialsValidatedAuthenticationPolicy.class.getSimpleName());
            return CollectionUtils.wrapList(new AllCredentialsValidatedAuthenticationPolicy());
        }

        if (policyProps.getNotPrevented().isEnabled()) {
            LOGGER.trace("Activating authentication policy [{}]", NotPreventedAuthenticationPolicy.class.getSimpleName());
            return CollectionUtils.wrapList(new NotPreventedAuthenticationPolicy());
        }

        if (!policyProps.getGroovy().isEmpty() && CasRuntimeHintsRegistrar.notInNativeImage()) {
            LOGGER.trace("Activating authentication policy [{}]", GroovyScriptAuthenticationPolicy.class.getSimpleName());
            return policyProps.getGroovy()
                .stream()
                .map(groovy -> new GroovyScriptAuthenticationPolicy(groovy.getScript()))
                .collect(Collectors.toList());
        }

        if (!policyProps.getRest().isEmpty()) {
            LOGGER.trace("Activating authentication policy [{}]", RestfulAuthenticationPolicy.class.getSimpleName());
            return policyProps.getRest()
                .stream()
                .map(RestfulAuthenticationPolicy::new)
                .collect(Collectors.toList());
        }

        if (policyProps.getAny().isEnabled()) {
            LOGGER.trace("Activating authentication policy [{}]", AtLeastOneCredentialValidatedAuthenticationPolicy.class.getSimpleName());
            return CollectionUtils.wrapList(new AtLeastOneCredentialValidatedAuthenticationPolicy(policyProps.getAny().isTryAll()));
        }
        return new ArrayList<>();
    }

    /**
     * New ip address intelligence service.
     *
     * @param adaptive the adaptive
     * @return the ip address intelligence service
     */
    public static IPAddressIntelligenceService newIpAddressIntelligenceService(final AdaptiveAuthenticationProperties adaptive) {
        val intel = adaptive.getIpIntel();

        if (StringUtils.isNotBlank(intel.getRest().getUrl())) {
            return new RestfulIPAddressIntelligenceService(adaptive);
        }
        if (intel.getGroovy().getLocation() != null && CasRuntimeHintsRegistrar.notInNativeImage()) {
            return new GroovyIPAddressIntelligenceService(adaptive);
        }
        if (StringUtils.isNotBlank(intel.getBlackDot().getEmailAddress())) {
            return new RestfulIPAddressIntelligenceService(adaptive);
        }
        return new DefaultIPAddressIntelligenceService(adaptive);
    }

    /**
     * New principal election strategy conflict resolver.
     *
     * @param properties the properties
     * @return the principal election strategy conflict resolver
     */
    public static PrincipalElectionStrategyConflictResolver newPrincipalElectionStrategyConflictResolver(
        final PersonDirectoryPrincipalResolverProperties properties) {
        if (StringUtils.equalsIgnoreCase(properties.getPrincipalResolutionConflictStrategy(), "first")) {
            return PrincipalElectionStrategyConflictResolver.first();
        }
        return PrincipalElectionStrategyConflictResolver.last();
    }


}
