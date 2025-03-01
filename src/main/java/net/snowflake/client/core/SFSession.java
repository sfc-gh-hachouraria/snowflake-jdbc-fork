/*
 * Copyright (c) 2012-2022 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.core;

import static net.snowflake.client.core.QueryStatus.getStatusFromString;
import static net.snowflake.client.core.QueryStatus.isAnError;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.security.PrivateKey;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.snowflake.client.jdbc.*;
import net.snowflake.client.jdbc.telemetry.Telemetry;
import net.snowflake.client.jdbc.telemetry.TelemetryClient;
import net.snowflake.client.log.JDK14Logger;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;
import net.snowflake.common.core.ClientAuthnDTO;
import net.snowflake.common.core.SqlState;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;

/** Snowflake session implementation */
public class SFSession extends SFBaseSession {
  public static final String SF_QUERY_REQUEST_ID = "requestId";
  public static final String SF_HEADER_AUTHORIZATION = HttpHeaders.AUTHORIZATION;
  public static final String SF_HEADER_SNOWFLAKE_AUTHTYPE = "Snowflake";
  public static final String SF_HEADER_TOKEN_TAG = "Token";
  static final SFLogger logger = SFLoggerFactory.getLogger(SFSession.class);
  private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.getObjectMapper();
  private static final String SF_PATH_SESSION_HEARTBEAT = "/session/heartbeat";
  private static final String SF_PATH_QUERY_MONITOR = "/monitoring/queries/";
  // temporarily have this variable to avoid hardcode.
  // Need to be removed when a better way to organize session parameter is introduced.
  private static final String CLIENT_STORE_TEMPORARY_CREDENTIAL =
      "CLIENT_STORE_TEMPORARY_CREDENTIAL";
  private static final ObjectMapper mapper = ObjectMapperFactory.getObjectMapper();
  private static final int MAX_SESSION_PARAMETERS = 1000;
  public static final int DEFAULT_HTTP_CLIENT_SOCKET_TIMEOUT = 300000; // millisec
  private final AtomicInteger sequenceId = new AtomicInteger(0);
  private final List<DriverPropertyInfo> missingProperties = new ArrayList<>();
  // list of active asynchronous queries. Used to see if session should be closed when connection
  // closes
  protected Set<String> activeAsyncQueries = ConcurrentHashMap.newKeySet();
  private boolean isClosed = true;
  private String sessionToken;
  private String masterToken;
  private long masterTokenValidityInSeconds;
  private String idToken;
  private String mfaToken;
  private String privateKeyFileLocation;
  private String privateKeyPassword;
  private PrivateKey privateKey;

  /**
   * Amount of seconds a user is willing to tolerate for establishing the connection with database.
   * In our case, it means the first login request to get authorization token.
   *
   * <p>Default:60 seconds
   */
  private int loginTimeout = 60;
  /**
   * Amount of milliseconds a user is willing to tolerate for network related issues (e.g. HTTP
   * 503/504) or database transient issues (e.g. GS not responding)
   *
   * <p>A value of 0 means no timeout
   *
   * <p>Default: 0
   */
  private int networkTimeoutInMilli = 0; // in milliseconds

  private int authTimeout = 0;
  private boolean enableCombineDescribe = false;
  private int httpClientConnectionTimeout = 60000; // milliseconds
  private int httpClientSocketTimeout = DEFAULT_HTTP_CLIENT_SOCKET_TIMEOUT; // milliseconds
  // whether we try to simulate a socket timeout (a default value of 0 means
  // no simulation). The value is in milliseconds
  private int injectSocketTimeout = 0;
  // simulate client pause after initial execute and before first get-result
  // call ( a default value of 0 means no pause). The value is in seconds
  private int injectClientPause = 0;
  // session parameters
  private Map<String, Object> sessionParametersMap = new HashMap<>();
  private boolean passcodeInPassword = false;

  // deprecated
  private Level tracingLevel = Level.INFO;
  // client to log session metrics to telemetry in GS
  private Telemetry telemetryClient;
  private SnowflakeConnectString sfConnStr;

  // This constructor is used only by tests with no real connection.
  // For real connections, the other constructor is always used.
  @VisibleForTesting
  public SFSession() {
    this(new DefaultSFConnectionHandler(null));
  }

  public SFSession(DefaultSFConnectionHandler sfConnectionHandler) {
    super(sfConnectionHandler);
  }

  /**
   * Function that checks if the active session can be closed when the connection is closed. If
   * there are active asynchronous queries running, the session should stay open even if the
   * connection closes so that the queries can finish running.
   *
   * @return true if it is safe to close this session, false if not
   */
  @Override
  public boolean isSafeToClose() {
    boolean canClose = true;
    // if the set of asynchronous queries is empty, return true
    if (this.activeAsyncQueries.isEmpty()) {
      return canClose;
    }
    // if the set is not empty, iterate through each query and check its status
    for (String query : this.activeAsyncQueries) {
      try {
        QueryStatus qStatus = getQueryStatus(query);
        //  if any query is still running, it is not safe to close.
        if (QueryStatus.isStillRunning(qStatus)) {
          canClose = false;
        }
      } catch (SQLException e) {
        logger.error(e.getMessage(), true);
      }
    }
    return canClose;
  }

