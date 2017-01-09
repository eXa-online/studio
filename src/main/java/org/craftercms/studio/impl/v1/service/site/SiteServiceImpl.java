/*******************************************************************************
 * Crafter Studio Web-content authoring solution
 *     Copyright (C) 2007-2016 Crafter Software Corporation.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.craftercms.studio.impl.v1.service.site;

import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.craftercms.studio.api.v1.constant.StudioConstants;
import org.craftercms.studio.api.v1.constant.DmConstants;
import org.craftercms.studio.api.v1.dal.SiteFeed;
import org.craftercms.studio.api.v1.dal.SiteFeedMapper;
import org.craftercms.studio.api.v1.exception.ContentNotFoundException;
import org.craftercms.studio.api.v1.exception.ServiceException;
import org.craftercms.studio.api.v1.log.Logger;
import org.craftercms.studio.api.v1.log.LoggerFactory;
import org.craftercms.studio.api.v1.service.GeneralLockService;
import org.craftercms.studio.api.v1.service.activity.ActivityService;
import org.craftercms.studio.api.v1.service.configuration.DeploymentEndpointConfig;
import org.craftercms.studio.api.v1.service.configuration.ServicesConfig;
import org.craftercms.studio.api.v1.service.configuration.SiteEnvironmentConfig;
import org.craftercms.studio.api.v1.service.content.*;
import org.craftercms.studio.api.v1.service.dependency.DmDependencyService;
import org.craftercms.studio.api.v1.service.deployment.DeploymentService;
import org.craftercms.studio.api.v1.service.notification.NotificationService;
import org.craftercms.studio.api.v1.service.objectstate.ObjectStateService;
import org.craftercms.studio.api.v1.service.security.SecurityProvider;
import org.craftercms.studio.api.v1.service.security.SecurityService;
import org.craftercms.studio.api.v1.service.site.SiteConfigNotFoundException;
import org.craftercms.studio.api.v1.service.site.SiteService;
import org.craftercms.studio.api.v1.to.*;
import org.craftercms.studio.api.v1.util.StudioConfiguration;
import org.craftercms.studio.impl.v1.repository.job.RebuildRepositoryMetadata;
import org.dom4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.craftercms.studio.api.v1.repository.ContentRepository;
import org.craftercms.studio.api.v1.repository.RepositoryItem;

import org.craftercms.studio.api.v1.to.SiteBlueprintTO;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.craftercms.studio.api.v1.util.StudioConfiguration.*;

/**
 * Note: consider renaming
 * A site in Crafter Studio is currently the name for a WEM project being managed.
 * This service provides access to site configuration
 * @author russdanner
 */
public class SiteServiceImpl implements SiteService {

	private final static Logger logger = LoggerFactory.getLogger(SiteServiceImpl.class);

	@Override
	public boolean writeConfiguration(String site, String path, InputStream content) throws ServiceException {
	    // Write site configuration
		String commitId = contentRepository.writeContent(site, path, content);
        boolean toRet = StringUtils.isEmpty(commitId);

        return toRet;
	}

	@Override
	public boolean writeConfiguration(String path, InputStream content) throws ServiceException {
	    // Write global configuration
        String commitId = contentRepository.writeContent("", path, content);
        boolean toReturn = StringUtils.isEmpty(commitId);
        String site = extractSiteFromConfigurationPath(path);
        return toReturn;
	}

    private String extractSiteFromConfigurationPath(String configurationPath) {
        String var = configurationPath.replace("/cstudio/config/sites/", "");
        int idx = var.indexOf("/");
        String site = var.substring(0, idx);
        return site;
    }

	@Override
	public Map<String, Object> getConfiguration(String path) {
		return null;
	}


	/**
	 * given a site ID return the configuration as a document
	 * This method allows extensions to add additional properties to the configuration that
	 * are not made available through the site configuration object
	 * @param site the name of the site
	 * @return a Document containing the entire site configuration
	 */
	public Document getSiteConfiguration(String site)
	throws SiteConfigNotFoundException {
		return _siteServiceDAL.getSiteConfiguration(site);
	}

