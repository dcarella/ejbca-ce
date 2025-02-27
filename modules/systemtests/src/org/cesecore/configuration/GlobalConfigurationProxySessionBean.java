/*************************************************************************
 *                                                                       *
 *  CESeCore: CE Security Core                                           *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.cesecore.configuration;

import java.util.Properties;
import java.util.Set;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.config.CesecoreConfiguration;

@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class GlobalConfigurationProxySessionBean implements GlobalConfigurationProxySessionRemote {

    @EJB
    private GlobalConfigurationSessionLocal globalConfigurationSession;
    
    @PersistenceContext(unitName = CesecoreConfiguration.PERSISTENCE_UNIT)
    private EntityManager entityManager;
    
    @Override
    public ConfigurationBase getCachedConfiguration(String configID) {
        final ConfigurationBase cb = globalConfigurationSession.getCachedConfiguration(configID);
        if (cb == null) {
            throw new IllegalStateException("Missing configuration for ID " + configID);
        }
        return cb;
    }

    @Override
    public void flushConfigurationCache(String configID) {
        globalConfigurationSession.flushConfigurationCache(configID);
    }

    @Override
    public Properties getAllProperties(AuthenticationToken admin, String configID) throws AuthorizationDeniedException {
        return globalConfigurationSession.getAllProperties(admin, configID);
    }

    @Override
    public void saveConfiguration(AuthenticationToken admin, ConfigurationBase conf) throws AuthorizationDeniedException {
        globalConfigurationSession.saveConfiguration(admin, conf);
    }
    
    @Override
    public void saveConfigurationWithRootAccessCheck(AuthenticationToken admin, ConfigurationBase conf) throws AuthorizationDeniedException {
        globalConfigurationSession.saveConfigurationWithRootAccessCheck(admin, conf);
    }

    @Override
    public GlobalConfigurationData findByConfigurationId(String configurationId) {
        return globalConfigurationSession.findByConfigurationId(configurationId);
    }

    @Override
    public Set<String> getIds() {
        return globalConfigurationSession.getIds();
    }

    @Override
    public void removeConfiguration(AuthenticationToken authenticationToken, String configurationId) throws AuthorizationDeniedException {
        globalConfigurationSession.removeConfiguration(authenticationToken, configurationId);
    }
    
    @Override
    public void addConfiguration(ConfigurationBase configurationBase) {
        final GlobalConfigurationData globalConfigurationData = new GlobalConfigurationData(configurationBase.getConfigurationId(), configurationBase);
        entityManager.persist(globalConfigurationData);
    }

}