  /**
   * @param queryID query ID of the query whose status is being investigated
   * @return enum of type QueryStatus indicating the query's status
   * @throws SQLException
   */
  public QueryStatus getQueryStatus(String queryID) throws SQLException {
    // create the URL to check the query monitoring endpoint
    String statusUrl = "";
    String sessionUrl = getUrl();
    if (sessionUrl.endsWith("/")) {
      statusUrl =
          sessionUrl.substring(0, sessionUrl.length() - 1) + SF_PATH_QUERY_MONITOR + queryID;
    } else {
      statusUrl = sessionUrl + SF_PATH_QUERY_MONITOR + queryID;
    }
    // Create a new HTTP GET object and set appropriate headers

    HttpGet get = new HttpGet(statusUrl);
    String response = null;
    JsonNode jsonNode = null;
    boolean sessionRenewed;
    // Do this while the session hasn't been renewed
    do {
      sessionRenewed = false;
      try {
        get.setHeader("Content-type", "application/json");
        get.setHeader("Authorization", "Snowflake Token=\"" + this.sessionToken + "\"");
        response =
            HttpUtil.executeGeneralRequest(
                get, loginTimeout, authTimeout, httpClientSocketTimeout, 0, getHttpClientKey());
        jsonNode = OBJECT_MAPPER.readTree(response);
      } catch (Exception e) {
        throw new SnowflakeSQLLoggedException(
            this, e.getMessage(), "No response or invalid response from GET request. Error: {}");
      }

      // Get response as JSON and parse it to get the query status
      // check the success field first
      if (!jsonNode.path("success").asBoolean()) {
        logger.debug("response = {}", response);

        int errorCode = jsonNode.path("code").asInt();
        // If the error is due to an expired session token, try renewing the session and trying
        // again
        if (errorCode == Constants.SESSION_EXPIRED_GS_CODE) {
          try {
            this.renewSession(this.sessionToken);
          } catch (SnowflakeReauthenticationRequest | SFException ex) {
            // If we fail to renew the session based on a re-authentication error, try to
            // re-authenticate the session first
            if (ex instanceof SnowflakeReauthenticationRequest
                && this.isExternalbrowserAuthenticator()) {
              try {
                this.open();
              } catch (SFException e) {
                throw new SnowflakeSQLException(e);
              }
            }
            // If we reach a re-authentication error but cannot re-authenticate, throw an exception
            else if (ex instanceof SnowflakeReauthenticationRequest) {
              throw (SnowflakeSQLException) ex;
            }
            // If trying to renew the session results in an error for any other reason, throw an
            // exception
            else if (ex instanceof SFException) {
              throw new SnowflakeSQLException((SFException) ex);
            }
            throw new SnowflakeSQLException(ex.getMessage());
          }
          sessionRenewed = true;
          // If the error code was not due to session renewal issues, throw an exception
        } else {
          throw new SnowflakeSQLException(
              queryID,
              jsonNode.path("message").asText(),
              SqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION,
              errorCode);
        }
      }
    } while (sessionRenewed);
    JsonNode queryNode = jsonNode.path("data").path("queries");
    String queryStatus = "";
    String errorMessage = "";
    int errorCode = 0;
    if (queryNode.size() > 0) {
      queryStatus = queryNode.get(0).path("status").asText();
      errorMessage = queryNode.get(0).path("errorMessage").asText();
      errorCode = queryNode.get(0).path("errorCode").asInt();
    }
    logger.debug("Query status: {}", queryNode.asText());
    // Turn string with query response into QueryStatus enum and return it
    QueryStatus result = getStatusFromString(queryStatus);
    // if an error code has been provided, set appropriate error code
    if (errorCode != 0) {
      result.setErrorCode(errorCode);
    }
    // if no code was provided but query status indicates an error, set
    // code to be an internal error and set error message
    else if (isAnError(result)) {
      result.setErrorCode(ErrorCode.INTERNAL_ERROR.getMessageCode());
      result.setErrorMessage("no_error_code_from_server");
    }
    // if an error message has been provided, set appropriate error message.
    // This should override the default error message displayed when there is
    // an error with no code.
    if (!Strings.isNullOrEmpty(errorMessage) && !errorMessage.equalsIgnoreCase("null")) {
      result.setErrorMessage(errorMessage);
    }
    return result;
  }