	@Override
	public Map<String, Object> getConfiguration(String site, String path, boolean applyEnv) {
		String configPath = "";
		if (StringUtils.isEmpty(site)) {
			configPath = getGlobalConfigRoot() + path;
		} else {
			if (applyEnv) {
				configPath = getEnvironmentConfigPath().replaceAll(StudioConstants.PATTERN_SITE, site).replaceAll(
						StudioConstants.PATTERN_ENVIRONMENT, getEnvironment())
						+ path;
			} else {
				configPath = getSitesConfigPath() + path;
			}
		}
		String configContent = contentService.getContentAsString(site, configPath);

		JSON response = null;
		Map<String, Object> toRet = null;
		if (configContent != null) {
			configContent = configContent.replaceAll("\\n([\\s]+)?+", "");
			configContent = configContent.replaceAll("<!--(.*?)-->", "");
			toRet = convertNodesFromXml(configContent);
		} else {
			response = new JSONObject();
		}
		return toRet;
	}

	private Map<String, Object> convertNodesFromXml(String xml) {
		try {
			InputStream is = new ByteArrayInputStream(xml.getBytes());

			Document document = DocumentHelper.parseText(xml);
			return createMap(document.getRootElement());

		} catch (DocumentException e) {
			e.printStackTrace();
		}
		return null;
	}

	private  Map<String, Object> createMap(Element element) {
		Map<String, Object> map = new HashMap<String, Object>();
		for ( int i = 0, size = element.nodeCount(); i < size; i++ ) {
			Node currentNode = element.node(i);
			if ( currentNode instanceof Element ) {
				Element currentElement = (Element)currentNode;
				String key = currentElement.getName();
				Object toAdd = null;
				if (currentElement.isTextOnly()) {
					 toAdd = currentElement.getStringValue();
				} else {
					toAdd = createMap(currentElement);
				}
				if (map.containsKey(key)) {
					Object value = map.get(key);
					List listOfValues = new ArrayList<Object>();
					if (value instanceof List) {
						listOfValues = (List<Object>)value;
					} else {
						listOfValues.add(value);
					}
					listOfValues.add(toAdd);
					map.put(key, listOfValues);
				} else {
					map.put(key, toAdd);
				}
			}
		}
		return map;
	}

	/**
	 * load site configuration info (not environment specific)
	 *
	 * @param site
	 * @param siteConfig
	 */
	protected void loadSiteConfig(String site, SiteTO siteConfig) {
		// get site configuration
		siteConfig.setWebProject(servicesConfig.getWemProject(site));
		siteConfig.setRepositoryRootPath("/wem-projects/" + site + "/" + site + "/work-area");
	}

	/***
	 * load site environment specific info
	 *
	 * @param site
	 * @param siteConfig
	 */
	protected void loadSiteEnvironmentConfig(String site, SiteTO siteConfig) {
		// get environment specific configuration
		logger.debug("Loading site environment configuration for " + site + "; Environemnt: " + getEnvironment());
		EnvironmentConfigTO environmentConfigTO = environmentConfig.getEnvironmentConfig(site);
		if (environmentConfigTO == null) {
			logger.error("Environment configuration for site " + site + " does not exist.");
			return;
		}
		siteConfig.setLiveUrl(environmentConfigTO.getLiveServerUrl());
		siteConfig.setAuthoringUrl(environmentConfigTO.getAuthoringServerUrl());
		siteConfig.setAuthoringUrlPattern(environmentConfigTO.getAuthoringServerUrlPattern());
		siteConfig.setPreviewUrl(environmentConfigTO.getPreviewServerUrl());
		siteConfig.setPreviewUrlPattern(environmentConfigTO.getPreviewServerUrlPattern());
		siteConfig.setAdminEmail(environmentConfigTO.getAdminEmailAddress());
		siteConfig.setCookieDomain(environmentConfigTO.getCookieDomain());
		siteConfig.setOpenSiteDropdown(environmentConfigTO.getOpenDropdown());
		siteConfig.setFormServerUrl(environmentConfigTO.getFormServerUrlPattern());
		siteConfig.setPublishingChannelGroupConfigs(environmentConfigTO.getPublishingChannelGroupConfigs());
	}

	/***
	 * load site environment specific info
	 *
	 * @param site
	 * @param siteConfig
	 */
	protected void loadSiteDeploymentConfig(String site, SiteTO siteConfig) {
		// get environment specific configuration
		logger.debug("Loading deployment configuration for " + site + "; Environment: " + getEnvironment());
		DeploymentConfigTO deploymentConfig = deploymentEndpointConfig.getSiteDeploymentConfig(site);
		if (deploymentConfig == null) {
			logger.error("Deployment configuration for site " + site + " does not exist.");
			return;
		}
		siteConfig.setDeploymentEndpointConfigs(deploymentConfig.getEndpointMapping());
	}

