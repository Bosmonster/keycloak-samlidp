/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.broker.saml;

import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.IdentityProviderDataMarshaller;
import org.keycloak.broker.provider.IdentityProviderMapper;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.common.util.PemUtils;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.dom.saml.v2.assertion.SubjectType;
import org.keycloak.dom.saml.v2.metadata.AttributeConsumingService;
import org.keycloak.dom.saml.v2.metadata.AttributeConsumingServiceType;
import org.keycloak.dom.saml.v2.metadata.EndpointType;
import org.keycloak.dom.saml.v2.metadata.EntityDescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyDescriptorType;
import org.keycloak.dom.saml.v2.metadata.KeyTypes;
import org.keycloak.dom.saml.v2.metadata.LocalizedNameType;
import org.keycloak.dom.saml.v2.metadata.RequestedAttributeType;
import org.keycloak.dom.saml.v2.protocol.ArtifactResolveType;
import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.dom.saml.v2.protocol.LogoutRequestType;
import org.keycloak.dom.saml.v2.protocol.ResponseType;
import org.keycloak.events.EventBuilder;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.keys.PublicKeyStorageUtils;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.saml.JaxrsSAML2BindingBuilder;
import org.keycloak.protocol.saml.JaxrsSAML2BindingBuilderWithConfig;
import org.keycloak.protocol.saml.SamlMetadataPublicKeyLoader;
import org.keycloak.protocol.saml.SamlProtocol;
import org.keycloak.protocol.saml.SamlService;
import org.keycloak.protocol.saml.SamlSessionUtils;
import org.keycloak.protocol.saml.mappers.SamlMetadataDescriptorUpdater;
import org.keycloak.protocol.saml.preprocessor.SamlAuthenticationPreprocessor;
import org.keycloak.protocol.saml.SAMLEncryptionAlgorithms;
import org.keycloak.protocol.saml.profile.util.Soap;
import org.keycloak.saml.SAML2ArtifactResolutionBuilder;
import org.keycloak.saml.SAML2ArtifactResolveRequestBuilder;
import org.keycloak.saml.SAML2AuthnRequestBuilder;
import org.keycloak.saml.SAML2LogoutRequestBuilder;
import org.keycloak.saml.SAML2NameIDPolicyBuilder;
import org.keycloak.saml.SAML2RequestedAuthnContextBuilder;
import org.keycloak.saml.SPMetadataDescriptor;
import org.keycloak.saml.SPMetadataDescriptorBuilder;
import org.keycloak.saml.SamlProtocolExtensionsAwareBuilder.NodeGenerator;
import org.keycloak.saml.SignatureAlgorithm;
import org.keycloak.saml.common.constants.GeneralConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.exceptions.ConfigurationException;
import org.keycloak.saml.common.exceptions.ParsingException;
import org.keycloak.saml.common.exceptions.ProcessingException;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.common.util.StaxUtil;
import org.keycloak.saml.processing.api.saml.v2.request.SAML2Request;
import org.keycloak.saml.processing.api.saml.v2.response.SAML2Response;
import org.keycloak.saml.processing.api.saml.v2.sig.SAML2Signature;
import org.keycloak.saml.processing.core.saml.v2.common.SAMLDocumentHolder;
import org.keycloak.saml.processing.core.saml.v2.util.SAMLMetadataUtil;
import org.keycloak.saml.processing.core.saml.v2.writers.SAMLMetadataWriter;
import org.keycloak.saml.processing.core.util.KeycloakKeySamlExtensionGenerator;
import org.keycloak.saml.validators.DestinationValidator;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.keycloak.saml.common.constants.JBossSAMLURIConstants.ATTRIBUTE_FORMAT_BASIC;

/**
 * @author Pedro Igor
 */
public class SAMLIdentityProvider extends AbstractIdentityProvider<SAMLIdentityProviderConfig> {
    protected static final Logger logger = Logger.getLogger(SAMLIdentityProvider.class);

    protected final SAMLIdentityProviderConfig config;

    private final DestinationValidator destinationValidator;
    public SAMLIdentityProvider(KeycloakSession session, SAMLIdentityProviderConfig config, DestinationValidator destinationValidator) {
        super(session, config);
        this.destinationValidator = destinationValidator;
        this.config = config;
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new SAMLEndpoint(session, this, getConfig(), callback, destinationValidator);
    }

