package org.apereo.cas.syncope;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.configuration.model.support.syncope.SyncopeAccountManagementRegistrationProvisioningProperties;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.HttpUtils;
import org.apereo.cas.util.serialization.JacksonObjectMapperFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This is {@link SyncopeAccountRegistrationProvisioner}.
 *
 * @author Misagh Moayyed
 * @since 7.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class SyncopeAccountRegistrationProvisioner implements AccountRegistrationProvisioner {
    private static final ObjectMapper MAPPER = JacksonObjectMapperFactory.builder()
        .defaultTypingEnabled(false).build().toObjectMapper();

    private final SyncopeAccountManagementRegistrationProvisioningProperties properties;

    @Override
    public AccountRegistrationResponse provision(final AccountRegistrationRequest request) throws Exception {
        HttpResponse response = null;
        try {
            val syncopeRestUrl = StringUtils.appendIfMissing(properties.getUrl(), "/rest/users");
            val headers = CollectionUtils.<String, String>wrap("X-Syncope-Domain", properties.getDomain(),
                "Accept", MediaType.APPLICATION_JSON_VALUE,
                "Content-Type", MediaType.APPLICATION_JSON_VALUE);
            headers.putAll(properties.getHeaders());

            val entity = MAPPER.writeValueAsString(convertRegistrationRequestToEntity(request));
            val exec = HttpUtils.HttpExecutionRequest.builder()
                .method(HttpMethod.POST)
                .url(syncopeRestUrl)
                .basicAuthUsername(properties.getBasicAuthUsername())
                .basicAuthPassword(properties.getBasicAuthPassword())
                .headers(headers)
                .entity(entity)
                .build();
            response = Objects.requireNonNull(HttpUtils.execute(exec));
            LOGGER.debug("Received http response status as [{}]", response.getStatusLine());
            if (!HttpStatus.valueOf(response.getStatusLine().getStatusCode()).isError()) {
                val result = EntityUtils.toString(response.getEntity());
                LOGGER.debug("Received user object as [{}]", result);
                val responseJson = MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {
                });
                return AccountRegistrationResponse.success()
                    .putProperty("entity", responseJson.get("entity"))
                    .putProperty("propagationStatuses", responseJson.get("propagationStatuses"));
            }
        } finally {
            HttpUtils.close(response);
        }
        return AccountRegistrationResponse.failure();
    }

    protected Map<String, Object> convertRegistrationRequestToEntity(final AccountRegistrationRequest request) {
        val entity = new LinkedHashMap<String, Object>();
        entity.put("_class", getUserClassName());
        entity.put("realm", getSyncopeRealm(request));
        entity.put("username", request.getUsername());
        entity.put("password", request.getPassword());

        val plainAttrs = new ArrayList<Map<String, Object>>();
        request.getProperties()
            .entrySet()
            .stream()
            .filter(entry -> !"username".equals(entry.getKey()) && !"password".equals(entry.getKey()))
            .forEach(entry -> plainAttrs.add(Map.of("schema", entry.getKey(), "values", CollectionUtils.toCollection(entry.getValue()))));
        entity.put("plainAttrs", plainAttrs);
        return entity;
    }

    protected String getUserClassName() {
        return "org.apache.syncope.common.lib.request.UserCR";
    }

    protected String getSyncopeRealm(final AccountRegistrationRequest request) {
        return StringUtils.defaultString(request.getProperty("realm", String.class), properties.getRealm());
    }
}