    @Override
    public DeploymentEndpointConfigTO getDeploymentEndpoint(String site, String endpoint) {
        return deploymentEndpointConfig.getDeploymentConfig(site, endpoint);
    }

    @Override
    public Map<String, PublishingChannelGroupConfigTO> getPublishingChannelGroupConfigs(String site) {
        return environmentConfig.getPublishingChannelGroupConfigs(site);
    }

	@Override
	public List<SiteFeed> getUserSites(String user) {
        String username = user;
        if (StringUtils.isEmpty(username)) {
            username = securityService.getCurrentUser();
        }
        List<SiteFeed> sites = siteFeedMapper.getSites();
        List<SiteFeed> toRet = new ArrayList<SiteFeed>();
        for (SiteFeed site : sites) {
            Set<String> userRoles = securityService.getUserRoles(site.getSiteId(),username);
            if (CollectionUtils.isNotEmpty(userRoles)) {
                site.setLiveUrl(getLiveServerUrl(site.getSiteId()));
                toRet.add(site);
            }
        }
        return toRet;
	}

    @Override
    public DeploymentEndpointConfigTO getPreviewDeploymentEndpoint(String site) {
        String endpoint = environmentConfig.getPreviewDeploymentEndpoint(site);
        return getDeploymentEndpoint(site, endpoint);
    }

    @Override
    public Set<String> getAllAvailableSites() {
        List<SiteFeed> sites = siteFeedMapper.getSites();
        Set<String> toRet = new HashSet<>();
        for (SiteFeed site : sites) {
            toRet.add(site.getSiteId());
        }
        return toRet;
    }

    @Override
    public String getLiveEnvironmentName(String site) {
        PublishingChannelGroupConfigTO pcgcTO = environmentConfig.getLiveEnvironmentPublishingGroup(site);
        if (pcgcTO != null) {
            return pcgcTO.getName();
        } else {
            return null;
        }
    }

   	@Override
   	public boolean createSiteFromBlueprint(String blueprintName, String siteName, String siteId, String desc) {
        boolean success = true;

 		try {
            success = createSiteFromBlueprintGit(blueprintName, siteName, siteId, desc);

			// Set object states
			createObjectStatesforNewSite(siteId);

			// Extract dependencies
			extractDependenciesForNewSite(siteId);

			// Extract metadata ?

	 		// permissions
	 		// environment overrides
	 		// deployment

	 		// insert database records
			SiteFeed siteFeed = new SiteFeed();
			siteFeed.setName(siteName);
			siteFeed.setSiteId(siteId);
			siteFeed.setDescription(desc);
			siteFeedMapper.createSite(siteFeed);

			// TODO: SJ: Must call PreviewDeployer and ask it to call to create target(s) for PreviewDeployer(s)

            deploymentService.syncAllContentToPreview(siteId);
        }
	 	catch(Exception e) {
 		    // TODO: SJ: We need better exception handling here
            success = false;
            logger.error("Error while creating site: " + siteName + " ID: " + siteId + " from blueprint: " +
                    blueprintName, e);
	 	}

	 	return success;
    }

    protected boolean createSiteFromBlueprintGit(String blueprintName, String siteName, String siteId, String desc) throws Exception {
        boolean success = true;

        // create site with git repo
        contentRepository.createSiteFromBlueprint(blueprintName, siteId);

        String siteConfigFolder = "/config/studio";
        replaceFileContentGit(siteId, siteConfigFolder + "/site-config.xml", "SITENAME", siteId);
        replaceFileContentGit(siteId, siteConfigFolder + "/role-mappings-config.xml", "SITENAME", siteId);
        replaceFileContentGit(siteId, siteConfigFolder + "/permission-mappings-config.xml", "SITENAME", siteId);

        return success;
    }

    protected void replaceFileContentGit(String site, String path, String find, String replace) throws Exception {
        InputStream content = contentRepository.getContent(site, path);
        String contentAsString = IOUtils.toString(content);

        contentAsString = contentAsString.replaceAll(find, replace);

        InputStream contentToWrite = IOUtils.toInputStream(contentAsString);

        contentRepository.writeContent(site, path, contentToWrite);
    }