    @Override
    public Response performLogin(AuthenticationRequest request) {
        try {
            UriInfo uriInfo = request.getUriInfo();
            RealmModel realm = request.getRealm();
            String issuerURL = getEntityId(uriInfo, realm);
            String destinationUrl = getConfig().getSingleSignOnServiceUrl();
            String nameIDPolicyFormat = getConfig().getNameIDPolicyFormat();

            if (nameIDPolicyFormat == null) {
                nameIDPolicyFormat =  JBossSAMLURIConstants.NAMEID_FORMAT_PERSISTENT.get();
            }

            String protocolBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.get();

            String assertionConsumerServiceUrl = request.getRedirectUri();

            if (getConfig().isArtifactBindingResponse()) {
                protocolBinding = JBossSAMLURIConstants.SAML_HTTP_ARTIFACT_BINDING.get();
            } else if (getConfig().isPostBindingResponse()) {
                protocolBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.get();
            }

            SAML2RequestedAuthnContextBuilder requestedAuthnContext =
                new SAML2RequestedAuthnContextBuilder()
                    .setComparison(getConfig().getAuthnContextComparisonType());

            for (String authnContextClassRef : getAuthnContextClassRefUris())
                requestedAuthnContext.addAuthnContextClassRef(authnContextClassRef);

            for (String authnContextDeclRef : getAuthnContextDeclRefUris())
                requestedAuthnContext.addAuthnContextDeclRef(authnContextDeclRef);

            Integer attributeConsumingServiceIndex = getConfig().getAttributeConsumingServiceIndex();

            String loginHint = getConfig().isLoginHint() ? request.getAuthenticationSession().getClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM) : null;
            Boolean allowCreate = null;
            if (getConfig().getConfig().get(SAMLIdentityProviderConfig.ALLOW_CREATE) == null || getConfig().isAllowCreate())
                allowCreate = Boolean.TRUE;
            LoginProtocol protocol = session.getProvider(LoginProtocol.class, request.getAuthenticationSession().getProtocol());
            Boolean forceAuthn = getConfig().isForceAuthn();
            if (protocol.requireReauthentication(null, request.getAuthenticationSession()))
                forceAuthn = Boolean.TRUE;
            SAML2AuthnRequestBuilder authnRequestBuilder = new SAML2AuthnRequestBuilder()
                    .assertionConsumerUrl(assertionConsumerServiceUrl)
                    .destination(destinationUrl)
                    .issuer(issuerURL)
                    .forceAuthn(forceAuthn)
                    .protocolBinding(protocolBinding)
                    .nameIdPolicy(SAML2NameIDPolicyBuilder
                        .format(nameIDPolicyFormat)
                        .setAllowCreate(allowCreate))
                    .attributeConsumingServiceIndex(attributeConsumingServiceIndex)
                    .requestedAuthnContext(requestedAuthnContext)
                    .subject(loginHint);

            JaxrsSAML2BindingBuilderWithConfig binding = new JaxrsSAML2BindingBuilderWithConfig(session, config)
                    .relayState(request.getState().getEncoded());
            boolean postBinding = getConfig().isPostBindingAuthnRequest();

            if (getConfig().isWantAuthnRequestsSigned()) {
                KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);

                String keyName = getConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
                binding.signWith(keyName, keys.getPrivateKey(), keys.getPublicKey(), keys.getCertificate())
                        .signatureAlgorithm(getSignatureAlgorithm())
                        .signDocument();
                if (! postBinding && getConfig().isAddExtensionsElementWithKeyInfo()) {    // Only include extension if REDIRECT binding and signing whole SAML protocol message
                    authnRequestBuilder.addExtension(new KeycloakKeySamlExtensionGenerator(keyName));
                }
            }

            AuthnRequestType authnRequest = authnRequestBuilder.createAuthnRequest();
            for(Iterator<SamlAuthenticationPreprocessor> it = SamlSessionUtils.getSamlAuthenticationPreprocessorIterator(session); it.hasNext(); ) {
                authnRequest = it.next().beforeSendingLoginRequest(authnRequest, request.getAuthenticationSession());
            }

