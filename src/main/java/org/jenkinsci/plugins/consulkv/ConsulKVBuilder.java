package org.jenkinsci.plugins.consulkv;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.consulkv.common.Constants;
import org.jenkinsci.plugins.consulkv.common.DebugMode;
import org.jenkinsci.plugins.consulkv.common.RequestMode;
import org.jenkinsci.plugins.consulkv.common.VariableInjectionAction;
import org.jenkinsci.plugins.consulkv.common.exceptions.ConsulRequestException;
import org.jenkinsci.plugins.consulkv.common.exceptions.ValidationException;
import org.jenkinsci.plugins.consulkv.common.utils.ConsulRequestUtils;
import org.jenkinsci.plugins.consulkv.common.utils.Strings;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Jenkins Plugin to READ/WRITE/DELETE K/V pairs from/to a Consul cluster, and set a build ENV variable to
 * be used by downstream build steps.
 *
 * @author Jimmy Ray
 * @version 2.0.0
 */
public class ConsulKVBuilder extends Builder implements SimpleBuildStep {
    private static Logger LOGGER = Logger.getLogger(ConsulKVBuilder.class.getName());

    private String hostUrl;
    private String key;
    private String aclToken;
    private String keyValue;
    private String apiUri;
    private String envVarKey;
    private RequestMode requestMode;
    private int timeoutConnection;
    private int timeoutResponse;
    private DebugMode debugMode;
    private boolean ignoreGlobalSettings;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @Deprecated
    public ConsulKVBuilder(String aclToken, String hostUrl, String key, String keyValue, String apiUri, String
            envVarKey, RequestMode requestMode, Integer timeoutConnection, Integer timeoutResponse, DebugMode
                                   debugMode, boolean ignoreGlobalSettings) {
        this.aclToken = aclToken;
        this.hostUrl = hostUrl;
        this.key = key;
        this.keyValue = keyValue;
        this.apiUri = apiUri;
        this.envVarKey = envVarKey;
        this.requestMode = requestMode;
        this.timeoutConnection = timeoutConnection;
        this.timeoutResponse = timeoutResponse;
        this.debugMode = debugMode;
        this.ignoreGlobalSettings = ignoreGlobalSettings;
    }

    @DataBoundConstructor
    public ConsulKVBuilder(@CheckForNull String hostUrl, @CheckForNull String key) {
        this.hostUrl = hostUrl;
        this.key = key;
    }

    public String getAclToken() {
        return this.aclToken;
    }

    @DataBoundSetter
    public void setAclToken(@CheckForNull String aclToken) {
        this.aclToken = aclToken;
    }

    public String getHostUrl() {
        return this.hostUrl;
    }

    public String getKey() {
        return this.key;
    }

    public String getKeyValue() {
        return this.keyValue;
    }

    @DataBoundSetter
    public void setKeyValue(@CheckForNull String keyValue) {
        this.keyValue = keyValue;
    }

    public String getApiUri() {
        return this.apiUri;
    }

    @DataBoundSetter
    public void setApiUri(@CheckForNull String apiUri) {
        this.apiUri = apiUri;
    }

    public String getEnvVarKey() {
        return this.envVarKey;
    }

    @DataBoundSetter
    public void setEnvVarKey(@CheckForNull String envVarKey) {
        this.envVarKey = envVarKey;
    }

    public RequestMode getRequestMode() {
        return this.requestMode;
    }

    @DataBoundSetter
    public void setRequestMode(@CheckForNull RequestMode requestMode) {
        this.requestMode = requestMode;
    }

    public int getTimeoutConnection() {
        return this.timeoutConnection;
    }

    @DataBoundSetter
    public void setTimeoutConnection(int timeoutConnection) {
        this.timeoutConnection = timeoutConnection;
    }

    public int getTimeoutResponse() {
        return this.timeoutResponse;
    }

    @DataBoundSetter
    public void setTimeoutResponse(int timeoutResponse) {
        this.timeoutResponse = timeoutResponse;
    }

    public DebugMode getDebugMode() {
        return this.debugMode;
    }