    protected void replaceFileContent(String path, String find, String replace) throws Exception {
    	InputStream content = contentRepository.getContent("", path);
    	String contentAsString = IOUtils.toString(content);

    	contentAsString = contentAsString.replaceAll(find, replace);

    	InputStream contentToWrite = IOUtils.toInputStream(contentAsString);

		contentRepository.writeContent("", path, contentToWrite);
    }

	protected void createObjectStatesforNewSite(String site) {
		createObjectStateNewSiteObjectFolder(site, "/");
	}

	protected void createObjectStateNewSiteObjectFolder(String site, String path) {
		RepositoryItem[] children = contentRepository.getContentChildren(site, path);
		for (RepositoryItem child : children) {
			if (child.isFolder) {
				createObjectStateNewSiteObjectFolder(site, child.path + "/" + child.name);
			} else {
				objectStateService.insertNewEntry(site, child.path + "/" + child.name);
			}
		}
	}

	protected void extractDependenciesForNewSite(String site) {
        Map<String, Set<String>> globalDeps = new HashMap<String, Set<String>>();
        extractDependenciesItemForNewSite(site, "/", globalDeps);
	}

    private void extractDependenciesItemForNewSite(String site, String fullPath, Map<String, Set<String>> globalDeps) {
        RepositoryItem[] children = contentRepository.getContentChildren(site, fullPath);
        for (RepositoryItem child : children) {
            if (child.isFolder) {
                extractDependenciesItemForNewSite(site, child.path + "/" + child.name, globalDeps);
            } else {
                String childPath = child.path + "/" + child.name;

                if (childPath.endsWith(DmConstants.XML_PATTERN)) {
                    try {
                        Document doc = contentService.getContentAsDocument(site, childPath);
                        dmDependencyService.extractDependencies(site, childPath, doc, globalDeps);
                    } catch (ContentNotFoundException e) {
                        logger.error("Failed to extract dependencies for document: site " + site + " path " + childPath, e);
                    } catch (ServiceException e) {
                        logger.error("Failed to extract dependencies for document: site " + site + " path " + childPath, e);
                    } catch (DocumentException e) {
                        logger.error("Failed to extract dependencies for document: site " + site + " path " + childPath, e);
                    }
                } else {

                    boolean isCss = childPath.endsWith(DmConstants.CSS_PATTERN);
                    boolean isJs = childPath.endsWith(DmConstants.JS_PATTERN);
                    List<String> templatePatterns = servicesConfig.getRenderingTemplatePatterns(site);
                    boolean isTemplate = false;
                    for (String templatePattern : templatePatterns) {
                        Pattern pattern = Pattern.compile(templatePattern);
                        Matcher matcher = pattern.matcher(childPath);
                        if (matcher.matches()) {
                            isTemplate = true;
                            break;
                        }
                    }
                    try {
                        if (isCss || isJs || isTemplate) {
                            StringBuffer sb = new StringBuffer(contentService.getContentAsString(site, childPath));
                            if (isCss) {
                                dmDependencyService.extractDependenciesStyle(site, childPath, sb, globalDeps);
                            } else if (isJs) {
                                dmDependencyService.extractDependenciesJavascript(site, childPath, sb, globalDeps);
                            } else if (isTemplate) {
                                dmDependencyService.extractDependenciesTemplate(site, childPath, sb, globalDeps);
                            }
                        }
                    } catch (ServiceException e) {
                        logger.error("Failed to extract dependencies for: site " + site + " path " + childPath, e);
                    }
                }
            }
        }
    }

	@Override
   	public boolean deleteSite(String siteId) {
 		boolean success = true;
 		try {
 			contentRepository.deleteSite(siteId);

	 		// delete database records
			siteFeedMapper.deleteSite(siteId);
			activityService.deleteActivitiesForSite(siteId);
			dmDependencyService.deleteDependenciesForSite(siteId);
            deploymentService.deleteDeploymentDataForSite(siteId);
            objectStateService.deleteObjectStatesForSite(siteId);
            dmPageNavigationOrderService.deleteSequencesForSite(siteId);

            // TODO: SJ: Must call PreviewDeployer to delete site from PreviewDeployer(s)
	 	} catch(Exception err) {
	 		success = false;
	 	}

	 	return success;
    }

