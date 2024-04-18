/*
*  Copyright (c) 2005-2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.registry.indexing;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.registry.core.ActionConstants;
import org.wso2.carbon.registry.core.LogEntry;
import org.wso2.carbon.registry.core.internal.RegistryCoreServiceComponent;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.indexing.internal.IndexingServiceComponent;
import org.wso2.carbon.registry.indexing.utils.IndexingUtils;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.WaitBeforeShutdownObserver;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.*;

/**
 * run() method of this class checks the resources which have been changed since last index time and
 * submits them for indexing. This uses registry logs to detect resources that need to be indexed.
 * An instance of this class should be executed with a ScheduledExecutorService so that run() method
 * runs periodically.
 */
public class ResourceSubmitter implements Runnable {

    private static Log log = LogFactory.getLog(ResourceSubmitter.class);

    private IndexingManager indexingManager;
    private boolean taskComplete = false;
    private boolean isShutdown = false;

    protected ResourceSubmitter(IndexingManager indexingManager) {
        this.indexingManager = indexingManager;
        Utils.setWaitBeforeShutdownObserver(new WaitBeforeShutdownObserver() {
            public void startingShutdown() {
                isShutdown = true;
            }

            public boolean isTaskComplete() {
                return taskComplete;
            }
        });
    }

    /**
     * This method checks the resources which have been changed since last index time and
     * submits them for indexing. This uses registry logs to detect resources that need to be
     * indexed. This method handles interrupts properly so that it is compatible with the
     * Executor framework
     */
    @SuppressWarnings({ "REC_CATCH_EXCEPTION" })
    public void run() {

        try {
            PrivilegedCarbonContext.startTenantFlow();
            try {
                Date currentTime = indexingManager.getLastAccessTime(MultitenantConstants.SUPER_TENANT_ID);
                indexingManager.setLastAccessTime(MultitenantConstants.SUPER_TENANT_ID,
                        submitResource(currentTime, MultitenantConstants.SUPER_TENANT_ID,
                                MultitenantConstants.SUPER_TENANT_DOMAIN_NAME));
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
            Tenant[] allTenants = RegistryCoreServiceComponent.getRealmService().getTenantManager().getAllTenants();
            for (Tenant tenant : allTenants) {
                PrivilegedCarbonContext.startTenantFlow();
                try {
                    int tenantId = tenant.getId();
                    Date currentTime = indexingManager.getLastAccessTime(tenantId);
                    indexingManager.setLastAccessTime(tenantId, submitResource(currentTime,
                            tenantId, tenant.getDomain()));
                } finally {
                    PrivilegedCarbonContext.endTenantFlow();
                }
            }
        } catch (UserStoreException ignored) {

        }
    }

    private Date submitResource(Date currentTime, int tenantId, String tenantDomain) {
        if (!IndexingServiceComponent.canIndexTenant(tenantId)) {
            return currentTime;
        }
        if (isShutdown || Thread.currentThread().isInterrupted()) {
            // interruption can happen due to shutdown or some other reason.
            taskComplete = true;
            return currentTime; // To be compatible with shutdownNow() method on the executor service
        }
        PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        carbonContext.setTenantDomain(tenantDomain);
        carbonContext.setTenantId(tenantId);
        try {
            UserRegistry registry = indexingManager.getRegistry(tenantId);
            if (registry == null) {
                log.warn("Unable to submit resource for tenant " + tenantId + ". Unable to get registry instance");
                return currentTime;
            }
            String lastAccessTimeLocation = indexingManager.getLastAccessTimeLocation();

            LogEntry[] entries = registry.getLogs(null, LogEntry.ALL, null, indexingManager.getLastAccessTime(tenantId),
                    new Date(), true);

            if (entries.length > 0) {
                Date temp = entries[0].getDate();
                if (currentTime == null || currentTime.before(temp)) {
                    currentTime = temp;
                }

                ArrayList<LogEntry> logEntryList = removeLogEntriesWithDuplicatePaths(entries);

                for (LogEntry logEntry : logEntryList){
                    String path = logEntry.getResourcePath();
                    try {
                        if (path.equals(lastAccessTimeLocation)) {
                            continue;
                        }
                        if (logEntry.getAction() == (LogEntry.DELETE_RESOURCE)) {
                            indexingManager.deleteFromIndex(logEntry.getResourcePath(), tenantId);
                            log.info(">>>>>>>> Resource Deleted: [TenantID: " + tenantId + "] Resource at " + path +
                                    " will be deleted from Indexing Server @ " + System.currentTimeMillis());
                        } else if (IndexingUtils.isAuthorized(registry, path, ActionConstants.GET) && registry
                                        .resourceExists(path)) {
                            if (logEntry.getAction() == LogEntry.UPDATE) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource Updated: [TenantID: " + tenantId + "] " +
                                        "Resource at " + path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.DELETE_COMMENT) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource comment deleted: [TenantID: " + tenantId + "] Resource at" +
                                        " " + path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.REMOVE_ASSOCIATION) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource association removed:  [TenantID: " + tenantId +
                                        "] Resource at " + path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.REMOVE_TAG) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource tag removed: [TenantID: " + tenantId + "] Resource at " +
                                        path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.ADD) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource Inserted:  [TenantID: " + tenantId + "] Resource at " +
                                        path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.TAG) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource tag added: [TenantID: " + tenantId + "] Resource at " +
                                        path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.COMMENT) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource comment added: [TenantID: " + tenantId + "] Resource at " +
                                        path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == LogEntry.ADD_ASSOCIATION) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource association added: [TenantID: " + tenantId +
                                        "] Resource at " + path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            } else if (logEntry.getAction() == (LogEntry.MOVE)) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                indexingManager.deleteFromIndex(logEntry.getActionData(), tenantId);
                                log.info(">>>>>>>> Resource Moved: [TenantID: " + tenantId + "] Resource at " + path +
                                        " has been submitted to the Indexing Server @ " + System.currentTimeMillis());
                            } else if (logEntry.getAction() == (LogEntry.COPY)) {
                                path = logEntry.getActionData();
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource Copied : [TenantID: " + tenantId + "] Resource at " + path +
                                        " has been submitted to the Indexing Server @ " + System.currentTimeMillis());
                            } else if (logEntry.getAction() == (LogEntry.RENAME)) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource Renamed : [TenantID: " + tenantId + "] Resource at " + path +
                                        " has been submitted to the Indexing Server @ " + System.currentTimeMillis());
                            } else if (logEntry.getAction() == (LogEntry.RESTORE)) {
                                indexingManager.submitFileForIndexing(tenantId, tenantDomain, path, null);
                                log.info(">>>>>>>> Resource Res+tore : [TenantID: " + tenantId + "] Resource at " +
                                        path + " has been submitted to the Indexing Server @ " +
                                        System.currentTimeMillis());
                            }
                        }
                    } catch (Exception e) { // to ease debugging
                        log.warn("An error occurred while submitting the resource for indexing, path: "
                                + path, e);
                    }
                }
            } else {
                // remove the indexer for this tenant, if this is a tenant load triggered
                // by an anonymous user (without explicitly logging in).
                // should not stop super tenant indexing.
                if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
                    if (IndexingServiceComponent.isTenantIndexLoadedFromLogin(tenantId) != null &&
                            !IndexingServiceComponent.isTenantIndexLoadedFromLogin(tenantId)) {
                        IndexingServiceComponent.unloadTenantIndex(tenantId);
                    }
                }
            }
            if (log.isTraceEnabled()) {
                log.trace("last successfully indexed activity time is : " +
                        indexingManager.getLastAccessTime(tenantId).toString());
            }
        } catch (Throwable e) {
            // Throwable is caught to prevent termination of the executor
            log.warn("An error occurred while submitting resources for indexing", e);
        }
        return currentTime;
    }

    /**
     * removes log entries with duplicate paths and non-indexed actions
     * preserves time order in reverse in returned ArrayList
     *
     * @param logEntries array containing log entries in oldest first order
     * @return ArrayList<LogEntry> containing time order reversed log entry list with unique path values
     */
    private ArrayList<LogEntry> removeLogEntriesWithDuplicatePaths(LogEntry[] logEntries){
        Set set = new HashSet();
        ArrayList newList = new ArrayList();
        for (int i = 0 ; i < logEntries.length ; i++) {
            if (!set.contains(logEntries[i].getResourcePath())) {
                if (logEntries[i].getAction() == LogEntry.DELETE_RESOURCE ||
                        logEntries[i].getAction() == LogEntry.UPDATE ||
                        logEntries[i].getAction() == LogEntry.DELETE_COMMENT ||
                        logEntries[i].getAction() == LogEntry.REMOVE_TAG ||
                        logEntries[i].getAction() == LogEntry.ADD ||
                        logEntries[i].getAction() == LogEntry.TAG ||
                        logEntries[i].getAction() == LogEntry.COMMENT ||
                        logEntries[i].getAction() == LogEntry.ADD_ASSOCIATION ||
                        logEntries[i].getAction() == LogEntry.MOVE ||
                        logEntries[i].getAction() == LogEntry.COPY ||
                        logEntries[i].getAction() == LogEntry.RENAME ||
                        logEntries[i].getAction() == LogEntry.RESTORE) {
                    if (logEntries[i].getAction() != LogEntry.COPY) {
                        set.add(logEntries[i].getResourcePath());
                    }
                    newList.add(logEntries[i]);
                }
            }
        }
        return newList;
    }
}