    @DataBoundSetter
    public void setDebugMode(@CheckForNull DebugMode debugMode) {
        this.debugMode = debugMode;
    }

    public boolean isIgnoreGlobalSettings() {
        return this.ignoreGlobalSettings;
    }

    @DataBoundSetter
    public void setIgnoreGlobalSettings(boolean ignoreGlobalSettings) {
        this.ignoreGlobalSettings = ignoreGlobalSettings;
    }

    /**
     * Perform the work of the build step
     *
     * @param build
     * @param workspace
     * @param launcher
     * @param listener
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull
    TaskListener listener) throws InterruptedException, IOException {

        final PrintStream logger = listener.getLogger();
        final EnvVars environment = build.getEnvironment(listener);

        try {
            if (!this.ignoreGlobalSettings) {
                //Try to use global settings and backup from constants.
                this.updateFromGlobalConfiguration();

                if (Strings.isEmpty(this.hostUrl)) {
                    throw new ConsulRequestException("Global settings host URL was not found.");
                }
            }

            int timeoutConn = (this.timeoutConnection == 0) ? Constants
                    .TIMEOUT_CONNECTION : this.timeoutConnection;
            int timeoutResp = (this.timeoutResponse == 0) ? Constants
                    .TIMEOUT_RESPONSE : this.timeoutResponse;

            String expandedUrl = environment.expand(this.hostUrl);
            StringBuilder urlStringBuilder = new StringBuilder(expandedUrl);
            String apiUrl = null;

            if (Strings.isEmpty(this.apiUri)) {
                if (this.debugMode.equals(DebugMode.ENABLED)) {
                    logger.println(String.format("Using default API URI:  %s", Constants.API_URI));
                }
                this.apiUri = Constants.API_URI;
            }

            apiUrl = environment.expand(apiUri);

            String responseRaw = null;
            String expandedKey = environment.expand(this.key);

            if (Strings.isEmpty(this.aclToken)) {
                //No token
                urlStringBuilder.append(apiUrl).append(expandedKey);
            } else {

                String expandedToken = environment.expand(this.aclToken);
                String formattedTokenString = String.format(Constants.TOKEN_URL_PATTERN, expandedToken);
                urlStringBuilder.append(apiUrl).append(expandedKey).append(formattedTokenString);
            }

            if (this.debugMode.equals(DebugMode.ENABLED)) {
                logger.println("Consul " + this.requestMode.name() + " URL:  " + urlStringBuilder.toString());
            }

            if (this.requestMode.equals(RequestMode.READ)) {
                //Read
                ConsulRequest consulRequest = ConsulRequestFactory.request().withUrl(urlStringBuilder.toString())
                        .withTimeoutConnect
                                (timeoutConn).withTimeoutResponse(timeoutResp).withDebugMode(debugMode).withRequestMode
                                (requestMode).withLogger(logger).build();

                responseRaw = ConsulRequestUtils.read(consulRequest);
                String value =
                        ConsulRequestUtils.decodeValue(ConsulRequestUtils.parseJson(responseRaw, Constants.FIELD_VALUE));
                logger.println(String.format("Consul K/V pair:  %s=%s", this.key, value));

                //Set ENV Variable
                String expandedEnvVarKey = environment.expand(this.envVarKey);
                String storageKey = Strings.normalizeStoragekey(expandedEnvVarKey);
                environment.addLine(String.format("%s=%s", storageKey, value));

                logger.println(String.format("Stored ENV variable (k,v):  %s=%s", storageKey, environment.get(storageKey)));
            } else if (this.requestMode.equals(RequestMode.WRITE)) {
                //Write
                String expandedKeyValue = environment.expand(this.keyValue);
                ConsulRequest consulRequest = ConsulRequestFactory.request().withUrl(urlStringBuilder.toString())
                        .withValue(expandedKeyValue)
                        .withTimeoutConnect(timeoutConn).withTimeoutResponse(timeoutResp).withDebugMode(debugMode)
                        .withRequestMode(requestMode).withLogger(logger).build();

                responseRaw = ConsulRequestUtils.write(consulRequest);
            } else {
                //Delete
                ConsulRequest consulRequest = ConsulRequestFactory.request().withUrl(urlStringBuilder.toString())
                        .withTimeoutConnect
                                (timeoutConn).withTimeoutResponse(timeoutResp).withDebugMode(debugMode).withRequestMode
                                (requestMode).withLogger(logger).build();

                responseRaw = ConsulRequestUtils.delete(consulRequest);
            }

            if (this.debugMode.equals(DebugMode.ENABLED)) {
                logger.printf("Raw content:  %s%n", responseRaw);
            }

        } catch (IOException ioe) {
            build.setResult(Result.FAILURE);
            listener.fatalError("IO exception was detected:  %s%n", ioe);
        } catch (ValidationException ve) {
            build.setResult(Result.FAILURE);
            listener.fatalError("Validation exception was detected:  %s%n", ve);
        } catch (ConsulRequestException cre) {
            build.setResult(Result.FAILURE);
            listener.fatalError("Consul request exception was detected:  %s%n", cre);
        }

    }

    private DescriptorImpl getDescriptorImpl() {
        return ((DescriptorImpl) getDescriptor());
    }

    /*
     * Loads global settings from <code>GlobalConsulConfig</code>
     */
    private void updateFromGlobalConfiguration() {
        Jenkins jenkins = Jenkins.getInstance();

        if (jenkins !=  null) {
            GlobalConsulConfig.DescriptorImpl globalDescriptor = (GlobalConsulConfig.DescriptorImpl)
                    jenkins.getDescriptor(GlobalConsulConfig.class);

            if (globalDescriptor != null) {
                this.hostUrl = globalDescriptor.getConsulHostUrl();
                this.apiUri = globalDescriptor.getConsulApiUri();
                this.aclToken = globalDescriptor.getConsulAclToken();
                this.timeoutConnection = globalDescriptor.getConsulTimeoutConnection();
                this.timeoutResponse = globalDescriptor.getConsulTimeoutResponse();
                this.debugMode = globalDescriptor.getConsulDebugMode();
            } else {
                LOGGER.warning("Could not load global settings.");
            }
        } else {
            LOGGER.warning("Could not load global settings.");
        }
    }

