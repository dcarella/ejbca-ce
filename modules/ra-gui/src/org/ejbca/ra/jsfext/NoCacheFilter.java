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
package org.ejbca.ra.jsfext;

import java.io.IOException;

import jakarta.faces.application.ResourceHandler;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

/**
 * Filter that prevents a browser to cache everything except resources like java script, css and images.
 * It's part of JSF best practices.
 * 
 * @version $Id$
 */
@WebFilter(filterName = "NoCacheFilter", urlPatterns = {"/*"})
public class NoCacheFilter implements Filter {

    private static final Logger log = Logger.getLogger(NoCacheFilter.class);

    @Override
    public void destroy() {
    }

    /**
     * Returns true for the request URIs that should be cached, false otherwise
     * @param request
     * @return
     */
    private boolean cacheURI(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return requestUri.startsWith(request.getContextPath() + ResourceHandler.RESOURCE_IDENTIFIER)
                || requestUri.startsWith(request.getContextPath() + "/css") || requestUri.startsWith(request.getContextPath() + "/js")
                || requestUri.startsWith(request.getContextPath() + "/img");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (!cacheURI(request)) {
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1.
            response.setHeader("Pragma", "no-cache"); // HTTP 1.0.
            response.setDateHeader("Expires", 0); // Proxies.
        }

        chain.doFilter(req, res);
    }

    @Override
    public void init(FilterConfig arg0) throws ServletException {
        log.debug("NoCacheFilter: init");
    }

}