            if (authnRequest.getDestination() != null) {
                destinationUrl = authnRequest.getDestination().toString();
            }

            // Save the current RequestID in the Auth Session as we need to verify it against the ID returned from the IdP
            request.getAuthenticationSession().setClientNote(SamlProtocol.SAML_REQUEST_ID_BROKER, authnRequest.getID());

            if (postBinding) {
                return binding.postBinding(SAML2Request.convert(authnRequest)).request(destinationUrl);
            } else {
                return binding.redirectBinding(SAML2Request.convert(authnRequest)).request(destinationUrl);
            }
        } catch (Exception e) {
            throw new IdentityBrokerException("Could not create authentication request.", e);
        }
    }

    private String getEntityId(UriInfo uriInfo, RealmModel realm) {
        String configEntityId = getConfig().getEntityId();

        if (configEntityId == null || configEntityId.isEmpty())
            return UriBuilder.fromUri(uriInfo.getBaseUri()).path("realms").path(realm.getName()).build().toString();
        else
            return configEntityId;
    }

    private List<String> getAuthnContextClassRefUris() {
        String authnContextClassRefs = getConfig().getAuthnContextClassRefs();
        if (authnContextClassRefs == null || authnContextClassRefs.isEmpty())
            return new LinkedList<String>();

        try {
            return Arrays.asList(JsonSerialization.readValue(authnContextClassRefs, String[].class));
        } catch (Exception e) {
            logger.warn("Could not json-deserialize AuthContextClassRefs config entry: " + authnContextClassRefs, e);
            return new LinkedList<String>();
        }
    }

    private List<String> getAuthnContextDeclRefUris() {
        String authnContextDeclRefs = getConfig().getAuthnContextDeclRefs();
        if (authnContextDeclRefs == null || authnContextDeclRefs.isEmpty())
            return new LinkedList<String>();

        try {
            return Arrays.asList(JsonSerialization.readValue(authnContextDeclRefs, String[].class));
        } catch (Exception e) {
            logger.warn("Could not json-deserialize AuthContextDeclRefs config entry: " + authnContextDeclRefs, e);
            return new LinkedList<String>();
        }
    }

    @Override
    public void authenticationFinished(AuthenticationSessionModel authSession, BrokeredIdentityContext context)  {
        ResponseType responseType = (ResponseType)context.getContextData().get(SAMLEndpoint.SAML_LOGIN_RESPONSE);
        AssertionType assertion = (AssertionType)context.getContextData().get(SAMLEndpoint.SAML_ASSERTION);
        SubjectType subject = assertion.getSubject();
        SubjectType.STSubType subType = subject.getSubType();
        if (subType != null) {
            NameIDType subjectNameID = (NameIDType) subType.getBaseID();
            authSession.setUserSessionNote(SAMLEndpoint.SAML_FEDERATED_SUBJECT_NAMEID, subjectNameID.serializeAsString());
        }
        AuthnStatementType authn =  (AuthnStatementType)context.getContextData().get(SAMLEndpoint.SAML_AUTHN_STATEMENT);
        if (authn != null && authn.getSessionIndex() != null) {
            authSession.setUserSessionNote(SAMLEndpoint.SAML_FEDERATED_SESSION_INDEX, authn.getSessionIndex());

        }
    }

    @Override
    public Response retrieveToken(KeycloakSession session, FederatedIdentityModel identity) {
        return Response.ok(identity.getToken()).type(MediaType.TEXT_PLAIN_TYPE).build();
    }

    @Override
    public void backchannelLogout(KeycloakSession session, UserSessionModel userSession, UriInfo uriInfo, RealmModel realm) {
        String singleLogoutServiceUrl = getConfig().getSingleLogoutServiceUrl();
        if (singleLogoutServiceUrl == null || singleLogoutServiceUrl.trim().equals("") || !getConfig().isBackchannelSupported()) return;
        JaxrsSAML2BindingBuilderWithConfig binding = buildLogoutBinding(session, userSession, realm);
        try {
            LogoutRequestType logoutRequest = buildLogoutRequest(userSession, uriInfo, realm, singleLogoutServiceUrl);
            if (logoutRequest.getDestination() != null) {
                singleLogoutServiceUrl = logoutRequest.getDestination().toString();
            }
            int status = SimpleHttp.doPost(singleLogoutServiceUrl, session)
                    .param(GeneralConstants.SAML_REQUEST_KEY, binding.postBinding(SAML2Request.convert(logoutRequest)).encoded())
                    .param(GeneralConstants.RELAY_STATE, userSession.getId()).asStatus();
            boolean success = status >=200 && status < 400;
            if (!success) {
                logger.warn("Failed saml backchannel broker logout to: " + singleLogoutServiceUrl);
            }
        } catch (Exception e) {
            logger.warn("Failed saml backchannel broker logout to: " + singleLogoutServiceUrl, e);
        }

    }

    @Override
    public Response keycloakInitiatedBrowserLogout(KeycloakSession session, UserSessionModel userSession, UriInfo uriInfo, RealmModel realm) {
        String singleLogoutServiceUrl = getConfig().getSingleLogoutServiceUrl();
        if (singleLogoutServiceUrl == null || singleLogoutServiceUrl.trim().equals("")) return null;

        if (getConfig().isBackchannelSupported()) {
            backchannelLogout(session, userSession, uriInfo, realm);
            return null;
       } else {
            try {
                LogoutRequestType logoutRequest = buildLogoutRequest(userSession, uriInfo, realm, singleLogoutServiceUrl);
                if (logoutRequest.getDestination() != null) {
                    singleLogoutServiceUrl = logoutRequest.getDestination().toString();
                }
                JaxrsSAML2BindingBuilderWithConfig binding = buildLogoutBinding(session, userSession, realm);
                if (getConfig().isPostBindingLogout()) {
                    return binding.postBinding(SAML2Request.convert(logoutRequest)).request(singleLogoutServiceUrl);
                } else {
                    return binding.redirectBinding(SAML2Request.convert(logoutRequest)).request(singleLogoutServiceUrl);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected LogoutRequestType buildLogoutRequest(UserSessionModel userSession, UriInfo uriInfo, RealmModel realm, String singleLogoutServiceUrl, NodeGenerator... extensions) throws ConfigurationException {
        SAML2LogoutRequestBuilder logoutBuilder = new SAML2LogoutRequestBuilder()
                .assertionExpiration(realm.getAccessCodeLifespan())
                .issuer(getEntityId(uriInfo, realm))
                .sessionIndex(userSession.getNote(SAMLEndpoint.SAML_FEDERATED_SESSION_INDEX))
                .nameId(NameIDType.deserializeFromString(userSession.getNote(SAMLEndpoint.SAML_FEDERATED_SUBJECT_NAMEID)))
                .destination(singleLogoutServiceUrl);
        LogoutRequestType logoutRequest = logoutBuilder.createLogoutRequest();
        for (NodeGenerator extension : extensions) {
            logoutBuilder.addExtension(extension);
        }
        for (Iterator<SamlAuthenticationPreprocessor> it = SamlSessionUtils.getSamlAuthenticationPreprocessorIterator(session); it.hasNext();) {
            logoutRequest = it.next().beforeSendingLogoutRequest(logoutRequest, userSession, null);
        }
        return logoutRequest;
    }

    private JaxrsSAML2BindingBuilderWithConfig buildLogoutBinding(KeycloakSession session, UserSessionModel userSession, RealmModel realm) {
        JaxrsSAML2BindingBuilderWithConfig binding = new JaxrsSAML2BindingBuilderWithConfig(session, config)
                .relayState(userSession.getId());
        if (getConfig().isWantAuthnRequestsSigned()) {
            KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);
            String keyName = getConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
            binding.signWith(keyName, keys.getPrivateKey(), keys.getPublicKey(), keys.getCertificate())
                    .signatureAlgorithm(getSignatureAlgorithm())
                    .signDocument();
        }
        return binding;
    }

    public String resolveArtifact(String artifact, String issuerURL, RealmModel realm) {
        String response = "";
        try {
            SAML2ArtifactResolutionBuilder builder = new SAML2ArtifactResolutionBuilder()
                    .artifact(artifact)
                    .issuer(issuerURL);

            JaxrsSAML2BindingBuilderWithConfig binding = new JaxrsSAML2BindingBuilderWithConfig(session, getConfig());
            if (getConfig().isSignArtifactResolutionRequest()) {
                KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);

                KeyPair keypair = new KeyPair(keys.getPublicKey(), keys.getPrivateKey());

                String keyName = getConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
                binding.signWith(keyName, keypair);
                binding.signatureAlgorithm(getSignatureAlgorithm());
                binding.signDocument();
            }

            URI artifactResolutionEndpoint = new URI(getConfig().getArtifactResolutionEndpoint());
            response = binding.postBinding(builder.toDocument()).artifactResolutionRequest(artifactResolutionEndpoint, realm);
        } catch (ProcessingException | IOException | URISyntaxException e) {
            logger.warn("Cannot resolve SAMLArtifact returning empty response");
            logger.warn(e.getMessage(), e);
        }

        return response;
    }

    @Override
    public Response export(UriInfo uriInfo, RealmModel realm, String format) {
        try
        {
            List<URI> endpoints = new ArrayList();
            endpoints.add(uriInfo.getBaseUriBuilder()
                    .path("realms").path(realm.getName())
                    .path("broker")
                    .path(getConfig().getAlias())
                    .path("endpoint")
                    .build());

            List<String> linkedProviders = getConfig().getLinkedProviders();
            if (!linkedProviders.isEmpty()) {
                for (String linkedProvider : linkedProviders) {
                    endpoints.add(uriInfo.getBaseUriBuilder()
                            .path("realms").path(realm.getName())
                            .path("broker")
                            .path(linkedProvider)
                            .path("endpoint")
                            .build());
                }
            }

            URI authnResponseBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.getUri();
            if (getConfig().isPostBindingAuthnRequest()) {
                authnResponseBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.getUri();
            }

            URI artifactBinding = JBossSAMLURIConstants.SAML_SOAP_BINDING.getUri();
            URI logoutBinding = JBossSAMLURIConstants.SAML_HTTP_REDIRECT_BINDING.getUri();

            if (getConfig().isPostBindingLogout()) {
                logoutBinding = JBossSAMLURIConstants.SAML_HTTP_POST_BINDING.getUri();
            }

            URI artifactEndpoint = uriInfo.getBaseUriBuilder()
                    .path("realms").path(realm.getName())
                    .path("broker")
                    .path(getConfig().getAlias())
                    .path("endpoint")
                    .build();

            boolean wantAuthnRequestsSigned = getConfig().isWantAuthnRequestsSigned();
            boolean wantAssertionsSigned = getConfig().isWantAssertionsSigned();
            boolean wantAssertionsEncrypted = getConfig().isWantAssertionsEncrypted();
            String entityId = getEntityId(uriInfo, realm);
            String nameIDPolicyFormat = getConfig().getNameIDPolicyFormat();

            // We export all keys for algorithm RS256, both active and passive so IDP is able to verify signature even
            //  if a key rotation happens in the meantime
            List<KeyDescriptorType> signingKeys = session.keys().getKeysStream(realm, KeyUse.SIG, Algorithm.RS256)
                    .filter(key -> key.getCertificate() != null)
                    .sorted(SamlService::compareKeys)
                    .map(key -> {
                        try {
                            return SPMetadataDescriptor.buildKeyInfoElement(key.getKid(), PemUtils.encodeCertificate(key.getCertificate()));
                        } catch (ParserConfigurationException e) {
                            logger.warn("Failed to export SAML SP Metadata!", e);
                            throw new RuntimeException(e);
                        }
                    })
                    .map(key -> SPMetadataDescriptor.buildKeyDescriptorType(key, KeyTypes.SIGNING, null))
                    .collect(Collectors.toList());

            // We export only active ENC keys so IDP uses different key as soon as possible if a key rotation happens
            String encAlg = getConfig().getEncryptionAlgorithm();
            List<KeyDescriptorType> encryptionKeys = session.keys().getKeysStream(realm)
                    .filter(key -> key.getStatus().isActive() && KeyUse.ENC.equals(key.getUse())
                            && (encAlg == null || Objects.equals(encAlg, key.getAlgorithmOrDefault()))
                            && SAMLEncryptionAlgorithms.forKeycloakIdentifier(key.getAlgorithm()) != null
                            && key.getCertificate() != null)
                    .sorted(SamlService::compareKeys)
                    .map(key -> {
                        Element keyInfo;
                        try {
                            keyInfo = SPMetadataDescriptor.buildKeyInfoElement(key.getKid(), PemUtils.encodeCertificate(key.getCertificate()));
                        } catch (ParserConfigurationException e) {
                            logger.warn("Failed to export SAML SP Metadata!", e);
                            throw new RuntimeException(e);
                        }

                        return SPMetadataDescriptor.buildKeyDescriptorType(keyInfo, KeyTypes.ENCRYPTION, SAMLEncryptionAlgorithms.forKeycloakIdentifier(key.getAlgorithm()).getXmlEncIdentifiers());
                    })
                    .collect(Collectors.toList());

            // Prepare the metadata descriptor model
            StringWriter sw = new StringWriter();
            XMLStreamWriter writer = StaxUtil.getXMLStreamWriter(sw);
            SAMLMetadataWriter metadataWriter = new SAMLMetadataWriter(writer);

            Integer defaultAssertionEndpointIndex = getConfig().getAssertionConsumingServiceIndex();
            if (defaultAssertionEndpointIndex == null) {
                defaultAssertionEndpointIndex = 1;
            }

            SPMetadataDescriptorBuilder spMetadataDescriptorBuilder = new SPMetadataDescriptorBuilder()
                    .loginBinding(authnResponseBinding)
                    .logoutBinding(logoutBinding)
                    .assertionEndpoints(endpoints)
                    .defaultAssertionEndpoint(defaultAssertionEndpointIndex)
                    .logoutEndpoints(endpoints)
                    .wantAuthnRequestsSigned(wantAuthnRequestsSigned)
                    .wantAssertionsSigned(wantAssertionsSigned)
                    .wantAssertionsEncrypted(wantAssertionsEncrypted)
                    .entityId(entityId)
                    .nameIDPolicyFormat(nameIDPolicyFormat)
                    .signingCerts(signingKeys)
                    .encryptionCerts(encryptionKeys);
            if (getConfig().isIncludeArtifactResolutionServiceMetadata()) {
                spMetadataDescriptorBuilder.artifactResolutionBinding(artifactBinding)
                        .artifactResolutionEndpoint(artifactEndpoint);
            }
            if (getConfig().getMetadataValidUntilUnit() != null && getConfig().getMetadataValidUntilPeriod() != null) {
                logger.debugf("Valid Until set for Metadata. Setting valid until current date + %s %s",
                        getConfig().getMetadataValidUntilUnit(), getConfig().getMetadataValidUntilPeriod());
                spMetadataDescriptorBuilder
                        .metadataValidUntilUnit(getConfig().getMetadataValidUntilUnit())
                        .metadataValidUntilPeriod(getConfig().getMetadataValidUntilPeriod());
            }
            EntityDescriptorType entityDescriptor = spMetadataDescriptorBuilder.build();

            // Assuming AttributeConsumingServiceType.getServices() returns a list of AttributeConsumingService objects.
            List<AttributeConsumingService> attributeValues = AttributeConsumingServiceType.getAttributeConsumingServices();
            if (attributeValues != null && !attributeValues.isEmpty()) {
                int attributeConsumingServiceIndex = 1;
                int defaultAttributeConsumingServiceIndex = (getConfig().getAttributeConsumingServiceIndex() != null && getConfig().getAttributeConsumingServiceIndex() > 0) ? getConfig().getAttributeConsumingServiceIndex() : 1;

                for (AttributeConsumingService config : attributeValues) {
                    String attributeConsumingServiceName = config.getServiceName();
                    if (attributeConsumingServiceName == null) {
                        attributeConsumingServiceName = realm.getDisplayName() != null ? realm.getDisplayName() : realm.getName();
                    }

                    AttributeConsumingServiceType attributeConsumingService = new AttributeConsumingServiceType(attributeConsumingServiceIndex);
                    attributeConsumingService.setIsDefault(attributeConsumingServiceIndex == defaultAttributeConsumingServiceIndex);

                    String currentLocale = realm.getDefaultLocale() == null ? "en" : realm.getDefaultLocale();
                    LocalizedNameType attributeConsumingServiceNameElement = new LocalizedNameType(currentLocale);
                    attributeConsumingServiceNameElement.setValue(attributeConsumingServiceName);
                    attributeConsumingService.addServiceName(attributeConsumingServiceNameElement);

                    String attributeName = config.getAttributeName();
                    String attributeFriendlyName = config.getFriendlyName();
                    String attributeValue = config.getAttributeValue();

                    RequestedAttributeType requestedAttribute = new RequestedAttributeType(attributeName);
                    requestedAttribute.setIsRequired(null);
                    requestedAttribute.setNameFormat(ATTRIBUTE_FORMAT_BASIC.get());

                    if (attributeFriendlyName != null && !attributeFriendlyName.isEmpty()) {
                        requestedAttribute.setFriendlyName(attributeFriendlyName);
                    }

                    if (attributeValue != null && !attributeValue.isEmpty()) {
                        requestedAttribute.addAttributeValue(attributeValue);
                    }

                    boolean alreadyPresent = attributeConsumingService.getRequestedAttribute().stream()
                            .anyMatch(t -> (attributeName == null || attributeName.equalsIgnoreCase(t.getName())) &&
                                    (attributeFriendlyName == null || attributeFriendlyName.equalsIgnoreCase(t.getFriendlyName())));

                    if (!alreadyPresent) {
                        attributeConsumingService.addRequestedAttribute(requestedAttribute);
                    }

                    for (EntityDescriptorType.EDTChoiceType choiceType : entityDescriptor.getChoiceType()) {
                        List<EntityDescriptorType.EDTDescriptorChoiceType> descriptors = choiceType.getDescriptors();
                        for (EntityDescriptorType.EDTDescriptorChoiceType descriptor : descriptors) {
                            descriptor.getSpDescriptor().addAttributeConsumerService(attributeConsumingService);
                        }
                    }

                    attributeConsumingServiceIndex++;
                }
            }

            metadataWriter.writeEntityDescriptor(entityDescriptor);

            String descriptor = sw.toString();

            // Metadata signing
            if (getConfig().isSignSpMetadata()) {
                KeyManager.ActiveRsaKey activeKey = session.keys().getActiveRsaKey(realm);
                X509Certificate certificate = activeKey.getCertificate();
                String keyName = getConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(activeKey.getKid(), certificate);
                KeyPair keyPair = new KeyPair(activeKey.getPublicKey(), activeKey.getPrivateKey());

                Document metadataDocument = DocumentUtil.getDocument(descriptor);
                SAML2Signature signatureHelper = new SAML2Signature();
                signatureHelper.setSignatureMethod(getSignatureAlgorithm().getXmlSignatureMethod());
                signatureHelper.setDigestMethod(getSignatureAlgorithm().getXmlSignatureDigestMethod());
                signatureHelper.setX509Certificate(certificate);

                Node nextSibling = metadataDocument.getDocumentElement().getFirstChild();
                signatureHelper.setNextSibling(nextSibling);

                signatureHelper.signSAMLDocument(metadataDocument, keyName, keyPair, CanonicalizationMethod.EXCLUSIVE);

                descriptor = DocumentUtil.getDocumentAsString(metadataDocument);
            } else {
                descriptor = SAMLMetadataUtil.writeEntityDescriptorType(entityDescriptor);
            }

            return Response.ok(descriptor, MediaType.APPLICATION_XML_TYPE).build();
        } catch (Exception e) {
            logger.warn("Failed to export SAML SP Metadata!", e);
            throw new RuntimeException(e);
        }
    }

    public SignatureAlgorithm getSignatureAlgorithm() {
        String alg = getConfig().getSignatureAlgorithm();
        if (alg != null) {
            SignatureAlgorithm algorithm = SignatureAlgorithm.valueOf(alg);
            if (algorithm != null) return algorithm;
        }
        return SignatureAlgorithm.RSA_SHA256;
    }

    @Override
    public IdentityProviderDataMarshaller getMarshaller() {
        return new SAMLDataMarshaller();
    }

    @Override
    public boolean reloadKeys() {
        if (getConfig().isEnabled() && getConfig().isUseMetadataDescriptorUrl()) {
            String modelKey = PublicKeyStorageUtils.getIdpModelCacheKey(session.getContext().getRealm().getId(), getConfig().getInternalId());
            PublicKeyStorageProvider keyStorage = session.getProvider(PublicKeyStorageProvider.class);
            return keyStorage.reloadKeys(modelKey, new SamlMetadataPublicKeyLoader(session, getConfig().getMetadataDescriptorUrl()));
        }
        return false;
    }

    @Override
    public boolean supportsLongStateParameter() {
        // SAML RelayState parameter has limits of 80 bytes per SAML specification
        return false;
    }

    public SAMLDocumentHolder resolveArtifact(KeycloakSession session, UriInfo uriInfo, RealmModel realm, String relayState, String samlArt) {
        //get the URL of the artifact resolution service provided by the Identity Provider
        String artifactResolutionServiceUrl = getConfig().getArtifactResolutionServiceUrl();
        if (artifactResolutionServiceUrl == null || artifactResolutionServiceUrl.trim().isEmpty()) {
            throw new RuntimeException("Artifact Resolution Service URL is not configured for the Identity Provider.");
        }
        try {
            // create the SAML Request object to resolve an artifact
            ArtifactResolveType artifactResolveRequest = buildArtifactResolveRequest(uriInfo, realm, artifactResolutionServiceUrl, samlArt);
            if (artifactResolveRequest.getDestination() != null) {
                artifactResolutionServiceUrl = artifactResolveRequest.getDestination().toString();
            }

            // convert the SAML Request object to a SAML Document (DOM)
            Document artifactResolveRequestAsDoc = SAML2Request.convert(artifactResolveRequest);

            // convert the SAML Document (DOM) to a SOAP Document (DOM)
            Document soapRequestAsDoc = buildArtifactResolveBinding(session, relayState, realm)
                    .soapBinding(artifactResolveRequestAsDoc).getDocument();

            // execute the SOAP request
            SOAPMessage soapResponse = Soap.createMessage()
                    .addMimeHeader("SOAPAction", "http://www.oasis-open.org/committees/security") // MAY in SOAP binding spec
                    .addToBody(soapRequestAsDoc)
                    .call(artifactResolutionServiceUrl, session);

            // extract the SAML Response (DOM) from the SOAP response
            Document artifactResolveResponseAsDoc = Soap.extractSoapMessage(soapResponse);

            // convert the SAML Response (DOM) to a SAML Response object and return it
            return SAML2Response.getSAML2ObjectFromDocument(artifactResolveResponseAsDoc);
        } catch (SOAPException | ConfigurationException | ProcessingException | ParsingException e) {
            logger.warn("Unable to resolve a SAML artifact to: " + artifactResolutionServiceUrl, e);
            throw new RuntimeException("Unable to resolve a SAML artifact to: " + artifactResolutionServiceUrl, e);
        }
    }

    protected ArtifactResolveType buildArtifactResolveRequest(UriInfo uriInfo, RealmModel realm, String artifactServiceUrl, String artifact, NodeGenerator... extensions) throws ConfigurationException {
        SAML2ArtifactResolveRequestBuilder artifactResolveRequestBuilder = new SAML2ArtifactResolveRequestBuilder()
                .issuer(getEntityId(uriInfo, realm))
                .destination(artifactServiceUrl)
                .artifact(artifact);
        ArtifactResolveType artifactResolveRequest = artifactResolveRequestBuilder.createArtifactResolveRequest();
        for (NodeGenerator extension : extensions) {
            artifactResolveRequestBuilder.addExtension(extension);
        }
        return artifactResolveRequest;
    }

    private JaxrsSAML2BindingBuilderWithConfig buildArtifactResolveBinding(KeycloakSession session, String relayState, RealmModel realm) {
        JaxrsSAML2BindingBuilderWithConfig binding = new JaxrsSAML2BindingBuilderWithConfig(session, config).relayState(relayState);
        if (getConfig().isWantAuthnRequestsSigned()) {
            KeyManager.ActiveRsaKey keys = session.keys().getActiveRsaKey(realm);
            String keyName = getConfig().getXmlSigKeyInfoKeyNameTransformer().getKeyName(keys.getKid(), keys.getCertificate());
            binding.signWith(keyName, keys.getPrivateKey(), keys.getPublicKey(), keys.getCertificate())
                    .signatureAlgorithm(getSignatureAlgorithm())
                    .signDocument();
        }
        return binding;
    }
}