    @Override
	public SiteBlueprintTO[] getAvailableBlueprints() {
		RepositoryItem[] blueprintsFolders = contentRepository.getContentChildren("", studioConfiguration.getProperty(BLUE_PRINTS_PATH));
		SiteBlueprintTO[] blueprints = new SiteBlueprintTO[blueprintsFolders.length];
		int idx = 0;
		for (RepositoryItem folder : blueprintsFolders) {
			SiteBlueprintTO blueprintTO = new SiteBlueprintTO();
			blueprintTO.id = folder.name;
			blueprintTO.label = StringUtils.capitalize(folder.name);
			blueprintTO.description = ""; // How do we populate this dynamicly
			blueprintTO.screenshots = null;
			blueprints[idx++] = blueprintTO;
		}

		return blueprints;
	}

    @Override
    public String getPreviewServerUrl(String site) {
        return environmentConfig.getPreviewServerUrl(site);
    }

    @Override
    public String getLiveServerUrl(String site) {
        return environmentConfig.getLiveServerUrl(site);
    }

    @Override
    public String getAuthoringServerUrl(String site) {
        return environmentConfig.getAuthoringServerUrl(site);
    }

    @Override
    public String getAdminEmailAddress(String site) {
        return environmentConfig.getAdminEmailAddress(site);
    }


    @Override
    public void reloadSiteConfigurations() {
        reloadGlobalConfiguration();
        Set<String> sites = getAllAvailableSites();
    }

    @Override
    public void reloadSiteConfiguration(String site) {
        reloadSiteConfiguration(site, true);
    }

    @Override
    public void reloadSiteConfiguration(String site, boolean triggerEvent) {
        SiteTO siteConfig = new SiteTO();
        siteConfig.setSite(site);
        siteConfig.setEnvironment(getEnvironment());
        servicesConfig.reloadConfiguration(site);
        loadSiteConfig(site, siteConfig);
        environmentConfig.reloadConfiguration(site);
        loadSiteEnvironmentConfig(site, siteConfig);
        deploymentEndpointConfig.reloadConfiguration(site);
        loadSiteDeploymentConfig(site, siteConfig);
        notificationService.reloadConfiguration(site);
		notificationService2.reloadConfiguration(site);
        securityService.reloadConfiguration(site);
        contentTypeService.reloadConfiguration(site);
    }

    @Override
    public void reloadGlobalConfiguration() {
        securityService.reloadGlobalConfiguration();
    }

    @Override
    public void importSite(String config) {

    }

    @Override
    public void rebuildRepositoryMetadata(String site) {
        rebuildRepositoryMetadata.execute(site);
    }

    public String getGlobalConfigRoot() {
        return studioConfiguration.getProperty(CONFIGURATION_GLOBAL_CONFIG_BASE_PATH);
    }

    public String getSitesConfigPath() {
        return studioConfiguration.getProperty(CONFIGURATION_SITE_CONFIG_BASE_PATH);
    }

    public String getEnvironment() {
        return studioConfiguration.getProperty(CONFIGURATION_SITE_ENVIRONMENT);
    }

    public String getEnvironmentConfigPath() {
        return studioConfiguration.getProperty(CONFIGURATION_SITE_ENVIRONMENT_CONFIG_BASE_PATH);
    }

    /** getter site service dal */
	public SiteServiceDAL getSiteService() { return _siteServiceDAL; }
	/** setter site service dal */
	public void setSiteServiceDAL(SiteServiceDAL service) { _siteServiceDAL = service; }

	public ServicesConfig getServicesConfig() { return servicesConfig; }
	public void setServicesConfig(ServicesConfig servicesConfig) { this.servicesConfig = servicesConfig; }

	public ContentService getContentService() { return contentService; }
	public void setContentService(ContentService contentService) { this.contentService = contentService; }

	public SiteEnvironmentConfig getEnvironmentConfig() { return environmentConfig; }
	public void setEnvironmentConfig(SiteEnvironmentConfig environmentConfig) { this.environmentConfig = environmentConfig; }

	public DeploymentEndpointConfig getDeploymentEndpointConfig() { return deploymentEndpointConfig; }
	public void setDeploymentEndpointConfig(DeploymentEndpointConfig deploymentEndpointConfig) { this.deploymentEndpointConfig = deploymentEndpointConfig; }

