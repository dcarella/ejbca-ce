/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.ejbca.core.protocool.acme;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.ejbca.acme.AcmeAuthorizationData;
import org.ejbca.core.protocol.acme.AcmeAuthorization;
import org.ejbca.core.protocol.acme.AcmeAuthorizationDataSessionLocal;
import org.ejbca.core.protocol.acme.AcmeAuthorizationDataSessionProxyRemote;

/**
 *
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.SUPPORTS)
public class AcmeAuthorizationDataSessionProxyBean implements AcmeAuthorizationDataSessionProxyRemote {

    @EJB
    private AcmeAuthorizationDataSessionLocal acmeAuthorizationDataSessionLocal;

    @Override
    public String createOrUpdate(AcmeAuthorization acmeAuthorization) {
        return acmeAuthorizationDataSessionLocal.createOrUpdate(acmeAuthorization);
    }

    @Override
    public void remove(String authorizationId) {
        acmeAuthorizationDataSessionLocal.remove(authorizationId);
    }

    @Override
    public void persistAcmeAuthorizationData(AcmeAuthorizationData data) {
        acmeAuthorizationDataSessionLocal.persistAcmeAuthorizationData(data);
    }

    @Override
    public AcmeAuthorizationData find(String authorizationId) {
        return acmeAuthorizationDataSessionLocal.find(authorizationId);
    }
    
}
