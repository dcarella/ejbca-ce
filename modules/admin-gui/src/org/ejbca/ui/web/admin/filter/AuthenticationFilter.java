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
package org.ejbca.ui.web.admin.filter;

import java.io.IOException;
import java.security.cert.X509Certificate;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.cesecore.authentication.AuthenticationFailedException;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.control.CryptoTokenRules;
import org.cesecore.authorization.control.StandardRules;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.ui.web.admin.bean.SessionBeans;
import org.ejbca.ui.web.jsf.configuration.EjbcaWebBean;
import org.ejbca.util.HttpTools;

import com.keyfactor.util.CertTools;

/**
 * Authentication filter.
 *
 */
public class AuthenticationFilter implements Filter {

    private static final Logger log = Logger.getLogger(AuthenticationFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        final HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        final HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        boolean hasAuthenticationError = false;
        String authenticationErrorMessage = "";
        String authenticationErrorPublicMessage = "Authorization Denied";
        final String accessResourcesByRequestURI = getAccessResourcesByRequestURI(httpServletRequest.getRequestURI());
        if (log.isTraceEnabled()) {
            log.trace("Access rule for '" + httpServletRequest.getRequestURI() + "' is " + accessResourcesByRequestURI + " (if null, then the page should do authorization checks by itself)");
        }
        try {
            // Check whether AuthenticationFilter has binding
            if (StringUtils.isNotBlank(accessResourcesByRequestURI)) {
                final EjbcaWebBean ejbcaWebBean = SessionBeans.getEjbcaWebBean(httpServletRequest);
                final String oauthBearerToken = HttpTools.extractBearerAuthorization(httpServletRequest.getHeader(HttpTools.AUTHORIZATION_HEADER));
                try {
                    // creates admin token and does access rules check
                    ejbcaWebBean.initialize(httpServletRequest, accessResourcesByRequestURI);
                } catch (AuthenticationFailedException e) {
                    if (oauthBearerToken != null) {
                        hasAuthenticationError = true;
                        authenticationErrorMessage = e.getMessage();
                        authenticationErrorPublicMessage = authenticationErrorMessage;
                    } else {
                        throw e;
                    }
                } catch (AuthorizationDeniedException e) {
                    hasAuthenticationError = true;
                    authenticationErrorMessage = e.getMessage();
                    authenticationErrorPublicMessage = "You are not authorized to view this page.";
                }
                final X509Certificate x509Certificate = ejbcaWebBean.getClientX509Certificate(httpServletRequest);
                final AuthenticationToken admin = ejbcaWebBean.getAdminObject();
                if (admin != null && !hasAuthenticationError) {
                    // let through only if admin token exists AND no auth error
                    httpServletRequest.setAttribute("authenticationtoken", admin);
                    filterChain.doFilter(servletRequest, servletResponse);
                } else {
                    hasAuthenticationError = true;
                    if (x509Certificate != null || oauthBearerToken != null) {
                        authenticationErrorMessage = oauthBearerToken != null ? "Authentication failed using OAuth Bearer Token"
                                : "Authorization denied for certificate: " + CertTools.getSubjectDN(x509Certificate);
                        authenticationErrorPublicMessage = authenticationErrorMessage;
                    } else {
                        authenticationErrorMessage = "No client certificate sent.";
                        authenticationErrorPublicMessage = "This operation requires certificate authentication!";
                    }
                }
            }
            // No binding defined, pass the request along the filter chain
            else {
                filterChain.doFilter(servletRequest, servletResponse);
            }
        }
        catch (Exception ex) {
            log.info("Could not initialize for client " + httpServletRequest.getRemoteAddr());
            log.debug("Client initialization failed", ex);
            throw new ServletException("Cannot process the request.");
        }
        if (hasAuthenticationError) {
            log.info("Client " + httpServletRequest.getRemoteAddr() + " was denied. " + authenticationErrorMessage);
            httpServletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, authenticationErrorPublicMessage);
        }
    }

    @Override
    public void destroy() {
    }

    private String getAccessResourcesByRequestURI(final String requestURI) {
        if (StringUtils.isNotBlank(requestURI)) {
            if (requestURI.endsWith("/ca/certreq")) {
                return AccessRulesConstants.REGULAR_CREATEENDENTITY;
            }
            else if(requestURI.endsWith("/ca/editcas/cacertreq")) {
                return StandardRules.CAEDIT.resource();
            }
            else if(requestURI.endsWith("/ca/cacert")) {
                return AccessRulesConstants.REGULAR_VIEWCERTIFICATE;
            }
            else if(requestURI.endsWith("/ca/exportca")) {
                // This line is usually not reached, because the exportca servlet is accessed via an internal redirect from EditCAsMBean.
                // So the authentication has to be done in CAExportServlet also.
                return StandardRules.ROLE_ROOT.resource();
            }
            else if(requestURI.endsWith("/ca/endentitycert")) {
                return AccessRulesConstants.REGULAR_VIEWCERTIFICATE;
            }
            else if(requestURI.endsWith("/ca/getcrl/getcrl")) {
                return AccessRulesConstants.REGULAR_VIEWCERTIFICATE;
            }
            else if(requestURI.endsWith("/profilesexport")) {
                return AccessRulesConstants.ROLE_ADMINISTRATOR;
            }
            else if(requestURI.endsWith("/cryptotoken/cryptoTokenDownloads")) {
                return CryptoTokenRules.VIEW.resource();
            }
        }
        return null;
    }
}