  /**
   * Add a property If a property is known for connection, add it to connection properties If not,
   * add it as a dynamic session parameters
   *
   * <p>Make sure a property is not added more than once and the number of properties does not
   * exceed limit.
   *
   * @param propertyName property name
   * @param propertyValue property value
   * @throws SFException exception raised from Snowflake components
   */
  public void addSFSessionProperty(String propertyName, Object propertyValue) throws SFException {
    SFSessionProperty connectionProperty = SFSessionProperty.lookupByKey(propertyName);

    if (connectionProperty != null) {
      addProperty(propertyName, propertyValue);
      // check if the value type is as expected
      propertyValue = SFSessionProperty.checkPropertyValue(connectionProperty, propertyValue);

      switch (connectionProperty) {
        case LOGIN_TIMEOUT:
          if (propertyValue != null) {
            loginTimeout = (Integer) propertyValue;
          }
          break;

        case NETWORK_TIMEOUT:
          if (propertyValue != null) {
            networkTimeoutInMilli = (Integer) propertyValue;
          }
          break;

        case INJECT_CLIENT_PAUSE:
          if (propertyValue != null) {
            injectClientPause = (Integer) propertyValue;
          }
          break;

        case INJECT_SOCKET_TIMEOUT:
          if (propertyValue != null) {
            injectSocketTimeout = (Integer) propertyValue;
          }
          break;

        case PASSCODE_IN_PASSWORD:
          passcodeInPassword = (propertyValue != null && (Boolean) propertyValue);
          break;

        case TRACING:
          if (propertyValue != null) {
            tracingLevel = Level.parse(((String) propertyValue).toUpperCase());
            if (tracingLevel != null && logger instanceof JDK14Logger) {
              JDK14Logger.honorTracingParameter(tracingLevel);
            }
          }
          break;

        case DISABLE_SOCKS_PROXY:
          // note: if any session has this parameter, it will be used for all
          // sessions on the current JVM.
          if (propertyValue != null) {
            HttpUtil.setSocksProxyDisabled((Boolean) propertyValue);
          }
          break;

        case VALIDATE_DEFAULT_PARAMETERS:
          if (propertyValue != null) {
            setValidateDefaultParameters(SFLoginInput.getBooleanValue(propertyValue));
          }
          break;

        case PRIVATE_KEY_FILE:
          if (propertyValue != null) {
            privateKeyFileLocation = (String) propertyValue;
          }
          break;

        case PRIVATE_KEY_FILE_PWD:
          if (propertyValue != null) {
            privateKeyPassword = (String) propertyValue;
          }
          break;

        default:
          break;
      }
    } else {
      // this property does not match any predefined property, treat it as
      // session parameter
      if (sessionParametersMap.containsKey(propertyName)) {
        throw new SFException(ErrorCode.DUPLICATE_CONNECTION_PROPERTY_SPECIFIED, propertyName);
      } else {
        sessionParametersMap.put(propertyName, propertyValue);
      }

      // check if the number of session properties exceed limit
      if (sessionParametersMap.size() > MAX_SESSION_PARAMETERS) {
        throw new SFException(ErrorCode.TOO_MANY_SESSION_PARAMETERS, MAX_SESSION_PARAMETERS);
      }
    }
  }

  public boolean containProperty(String key) {
    return sessionParametersMap.containsKey(key);
  }