    @Override
    public String toString() {
        return "ConsulKVBuilder{" +
                "hostUrl='" + hostUrl + '\'' +
                ", key='" + key + '\'' +
                ", token='" + aclToken + '\'' +
                ", keyValue='" + keyValue + '\'' +
                ", apiURi='" + apiUri + '\'' +
                ", envVarKey='" + envVarKey + '\'' +
                ", requestMode=" + requestMode +
                ", timeoutConnection=" + timeoutConnection +
                ", timeoutResponse=" + timeoutResponse +
                ", debugMode=" + debugMode +
                ", ignoreGlobalSettings=" + ignoreGlobalSettings +
                '}';
    }

    /**
     * Descriptor for {@link ConsulKVBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * <p>
     * <p>
     * See <tt>src/main/resources/hudson/plugins/ConsulKVBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private String key;
        private String hostUrl;
        private String aclToken;
        private String apiUri;
        private int timeoutConnection;
        private int timeoutResponse;
        private DebugMode debugMode;
        private boolean ignoreGlobalSettings;

        public DescriptorImpl() {
            load();
        }

        public String getHostUrl() {
            return hostUrl;
        }

        public void setHostUrl(String hostUrl) {
            this.hostUrl = hostUrl;
        }

        public String getAclToken() {
            return aclToken;
        }

        public void setAclToken(String aclToken) {
            this.aclToken = aclToken;
        }

        public String getApiUri() {
            return apiUri;
        }

        public void setApiUri(String apiUri) {
            this.apiUri = apiUri;
        }

        public int getTimeoutConnection() {
            return timeoutConnection;
        }

        public void setTimeoutConnection(int timeoutConnection) {
            this.timeoutConnection = timeoutConnection;
        }

        public int getTimeoutResponse() {
            return timeoutResponse;
        }

        public void setTimeoutResponse(int timeoutResponse) {
            this.timeoutResponse = timeoutResponse;
        }

        public ListBoxModel doFillRequestModeItems() {
            return RequestMode.getFillItems();
        }

        public ListBoxModel doFillDefaultRequestModeItems() {
            return RequestMode.getFillItems();
        }

        public ListBoxModel doFillDebugModeItems() {
            return DebugMode.getFillItems();
        }

        public ListBoxModel doFillDefaultDebugModeItems() {
            return DebugMode.getFillItems();
        }

        public RequestMode getDefaultRequestMode() {
            return RequestMode.READ;
        }

        public DebugMode getDefaultDebugMode() {
            return DebugMode.DISABLED;
        }

        public boolean isIgnoreGlobalSettings() {
            return ignoreGlobalSettings;
        }

        public void setIgnoreGlobalSettings(boolean ignoreGlobalSettings) {
            this.ignoreGlobalSettings = ignoreGlobalSettings;
        }

        public DebugMode getDebugMode() {
            return debugMode;
        }

        public void setDebugMode(DebugMode debugMode) {
            this.debugMode = debugMode;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public FormValidation doCheckToken(@QueryParameter String value) {
            if (value.length() == 0) {
                return FormValidation.error("No token specified.  Call will be made without a token.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckHostUrl(@QueryParameter String value) {
            String message = "Please set a Host URL, including protocol, eg: http/https.";

            if (value.length() == 0) {
                return FormValidation.error(message);
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckKey(@QueryParameter String value) {
            if (value.length() == 0) {
                return FormValidation.error("Please set the key for this request.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckEnvVarKey(@QueryParameter String value) {

            String message = "Please enter an ENV variable storage key that is only RegEx word characters, and " +
                    "hyphens.";
            if (value.length() == 0) {
                return FormValidation.error(message);
            }
            if (value.contains(".") || value.contains("/")) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckApiUri(@QueryParameter String value) {

            String message = "Please set a API URI that begins and ends with a \"/\"., example: " + Constants
                    .API_URI + ".";

            if (value.length() == 0) {
                return FormValidation.error(message);
            }
            if (!Strings.checkPattern(value, Constants.REGEX_PATTERN_API_URI)) {
                return FormValidation.error("Invalid API URI pattern supplied.");
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            if (!ignoreGlobalSettings) {
                updateFromGlobalConfiguration();
            }

            req.bindJSON(this, formData);
            save();

            return super.configure(req, formData);
        }

        @Override
        public String getDisplayName() {
            return "Consul K/V Builder";
        }

        private void updateFromGlobalConfiguration() {
            Jenkins jenkins = Jenkins.getInstance();

            if (jenkins !=  null) {
                GlobalConsulConfig.DescriptorImpl globalDescriptor = (GlobalConsulConfig.DescriptorImpl)
                        jenkins.getDescriptor(GlobalConsulConfig.class);

                if (globalDescriptor != null) {
                    hostUrl = globalDescriptor.getConsulHostUrl();
                    apiUri = globalDescriptor.getConsulApiUri();
                    aclToken = globalDescriptor.getConsulAclToken();
                    timeoutConnection = globalDescriptor.getConsulTimeoutConnection();
                    timeoutResponse = globalDescriptor.getConsulTimeoutResponse();
                    debugMode = globalDescriptor.getConsulDebugMode();
                } else {
                    LOGGER.warning("Could not load global settings.");
                }
            } else {
                LOGGER.warning("Could not load global settings.");
            }
        }

        @Override
        public String toString() {
            return "DescriptorImpl{" +
                    "hostUrl='" + hostUrl + '\'' +
                    ", aclToken='" + aclToken + '\'' +
                    ", apiUri='" + apiUri + '\'' +
                    ", timeoutConnection=" + timeoutConnection +
                    ", timeoutResponse=" + timeoutResponse +
                    ", debugMode=" + debugMode +
                    ", ignoreGlobalSettings=" + ignoreGlobalSettings +
                    '}';
        }
    }
}

