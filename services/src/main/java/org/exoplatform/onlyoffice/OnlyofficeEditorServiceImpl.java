/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.onlyoffice;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.lock.Lock;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.input.AutoCloseInputStream;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationException;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.PropertiesParam;
import org.exoplatform.onlyoffice.jcr.NodeFinder;
import org.exoplatform.portal.Constants;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.UserProfileHandler;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityRegistry;
import org.picocontainer.Startable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Service implementing {@link OnlyofficeEditorService} and {@link Startable}.<br>
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: OnlyofficeEditorServiceImpl.java 00000 Jan 31, 2016 pnedonosko $
 */
public class OnlyofficeEditorServiceImpl implements OnlyofficeEditorService, Startable {

  /** The Constant LOG. */
  protected static final Log                                                   LOG                   =
                                                                                   ExoLogger.getLogger(OnlyofficeEditorServiceImpl.class);

  /** The Constant RANDOM. */
  protected static final Random                                                RANDOM                = new Random();

  /** The Constant CONFIG_DS_HOST. */
  public static final String                                                   CONFIG_DS_HOST        = "documentserver-host";

  /** The Constant CONFIG_DS_SCHEMA. */
  public static final String                                                   CONFIG_DS_SCHEMA      = "documentserver-schema";

  /** The Constant CONFIG_DS_ACCESS_ONLY. */
  public static final String                                                   CONFIG_DS_ACCESS_ONLY = "documentserver-access-only";

  /** The Constant HTTP_PORT_DELIMITER. */
  protected static final char                                                  HTTP_PORT_DELIMITER   = ':';

  /** The Constant TYPE_TEXT. */
  protected static final String                                                TYPE_TEXT             = "text";

  /** The Constant TYPE_SPREADSHEET. */
  protected static final String                                                TYPE_SPREADSHEET      = "spreadsheet";

  /** The Constant TYPE_PRESENTATION. */
  protected static final String                                                TYPE_PRESENTATION     = "presentation";

  /** The Constant LOCK_WAIT_ATTEMTS. */
  protected static final int                                                   LOCK_WAIT_ATTEMTS     = 20;

  /** The Constant LOCK_WAIT_TIMEOUT. */
  protected static final long                                                  LOCK_WAIT_TIMEOUT     = 250;

  /** The jcr service. */
  protected final RepositoryService                                            jcrService;

  /** The session providers. */
  protected final SessionProviderService                                       sessionProviders;

  /** The identity registry. */
  protected final IdentityRegistry                                             identityRegistry;

  /** The finder. */
  protected final NodeFinder                                                   finder;

  /** The organization. */
  protected final OrganizationService                                          organization;

  /**
   * Editing documents.
   */
  protected final ConcurrentHashMap<String, ConcurrentHashMap<String, Config>> active                =
                                                                                      new ConcurrentHashMap<String, ConcurrentHashMap<String, Config>>();

  /** The config. */
  protected final Map<String, String>                                          config;

  /** The upload url. */
  protected final String                                                       uploadUrl;

  /** The documentserver host name. */
  protected final String                                                       documentserverHostName;

  /** The documentserver url. */
  protected final String                                                       documentserverUrl;

  /** The documentserver access only. */
  protected final boolean                                                      documentserverAccessOnly;

  /** The file types. */
  protected final Map<String, String>                                          fileTypes             = new ConcurrentHashMap<String, String>();

  /** The upload params. */
  protected final MessageFormat                                                uploadParams          =
                                                                                            new MessageFormat("?url={0}&outputtype={1}&filetype={2}&title={3}&key={4}");

  /** The listeners. */
  protected final ConcurrentLinkedQueue<OnlyofficeEditorListener>              listeners             =
                                                                                         new ConcurrentLinkedQueue<OnlyofficeEditorListener>();

