/*************************************************************************
 *                                                                       *
 *  EJBCA Community: The OpenSource Certificate Authority                *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.ejbca.ui.web.rest.api.resource;

import com.keyfactor.CesecoreException;
import com.keyfactor.ErrorCode;
import com.keyfactor.util.CertTools;
import com.keyfactor.util.EJBTools;
import com.keyfactor.util.StringTools;
import com.keyfactor.util.certificate.CertificateWrapper;
import com.keyfactor.util.crypto.algorithm.AlgorithmTools;
import com.keyfactor.util.keys.KeyTools;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.bouncycastle.cms.CMSException;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.certificates.ca.CACommon;
import org.cesecore.certificates.ca.CADoesntExistsException;
import org.cesecore.certificates.ca.CaSessionLocal;
import org.cesecore.certificates.certificate.CertificateConstants;
import org.cesecore.certificates.certificate.CertificateCreateException;
import org.cesecore.certificates.certificate.CertificateStatus;
import org.cesecore.certificates.certificate.IllegalKeyException;
import org.cesecore.certificates.certificate.certextensions.CertificateExtensionException;
import org.cesecore.certificates.certificateprofile.CertificateProfileDoesNotExistException;
import org.cesecore.certificates.crl.RevocationReasons;
import org.cesecore.certificates.endentity.EndEntityConstants;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.certificates.endentity.ExtendedInformation;
import org.cesecore.util.LogRedactionUtils;
import org.ejbca.core.EjbcaException;
import org.ejbca.core.ejb.dto.CertRevocationDto;
import org.ejbca.core.ejb.ra.NoSuchEndEntityException;
import org.ejbca.core.model.InternalEjbcaResources;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.TokenDownloadType;
import org.ejbca.core.model.approval.ApprovalDataVO;
import org.ejbca.core.model.approval.ApprovalException;
import org.ejbca.core.model.approval.ApprovalRequest;
import org.ejbca.core.model.approval.WaitingForApprovalException;
import org.ejbca.core.model.approval.approvalrequests.KeyRecoveryApprovalRequest;
import org.ejbca.core.model.era.RaApprovalRequestInfo;
import org.ejbca.core.model.era.RaCertificateSearchRequest;
import org.ejbca.core.model.era.RaCertificateSearchResponse;
import org.ejbca.core.model.era.RaMasterApiProxyBeanLocal;
import org.ejbca.core.model.ra.AlreadyRevokedException;
import org.ejbca.core.model.ra.NotFoundException;
import org.ejbca.core.model.ra.RevokeBackDateNotAllowedForProfileException;
import org.ejbca.core.model.ra.raadmin.EndEntityProfileValidationException;
import org.ejbca.core.protocol.rest.EnrollPkcs10CertificateRequest;
import org.ejbca.cvc.exception.ConstructionException;
import org.ejbca.cvc.exception.ParseException;
import org.ejbca.ui.web.rest.api.exception.RestException;
import org.ejbca.ui.web.rest.api.io.request.CertificateRequestRestRequest;
import org.ejbca.ui.web.rest.api.io.request.EnrollCertificateRestRequest;
import org.ejbca.ui.web.rest.api.io.request.FinalizeRestRequest;
import org.ejbca.ui.web.rest.api.io.request.KeyStoreRestRequest;
import org.ejbca.ui.web.rest.api.io.request.SearchCertificatesRestRequest;
import org.ejbca.ui.web.rest.api.io.response.CertificateEnrollmentRestResponse;
import org.ejbca.ui.web.rest.api.io.response.CertificateRestResponse;
import org.ejbca.ui.web.rest.api.io.response.CertificatesRestResponse;
import org.ejbca.ui.web.rest.api.io.response.ExpiringCertificatesRestResponse;
import org.ejbca.ui.web.rest.api.io.response.PaginationRestResponseComponent;
import org.ejbca.ui.web.rest.api.io.response.RevokeStatusRestResponse;
import org.ejbca.ui.web.rest.api.io.response.SearchCertificatesRestResponse;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.keyfactor.ErrorCode.CERTIFICATE_FOR_THIS_KEY_ALREADY_EXISTS;
import static com.keyfactor.ErrorCode.CERTIFICATE_FOR_THIS_KEY_ALREADY_EXISTS_FOR_ANOTHER_USER;
import static com.keyfactor.ErrorCode.CERTIFICATE_WITH_THIS_SUBJECTDN_ALREADY_EXISTS_FOR_ANOTHER_USER;
import static com.keyfactor.ErrorCode.CUSTOM_CERTIFICATE_EXTENSION_ERROR;
import static org.ejbca.ui.web.rest.api.resource.CertificateRestResourceUtil.authorizeSearchCertificatesRestRequestReferences;


/**
 * JAX-RS resource handling certificate-related requests.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.SUPPORTS)
public class CertificateRestResource extends BaseRestResource {

    /**
     * Internal localization of logs and errors
     */
    private static final InternalEjbcaResources intres = InternalEjbcaResources.getInstance();
    private static final Logger log = Logger.getLogger(CertificateRestResource.class);

    @EJB
    private CaSessionLocal caSessionLocal;

    @EJB
    private RaMasterApiProxyBeanLocal raMasterApi;

    /**
     * Enrolls a user generated certificate from csr.
     * @param requestContext HttpServletRequest
     * @param enrollCertificateRestRequest
     * @return Certificate with or without certificate chain (optional). Response Format is
     * DER (default) or PKCS7 in PEM format.
     * @throws RestException
     * @throws AuthorizationDeniedException
     */
    public Response enrollPkcs10Certificate(final HttpServletRequest requestContext,
                                            final EnrollCertificateRestRequest enrollCertificateRestRequest)
            throws RestException, AuthorizationDeniedException {
        String responseFormat;
        try {
            final AuthenticationToken authenticationToken = getAdmin(requestContext, false);
            if (enrollCertificateRestRequest.getResponseFormat() == null) {
                throw new RestException(400, "Invalid input. Incorrect response format");
            } else {
                responseFormat = enrollCertificateRestRequest.getResponseFormat().toUpperCase();
            }
            if (TokenDownloadType.PKCS7.name().equals(responseFormat) || TokenDownloadType.DER.name()
                    .equals(responseFormat)) {
                final byte[] certificateBytes = raMasterApi.createCertificateRest(authenticationToken,
                        EnrollCertificateRestRequest.converter()
                                .toEnrollPkcs10CertificateRequest(enrollCertificateRestRequest));
                final boolean includeChain = enrollCertificateRestRequest.getIncludeChain();
                final Certificate certificate = CertTools.getCertfromByteArray(certificateBytes, Certificate.class);
                final List<Certificate> certificateChain = fetchCaCertificateChain(authenticationToken, includeChain,
                        enrollCertificateRestRequest.getCertificateAuthorityName());
                CertificateRestResponse enrollCertificateRestResponse;

                switch (TokenDownloadType.valueOf(responseFormat)) {
                case PKCS7:
                    byte[] certResponseBytes = CertTools.getPemFromPkcs7(certificateBytes);
                    if (includeChain && !certificateChain.isEmpty()) {
                        byte[] certificateChainBytes = CertTools.getPemFromPkcs7(CertTools.createCertsOnlyCMS(
                                CertTools.convertCertificateChainToX509Chain(certificateChain)));
                        enrollCertificateRestResponse = CertificateRestResponse.converter()
                                .toRestResponse(certResponseBytes, certificateChainBytes, certificate, responseFormat);
                    } else {
                        enrollCertificateRestResponse = CertificateRestResponse.converter()
                                .toRestResponse(certResponseBytes, certificate, responseFormat);
                    }
                    break;
                case DER:
                    enrollCertificateRestResponse = CertificateRestResponse.converter()
                            .toRestResponse(certificateChain, certificate);
                    break;
                default:
                    throw new RestException(400, "Invalid input. Incorrect response format");
                }
                return Response.status(Status.CREATED).entity(enrollCertificateRestResponse).build();
            } else {
                throw new RestException(400, "Invalid input. Response format can only be DER or PKCS7");
            }
        } catch (EjbcaException | CertificateException | EndEntityProfileValidationException | CesecoreException |
                CMSException e) {
            log.info("Exception during enrollPkcs10Certificate: ", LogRedactionUtils.getRedactedThrowable(e));
            throw new RestException(Status.BAD_REQUEST.getStatusCode(),
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }


    private List<Certificate> fetchCaCertificateChain(AuthenticationToken authenticationToken, boolean includeChain, String caName) throws AuthorizationDeniedException, CADoesntExistsException {
        return includeChain ? raMasterApi.getLastCaChain(authenticationToken, caName).stream().map(CertificateWrapper::getCertificate).collect(Collectors.toList()) : null;
    }

    public Response certificateRequest(final HttpServletRequest requestContext, final CertificateRequestRestRequest certificateRequestRestRequest)
            throws RestException, AuthorizationDeniedException, CesecoreException, IOException, SignatureException, NoSuchFieldException {
        try {
            final AuthenticationToken authenticationToken = getAdmin(requestContext, false);
            final EnrollPkcs10CertificateRequest requestData = CertificateRequestRestRequest.converter().toEnrollPkcs10CertificateRequest(certificateRequestRestRequest);
            
            // local call as the CA should normally be present as external CA in RA
            String caName = requestData.getCertificateAuthorityName();
            // seems to be only way to read from cache without instantiating the CA related objects
            if (!caSessionLocal.getCAIdToNameMap().values().contains(caName)) {
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "CA with name " + caName + " doesn't exist.");
            }
            
            final byte[] certificateBytes = raMasterApi.processCertificateRequest(authenticationToken, requestData.getUsername(), requestData.getPassword(),
                    requestData.getCertificateRequest(), CertificateConstants.CERT_REQ_TYPE_PKCS10, null, "CERTIFICATE");

            final X509Certificate certificate = CertTools.getCertfromByteArray(certificateBytes, X509Certificate.class);

            final List<Certificate> certificateChain = fetchCaCertificateChain(
                    authenticationToken,
                    certificateRequestRestRequest.getIncludeChain(),
                    certificateRequestRestRequest.getCertificateAuthorityName());
            final CertificateEnrollmentRestResponse enrollCertificateRestResponse = CertificateEnrollmentRestResponse.converter().toRestResponse(
                    certificate,
                    certificateChain
            );
            return Response.status(Status.CREATED).entity(enrollCertificateRestResponse).build();
        } catch (InvalidKeyException | InvalidKeySpecException | NoSuchAlgorithmException | NoSuchProviderException |
                 CertificateException | EjbcaException | ParseException e) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (ConstructionException e) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "An incorrect certificate request has been passed.");
        } catch (IllegalKeyException e) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "An incorrect certificate key has been passed contain rather too large key or having any other issue.");
        } catch (CertificateExtensionException e) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Failed to generate certificate due to an issue with certificate extensions.");
        } catch (IOException e) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Failed to generate certificate due to malformed CSR.");
        } catch (CertificateCreateException e) {
            throw makeCertificateCreationException(e);
        }
    }

    private RestException makeCertificateCreationException(CertificateCreateException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (CUSTOM_CERTIFICATE_EXTENSION_ERROR.equals(errorCode)) {
            return new RestException(Status.BAD_REQUEST.getStatusCode(), "Failed to generate certificate due to an issue with certificate extensions.");
        } else {
            return makeWhenSomeOfTheAttributesDoNotExist(errorCode);
        }
    }

    private RestException makeWhenSomeOfTheAttributesDoNotExist(ErrorCode errorCode) {

        boolean isSubjectExist = CERTIFICATE_WITH_THIS_SUBJECTDN_ALREADY_EXISTS_FOR_ANOTHER_USER.equals(errorCode);
        boolean isSubjectForTheKeyExist = CERTIFICATE_FOR_THIS_KEY_ALREADY_EXISTS_FOR_ANOTHER_USER.equals(errorCode);
        boolean isCertificateForTheKeyExist = CERTIFICATE_FOR_THIS_KEY_ALREADY_EXISTS.equals(errorCode);

        if (isSubjectExist || isSubjectForTheKeyExist || isCertificateForTheKeyExist) {
            return new RestException(Status.CONFLICT.getStatusCode(),
                    "Failed to generate certificate due to the new certificate conflicting with an existing one.");
        } else {
            return new RestException(Status.BAD_REQUEST.getStatusCode(), "Certificate could not be generated.");
        }
    }

    public Response enrollKeystore(final HttpServletRequest requestContext, final KeyStoreRestRequest keyStoreRestRequest)
            throws AuthorizationDeniedException, EjbcaException, KeyStoreException, NoSuchProviderException,
            NoSuchAlgorithmException, CertificateException, IOException, RestException, CADoesntExistsException,
            UnrecoverableKeyException {
        final AuthenticationToken admin = getAdmin(requestContext, false);
        EndEntityInformation endEntityInformation = raMasterApi.searchUser(admin, keyStoreRestRequest.getUsername());
        if (endEntityInformation == null) {
            throw new NotFoundException("The end entity '" + keyStoreRestRequest.getUsername() + "' does not exist");
        }
        if (!AlgorithmTools.getAvailableKeyAlgorithms().contains(keyStoreRestRequest.getKeyAlg())) {
            throw new RestException(422, "Unsupported key algorithm '" + keyStoreRestRequest.getKeyAlg() + "'");
        }
        try {
            KeyTools.checkValidKeyLength(keyStoreRestRequest.getKeySpec());
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new RestException(422, e.getMessage());
        }
        String keyStorePassword = keyStoreRestRequest.getPassword();
        endEntityInformation.setPassword(keyStorePassword);
        if (endEntityInformation.getExtendedInformation() == null) {
            endEntityInformation.setExtendedInformation(new ExtendedInformation());
        }
        endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmType(keyStoreRestRequest.getKeyAlg());
        endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmSubType(keyStoreRestRequest.getKeySpec());
        final int tokenType = endEntityInformation.getTokenType();
        if (!(tokenType == SecConst.TOKEN_SOFT_P12 || tokenType == SecConst.TOKEN_SOFT_JKS || tokenType == SecConst.TOKEN_SOFT_BCFKS)) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Unsupported token type. Must be one of 'PKCS12', 'BCFKS' or 'JKS'.");
        }
        final byte[] keyStoreBytes = raMasterApi.generateKeyStore(admin, endEntityInformation);
        CACommon caCommon = caSessionLocal.getCA(admin, endEntityInformation.getCAId());
        List<Certificate> caCertificateChain = fetchCaCertificateChain(admin, true, caCommon.getName());
        String keyStoreType = SecConst.getKeyStoreTypeAsString(tokenType);

        X509Certificate certificate = getFirstCertificate(keyStoreBytes, keyStoreType, keyStorePassword);

        CertificateEnrollmentRestResponse response = CertificateEnrollmentRestResponse.converter()
                .toRestResponse(keyStoreBytes,
                        CertTools.getSerialNumberAsString(certificate),
                        keyStoreType,
                        caCertificateChain);
        return Response.status(Status.CREATED).entity(response).build();
    }

    private static X509Certificate getFirstCertificate(byte[] keyStoreBytes, String keyStoreType, String keyStorePassword)
            throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        return CertTools.extractEndEntityCertificateFromKeyStore(keyStoreBytes, keyStoreType, keyStorePassword).get(0);
    }

    public Response revocationStatus(final HttpServletRequest requestContext, final String issuerDn, final String serialNumber)
            throws AuthorizationDeniedException, RestException, CADoesntExistsException, NotFoundException {
        final AuthenticationToken admin = getAdmin(requestContext, false);
        final BigInteger serialNr;
        final CertificateStatus status;
        try {
            serialNr = StringTools.getBigIntegerFromHexString(serialNumber);
            status = raMasterApi.getCertificateStatus(admin, issuerDn, serialNr);
        } catch (NumberFormatException e) {
            throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), "Invalid serial number format. Should be "
                    + "HEX encoded (optionally with '0x' prefix) e.g. '0x10782a83eef170d4'");
        } catch (CADoesntExistsException e) {
            // Returning an ID which doesn't exist makes no sense, replace with SDN.
            throw new CADoesntExistsException("CA '" + issuerDn + "' does not exist.");
        }
        if (status == null || status.equals(CertificateStatus.NOT_AVAILABLE)) {
            throw new NotFoundException("Certificate with serial number '" + serialNumber + "' and issuer DN '" + issuerDn + "' was not found");
        }
        return Response.ok(RevokeStatusRestResponse.converter().toRestResponse(status, issuerDn, serialNumber)).build();
    }

    /**
     * Revokes the specified certificate
     *
     * @param requestContext HttpServletRequest
     * @param issuerDN       of the certificate to revoke
     * @param serialNumber   HEX encoded SN with or without 0x prefix
     * @param reason         revocation reason (optional). Must be valid RFC5280 reason:
     *                       NOT_REVOKED, UNSPECIFIED , KEY_COMPROMISE,
     *                       CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED, CESSATION_OF_OPERATION,
     *                       CERTIFICATE_HOLD, REMOVE_FROM_CRL, PRIVILEGES_WITHDRAWN, AA_COMPROMISE
     * @param date           revocation date (optional). Must be valid ISO8601 date string
     * @param invalidityDate invalidity date (optional). Must be valid ISO8601 date string
     * @return JSON representation of serialNr, issuerDn, revocation status, date and optional message.
     * @throws CertificateProfileDoesNotExistException 
     * @throws IllegalArgumentException 
     */
    public Response revokeCertificate(
            final HttpServletRequest requestContext,
            final String issuerDN,
            final String serialNumber,
            final String reason,
            final String date,
            final String invalidityDate)
            throws AuthorizationDeniedException, RestException, ApprovalException, RevokeBackDateNotAllowedForProfileException,
            CADoesntExistsException, AlreadyRevokedException, NoSuchEndEntityException, WaitingForApprovalException, IllegalArgumentException, CertificateProfileDoesNotExistException {
        final AuthenticationToken admin = getAdmin(requestContext, false);
        RevocationReasons reasons = RevocationReasons.getFromCliValue(reason);
        final BigInteger serialNr;
        try {
            serialNr = StringTools.getBigIntegerFromHexString(serialNumber);
        } catch (NumberFormatException e) {
            throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), "Invalid serial number format. Should be "
                    + "HEX encoded (optionally with '0x' prefix) e.g. '0x10782a83eef170d4'");
        }
        final int revocationReason;
        if (reasons != null) {
            revocationReason = reasons.getDatabaseValue();
        } else {
            final CertificateStatus existingStatus = raMasterApi.getCertificateStatus(admin, issuerDN, serialNr);
            if (!existingStatus.isRevoked()) {
                throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), "Invalid revocation reason.");
            } else if (invalidityDate != null) {
                revocationReason = existingStatus.revocationReason;
            } else {
                throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), "Invalidity date or revocation reason missing.");  
            }
        }
        final Date validatedInvalidityDate = getValidatedDate(invalidityDate);
        CertRevocationDto certRevocationMetadata = new CertRevocationDto(issuerDN, serialNumber); 
        certRevocationMetadata.setInvalidityDate(validatedInvalidityDate);
        certRevocationMetadata.setRevocationDate(getValidatedDate(date));
        certRevocationMetadata.setReason(revocationReason);
        certRevocationMetadata.setCheckDate(true);
        raMasterApi.revokeCertWithMetadata(admin, certRevocationMetadata);
        final CertificateStatus certificateStatus = raMasterApi.getCertificateStatus(admin, issuerDN, serialNr);
        final Date revocationDate = certificateStatus.isRevoked() ? certificateStatus.revocationDate : null;

        final RevokeStatusRestResponse result = RevokeStatusRestResponse.builder().
                serialNumber(serialNumber).
                issuerDn(issuerDN).
                revocationDate(revocationDate).
                revoked(certificateStatus.isRevoked()).
                revocationReason(reason).
                invalidityDate(validatedInvalidityDate).
                message("Successfully revoked").
                build();
        return Response.ok(result).build();
    }

    private Date getValidatedDate(String sDate) throws RestException {
        Date date = null;
        if (sDate != null) {
            try {
                date = DatatypeConverter.parseDateTime(sDate).getTime();
            } catch (IllegalArgumentException e) {
                throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), intres.getLocalizedMessage("ra.bad.date.generic", sDate));
            }
            if (date.after(new Date())) {
                throw new RestException(Response.Status.BAD_REQUEST.getStatusCode(), "Date in the future: '" + sDate + "'.");
            }
        }
        return date;
    }

    public Response getCertificatesAboutToExpire(final HttpServletRequest requestContext,
                                                 final long days,
                                                 final int offset,
                                                 final int maxNumberOfResults)
            throws AuthorizationDeniedException, CertificateEncodingException, RestException {
        final AuthenticationToken admin = getAdmin(requestContext, true);
        final int count = raMasterApi.getCountOfCertificatesByExpirationTime(admin, days);
        final Collection<Certificate> expiringCertificates = EJBTools
                .unwrapCertCollection(raMasterApi.getCertificatesByExpirationTime(admin, days, maxNumberOfResults, offset));
        final int numberOfResults = expiringCertificates.size();
        final int processedResults = offset + numberOfResults;
        PaginationRestResponseComponent paginationRestResponseComponent = PaginationRestResponseComponent.builder().setMoreResults(count > processedResults)
                .setNextOffset(offset + numberOfResults)
                .setNumberOfResults(count - processedResults)
                .build();
        CertificatesRestResponse certificatesRestResponse = new CertificatesRestResponse(
                CertificatesRestResponse.converter().toRestResponses(new ArrayList<>(expiringCertificates)));
        ExpiringCertificatesRestResponse response = new ExpiringCertificatesRestResponse(paginationRestResponseComponent, certificatesRestResponse);
        return Response.ok(response).build();
    }

    public Response finalizeEnrollment(
            final HttpServletRequest requestContext,
            final int requestId,
            final FinalizeRestRequest request)
            throws AuthorizationDeniedException, RestException, EjbcaException, WaitingForApprovalException,
            KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        final AuthenticationToken admin = getAdmin(requestContext, false);
        final RaApprovalRequestInfo approvalRequestInfo = raMasterApi.getApprovalRequest(admin, requestId);
        final String password = request.getPassword();
        final String responseFormat = request.getResponseFormat();
        final String keyAlg = request.getKeyAlg();
        final String keySpec = request.getKeySpec();
        if (approvalRequestInfo == null) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Could not find request with Id '" + requestId + "'");
        }
        if (TokenDownloadType.getIdFromName(responseFormat) == null) {
            throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Invalid parameter: response_format");
        }

        final ApprovalRequest approvalRequest = approvalRequestInfo.getApprovalData().getApprovalRequest();
        String requestUsername = approvalRequestInfo.getEditableData().getUsername();
        EndEntityInformation endEntityInformation;
        final int requestStatus = approvalRequestInfo.getStatus();
        switch (requestStatus) {
            case ApprovalDataVO.STATUS_WAITINGFORAPPROVAL:
                throw new WaitingForApprovalException("Request with Id '" + requestId + "' is still waiting for approval", requestId);
            case ApprovalDataVO.STATUS_REJECTED:
            case ApprovalDataVO.STATUS_EXECUTIONDENIED:
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Request with Id '" + requestId + "' has been rejected");
            case ApprovalDataVO.STATUS_EXPIRED:
            case ApprovalDataVO.STATUS_EXPIREDANDNOTIFIED:
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Request with Id '" + requestId + "' has expired");
            case ApprovalDataVO.STATUS_EXECUTIONFAILED:
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Request with Id '" + requestId + "' could not be executed");
            case ApprovalDataVO.STATUS_APPROVED:
            case ApprovalDataVO.STATUS_EXECUTED:
                if (approvalRequest instanceof KeyRecoveryApprovalRequest) {
                    KeyRecoveryApprovalRequest keyRecoveryApprovalRequest = (KeyRecoveryApprovalRequest) approvalRequest;
                    requestUsername = keyRecoveryApprovalRequest.getUsername();
                }
                endEntityInformation = raMasterApi.searchUser(admin, requestUsername);
                if (endEntityInformation == null) {
                    log.error("Could not find endEntity for the username '" + requestUsername + "'");
                    throw new NotFoundException("The end entity '" + requestUsername + "' does not exist");
                } else if (endEntityInformation.getStatus() == EndEntityConstants.STATUS_GENERATED) {
                    throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Enrollment with Id '" + requestId + "' has already been finalized");
                }
                break;
            default:
                throw new IllegalStateException("The status of request with Id '" + requestId + "' is unknown");
        }

        final CertificateRestResponse response;
        endEntityInformation.setPassword(password);
        // Initial request was a CSR
        if (endEntityInformation.getTokenType() == EndEntityConstants.TOKEN_USERGEN) {
            if (!(responseFormat.equals(TokenDownloadType.DER.name()) || responseFormat.equals(TokenDownloadType.PEM.name()))) {
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Invalid response format. Cannot create keystore for certificate request "
                        + " with user generated keys");
            }
            byte[] certificateBytes = raMasterApi.createCertificate(admin, endEntityInformation); // X509Certificate
            Certificate certificate = CertTools.getCertfromByteArray(certificateBytes, Certificate.class);
            if (responseFormat.equals(TokenDownloadType.PEM.name())) {
                byte[] pemBytes = CertTools.getPemFromCertificateChain(Collections.singletonList(certificate));
                response = CertificateRestResponse.builder().setCertificate(pemBytes).
                        setSerialNumber(CertTools.getSerialNumberAsString(certificate)).setResponseFormat("PEM").build();
            } else {
                // DER encoding
                response = CertificateRestResponse.converter().toRestResponse(certificate);
            }
        } else {
            // Initial request was server generated key store
            byte[] keyStoreBytes;
            if (responseFormat.equals(TokenDownloadType.JKS.name())) {
                endEntityInformation.setTokenType(EndEntityConstants.TOKEN_SOFT_JKS);
            } else if (responseFormat.equals(TokenDownloadType.P12.name())) {
                endEntityInformation.setTokenType(EndEntityConstants.TOKEN_SOFT_P12);
            } else if (responseFormat.equals(TokenDownloadType.PEM.name())) {
                endEntityInformation.setTokenType(EndEntityConstants.TOKEN_SOFT_PEM);
            } else if (responseFormat.equals(TokenDownloadType.BCFKS.name())) {
                endEntityInformation.setTokenType((EndEntityConstants.TOKEN_SOFT_BCFKS));
            } else {
                throw new RestException(Status.BAD_REQUEST.getStatusCode(), "Invalid response format. Must be 'JKS', 'P12', 'BCFKS', or 'PEM'");
            }
            if (StringUtils.isNotEmpty(keyAlg) && StringUtils.isNoneEmpty(keySpec)) {
                endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmType(keyAlg);
                endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmSubType(keySpec);
            }
            keyStoreBytes = raMasterApi.generateKeyStore(admin, endEntityInformation);
            if (responseFormat.equals(TokenDownloadType.PEM.name())) {
                Certificate certificate = CertTools.getCertfromByteArray(keyStoreBytes, Certificate.class);
                response = CertificateRestResponse.builder().setCertificate(keyStoreBytes).
                        setSerialNumber(CertTools.getSerialNumberAsString(certificate)).setResponseFormat("PEM").build();
            } else if (responseFormat.equals(TokenDownloadType.DER.name())) {
                final Certificate certificate = CertTools.getCertfromByteArray(keyStoreBytes, Certificate.class);
                response = CertificateRestResponse.converter().toRestResponse(certificate);
            } else {
                response = CertificateRestResponse.converter().toRestResponse(keyStoreBytes, endEntityInformation.getTokenType());
            }
        }
        return Response.status(Status.CREATED).entity(response).build();
    }

    public Response searchCertificates(
            final HttpServletRequest requestContext,
            @Valid final SearchCertificatesRestRequest searchCertificatesRestRequest
    ) throws AuthorizationDeniedException, RestException, CertificateEncodingException {
        final AuthenticationToken authenticationToken = getAdmin(requestContext, true);
        
        Map<Integer, String> availableEndEntityProfiles = 
                CertificateRestResourceUtil.loadAuthorizedEndEntityProfiles(authenticationToken, raMasterApi);
        Map<Integer, String> availableCertificateProfiles = 
                CertificateRestResourceUtil.loadAuthorizedCertificateProfiles(authenticationToken, raMasterApi);
        Map<Integer, String> availableCAs = 
                CertificateRestResourceUtil.loadAuthorizedCAs(authenticationToken, raMasterApi);
        authorizeSearchCertificatesRestRequestReferences(
                authenticationToken, raMasterApi, searchCertificatesRestRequest,
                availableEndEntityProfiles, availableCertificateProfiles, availableCAs);
        final SearchCertificatesRestResponse searchCertificatesRestResponse = 
                searchCertificates(authenticationToken, searchCertificatesRestRequest, availableEndEntityProfiles, availableCertificateProfiles);
        return Response.ok(searchCertificatesRestResponse).build();
    }

    /**
     * Searches for certificates within given criteria.
     *
     * @param authenticationToken           authentication token to use.
     * @param searchCertificatesRestRequest search criteria.
     * @return Search results.
     * @throws RestException                In case of malformed criteria.
     * @throws CertificateEncodingException In case of failure in certificate reading.
     */
    private SearchCertificatesRestResponse searchCertificates(
            final AuthenticationToken authenticationToken,
            final SearchCertificatesRestRequest searchCertificatesRestRequest,
            Map<Integer, String> availableEndEntityProfiles,
            Map<Integer, String> availableCertificateProfiles
    ) throws RestException, CertificateEncodingException {
        final RaCertificateSearchRequest raCertificateSearchRequest = SearchCertificatesRestRequest.converter().toEntity(searchCertificatesRestRequest);
        final RaCertificateSearchResponse raCertificateSearchResponse = raMasterApi.searchForCertificates(authenticationToken, raCertificateSearchRequest);
        return SearchCertificatesRestResponse.converter().toRestResponse(raCertificateSearchResponse, availableEndEntityProfiles, availableCertificateProfiles);
    }
}