	public ContentRepository getContenetRepository() { return contentRepository; }
	public void setContentRepository(ContentRepository repo) { contentRepository = repo; }

	public ObjectStateService getObjectStateService() { return objectStateService; }
	public void setObjectStateService(ObjectStateService objectStateService) { this.objectStateService = objectStateService; }

	public DmDependencyService getDmDependencyService() { return dmDependencyService; }
	public void setDmDependencyService(DmDependencyService dmDependencyService) { this.dmDependencyService = dmDependencyService; }

	public SecurityService getSecurityService() { return securityService; }
	public void setSecurityService(SecurityService securityService) { this.securityService = securityService; }

	public ActivityService getActivityService() { return activityService; }
	public void setActivityService(ActivityService activityService) { this.activityService = activityService; }

	public DeploymentService getDeploymentService() { return deploymentService; }
	public void setDeploymentService(DeploymentService deploymentService) { this.deploymentService = deploymentService; }

    public ObjectMetadataManager getObjectMetadataManager() { return objectMetadataManager; }
    public void setObjectMetadataManager(ObjectMetadataManager objectMetadataManager) { this.objectMetadataManager = objectMetadataManager; }

    public DmPageNavigationOrderService getDmPageNavigationOrderService() { return dmPageNavigationOrderService; }
    public void setDmPageNavigationOrderService(DmPageNavigationOrderService dmPageNavigationOrderService) { this.dmPageNavigationOrderService = dmPageNavigationOrderService; }

    public NotificationService getNotificationService() { return notificationService; }
    public void setNotificationService(NotificationService notificationService) { this.notificationService = notificationService; }

    public ContentTypeService getContentTypeService() { return contentTypeService; }
    public void setContentTypeService(ContentTypeService contentTypeService) { this.contentTypeService = contentTypeService; }

    public SecurityProvider getSecurityProvider() { return securityProvider; }
    public void setSecurityProvider(SecurityProvider securityProvider) { this.securityProvider = securityProvider; }

    public ImportService getImportService() { return importService; }
    public void setImportService(ImportService importService) { this.importService = importService; }

	public void setNotificationService2(final org.craftercms.studio.api.v2.service.notification.NotificationService
											notificationService2) {
		this.notificationService2 = notificationService2;
	}

    public GeneralLockService getGeneralLockService() { return generalLockService; }
    public void setGeneralLockService(GeneralLockService generalLockService) { this.generalLockService = generalLockService; }

    public RebuildRepositoryMetadata getRebuildRepositoryMetadata() { return rebuildRepositoryMetadata; }
    public void setRebuildRepositoryMetadata(RebuildRepositoryMetadata rebuildRepositoryMetadata) { this.rebuildRepositoryMetadata = rebuildRepositoryMetadata; }

    public StudioConfiguration getStudioConfiguration() { return studioConfiguration; }
    public void setStudioConfiguration(StudioConfiguration studioConfiguration) { this.studioConfiguration = studioConfiguration; }

    protected SiteServiceDAL _siteServiceDAL;
	protected ServicesConfig servicesConfig;
	protected ContentService contentService;
	protected SiteEnvironmentConfig environmentConfig;
	protected DeploymentEndpointConfig deploymentEndpointConfig;
	protected ContentRepository contentRepository;
	protected ObjectStateService objectStateService;
	protected DmDependencyService dmDependencyService;
	protected SecurityService securityService;
	protected ActivityService activityService;
	protected DeploymentService deploymentService;
    protected ObjectMetadataManager objectMetadataManager;
    protected DmPageNavigationOrderService dmPageNavigationOrderService;
    protected NotificationService notificationService;
    protected ContentTypeService contentTypeService;
    protected SecurityProvider securityProvider;
    protected ImportService importService;
	protected org.craftercms.studio.api.v2.service.notification.NotificationService notificationService2;
    protected GeneralLockService generalLockService;
    protected RebuildRepositoryMetadata rebuildRepositoryMetadata;

    protected StudioConfiguration studioConfiguration;

	@Autowired
	protected SiteFeedMapper siteFeedMapper;


	/**
	 * a map of site key and site information
	 */

	protected SitesConfigTO sitesConfig = null;
}