  /**
   * Cloud Drive service with storage in JCR and with managed features.
   *
   * @param jcrService {@link RepositoryService}
   * @param sessionProviders {@link SessionProviderService}
   * @param identityRegistry the identity registry
   * @param finder the finder
   * @param organization the organization
   * @param params the params
   * @throws ConfigurationException the configuration exception
   */
  public OnlyofficeEditorServiceImpl(RepositoryService jcrService,
                                     SessionProviderService sessionProviders,
                                     IdentityRegistry identityRegistry,
                                     NodeFinder finder,
                                     OrganizationService organization,
                                     InitParams params)
      throws ConfigurationException {
    this.jcrService = jcrService;
    this.sessionProviders = sessionProviders;
    this.identityRegistry = identityRegistry;
    this.finder = finder;
    this.organization = organization;

    // predefined file types
    // TODO keep map of type configurations with need of conversion to modern format and back
    // FYI we enable editor for only modern office formats (e.g. docx or odt)
    // Text formats
    fileTypes.put("docx", TYPE_TEXT);
    fileTypes.put("doc", TYPE_TEXT);
    fileTypes.put("odt", TYPE_TEXT);
    fileTypes.put("txt", TYPE_TEXT);
    fileTypes.put("rtf", TYPE_TEXT);
    fileTypes.put("mht", TYPE_TEXT);
    fileTypes.put("html", TYPE_TEXT);
    fileTypes.put("htm", TYPE_TEXT);
    fileTypes.put("epub", TYPE_TEXT);
    fileTypes.put("pdf", TYPE_TEXT);
    fileTypes.put("djvu", TYPE_TEXT);
    fileTypes.put("xps", TYPE_TEXT);
    // Speadsheet formats
    fileTypes.put("xlsx", TYPE_SPREADSHEET);
    fileTypes.put("xls", TYPE_SPREADSHEET);
    fileTypes.put("ods", TYPE_SPREADSHEET);
    // Presentation formats
    fileTypes.put("pptx", TYPE_PRESENTATION);
    fileTypes.put("ppt", TYPE_PRESENTATION);
    fileTypes.put("ppsx", TYPE_PRESENTATION);
    fileTypes.put("pps", TYPE_PRESENTATION);
    fileTypes.put("odp", TYPE_PRESENTATION);

    // configuration
    PropertiesParam param = params.getPropertiesParam("editor-configuration");

    if (param != null) {
      config = Collections.unmodifiableMap(param.getProperties());
    } else {
      throw new ConfigurationException("Property parameters editor-configuration required.");
    }

    String dsSchema = config.get(CONFIG_DS_SCHEMA);
    if (dsSchema == null || (dsSchema = dsSchema.trim()).length() == 0) {
      dsSchema = "http";
    }

    String dsHost = config.get(CONFIG_DS_HOST);
    if (dsHost == null || (dsHost = dsHost.trim()).length() == 0) {
      throw new ConfigurationException("Configuration of " + CONFIG_DS_HOST + " required");
    }
    int portIndex = dsHost.indexOf(HTTP_PORT_DELIMITER);
    if (portIndex > 0) {
      // cut port from DS host to use in canDownloadBy() method
      this.documentserverHostName = dsHost.substring(0, portIndex);
    } else {
      this.documentserverHostName = dsHost;
    }

    this.documentserverAccessOnly = Boolean.parseBoolean(config.get(CONFIG_DS_ACCESS_ONLY));

    // base parameters for API

    StringBuilder documentserverUrl = new StringBuilder();
    documentserverUrl.append(dsSchema);
    documentserverUrl.append("://");
    documentserverUrl.append(dsHost);

    this.uploadUrl = new StringBuilder(documentserverUrl).append("/FileUploader.ashx").toString();
    this.documentserverUrl = new StringBuilder(documentserverUrl).append("/OfficeWeb/").toString();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addListener(OnlyofficeEditorListener listener) {
    this.listeners.add(listener);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void removeListener(OnlyofficeEditorListener listener) {
    this.listeners.remove(listener);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Config getEditor(String userId, String workspace, String path) throws OnlyofficeEditorException, RepositoryException {
    return getEditor(userId, nodePath(workspace, path));
  }

  /**
   * Gets the editor.
   *
   * @param userId the user id
   * @param nodePath the node path
   * @return the editor
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  protected Config getEditor(String userId, String nodePath) throws OnlyofficeEditorException, RepositoryException {
    ConcurrentHashMap<String, Config> configs = active.get(nodePath);
    if (configs != null) {
      Config config = configs.get(userId);
      if (config == null) {
        // copy editor for this user from any entry in the configs map
        try {
          User user = getUser(userId);
          String lang = getUserLang(userId); // use this user language
          config = configs.values().iterator().next().forUser(user.getUserName(), user.getFirstName(), user.getLastName(), lang);
          Config existing = configs.putIfAbsent(userId, config);
          if (existing != null) {
            config = existing;
          }
          fireGet(config);
        } catch (NoSuchElementException e) { // if configs was cleaned by closing all active editors
          config = null;
        }
      } else {
        // otherwise: config already obtained
        fireGet(config);
      }
      return config; // can be null
    }

    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Config createEditor(String schema, String host, String userId, String workspace, String path) throws OnlyofficeEditorException,
                                                                                                       RepositoryException {
    Node node = node(workspace, path);
    String nodePath = nodePath(workspace, path);

    if (!node.isNodeType("nt:file")) {
      // TODO other types?
      throw new OnlyofficeEditorException("Only nt:file supported " + nodePath);
    }

    Config config = getEditor(userId, nodePath);
    if (config == null) {
      User user = getUser(userId);

      String fileType = fileType(node);
      String docType = documentType(fileType);

      Config.Builder builder = Config.editor(documentserverUrl, workspace, path, docType);
      builder.author(userId);
      builder.fileType(fileType);
      builder.created(nodeCreated(node));
      builder.folder(node.getParent().getName());
      String lang = getUserLang(userId);
      builder.lang(lang);
      builder.mode("edit");
      builder.title(nodeTitle(node));
      builder.userId(user.getUserName());
      builder.userFirstName(user.getFirstName());
      builder.userLastName(user.getLastName());

      UUID fileId = generateId(workspace, path);
      String key = fileId.toString();

      builder.key(key);

      // file and callback URLs fill be generated respectively the platform URL and actual user
      builder.generateUrls(editorUrl(schema, host).toString());

      config = builder.build();

      ConcurrentHashMap<String, Config> configs = new ConcurrentHashMap<String, Config>();
      configs.put(userId, config);

      // mapping by node path for getEditor(), we should care about concurrent calls here
      ConcurrentHashMap<String, Config> existing = active.putIfAbsent(nodePath, configs);
      if (existing != null) {
        configs = existing;
        config = getEditor(userId, nodePath);
        if (config == null) {
          // it's unexpected state as existing map SHOULD contain a config and it must be copied for
          // given user in getEditor(): client will need to retry the operation
          throw new ConflictException("Cannot obtain configuration for already existing editor");
        }
        // FYI mapping by unique file key should be done by the thread that created this existing map
      } else {
        // mapping by unique file key for updateDocument(): no concurrent calls (duplicates) expected
        active.put(key, configs);
      }
      fireCreated(config);
    }
    return config;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DocumentContent getContent(String userId, String key) throws OnlyofficeEditorException, RepositoryException {
    ConcurrentHashMap<String, Config> configs = active.get(key);
    if (configs != null) {
      Config config = configs.get(userId);
      if (config != null) {
        validateUser(userId, config);

        // use user session here:
        // remember real context state and session provider to restore them at the end
        ConversationState contextState = ConversationState.getCurrent();
        SessionProvider contextProvider = sessionProviders.getSessionProvider(null);
        try {
          // XXX we want do all the job under actual (requester) user here
          Identity userIdentity = identityRegistry.getIdentity(userId);
          if (userIdentity != null) {
            ConversationState state = new ConversationState(userIdentity);
            // Keep subject as attribute in ConversationState.
            state.setAttribute(ConversationState.SUBJECT, userIdentity.getSubject());
            ConversationState.setCurrent(state);
            SessionProvider userProvider = new SessionProvider(state);
            sessionProviders.setSessionProvider(null, userProvider);
          } else {
            LOG.warn("User identity not found " + userId + " for content of " + config.getDocument().getKey() + " " + config.getPath());
            throw new OnlyofficeEditorException("User identity not found " + userId);
          }

          // work in user session
          Node node = node(config.getWorkspace(), config.getPath());
          Node content = nodeContent(node);

          final String mimeType = content.getProperty("jcr:mimeType").getString();
          // data stream will be closed when EoF will be reached
          final InputStream data = new AutoCloseInputStream(content.getProperty("jcr:data").getStream());
          return new DocumentContent() {
            @Override
            public String getType() {
              return mimeType;
            }

            @Override
            public InputStream getData() {
              return data;
            }
          };
        } finally {
          // restore context env
          ConversationState.setCurrent(contextState);
          sessionProviders.setSessionProvider(null, contextProvider);
        }
      } else {
        throw new BadParameterException("User editor not found or already closed " + userId);
      }
    } else {
      throw new BadParameterException("File key not found " + key);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean canDownloadBy(String hostName) {
    if (documentserverAccessOnly) {
      return documentserverHostName.equals(hostName);
    }
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ChangeState getState(String userId, String key) throws OnlyofficeEditorException {
    ConcurrentHashMap<String, Config> configs = active.get(key);
    if (configs != null) {
      Config config = configs.get(userId);
      if (config != null) {
        validateUser(userId, config);
      } else {
        throw new BadParameterException("User editor not found " + userId);
      }
      String[] users = getCurrentUsers(configs);
      return new ChangeState(false, config.getError(), users);
    } else {
      return new ChangeState(true, null); // not found - thus already saved
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateDocument(String userId, DocumentStatus status) throws OnlyofficeEditorException, RepositoryException {
    String key = status.getKey();
    ConcurrentHashMap<String, Config> configs = active.get(key);
    if (configs != null) {
      Config config = configs.get(userId);
      if (config != null) {
        validateUser(userId, config);

        String nodePath = nodePath(config.getWorkspace(), config.getPath());

        // status of the document. Can have the following values: 0 - no document with the key identifier
        // could be found, 1 - document is being edited (user opened an editor), 2 - document is ready for
        // saving (last user closed it), 3 - document saving error has occurred, 4 - document is closed with
        // no changes.
        long statusCode = status.getStatus();

        if (LOG.isDebugEnabled()) {
          LOG.debug(">> Onlyoffice status " + statusCode + " for " + key + ". URL: " + status.getUrl() + ". Users: "
              + Arrays.toString(status.getUsers()) + " << Local file: " + nodePath);
        }

        if (statusCode == 0) {
          // Onlyoffice doesn't know about such document: we clean our records and raise an error
          active.remove(key);
          active.remove(nodePath);
          LOG.warn("Received Onlyoffice status: no document with the key identifier could be found. Key: " + key + ". Document " + nodePath);
          throw new OnlyofficeEditorException("Error editing document: document ID not found");
        } else if (statusCode == 1) {
          // while "document is being edited" (1) will come just before "document is ready for saving"
          // (2) we could do nothing at this point, indeed need study how Onlyoffice behave in different
          // situations when user leave page open or browser hangs/crashes/killed - it still could be useful
          // here to make a cleanup
          // Sync users from the status to active config: this should close configs of gone users
          String[] users = status.getUsers();
          syncUsers(configs, users);
        } else if (statusCode == 2) {
          // save as "document is ready for saving" (2)
          download(config, status);
          active.remove(key);
          active.remove(nodePath);
        } else if (statusCode == 3) {
          // it's an error of saving in Onlyoffice
          // we sync to remote editors list first
          syncUsers(configs, status.getUsers());
          if (configs.size() <= 1) {
            // if one or zero users we can save it
            String url = status.getUrl();
            if (url != null && url.length() > 0) {
              // if URL available then we can download it assuming it's last successful modification
              // the same behaviour as for status (2)
              download(config, status);
              active.remove(key);
              active.remove(nodePath);
              config.setError("Error in editor. Last change was successfully saved");
              // XXX even having it saved we don't known exactly what is it, thus user should see the editor
              // again and decide about content (e.g. it can download it manually from Onlyoffice)
              // config.close(); // close for use in listeners
              // fireSaved(config);
              fireError(config);
              LOG.warn("Received Onlyoffice error of saving document. Key: " + key + ". Users: " + Arrays.toString(status.getUsers())
                  + ". Last change was successfully saved for " + nodePath);
            } else {
              // if error without content URL and last user: it's error state
              LOG.warn("Received Onlyoffice error of saving document without changes URL. Key: " + key + ". Users: "
                  + Arrays.toString(status.getUsers()) + ". Document " + nodePath);
              config.setError("Error in editor. No changes saved");
              fireError(config);
              // TODO no sense to throw an ex here: it will be caught by the caller (REST) and returned to
              // the Onlyoffice server as 500 response, but it doesn't deal with it and will try send the
              // status again.
              // throw new OnlyofficeEditorException("Error saving document: no content returned");
            }
          } else {
            // otherwise we assume other user will save it later
            LOG.warn("Received Onlyoffice error of saving document with several editors. Key: " + key + ". Users: "
                + Arrays.toString(status.getUsers()) + ". Document " + nodePath);
            config.setError("Error in editor. Document still in editing state");
            fireError(config);
          }
        } else if (statusCode == 4) {
          // user(s) haven't changed the document but closed it: sync users to fire onLeaved event(s)
          syncUsers(configs, status.getUsers());
          // and remove this document from active configs
          active.remove(key);
          active.remove(nodePath);
        } else {
          // warn unexpected status, wait for next status
          LOG.warn("Received Onlyoffice unexpected status. Key: " + key + ". URL: " + status.getUrl() + ". Users: " + status.getUsers()
              + ". Document " + nodePath);
        }
      } else {
        throw new BadParameterException("User editor not found " + userId);
      }
    } else {
      throw new BadParameterException("File key not found " + key);
    }
  }

  /**
   * On-start initializer.
   */
  @Override
  public void start() {
    LOG.info("Onlyoffice Editor service successfuly started");
  }

  /**
   * On-stop finalizer.
   */
  @Override
  public void stop() {
    active.clear();
    LOG.info("Onlyoffice  Editor service successfuly stopped");
  }

  // *********************** implementation level ***************

  /**
   * Node title.
   *
   * @param node the node
   * @return the string
   * @throws RepositoryException the repository exception
   */
  protected String nodeTitle(Node node) throws RepositoryException {
    String title = null;
    if (node.hasProperty("exo:title")) {
      title = node.getProperty("exo:title").getString();
    } else if (node.hasProperty("jcr:content/dc:title")) {
      Property dcTitle = node.getProperty("jcr:content/dc:title");
      if (dcTitle.getDefinition().isMultiple()) {
        Value[] dctValues = dcTitle.getValues();
        if (dctValues.length > 0) {
          title = dctValues[0].getString();
        }
      } else {
        title = dcTitle.getString();
      }
    } else if (node.hasProperty("exo:name")) {
      // FYI exo:name seems the same as node name
      title = node.getProperty("exo:name").getString();
    }
    if (title == null) {
      title = node.getName();
    }
    return title;
  }

  /**
   * File type.
   *
   * @param node the node
   * @return the string
   * @throws RepositoryException the repository exception
   */
  protected String fileType(Node node) throws RepositoryException {
    String title = nodeTitle(node);
    int dotIndex = title.lastIndexOf('.');
    if (dotIndex >= 0 && dotIndex < title.length()) {
      String fileExt = title.substring(dotIndex + 1).trim();
      if (fileTypes.containsKey(fileExt)) {
        return fileExt;
      }
    }
    // TODO should we find a type from MIME-type?
    // String mimeType = node.getProperty("jcr:content/jcr:mimeType").getString();
    return null;
  }

  /**
   * Document type.
   *
   * @param fileType the file type
   * @return the string
   */
  protected String documentType(String fileType) {
    String docType = fileTypes.get(fileType);
    if (docType != null) {
      return docType;
    }
    return TYPE_TEXT; // we assume text document by default
  }

  /**
   * Node content.
   *
   * @param node the node
   * @return the node
   * @throws RepositoryException the repository exception
   */
  protected Node nodeContent(Node node) throws RepositoryException {
    return node.getNode("jcr:content");
  }

  /**
   * Node created.
   *
   * @param node the node
   * @return the calendar
   * @throws RepositoryException the repository exception
   */
  protected Calendar nodeCreated(Node node) throws RepositoryException {
    return node.getProperty("jcr:created").getDate();
  }

  /**
   * Mime type.
   *
   * @param content the content
   * @return the string
   * @throws RepositoryException the repository exception
   */
  protected String mimeType(Node content) throws RepositoryException {
    return content.getProperty("jcr:mimeType").getString();
  }

  /**
   * Data.
   *
   * @param content the content
   * @return the property
   * @throws RepositoryException the repository exception
   */
  protected Property data(Node content) throws RepositoryException {
    return content.getProperty("jcr:data");
  }

  /**
   * Generate id.
   *
   * @param workspace the workspace
   * @param path the path
   * @return the uuid
   */
  protected UUID generateId(String workspace, String path) {
    StringBuilder s = new StringBuilder();
    s.append(workspace);
    s.append(path);
    s.append(System.currentTimeMillis());
    s.append(String.valueOf(RANDOM.nextLong()));

    return UUID.nameUUIDFromBytes(s.toString().getBytes());
  }

  /**
   * Node path.
   *
   * @param workspace the workspace
   * @param path the path
   * @return the string
   */
  protected String nodePath(String workspace, String path) {
    return new StringBuilder().append(workspace).append(":").append(path).toString();
  }

  /**
   * Node path.
   *
   * @param config the config
   * @return the string
   */
  protected String nodePath(Config config) {
    return nodePath(config.getWorkspace(), config.getPath());
  }

  /**
   * Gets the user.
   *
   * @param username the username
   * @return the user
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   */
  protected User getUser(String username) throws OnlyofficeEditorException {
    try {
      return organization.getUserHandler().findUserByName(username);
    } catch (Exception e) {
      throw new OnlyofficeEditorException("Error searching user " + username, e);
    }
  }

  /**
   * Webdav url.
   *
   * @param schema the schema
   * @param host the host
   * @param workspace the workspace
   * @param path the path
   * @return the string
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  protected String webdavUrl(String schema, String host, String workspace, String path) throws OnlyofficeEditorException, RepositoryException {
    StringBuilder filePath = new StringBuilder();
    try {
      URI baseWebdavUri = webdavUri(schema, host);

      filePath.append(baseWebdavUri.getPath());
      filePath.append('/');
      filePath.append(jcrService.getCurrentRepository().getConfiguration().getName());
      filePath.append('/');
      filePath.append(workspace);
      filePath.append(path);

      URI uri = new URI(baseWebdavUri.getScheme(), null, baseWebdavUri.getHost(), baseWebdavUri.getPort(), filePath.toString(), null, null);
      return uri.toASCIIString();
    } catch (URISyntaxException e) {
      throw new OnlyofficeEditorException("Error creating content link (WebDAV) for " + path + " in " + workspace, e);
    }
  }

  /**
   * Upload content.
   *
   * @param localContent the local content
   * @param fileKey the file key
   * @param mimeType the mime type
   * @param length the length
   * @return the string
   * @throws IOException Signals that an I/O exception has occurred.
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   */
  @Deprecated
  protected String uploadContent(InputStream localContent, String fileKey, String mimeType, long length) throws IOException,
                                                                                                         OnlyofficeEditorException {
    String urlTostorage = uploadUrl + uploadParams.format(new String[] { "", "", "", "", fileKey });

    URL url = new URL(urlTostorage);
    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();

    connection.setDoOutput(true);
    connection.setDoInput(true);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", mimeType == null ? "application/octet-stream" : mimeType);
    connection.setRequestProperty("charset", "utf-8");
    connection.setRequestProperty("Content-Length", Long.toString(length));
    connection.setUseCaches(false);

    try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
      int read;
      final byte[] bytes = new byte[1024];
      while ((read = localContent.read(bytes)) != -1) {
        out.write(bytes, 0, read);
      }
      out.flush();
    }

    InputStream uploadXml = connection.getInputStream();

    if (uploadXml == null) {
      throw new OnlyofficeEditorException("Could not get an answer from Onlyoffice Document Server for " + fileKey);
    }

    try {
      DocumentBuilderFactory documentBuildFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder doccumentBuilder = documentBuildFactory.newDocumentBuilder();
      InputSource inputSource = new InputSource(uploadXml);
      Document document = doccumentBuilder.parse(inputSource);

      Element responceFromConvertService = document.getDocumentElement();
      if (responceFromConvertService == null) {
        throw new OnlyofficeEditorException("Invalid answer format from Onlyoffice Document Server for " + fileKey);
      }
      NodeList errorElement = responceFromConvertService.getElementsByTagName("Error");
      if (errorElement != null && errorElement.getLength() > 0) {
        processConvertServiceResponceError(Integer.parseInt(errorElement.item(0).getTextContent()));
      }
      NodeList endConvert = responceFromConvertService.getElementsByTagName("EndConvert");
      if (endConvert == null || endConvert.getLength() == 0) {
        throw new OnlyofficeEditorException("Invalid answer format from Onlyoffice Document Server for " + fileKey);
      }
      Boolean isEndConvert = Boolean.parseBoolean(endConvert.item(0).getTextContent());

      int resultPercent = 0;
      String responseUri;

      if (isEndConvert) {
        NodeList fileUrl = responceFromConvertService.getElementsByTagName("FileUrl");
        if (fileUrl == null || endConvert.getLength() == 0) {
          throw new OnlyofficeEditorException("Invalid answer" + "} format from Onlyoffice Document Server for " + fileKey);
        }
        resultPercent = 100;
        responseUri = fileUrl.item(0).getTextContent();
      } else {
        NodeList percent = responceFromConvertService.getElementsByTagName("Percent");
        if (percent != null && percent.getLength() > 0) {
          resultPercent = Integer.parseInt(percent.item(0).getTextContent());
        }
        resultPercent = resultPercent >= 100 ? 99 : resultPercent;
        if (LOG.isDebugEnabled()) {
          LOG.debug(">>> File not yet converted " + fileKey + ": " + resultPercent + "%");
        }
        responseUri = null;
      }

      return responseUri;
    } catch (ParserConfigurationException e) {
      throw new OnlyofficeEditorException("Error creating XML parser for reading upload response of " + fileKey, e);
    } catch (SAXException e) {
      throw new OnlyofficeEditorException("Error parsing answer from Onlyoffice Document Server for " + fileKey, e);
    } finally {
      connection.disconnect();
    }
  }

  /**
   * Process convert service responce error.
   *
   * @param errorCode the error code
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   */
  private void processConvertServiceResponceError(int errorCode) throws OnlyofficeEditorException {
    String errorMessage = "";
    String errorMessageTemplate = "Error occurred in the ConvertService: ";

    switch (errorCode) {
    case -8:
      errorMessage = errorMessageTemplate + "Error document VKey";
      break;
    case -7:
      errorMessage = errorMessageTemplate + "Error document request";
      break;
    case -6:
      errorMessage = errorMessageTemplate + "Error database";
      break;
    case -5:
      errorMessage = errorMessageTemplate + "Error unexpected guid";
      break;
    case -4:
      errorMessage = errorMessageTemplate + "Error download error";
      break;
    case -3:
      errorMessage = errorMessageTemplate + "Error convertation error";
      break;
    case -2:
      errorMessage = errorMessageTemplate + "Error convertation timeout";
      break;
    case -1:
      errorMessage = errorMessageTemplate + "Error convertation unknown";
      break;
    case 0:
      break;
    default:
      errorMessage = "ErrorCode = " + errorCode;
      break;
    }

    throw new OnlyofficeEditorException(errorMessage);
  }

  /**
   * Sync users.
   *
   * @param configs the configs
   * @param users the users
   */
  protected void syncUsers(ConcurrentHashMap<String, Config> configs, String[] users) {
    Set<String> editors = new HashSet<String>(Arrays.asList(users));
    // remove gone editors
    for (Iterator<Map.Entry<String, Config>> ceiter = configs.entrySet().iterator(); ceiter.hasNext();) {
      Map.Entry<String, Config> ce = ceiter.next();
      String cuser = ce.getKey();
      Config config = ce.getValue();
      if (editors.contains(cuser)) {
        if (config.isCreated() || config.isClosed()) {
          // editor was (re)opened by user
          config.open();
          fireJoined(config);
        }
      } else {
        // editor was closed by user
        if (config.isOpen()) {
          config.close();
          fireLeaved(config);
        }
      }
    }
  }

  /**
   * Gets the current users.
   *
   * @param configs the configs
   * @return the current users
   */
  protected String[] getCurrentUsers(ConcurrentHashMap<String, Config> configs) {
    // copy key set to avoid confuses w/ concurrency
    Set<String> userIds = new LinkedHashSet<String>(configs.keySet());
    // remove not existing locally, not yet open (created) or already closed
    for (Iterator<String> uiter = userIds.iterator(); uiter.hasNext();) {
      String userId = uiter.next();
      Config config = configs.get(userId);
      if (config == null || config.isCreated() || config.isClosed()) {
        uiter.remove();
      }
    }
    return userIds.toArray(new String[userIds.size()]);
  }

  /**
   * Download.
   *
   * @param config the config
   * @param status the status
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  protected void download(Config config, DocumentStatus status) throws OnlyofficeEditorException, RepositoryException {
    String workspace = config.getWorkspace();
    String path = config.getPath();
    String nodePath = nodePath(workspace, path);

    if (LOG.isDebugEnabled()) {
      LOG.debug(">> download(" + nodePath + ", " + config.getDocument().getKey() + ")");
    }

    String userId = status.getUsers()[0]; // assuming a single user here (last editor)
    validateUser(userId, config);

    String contentUrl = status.getUrl();
    Calendar editedTime = Calendar.getInstance();

    HttpURLConnection connection;
    InputStream data;
    try {
      URL url = new URL(contentUrl);
      connection = (HttpURLConnection) url.openConnection();
      data = connection.getInputStream();
      if (data == null) {
        throw new OnlyofficeEditorException("Content stream is null");
      }
    } catch (MalformedURLException e) {
      throw new OnlyofficeEditorException("Error parsing content URL " + contentUrl + " for " + nodePath, e);
    } catch (IOException e) {
      throw new OnlyofficeEditorException("Error reading content stream " + contentUrl + " for " + nodePath, e);
    }

    // remember real context state and session provider to restore them at the end
    ConversationState contextState = ConversationState.getCurrent();
    SessionProvider contextProvider = sessionProviders.getSessionProvider(null);
    try {
      // We want do all the job under actual (last editor) user here
      // Notable that some WCM actions (FileUpdateActivityListener) will fail if user will be anonymous
      // This will be reverted in finally block
      Identity userIdentity = identityRegistry.getIdentity(userId);
      if (userIdentity != null) {
        ConversationState state = new ConversationState(userIdentity);
        // Keep subject as attribute in ConversationState.
        state.setAttribute(ConversationState.SUBJECT, userIdentity.getSubject());
        ConversationState.setCurrent(state);
        SessionProvider userProvider = new SessionProvider(state);
        sessionProviders.setSessionProvider(null, userProvider);
        if (LOG.isDebugEnabled()) {
          LOG.debug(">>> download under user " + userIdentity.getUserId() + " (" + nodePath + ", " + config.getDocument().getKey() + ")");
        }
      } else {
        LOG.warn("User identity not found " + userId + " for downloading " + config.getDocument().getKey() + " " + nodePath);
        throw new OnlyofficeEditorException("User identity not found " + userId);
      }

      // work in user session
      Node node = node(workspace, path);
      Node content = nodeContent(node);

      // lock node first, this also will check if node isn't locked by another user (will throw exception)
      Lock lock = lock(node, config);
      if (lock == null) {
        throw new OnlyofficeEditorException("Document locked " + nodePath);
      }

      // manage version only if node already mix:versionable
      boolean checkIn = checkout(node);

      try {
        // update document
        content.setProperty("jcr:data", data);
        // update modified date (this will force PDFViewer to regenerate its images)
        content.setProperty("jcr:lastModified", editedTime);
        if (content.hasProperty("exo:dateModified")) {
          content.setProperty("exo:dateModified", editedTime);
        }
        if (content.hasProperty("exo:lastModifiedDate")) {
          content.setProperty("exo:lastModifiedDate", editedTime);
        }
        if (node.hasProperty("exo:lastModifiedDate")) {
          node.setProperty("exo:lastModifiedDate", editedTime);
        }
        if (node.hasProperty("exo:dateModified")) {
          node.setProperty("exo:dateModified", editedTime);
        }
        if (node.hasProperty("exo:lastModifier")) {
          node.setProperty("exo:lastModifier", userId);
        }

        node.save();
        if (checkIn) {
          // Make a new version from the downloaded state
          node.checkin();
          // Since 1.2.0-RC01 we check-out the document to let (more) other actions in ECMS appear on it
          node.checkout();
        }

        config.close(); // close for use in listeners
        fireSaved(config);
      } catch (RepositoryException e) {
        try {
          node.refresh(false); // rollback JCR modifications
        } catch (Throwable re) {
          LOG.warn("Error rolling back failed change for " + nodePath, re);
        }
        throw e; // let the caller handle it further
      } finally {
        try {
          data.close();
        } catch (Throwable e) {
          LOG.warn("Error closing exported content stream for " + nodePath, e);
        }
        try {
          connection.disconnect();
        } catch (Throwable e) {
          LOG.warn("Error closing export connection for " + nodePath, e);
        }
        try {
          if (lock != null && node.isLocked()) {
            node.unlock();
          }
        } catch (Throwable e) {
          LOG.warn("Error unlocking edited document " + nodePath(workspace, path), e);
        }
      }
    } finally {
      // restore context env
      ConversationState.setCurrent(contextState);
      sessionProviders.setSessionProvider(null, contextProvider);
    }
  }

  /**
   * Download change.
   *
   * @param config the config
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  @Deprecated
  protected void downloadChange(Config config) throws OnlyofficeEditorException, RepositoryException {
    String workspace = config.getWorkspace();
    String path = config.getPath();
    String nodePath = nodePath(workspace, path);

    if (LOG.isDebugEnabled()) {
      LOG.debug(">> downloadChange(" + nodePath + ", " + config.getDocument().getKey() + ")");
    }

    Node node = systemNode(workspace, path);
    Node content = nodeContent(node);
    if (content.isNodeType("exo:editing")) {
      // TODO ensure node updated under system account has real user in exo:lastModifier

      Property lastContentUrl = content.getProperty("exo:lastContentUrl");
      String contentUrl = lastContentUrl.getString();
      String editor;
      Property lastEditor = node.getProperty("exo:lastEditor");
      editor = lastEditor.getString();
      Property lastEditedTime = content.getProperty("exo:lastEditedTime");
      Calendar editedTime = lastEditedTime.getDate();

      HttpURLConnection connection;
      InputStream data;
      try {
        URL url = new URL(contentUrl);
        connection = (HttpURLConnection) url.openConnection();
        data = connection.getInputStream();
        if (data == null) {
          throw new OnlyofficeEditorException("Content stream is null");
        }
      } catch (MalformedURLException e) {
        throw new OnlyofficeEditorException("Error parsing content URL " + contentUrl + " for " + nodePath, e);
      } catch (IOException e) {
        throw new OnlyofficeEditorException("Error reading content stream " + contentUrl + " for " + nodePath, e);
      }

      // lock node first, this also will check if node isn't locked by another user (will throw exception)
      Lock lock = lock(node, config);
      if (lock == null) {
        throw new OnlyofficeEditorException("Document locked " + nodePath);
      }

      // manage version only if node already mix:versionable
      boolean checkIn = checkout(node);

      try {
        // update document
        content.setProperty("jcr:data", data);
        // update modified date (this will force PDFViewer to regenerate its images)
        content.setProperty("jcr:lastModified", editedTime);
        if (content.hasProperty("exo:dateModified")) {
          content.setProperty("exo:dateModified", editedTime);
        }
        if (content.hasProperty("exo:lastModifiedDate")) {
          content.setProperty("exo:lastModifiedDate", editedTime);
        }
        if (node.hasProperty("exo:lastModifiedDate")) {
          node.setProperty("exo:lastModifiedDate", editedTime);
        }
        if (node.hasProperty("exo:dateModified")) {
          node.setProperty("exo:dateModified", editedTime);
        }
        if (node.hasProperty("exo:lastModifier")) {
          node.setProperty("exo:lastModifier", editor);
        }

        // remove exo:editable from content node
        lastContentUrl.remove();
        lastEditor.remove();
        lastEditedTime.remove();
        content.removeMixin("exo:editing");

        node.save();
        if (checkIn) {
          node.checkin();
        }
      } catch (RepositoryException e) {
        try {
          node.refresh(false); // rollback JCR modifications
        } catch (Throwable re) {
          LOG.warn("Error rolling back failed change for " + nodePath, re);
        }
        throw e; // let the caller handle it further
      } finally {
        try {
          data.close();
        } catch (Throwable e) {
          LOG.warn("Error closing exported content stream for " + nodePath, e);
        }
        try {
          connection.disconnect();
        } catch (Throwable e) {
          LOG.warn("Error closing export connection for " + nodePath, e);
        }
        try {
          if (lock != null && node.isLocked()) {
            node.unlock();
          }
        } catch (Throwable e) {
          LOG.warn("Error unlocking edited document " + nodePath(workspace, path), e);
        }
      }
    } else {
      throw new OnlyofficeEditorException("Document node was not initialized for editing " + nodePath);
    }
  }

  /**
   * Track change.
   *
   * @param config the config
   * @param status the status
   * @throws BadParameterException the bad parameter exception
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  @Deprecated
  protected void trackChange(Config config, DocumentStatus status) throws BadParameterException, OnlyofficeEditorException, RepositoryException {
    String workspace = config.getWorkspace();
    String path = config.getPath();

    String contentUrl = status.getUrl();
    String user;
    String[] users = status.getUsers();
    if (users != null && users.length > 0) {
      user = users[0];
    } else {
      LOG.warn("No user in status from Onlyoffice document editing service for file " + status.getKey() + " (" + nodePath(workspace, path) + ")");
      user = null;
    }
    if (contentUrl != null && contentUrl.length() > 0) {
      // TODO ensure node updated under system account has real user in exo:lastModifier
      Node node = systemNode(workspace, path);
      // ensure node is not in checked-in state
      checkout(node);

      Node content = nodeContent(node);
      if (content.canAddMixin("exo:editing")) {
        content.addMixin("exo:editing");
        content.save();
      }
      content.setProperty("exo:lastContentUrl", contentUrl);
      content.setProperty("exo:lastEditor", user);
      content.setProperty("exo:lastEditedTime", Calendar.getInstance());
      content.save();
    } else {
      throw new OnlyofficeEditorException("Empty link to the edited document from editing service. User " + user + ". Document "
          + nodePath(workspace, path));
    }
  }

  /**
   * Node.
   *
   * @param workspace the workspace
   * @param path the path
   * @return the node
   * @throws BadParameterException the bad parameter exception
   * @throws RepositoryException the repository exception
   */
  protected Node node(String workspace, String path) throws BadParameterException, RepositoryException {
    SessionProvider sp = sessionProviders.getSessionProvider(null);
    Session userSession = sp.getSession(workspace, jcrService.getCurrentRepository());

    Item item = finder.findItem(userSession, path);
    if (item.isNode()) {
      return (Node) item;
    } else {
      throw new BadParameterException("Not a node " + path);
    }
  }

  /**
   * System node.
   *
   * @param workspace the workspace
   * @param path the path
   * @return the node
   * @throws BadParameterException the bad parameter exception
   * @throws RepositoryException the repository exception
   */
  protected Node systemNode(String workspace, String path) throws BadParameterException, RepositoryException {
    SessionProvider sp = sessionProviders.getSystemSessionProvider(null);
    Session sysSession = sp.getSession(workspace, jcrService.getCurrentRepository());

    Item item = finder.findItem(sysSession, path);
    if (item.isNode()) {
      return (Node) item;
    } else {
      throw new BadParameterException("Not a node " + path);
    }
  }

  /**
   * Checkout.
   *
   * @param node the node
   * @return true, if successful
   * @throws RepositoryException the repository exception
   */
  protected boolean checkout(Node node) throws RepositoryException {
    if (node.isNodeType("mix:versionable")) {
      if (!node.isCheckedOut()) {
        node.checkout();
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Lock by current user of the node. If lock attempts will succeed in predefined time this method will throw
   * {@link OnlyofficeEditorException}. If node isn't mix:versionable it will be added first and node saved.
   *
   * @param node {@link Node}
   * @param config {@link Config}
   * @return {@link Lock} acquired by current user.
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  protected Lock lock(Node node, Config config) throws OnlyofficeEditorException, RepositoryException {
    if (!node.isNodeType("mix:lockable")) {
      if (!node.isCheckedOut() && node.isNodeType("mix:versionable")) {
        node.checkout();
      }
      node.addMixin("mix:lockable");
      node.save();
    }

    Config.Editor.User user = config.getEditorConfig().getUser();
    Lock lock;
    int attempts = 0;
    try {
      do {
        attempts++;
        if (node.isLocked()) {
          String lockToken = user.getLockToken();
          if (node.getLock().getLockOwner().equals(user.getId()) && lockToken != null) {
            // already this user lock
            node.getSession().addLockToken(lockToken);
            lock = node.getLock();
          } else {
            // need wait for unlock
            Thread.sleep(LOCK_WAIT_TIMEOUT);
            lock = null;
          }
        } else {
          lock = node.lock(true, false); // TODO deep vs only file node lock?
          user.setLockToken(lock.getLockToken()); // keep own token for crossing sessions
        }
      } while (lock == null && attempts <= LOCK_WAIT_ATTEMTS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OnlyofficeEditorException("Error waiting for lock of " + nodePath(config.getWorkspace(), config.getPath()), e);
    }
    return lock;
  }

  /**
   * Unlock.
   *
   * @param node the node
   * @param config the config
   * @return true, if successful
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   * @throws RepositoryException the repository exception
   */
  @Deprecated
  protected boolean unlock(Node node, Config config) throws OnlyofficeEditorException, RepositoryException {
    if (node.isLocked()) {
      Config.Editor.User user = config.getEditorConfig().getUser();
      String lockToken = user.getLockToken();
      if (node.getLock().getLockOwner().equals(user.getId()) && lockToken != null) {
        // already this user lock
        node.getSession().addLockToken(lockToken);
        node.unlock();
        return true;
      }
    }
    return false;
  }

  /**
   * Validate user.
   *
   * @param userId the user id
   * @param config the config
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   */
  protected void validateUser(String userId, Config config) throws OnlyofficeEditorException {
    User user = getUser(userId);
    if (user == null) {
      LOG.warn("Attempt to access editor document (" + nodePath(config) + ") under not existing user " + userId);
      throw new BadParameterException("User not found for " + config.getDocument().getTitle());
    }
  }

  /**
   * Gets the user lang.
   *
   * @param userId the user id
   * @return the user lang
   * @throws OnlyofficeEditorException the onlyoffice editor exception
   */
  protected String getUserLang(String userId) throws OnlyofficeEditorException {
    UserProfileHandler hanlder = organization.getUserProfileHandler();
    try {
      UserProfile userProfile = hanlder.findUserProfileByName(userId);
      if (userProfile != null) {
        String lang = userProfile.getAttribute(Constants.USER_LANGUAGE);
        if (lang != null) {
          // XXX Onlyoffice doesn't support country codes (as of Apr 6, 2016)
          // All supported langauges here http://helpcenter.onlyoffice.com/tipstricks/available-languages.aspx
          int cci = lang.indexOf("_");
          if (cci > 0) {
            lang = lang.substring(0, cci);
          }
        } else {
          lang = Locale.ENGLISH.getLanguage();
        }
        return lang;
      } else {
        throw new BadParameterException("User profile not found for " + userId);
      }
    } catch (Exception e) {
      throw new OnlyofficeEditorException("Error searching user profile " + userId, e);
    }
  }

  /**
   * Platform url.
   *
   * @param schema the schema
   * @param host the host
   * @return the string builder
   */
  protected StringBuilder platformUrl(String schema, String host) {
    StringBuilder platformUrl = new StringBuilder();
    platformUrl.append(schema);
    platformUrl.append("://");
    platformUrl.append(host);
    platformUrl.append('/');
    platformUrl.append(PortalContainer.getCurrentPortalContainerName());
    platformUrl.append('/');
    platformUrl.append(PortalContainer.getCurrentRestContextName());

    return platformUrl;
  }

  /**
   * Editor url.
   *
   * @param schema the schema
   * @param host the host
   * @return the string builder
   */
  protected StringBuilder editorUrl(String schema, String host) {
    return platformUrl(schema, host).append("/onlyoffice/editor");
  }

  /**
   * Webdav uri.
   *
   * @param schema the schema
   * @param host the host
   * @return the uri
   * @throws URISyntaxException the URI syntax exception
   */
  protected URI webdavUri(String schema, String host) throws URISyntaxException {
    StringBuilder webdavUrl = new StringBuilder();
    webdavUrl.append(platformUrl(schema, host));
    webdavUrl.append("/jcr");
    return new URI(webdavUrl.toString());
  }

  /**
   * Fire created.
   *
   * @param config the config
   */
  protected void fireCreated(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onCreate(config);
      } catch (Throwable t) {
        LOG.warn("Creation listener error", t);
      }
    }
  }

  /**
   * Fire get.
   *
   * @param config the config
   */
  protected void fireGet(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onGet(config);
      } catch (Throwable t) {
        LOG.warn("Read (Get) listener error", t);
      }
    }
  }

  /**
   * Fire joined.
   *
   * @param config the config
   */
  protected void fireJoined(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onJoined(config);
      } catch (Throwable t) {
        LOG.warn("User joining listener error", t);
      }
    }
  }

  /**
   * Fire leaved.
   *
   * @param config the config
   */
  protected void fireLeaved(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onLeaved(config);
      } catch (Throwable t) {
        LOG.warn("User leaving listener error", t);
      }
    }
  }

  /**
   * Fire saved.
   *
   * @param config the config
   */
  protected void fireSaved(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onSaved(config);
      } catch (Throwable t) {
        LOG.warn("Saving listener error", t);
      }
    }
  }

  /**
   * Fire error.
   *
   * @param config the config
   */
  protected void fireError(Config config) {
    for (OnlyofficeEditorListener l : listeners) {
      try {
        l.onError(config);
      } catch (Throwable t) {
        LOG.warn("Error listener error", t);
      }
    }
  }
}