  /**
   * Open a new database session
   *
   * @throws SFException this is a runtime exception
   * @throws SnowflakeSQLException exception raised from Snowflake components
   */
  public synchronized void open() throws SFException, SnowflakeSQLException {
    performSanityCheckOnProperties();
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    logger.debug(
        "input: server={}, account={}, user={}, password={}, role={}, database={}, schema={},"
            + " warehouse={}, validate_default_parameters={}, authenticator={}, ocsp_mode={},"
            + " passcode_in_password={}, passcode={}, private_key={}, disable_socks_proxy={},"
            + " application={}, app_id={}, app_version={}, login_timeout={}, network_timeout={},"
            + " query_timeout={}, tracing={}, private_key_file={}, private_key_file_pwd={}."
            + " session_parameters: client_store_temporary_credential={}",
        connectionPropertiesMap.get(SFSessionProperty.SERVER_URL),
        connectionPropertiesMap.get(SFSessionProperty.ACCOUNT),
        connectionPropertiesMap.get(SFSessionProperty.USER),
        !Strings.isNullOrEmpty((String) connectionPropertiesMap.get(SFSessionProperty.PASSWORD))
            ? "***"
            : "(empty)",
        connectionPropertiesMap.get(SFSessionProperty.ROLE),
        connectionPropertiesMap.get(SFSessionProperty.DATABASE),
        connectionPropertiesMap.get(SFSessionProperty.SCHEMA),
        connectionPropertiesMap.get(SFSessionProperty.WAREHOUSE),
        connectionPropertiesMap.get(SFSessionProperty.VALIDATE_DEFAULT_PARAMETERS),
        connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR),
        getOCSPMode().name(),
        connectionPropertiesMap.get(SFSessionProperty.PASSCODE_IN_PASSWORD),
        !Strings.isNullOrEmpty((String) connectionPropertiesMap.get(SFSessionProperty.PASSCODE))
            ? "***"
            : "(empty)",
        connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY) != null
            ? "(not null)"
            : "(null)",
        connectionPropertiesMap.get(SFSessionProperty.DISABLE_SOCKS_PROXY),
        connectionPropertiesMap.get(SFSessionProperty.APPLICATION),
        connectionPropertiesMap.get(SFSessionProperty.APP_ID),
        connectionPropertiesMap.get(SFSessionProperty.APP_VERSION),
        connectionPropertiesMap.get(SFSessionProperty.LOGIN_TIMEOUT),
        connectionPropertiesMap.get(SFSessionProperty.NETWORK_TIMEOUT),
        connectionPropertiesMap.get(SFSessionProperty.QUERY_TIMEOUT),
        connectionPropertiesMap.get(SFSessionProperty.TRACING),
        connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY_FILE),
        !Strings.isNullOrEmpty(
                (String) connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY_FILE_PWD))
            ? "***"
            : "(empty)",
        sessionParametersMap.get(CLIENT_STORE_TEMPORARY_CREDENTIAL));

    HttpClientSettingsKey httpClientSettingsKey = getHttpClientKey();
    logger.debug(
        "connection proxy parameters: use_proxy={}, proxy_host={}, proxy_port={}, proxy_user={},"
            + " proxy_password={}, non_proxy_hosts={}, proxy_protocol={}",
        httpClientSettingsKey.usesProxy(),
        httpClientSettingsKey.getProxyHost(),
        httpClientSettingsKey.getProxyPort(),
        httpClientSettingsKey.getProxyUser(),
        !Strings.isNullOrEmpty(httpClientSettingsKey.getProxyPassword()) ? "***" : "(empty)",
        httpClientSettingsKey.getNonProxyHosts(),
        httpClientSettingsKey.getProxyProtocol());

    // TODO: temporarily hardcode sessionParameter debug info. will be changed in the future
    SFLoginInput loginInput = new SFLoginInput();

    loginInput
        .setServerUrl((String) connectionPropertiesMap.get(SFSessionProperty.SERVER_URL))
        .setDatabaseName((String) connectionPropertiesMap.get(SFSessionProperty.DATABASE))
        .setSchemaName((String) connectionPropertiesMap.get(SFSessionProperty.SCHEMA))
        .setWarehouse((String) connectionPropertiesMap.get(SFSessionProperty.WAREHOUSE))
        .setRole((String) connectionPropertiesMap.get(SFSessionProperty.ROLE))
        .setValidateDefaultParameters(
            connectionPropertiesMap.get(SFSessionProperty.VALIDATE_DEFAULT_PARAMETERS))
        .setAuthenticator((String) connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR))
        .setOKTAUserName((String) connectionPropertiesMap.get(SFSessionProperty.OKTA_USERNAME))
        .setAccountName((String) connectionPropertiesMap.get(SFSessionProperty.ACCOUNT))
        .setLoginTimeout(loginTimeout)
        .setAuthTimeout(authTimeout)
        .setUserName((String) connectionPropertiesMap.get(SFSessionProperty.USER))
        .setPassword((String) connectionPropertiesMap.get(SFSessionProperty.PASSWORD))
        .setToken((String) connectionPropertiesMap.get(SFSessionProperty.TOKEN))
        .setPasscodeInPassword(passcodeInPassword)
        .setPasscode((String) connectionPropertiesMap.get(SFSessionProperty.PASSCODE))
        .setConnectionTimeout(httpClientConnectionTimeout)
        .setSocketTimeout(httpClientSocketTimeout)
        .setAppId((String) connectionPropertiesMap.get(SFSessionProperty.APP_ID))
        .setAppVersion((String) connectionPropertiesMap.get(SFSessionProperty.APP_VERSION))
        .setSessionParameters(sessionParametersMap)
        .setPrivateKey((PrivateKey) connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY))
        .setPrivateKeyFile((String) connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY_FILE))
        .setPrivateKeyFilePwd(
            (String) connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY_FILE_PWD))
        .setApplication((String) connectionPropertiesMap.get(SFSessionProperty.APPLICATION))
        .setServiceName(getServiceName())
        .setOCSPMode(getOCSPMode())
        .setHttpClientSettingsKey(httpClientSettingsKey);

    // propagate OCSP mode to SFTrustManager. Note OCSP setting is global on JVM.
    HttpUtil.initHttpClient(httpClientSettingsKey, null);
    SFLoginOutput loginOutput =
        SessionUtil.openSession(loginInput, connectionPropertiesMap, tracingLevel.toString());
    isClosed = false;

    authTimeout = loginInput.getAuthTimeout();
    sessionToken = loginOutput.getSessionToken();
    masterToken = loginOutput.getMasterToken();
    idToken = loginOutput.getIdToken();
    mfaToken = loginOutput.getMfaToken();
    setDatabaseVersion(loginOutput.getDatabaseVersion());
    setDatabaseMajorVersion(loginOutput.getDatabaseMajorVersion());
    setDatabaseMinorVersion(loginOutput.getDatabaseMinorVersion());
    httpClientSocketTimeout = loginOutput.getHttpClientSocketTimeout();
    masterTokenValidityInSeconds = loginOutput.getMasterTokenValidityInSeconds();
    setDatabase(loginOutput.getSessionDatabase());
    setSchema(loginOutput.getSessionSchema());
    setRole(loginOutput.getSessionRole());
    setWarehouse(loginOutput.getSessionWarehouse());
    setSessionId(loginOutput.getSessionId());
    setAutoCommit(loginOutput.getAutoCommit());

    // Update common parameter values for this session
    SessionUtil.updateSfDriverParamValues(loginOutput.getCommonParams(), this);

    String loginDatabaseName = (String) connectionPropertiesMap.get(SFSessionProperty.DATABASE);
    String loginSchemaName = (String) connectionPropertiesMap.get(SFSessionProperty.SCHEMA);
    String loginRole = (String) connectionPropertiesMap.get(SFSessionProperty.ROLE);
    String loginWarehouse = (String) connectionPropertiesMap.get(SFSessionProperty.WAREHOUSE);

    if (loginDatabaseName != null && !loginDatabaseName.equalsIgnoreCase(getDatabase())) {
      sqlWarnings.add(
          new SFException(
              ErrorCode.CONNECTION_ESTABLISHED_WITH_DIFFERENT_PROP,
              "Database",
              loginDatabaseName,
              getDatabase()));
    }

    if (loginSchemaName != null && !loginSchemaName.equalsIgnoreCase(getSchema())) {
      sqlWarnings.add(
          new SFException(
              ErrorCode.CONNECTION_ESTABLISHED_WITH_DIFFERENT_PROP,
              "Schema",
              loginSchemaName,
              getSchema()));
    }

    if (loginRole != null && !loginRole.equalsIgnoreCase(getRole())) {
      sqlWarnings.add(
          new SFException(
              ErrorCode.CONNECTION_ESTABLISHED_WITH_DIFFERENT_PROP, "Role", loginRole, getRole()));
    }

    if (loginWarehouse != null && !loginWarehouse.equalsIgnoreCase(getWarehouse())) {
      sqlWarnings.add(
          new SFException(
              ErrorCode.CONNECTION_ESTABLISHED_WITH_DIFFERENT_PROP,
              "Warehouse",
              loginWarehouse,
              getWarehouse()));
    }

    // start heartbeat for this session so that the master token will not expire
    startHeartbeatForThisSession();
  }

  /**
   * If authenticator is null and private key is specified, jdbc will assume key pair authentication
   *
   * @return true if authenticator type is SNOWFLAKE (meaning password)
   */
  private boolean isSnowflakeAuthenticator() {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    String authenticator = (String) connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR);
    PrivateKey privateKey = (PrivateKey) connectionPropertiesMap.get(SFSessionProperty.PRIVATE_KEY);
    return (authenticator == null && privateKey == null && privateKeyFileLocation == null)
        || ClientAuthnDTO.AuthenticatorType.SNOWFLAKE.name().equalsIgnoreCase(authenticator);
  }

  /**
   * Returns true If authenticator is EXTERNALBROWSER.
   *
   * @return true if authenticator type is EXTERNALBROWSER
   */
  boolean isExternalbrowserAuthenticator() {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    String authenticator = (String) connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR);
    return ClientAuthnDTO.AuthenticatorType.EXTERNALBROWSER.name().equalsIgnoreCase(authenticator);
  }

  /**
   * Returns true if authenticator is OKTA native
   *
   * @return true or false
   */
  boolean isOKTAAuthenticator() {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    String authenticator = (String) connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR);
    return !Strings.isNullOrEmpty(authenticator) && authenticator.startsWith("https://");
  }

  /**
   * Returns true if authenticator is UsernamePasswordMFA native
   *
   * @return true or false
   */
  boolean isUsernamePasswordMFAAuthenticator() {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    String authenticator = (String) connectionPropertiesMap.get(SFSessionProperty.AUTHENTICATOR);
    return ClientAuthnDTO.AuthenticatorType.USERNAME_PASSWORD_MFA
        .name()
        .equalsIgnoreCase(authenticator);
  }

  /**
   * A helper function to call global service and renew session.
   *
   * @param prevSessionToken the session token that has expired
   * @throws SnowflakeSQLException if failed to renew the session
   * @throws SFException if failed to renew the session
   */
  synchronized void renewSession(String prevSessionToken)
      throws SFException, SnowflakeSQLException {
    if (sessionToken != null && !sessionToken.equals(prevSessionToken)) {
      logger.debug("not renew session because session token has not been updated.", false);
      return;
    }

    SFLoginInput loginInput = new SFLoginInput();
    loginInput
        .setServerUrl(getServerUrl())
        .setSessionToken(sessionToken)
        .setMasterToken(masterToken)
        .setIdToken(idToken)
        .setMfaToken(mfaToken)
        .setLoginTimeout(loginTimeout)
        .setDatabaseName(getDatabase())
        .setSchemaName(getSchema())
        .setRole(getRole())
        .setWarehouse(getWarehouse())
        .setOCSPMode(getOCSPMode())
        .setHttpClientSettingsKey(getHttpClientKey());

    SFLoginOutput loginOutput = SessionUtil.renewSession(loginInput);

    sessionToken = loginOutput.getSessionToken();
    masterToken = loginOutput.getMasterToken();
  }

  /**
   * get session token
   *
   * @return session token
   */
  public String getSessionToken() {
    return sessionToken;
  }

  /**
   * Close the connection
   *
   * @throws SnowflakeSQLException if failed to close the connection
   * @throws SFException if failed to close the connection
   */
  @Override
  public void close() throws SFException, SnowflakeSQLException {
    logger.debug(" public void close()", false);

    // stop heartbeat for this session
    stopHeartbeatForThisSession();

    if (isClosed) {
      return;
    }

    SFLoginInput loginInput = new SFLoginInput();
    loginInput
        .setServerUrl(getServerUrl())
        .setSessionToken(sessionToken)
        .setLoginTimeout(loginTimeout)
        .setOCSPMode(getOCSPMode())
        .setHttpClientSettingsKey(getHttpClientKey());

    SessionUtil.closeSession(loginInput);
    closeTelemetryClient();
    getClientInfo().clear();
    isClosed = true;
  }

  /**
   * Makes a heartbeat call to check for session validity.
   *
   * @param timeout the query timeout
   * @throws Exception if an error occurs
   * @throws SFException exception raised from Snowflake
   */
  public void callHeartBeat(int timeout) throws Exception, SFException {
    if (timeout > 0) {
      callHeartBeatWithQueryTimeout(timeout);
    } else {
      heartbeat();
    }
  }

  /**
   * Makes a heartbeat call with query timeout to check for session validity.
   *
   * @param timeout the query timeout
   * @throws Exception if an error occurs
   * @throws SFException exception raised from Snowflake
   */
  private void callHeartBeatWithQueryTimeout(int timeout) throws Exception, SFException {
    class HeartbeatTask implements Callable<Void> {

      @Override
      public Void call() throws SQLException {
        try {
          heartbeat();
        } catch (SFException e) {
          throw new SnowflakeSQLException(e, e.getSqlState(), e.getVendorCode(), e.getParams());
        }
        return null;
      }
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Void> future = executor.submit(new HeartbeatTask());

    // Cancel the heartbeat call when timeout is reached
    try {
      future.get(timeout, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new SFException(ErrorCode.QUERY_CANCELED);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Start heartbeat for this session */
  protected void startHeartbeatForThisSession() {
    if (getEnableHeartbeat() && !Strings.isNullOrEmpty(masterToken)) {
      logger.debug("start heartbeat, master token validity: " + masterTokenValidityInSeconds);

      HeartbeatBackground.getInstance()
          .addSession(this, masterTokenValidityInSeconds, heartbeatFrequency);
    } else {
      logger.debug("heartbeat not enabled for the session", false);
    }
  }

  /** Stop heartbeat for this session */
  protected void stopHeartbeatForThisSession() {
    if (getEnableHeartbeat() && !Strings.isNullOrEmpty(masterToken)) {
      logger.debug("stop heartbeat", false);

      HeartbeatBackground.getInstance().removeSession(this);
    } else {
      logger.debug("heartbeat not enabled for the session", false);
    }
  }

  /**
   * Send heartbeat for the session
   *
   * @throws SFException exception raised from Snowflake
   * @throws SQLException exception raised from SQL generic layers
   */
  protected void heartbeat() throws SFException, SQLException {
    logger.debug(" public void heartbeat()", false);

    if (isClosed) {
      return;
    }

    HttpPost postRequest = null;

    String requestId = UUID.randomUUID().toString();

    boolean retry = false;

    // the loop for retrying if it runs into session expiration
    do {
      try {
        URIBuilder uriBuilder;

        uriBuilder = new URIBuilder(getServerUrl());

        uriBuilder.addParameter(SFSession.SF_QUERY_REQUEST_ID, requestId);

        uriBuilder.setPath(SF_PATH_SESSION_HEARTBEAT);

        postRequest = new HttpPost(uriBuilder.build());

        // remember the session token in case it expires we need to renew
        // the session only when no other thread has renewed it
        String prevSessionToken = sessionToken;

        postRequest.setHeader(
            SF_HEADER_AUTHORIZATION,
            SF_HEADER_SNOWFLAKE_AUTHTYPE
                + " "
                + SF_HEADER_TOKEN_TAG
                + "=\""
                + prevSessionToken
                + "\"");

        logger.debug("Executing heartbeat request: {}", postRequest.toString());

        // the following will retry transient network issues
        // increase heartbeat timeout from 60 sec to 300 sec
        // per https://support-snowflake.zendesk.com/agent/tickets/6629
        int SF_HEARTBEAT_TIMEOUT = 300;
        String theResponse =
            HttpUtil.executeGeneralRequest(
                postRequest,
                SF_HEARTBEAT_TIMEOUT,
                authTimeout,
                httpClientSocketTimeout,
                0,
                getHttpClientKey());

        JsonNode rootNode;

        logger.debug("connection heartbeat response: {}", theResponse);

        rootNode = mapper.readTree(theResponse);

        // check the response to see if it is session expiration response
        if (rootNode != null
            && (Constants.SESSION_EXPIRED_GS_CODE == rootNode.path("code").asInt())) {
          logger.debug("renew session and retry", false);
          this.renewSession(prevSessionToken);
          retry = true;
          continue;
        }

        SnowflakeUtil.checkErrorAndThrowException(rootNode);

        // success
        retry = false;
      } catch (Throwable ex) {
        // for snowflake exception, just rethrow it
        if (ex instanceof SnowflakeSQLException) {
          throw (SnowflakeSQLException) ex;
        }

        logger.error("unexpected exception", ex);

        throw new SFException(
            ErrorCode.INTERNAL_ERROR, IncidentUtil.oneLiner("unexpected exception", ex));
      }
    } while (retry);
  }

  void injectedDelay() {

    AtomicInteger injectedDelay = getInjectedDelay();
    int d = injectedDelay.get();

    if (d != 0) {
      injectedDelay.set(0);
      try {
        logger.trace("delayed for {}", d);

        Thread.sleep(d);
      } catch (InterruptedException ex) {
      }
    }
  }

  public int getInjectSocketTimeout() {
    return injectSocketTimeout;
  }

  public void setInjectSocketTimeout(int injectSocketTimeout) {
    this.injectSocketTimeout = injectSocketTimeout;
  }

  public int getNetworkTimeoutInMilli() {
    return networkTimeoutInMilli;
  }

  public int getAuthTimeout() {
    return authTimeout;
  }

  public int getHttpClientSocketTimeout() {
    return httpClientSocketTimeout;
  }

  public int getHttpClientConnectionTimeout() {
    return httpClientConnectionTimeout;
  }

  public boolean isClosed() {
    return isClosed;
  }

  public int getInjectClientPause() {
    return injectClientPause;
  }

  public void setInjectClientPause(int injectClientPause) {
    this.injectClientPause = injectClientPause;
  }

  protected int getAndIncrementSequenceId() {
    return sequenceId.getAndIncrement();
  }

  public boolean getEnableCombineDescribe() {
    return this.enableCombineDescribe;
  }

  public void setEnableCombineDescribe(boolean enable) {
    this.enableCombineDescribe = enable;
  }

  @Override
  public synchronized Telemetry getTelemetryClient() {
    // initialize for the first time. this should only be done after session
    // properties have been set, else the client won't properly resolve the URL.
    if (telemetryClient == null) {
      if (getUrl() == null) {
        logger.error("Telemetry client created before session properties set.", false);
        return null;
      }
      telemetryClient = TelemetryClient.createTelemetry(this);
    }
    return telemetryClient;
  }

  public void closeTelemetryClient() {
    if (telemetryClient != null) {
      telemetryClient.close();
    }
  }

  public String getIdToken() {
    return idToken;
  }

  public String getMfaToken() {
    return mfaToken;
  }

  /**
   * Sets the current objects if the session is not up to date. It can happen if the session is
   * created by the id token, which doesn't carry the current objects.
   *
   * @param loginInput The login input to use for this session
   * @param loginOutput The login output to ose for this session
   */
  void setCurrentObjects(SFLoginInput loginInput, SFLoginOutput loginOutput) {
    this.sessionToken = loginOutput.getSessionToken(); // used to run the commands.
    runInternalCommand("USE ROLE IDENTIFIER(?)", loginInput.getRole());
    runInternalCommand("USE WAREHOUSE IDENTIFIER(?)", loginInput.getWarehouse());
    runInternalCommand("USE DATABASE IDENTIFIER(?)", loginInput.getDatabaseName());
    runInternalCommand("USE SCHEMA IDENTIFIER(?)", loginInput.getSchemaName());
    // This ensures the session returns the current objects and refresh
    // the local cache.
    SFBaseResultSet result = runInternalCommand("SELECT ?", "1");

    // refresh the current objects
    loginOutput.setSessionDatabase(getDatabase());
    loginOutput.setSessionSchema(getSchema());
    loginOutput.setSessionWarehouse(getWarehouse());
    loginOutput.setSessionRole(getRole());
    loginOutput.setIdToken(loginInput.getIdToken());

    // no common parameter is updated.
    if (result != null) {
      loginOutput.setCommonParams(result.parameters);
    }
  }

  private SFBaseResultSet runInternalCommand(String sql, String value) {
    if (value == null) {
      return null;
    }

    try {
      Map<String, ParameterBindingDTO> bindValues = new HashMap<>();
      bindValues.put("1", new ParameterBindingDTO("TEXT", value));
      SFStatement statement = new SFStatement(this);
      return statement.executeQueryInternal(
          sql,
          bindValues,
          false, // not describe only
          true, // internal
          false, // asyncExec
          null // caller isn't a JDBC interface method
          );
    } catch (SFException | SQLException ex) {
      logger.debug("Failed to run a command: {}, err={}", sql, ex);
    }
    return null;
  }

  public SnowflakeConnectString getSnowflakeConnectionString() {
    return sfConnStr;
  }

  public void setSnowflakeConnectionString(SnowflakeConnectString connStr) {
    sfConnStr = connStr;
  }

  /**
   * Performs a sanity check on properties. Sanity checking includes: - verifying that a server url
   * is present - verifying various combinations of properties given the authenticator
   *
   * @throws SFException Will be thrown if any of the necessary properties are missing
   */
  private void performSanityCheckOnProperties() throws SFException {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();

    for (SFSessionProperty property : SFSessionProperty.values()) {
      if (property.isRequired() && !connectionPropertiesMap.containsKey(property)) {
        switch (property) {
          case SERVER_URL:
            throw new SFException(ErrorCode.MISSING_SERVER_URL);

          default:
            throw new SFException(ErrorCode.MISSING_CONNECTION_PROPERTY, property.getPropertyKey());
        }
      }
    }

    if (isSnowflakeAuthenticator()
        || isOKTAAuthenticator()
        || isUsernamePasswordMFAAuthenticator()) {
      // userName and password are expected for both Snowflake and Okta.
      String userName = (String) connectionPropertiesMap.get(SFSessionProperty.USER);
      if (Strings.isNullOrEmpty(userName)) {
        throw new SFException(ErrorCode.MISSING_USERNAME);
      }

      String password = (String) connectionPropertiesMap.get(SFSessionProperty.PASSWORD);
      if (Strings.isNullOrEmpty(password)) {

        throw new SFException(ErrorCode.MISSING_PASSWORD);
      }
    }

    // perform sanity check on proxy settings
    boolean useProxy =
        (boolean) connectionPropertiesMap.getOrDefault(SFSessionProperty.USE_PROXY, false);
    if (useProxy) {
      if (!connectionPropertiesMap.containsKey(SFSessionProperty.PROXY_HOST)
          || connectionPropertiesMap.get(SFSessionProperty.PROXY_HOST) == null
          || ((String) connectionPropertiesMap.get(SFSessionProperty.PROXY_HOST)).isEmpty()
          || !connectionPropertiesMap.containsKey(SFSessionProperty.PROXY_PORT)
          || connectionPropertiesMap.get(SFSessionProperty.PROXY_HOST) == null) {
        throw new SFException(
            ErrorCode.INVALID_PROXY_PROPERTIES, "Both proxy host and port values are needed.");
      }
    }
  }

  @Override
  public List<DriverPropertyInfo> checkProperties() {
    Map<SFSessionProperty, Object> connectionPropertiesMap = getConnectionPropertiesMap();
    for (SFSessionProperty property : SFSessionProperty.values()) {
      if (property.isRequired() && !connectionPropertiesMap.containsKey(property)) {
        missingProperties.add(addNewDriverProperty(property.getPropertyKey(), null));
      }
    }
    if (isSnowflakeAuthenticator() || isOKTAAuthenticator()) {
      // userName and password are expected for both Snowflake and Okta.
      String userName = (String) connectionPropertiesMap.get(SFSessionProperty.USER);
      if (Strings.isNullOrEmpty(userName)) {
        missingProperties.add(
            addNewDriverProperty(SFSessionProperty.USER.getPropertyKey(), "username for account"));
      }

      String password = (String) connectionPropertiesMap.get(SFSessionProperty.PASSWORD);
      if (Strings.isNullOrEmpty(password)) {
        missingProperties.add(
            addNewDriverProperty(
                SFSessionProperty.PASSWORD.getPropertyKey(), "password for " + "account"));
      }
    }

    boolean useProxy =
        (boolean) connectionPropertiesMap.getOrDefault(SFSessionProperty.USE_PROXY, false);
    if (useProxy) {
      if (!connectionPropertiesMap.containsKey(SFSessionProperty.PROXY_HOST)) {
        missingProperties.add(
            addNewDriverProperty(SFSessionProperty.PROXY_HOST.getPropertyKey(), "proxy host name"));
      }
      if (!connectionPropertiesMap.containsKey(SFSessionProperty.PROXY_PORT)) {
        missingProperties.add(
            addNewDriverProperty(
                SFSessionProperty.PROXY_PORT.getPropertyKey(),
                "proxy port; " + "should be an integer"));
      }
    }
    return missingProperties;
  }

  private DriverPropertyInfo addNewDriverProperty(String name, String description) {
    DriverPropertyInfo info = new DriverPropertyInfo(name, null);
    info.description = description;
    return info;
  }

  /** @return whether this session uses async queries */
  public boolean isAsyncSession() {
    return !activeAsyncQueries.isEmpty();
  }
}
