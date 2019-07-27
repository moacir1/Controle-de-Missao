package org.yamcs;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yamcs.Spec.OptionType;
import org.yamcs.logging.Log;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.YamcsManagement.InstanceTemplate;
import org.yamcs.protobuf.YamcsManagement.TemplateVariable;
import org.yamcs.protobuf.YamcsManagement.YamcsInstance.InstanceState;
import org.yamcs.security.CryptoUtils;
import org.yamcs.security.SecurityStore;
import org.yamcs.time.RealtimeTimeService;
import org.yamcs.time.TimeService;
import org.yamcs.utils.ExceptionUtil;
import org.yamcs.utils.TemplateProcessor;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.yarch.YarchDatabase;
import org.yaml.snakeyaml.Yaml;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.PathConverter;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Service.State;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 *
 * Yamcs server together with the global instances
 * 
 * 
 * @author nm
 *
 */
public class YamcsServer {

    private static final String SERVER_ID_KEY = "serverId";
    private static final String SECRET_KEY = "secretKey";
    public static final String GLOBAL_INSTANCE = "_global";
    private static final Log LOG = new Log(YamcsServer.class);

    private static final Pattern INSTANCE_PATTERN = Pattern.compile("yamcs\\.(.*)\\.yaml(.offline)?");
    private static final YamcsServer YAMCS = new YamcsServer();

    /**
     * During shutdown, allow services this number of seconds for stopping
     */
    public static final int SERVICE_STOP_GRACE_TIME = 10;

    static TimeService realtimeTimeService = new RealtimeTimeService();

    // used for unit tests
    static TimeService mockupTimeService;

    private CrashHandler globalCrashHandler;

    @Parameter(names = { "-v", "--verbose" }, description = "Increase console log output")
    private boolean verbose;

    @Parameter(names = { "--etc-dir" }, description = "Path to config directory", converter = PathConverter.class)
    private Path configDirectory = Paths.get("etc").toAbsolutePath();

    @Parameter(names = { "--log-config" }, description = "Logging configuration file", converter = PathConverter.class)
    private Path logConfig = configDirectory.resolve("logging.properties").toAbsolutePath();

    @Parameter(names = { "--log-output" }, description = "Redirect stdout/stderr to the log system")
    private boolean logOutput;

    @Parameter(names = { "-h", "--help" }, help = true, description = "Show usage")
    private boolean help;

    private YConfiguration config;

    List<ServiceWithConfig> globalServiceList;
    Map<String, YamcsServerInstance> instances = new LinkedHashMap<>();
    Map<Class<? extends Plugin>, Plugin> plugins = new HashMap<>();
    Map<String, InstanceTemplate> instanceTemplates = new HashMap<>();
    List<StartListener> startListeners = new ArrayList<>();

    private SecurityStore securityStore;

    private String serverId;
    private byte[] secretKey;
    int maxOnlineInstances = 1000;
    int maxNumInstances = 20;
    Path dataDir;
    Path incomingDir;
    Path cacheDir;
    Path instanceDefDir;

    /**
     * Creates services at global (if instance is null) or instance level. The services are not yet started. This must
     * be done in a second step, so that components can ask YamcsServer for other service instantiations.
     *
     * @param instance
     *            if null, then start a global service, otherwise an instance service
     * @param services
     *            list of service configuration; each of them is a string (=classname) or a map
     * @throws IOException
     * @throws ConfigurationException
     */
    static List<ServiceWithConfig> createServices(String instance, List<YConfiguration> servicesConfig)
            throws ConfigurationException, IOException {
        ManagementService managementService = ManagementService.getInstance();
        Set<String> names = new HashSet<>();
        List<ServiceWithConfig> serviceList = new CopyOnWriteArrayList<>();
        for (YConfiguration servconf : servicesConfig) {
            String servclass;
            Object args = null;
            String name = null;
            servclass = servconf.getString("class");
            args = servconf.get("args");
            if (args instanceof Map) {
                args = servconf.getConfig("args");
            } else if (args == null) {
                args = YConfiguration.emptyConfig();
            }
            name = servconf.getString("name", servclass.substring(servclass.lastIndexOf('.') + 1));
            String candidateName = name;
            int count = 1;
            while (names.contains(candidateName)) {
                candidateName = name + "-" + count;
                count++;
            }
            name = candidateName;

            LOG.info("Loading {} service {} ({})", (instance == null) ? "global" : instance, name, servclass);
            ServiceWithConfig swc;
            try {
                swc = createService(instance, servclass, name, args);
                serviceList.add(swc);
            } catch (NoClassDefFoundError e) {
                LOG.error("Cannot create service {}, with arguments {}: class {} not found", servclass, args,
                        e.getMessage());
                throw e;
            } catch (ValidationException e) {
                LOG.error("Cannot create service {}, with arguments {}: {}", servclass, args, e.getMessage());
                throw new ConfigurationException("Invalid configuration");
            } catch (Exception e) {
                LOG.error("Cannot create service {}, with arguments {}: {}", servclass, args, e.getMessage());
                throw e;
            }
            if (managementService != null) {
                managementService.registerService(instance, name, swc.service);
            }
            names.add(name);
        }

        return serviceList;
    }

    public <T extends YamcsService> void addGlobalService(
            String name, Class<T> serviceClass, YConfiguration args) throws ValidationException, IOException {

        for (ServiceWithConfig otherService : YAMCS.globalServiceList) {
            if (otherService.getName().equals(name)) {
                throw new ConfigurationException(String.format(
                        "A service named '%s' already exists", name));
            }
        }

        LOG.info("Loading global service {} ({})", name, serviceClass.getName());
        ServiceWithConfig swc = createService(null, serviceClass.getName(), name, args);
        YAMCS.globalServiceList.add(swc);

        ManagementService managementService = ManagementService.getInstance();
        managementService.registerService(null, name, swc.service);
    }

    /**
     * Starts the specified list of services.
     *
     * @param serviceList
     *            list of service configurations
     * @throws ConfigurationException
     */
    public static void startServices(List<ServiceWithConfig> serviceList) throws ConfigurationException {
        for (ServiceWithConfig swc : serviceList) {
            LOG.debug("Starting service {}", swc.getName());
            swc.service.startAsync();
            try {
                swc.service.awaitRunning();
            } catch (IllegalStateException e) {
                // this happens when it fails, the next check will throw an error in this case
            }
            State result = swc.service.state();
            if (result == State.FAILED) {
                throw new ConfigurationException("Failed to start service " + swc.service, swc.service.failureCause());
            }
        }
    }

    public void shutDown() {
        for (YamcsServerInstance ys : instances.values()) {
            ys.stopAsync();
        }
        for (YamcsServerInstance ys : instances.values()) {
            ys.awaitOffline();
        }
    }

    public static boolean hasInstance(String instance) {
        return YAMCS.instances.containsKey(instance);
    }

    public static boolean hasInstanceTemplate(String template) {
        return YAMCS.instanceTemplates.containsKey(template);
    }

    public String getServerId() {
        return serverId;
    }

    public byte[] getSecretKey() {
        return secretKey;
    }

    /**
     * Returns the main Yamcs configuration
     */
    public YConfiguration getConfig() {
        return config;
    }

    private void loadConfiguration() {
        Spec serviceSpec = new Spec();
        serviceSpec.addOption("class", OptionType.STRING).withRequired(true);
        serviceSpec.addOption("args", OptionType.ANY);

        Spec spec = new Spec();
        spec.addOption("services", OptionType.LIST).withElementType(OptionType.MAP)
                .withSpec(serviceSpec);
        spec.addOption("instances", OptionType.LIST).withElementType(OptionType.STRING);
        spec.addOption("dataDir", OptionType.STRING).withDefault("yamcs-data");
        spec.addOption("cacheDir", OptionType.STRING).withDefault("cache");
        spec.addOption("incomingDir", OptionType.STRING).withDefault("yamcs-incoming");
        spec.addOption("serverId", OptionType.STRING);
        spec.addOption("secretKey", OptionType.STRING);

        // TODO The goal is to (over time) be able to remove this relaxation
        spec.allowUnknownKeys(true);

        config = YConfiguration.getConfiguration("yamcs");
        try {
            config = spec.validate(config);
        } catch (ValidationException e) {
            throw new ConfigurationException(String.format(
                    "Validation error in %s: %s", config.getPath(), e.getMessage()));
        }
    }

    private void discoverTemplates() throws IOException {
        Path templatesDir = Paths.get("etc", "instance-templates");
        if (!Files.exists(templatesDir)) {
            return;
        }

        try (Stream<Path> dirStream = Files.list(templatesDir)) {
            dirStream.filter(Files::isDirectory).forEach(p -> {
                if (Files.exists(p.resolve("template.yaml"))) {
                    String name = p.getFileName().toString();
                    InstanceTemplate.Builder templateb = InstanceTemplate.newBuilder()
                            .setName(name);

                    Path varFile = p.resolve("variables.yaml");
                    if (Files.exists(varFile)) {
                        try (InputStream in = new FileInputStream(varFile.toFile())) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> varDefs = (List<Map<String, Object>>) new Yaml().load(in);
                            for (Map<String, Object> varDef : varDefs) {
                                TemplateVariable.Builder varb = TemplateVariable.newBuilder();
                                varb.setName(YConfiguration.getString(varDef, "name"));
                                varb.setRequired(YConfiguration.getBoolean(varDef, "required", true));
                                if (varDef.containsKey("description")) {
                                    varb.setDescription(YConfiguration.getString(varDef, "description"));
                                }
                                templateb.addVariable(varb);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }

                    YAMCS.instanceTemplates.put(name, templateb.build());
                }
            });
        }
    }

    public void addGlobalServicesAndInstances() throws IOException {
        serverId = deriveServerId();
        deriveSecretKey();

        if (config.containsKey("crashHandler")) {
            globalCrashHandler = loadCrashHandler();
        } else {
            globalCrashHandler = new LogCrashHandler();
        }
        dataDir = Paths.get(config.getString("dataDir"));
        incomingDir = Paths.get(config.getString("incomingDir"));
        instanceDefDir = dataDir.resolve("instance-def");

        if (YConfiguration.configDirectory != null) {
            cacheDir = YConfiguration.configDirectory.getAbsoluteFile().toPath();
        } else {
            cacheDir = Paths.get(config.getString("cacheDir")).toAbsolutePath();
        }

        Path globalDir = dataDir.resolve(GLOBAL_INSTANCE);
        Files.createDirectories(globalDir);
        Files.createDirectories(instanceDefDir);

        try {
            securityStore = new SecurityStore();
        } catch (InitException e) {
            throw new ConfigurationException(e);
        }

        if (config.containsKey("services")) {
            List<YConfiguration> services = config.getServiceConfigList("services");
            globalServiceList = createServices(null, services);
        }

        // Load user-configured instances. These are the ones that are explictly mentioned in yamcs.yaml
        int instanceCount = 0;
        if (config.containsKey("instances")) {
            for (String name : config.<String> getList("instances")) {
                if (instances.containsKey(name)) {
                    throw new ConfigurationException("Duplicate instance specified: '" + name + "'");
                }
                YConfiguration instanceConfig = YConfiguration.getConfiguration("yamcs." + name);
                addInstance(name, instanceConfig, new InstanceMetadata());
                instanceCount++;
            }
        }

        // Load instances saved in storage
        try (Stream<Path> paths = Files.list(instanceDefDir)) {
            for (Path instanceDir : paths.collect(Collectors.toList())) {
                String dirname = instanceDir.getFileName().toString();
                Matcher m = INSTANCE_PATTERN.matcher(dirname);
                if (!m.matches()) {
                    continue;
                }

                String instanceName = m.group(1);
                boolean online = m.group(2) == null;
                if (online) {
                    instanceCount++;
                    if (instanceCount > maxOnlineInstances) {
                        throw new ConfigurationException("Instance limit exceeded: " + instanceCount);
                    }
                    YConfiguration instanceConfig = loadInstanceConfig(instanceName);
                    InstanceMetadata instanceMetadata = loadInstanceMetadata(instanceName);
                    addInstance(instanceName, instanceConfig, instanceMetadata);
                } else {
                    if (instances.size() > maxNumInstances) {
                        LOG.warn("Number of instances exceeds the maximum {}, offline instance {} not loaded",
                                maxNumInstances, instanceName);
                        continue;
                    }
                    InstanceMetadata instanceMetadata = loadInstanceMetadata(instanceName);
                    addOfflineInstance(instanceName, instanceMetadata);
                }
            }
        }
    }

    private CrashHandler loadCrashHandler() throws IOException {
        if (config.containsKey("crashHandler", "args")) {
            return YObjectLoader.loadObject(config.getSubString("crashHandler", "class"),
                    config.getSubMap("crashHandler", "args"));
        } else {
            return YObjectLoader.loadObject(config.getSubString("crashHandler", "class"));
        }
    }

    private void discoverPlugins() {
        List<String> disabledPlugins;
        YConfiguration yconf = YConfiguration.getConfiguration("yamcs");
        if (yconf.containsKey("disabledPlugins")) {
            disabledPlugins = yconf.getList("disabledPlugins");
        } else {
            disabledPlugins = Collections.emptyList();
        }

        for (Plugin plugin : ServiceLoader.load(Plugin.class)) {
            if (disabledPlugins.contains(plugin.getName())) {
                LOG.debug("Ignoring plugin {} (disabled by user config)", plugin.getName());
            } else {
                plugins.put(plugin.getClass(), plugin);
            }
        }
    }

    private void loadPlugins() throws PluginException {
        for (Plugin plugin : plugins.values()) {
            LOG.debug("Loading plugin {} {}", plugin.getName(), plugin.getVersion());
            try {
                plugin.onLoad();
            } catch (PluginException e) {
                LOG.error("Could not load plugin {} {}", plugin.getName(), plugin.getVersion());
                throw e;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Plugin> T getPlugin(Class<T> clazz) {
        return (T) plugins.get(clazz);
    }

    private int getNumOnlineInstances() {
        return (int) instances.values().stream().filter(ysi -> ysi.state() != InstanceState.OFFLINE).count();
    }

    private void startServices() {
        if (globalServiceList != null) {
            startServices(globalServiceList);
        }

        for (YamcsServerInstance ysi : instances.values()) {
            if (ysi.state() != InstanceState.OFFLINE) {
                ysi.startAsync();
            }
        }
    }

    /**
     * Restarts a yamcs instance.
     * 
     * @param instanceName
     *            the name of the instance
     * 
     * @return the newly created instance
     * @throws IOException
     */
    public YamcsServerInstance restartInstance(String instanceName) throws IOException {
        YamcsServerInstance ysi = instances.get(instanceName);

        if (ysi.state() == InstanceState.RUNNING || ysi.state() == InstanceState.FAILED) {
            try {
                ysi.stop();
            } catch (IllegalStateException e) {
                LOG.warn("Instance did not terminate normally", e);
            }
        }
        YarchDatabase.removeInstance(instanceName);
        XtceDbFactory.remove(instanceName);
        LOG.info("Re-loading instance '{}'", instanceName);

        YConfiguration instanceConfig = loadInstanceConfig(instanceName);
        ysi.init(instanceConfig);
        ysi.startAsync();
        try {
            ysi.awaitRunning();
        } catch (IllegalStateException e) {
            Throwable t = ExceptionUtil.unwind(e.getCause());
            LOG.warn("Failed to start instance", t);
            throw new UncheckedExecutionException(t);
        }
        return ysi;
    }

    private YConfiguration loadInstanceConfig(String instanceName) {
        Path configFile = instanceDefDir.resolve("yamcs." + instanceName + ".yaml");
        if (Files.exists(configFile)) {
            try (InputStream is = Files.newInputStream(configFile)) {
                String confPath = configFile.toAbsolutePath().toString();
                return new YConfiguration("yamcs." + instanceName, is, confPath);
            } catch (IOException e) {
                throw new ConfigurationException("Cannot load configuration from " + configFile.toAbsolutePath(), e);
            }
        } else {
            return YConfiguration.getConfiguration("yamcs." + instanceName);
        }
    }

    @SuppressWarnings("unchecked")
    private InstanceMetadata loadInstanceMetadata(String instanceName) throws IOException {
        Path metadataFile = instanceDefDir.resolve("yamcs." + instanceName + ".metadata");
        if (Files.exists(metadataFile)) {
            try (InputStream in = Files.newInputStream(metadataFile)) {
                Map<String, Object> map = new Yaml().loadAs(in, Map.class);
                return new InstanceMetadata(map);
            }
        }
        return new InstanceMetadata();
    }

    /**
     * Stop the instance (it will be offline after this)
     * 
     * @param instanceName
     *            the name of the instance
     * 
     * @return the instance
     * @throws IOException
     */
    public YamcsServerInstance stopInstance(String instanceName) throws IOException {
        YamcsServerInstance ysi = instances.get(instanceName);

        if (ysi.state() != InstanceState.OFFLINE) {
            try {
                ysi.stop();
            } catch (IllegalStateException e) {
                LOG.error("Instance did not terminate normally", e);
            }
        }
        YarchDatabase.removeInstance(instanceName);
        XtceDbFactory.remove(instanceName);
        Path f = instanceDefDir.resolve("yamcs." + instanceName + ".yaml");
        if (Files.exists(f)) {
            LOG.debug("Renaming {} to {}.offline", f.toAbsolutePath(), f.getFileName());
            Files.move(f, f.resolveSibling("yamcs." + instanceName + ".yaml.offline"));
        }

        return ysi;
    }

    public void removeInstance(String instanceName) throws IOException {
        stopInstance(instanceName);
        Files.deleteIfExists(instanceDefDir.resolve("yamcs." + instanceName + ".yaml"));
        Files.deleteIfExists(instanceDefDir.resolve("yamcs." + instanceName + ".yaml.offline"));
        instances.remove(instanceName);
    }

    /**
     * Start the instance. If the instance is already started, do nothing.
     * 
     * If the instance is FAILED, restart the instance
     * 
     * If the instance is OFFLINE, rename the &lt;instance&gt;.yaml.offline to &lt;instance&gt;.yaml and start the
     * instance
     * 
     * 
     * @param instanceName
     *            the name of the instance
     * 
     * @return the instance
     * @throws IOException
     */
    public YamcsServerInstance startInstance(String instanceName) throws IOException {
        YamcsServerInstance ysi = instances.get(instanceName);

        if (ysi.state() == InstanceState.RUNNING) {
            return ysi;
        } else if (ysi.state() == InstanceState.FAILED) {
            return restartInstance(instanceName);
        }

        if (getNumOnlineInstances() >= maxOnlineInstances) {
            throw new LimitExceededException("Number of online instances already at the limit " + maxOnlineInstances);
        }

        if (ysi.state() == InstanceState.OFFLINE) {
            Path f = instanceDefDir.resolve("yamcs." + instanceName + ".yaml.offline");
            if (Files.exists(f)) {
                Files.move(f, instanceDefDir.resolve("yamcs." + instanceName + ".yaml"));
            }
            YConfiguration instanceConfig = loadInstanceConfig(instanceName);
            ysi.init(instanceConfig);
        }
        ysi.startAsync();
        ysi.awaitRunning();
        return ysi;
    }

    public Collection<Plugin> getPlugins() {
        return plugins.values();
    }

    /**
     * Creates a new yamcs instance.
     * 
     * If the instance already exists an IllegalArgumentException is thrown
     * 
     * @param name
     *            the name of the new instance
     * @param config
     *            the configuration for this instance (equivalent of yamcs.instance.yaml)
     * @param metadata
     *            the metadata associated to this instance (labels or other attributes)
     * 
     * @return the newly created instance
     */
    public synchronized YamcsServerInstance addInstance(String name, YConfiguration config, InstanceMetadata metadata) {
        if (instances.containsKey(name)) {
            throw new IllegalArgumentException(String.format("There already exists an instance named '%s'", name));
        }
        LOG.info("Loading online instance '{}'", name);
        YamcsServerInstance ysi = new YamcsServerInstance(name, metadata);
        instances.put(name, ysi);
        ysi.init(config);
        ManagementService.getInstance().registerYamcsInstance(ysi);
        return ysi;
    }

    public synchronized YamcsServerInstance addOfflineInstance(String name, InstanceMetadata metadata) {
        if (instances.containsKey(name)) {
            throw new IllegalArgumentException(String.format("There already exists an instance named '%s'", name));
        }
        LOG.debug("Loading offline instance '{}'", name);
        YamcsServerInstance ysi = new YamcsServerInstance(name, metadata);
        instances.put(name, ysi);
        ManagementService.getInstance().registerYamcsInstance(ysi);
        return ysi;
    }

    /**
     * Create a new instance based on a template.
     * 
     * @param name
     *            the name of the instance
     * @param template
     *            the name of an available template
     * @param templateArgs
     *            arguments to use while processing the template
     * @param labels
     *            labels associated to this instance
     * @param customMetadata
     *            custom metadata associated with this instance.
     * @throws IOException
     *             when a disk operation failed
     * @return the newly create instance
     */
    public synchronized YamcsServerInstance createInstance(String name, String template,
            Map<String, Object> templateArgs, Map<String, String> labels, Map<String, Object> customMetadata)
            throws IOException {
        if (instances.containsKey("name")) {
            throw new IllegalArgumentException(String.format("There already exists an instance named '%s'", name));
        }

        // Build instance metadata as a combination of internal properties and custom metadata from the caller
        InstanceMetadata metadata = new InstanceMetadata();
        metadata.setTemplate(template);
        metadata.setTemplateArgs(templateArgs);
        metadata.setLabels(labels);
        customMetadata.forEach((k, v) -> metadata.put(k, v));

        String tmplResource = "/instance-templates/" + template + "/template.yaml";
        InputStream is = YConfiguration.getResolver().getConfigurationStream(tmplResource);

        StringBuilder buf = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                buf.append(line).append("\n");
            }
        }
        String source = buf.toString();
        String processed = TemplateProcessor.process(source, metadata.getTemplateArgs());

        Path confFile = instanceDefDir.resolve("yamcs." + name + ".yaml");
        try (FileWriter writer = new FileWriter(confFile.toFile())) {
            writer.write(processed);
        }

        Path metadataFile = instanceDefDir.resolve("yamcs." + name + ".metadata");
        try (Writer writer = Files.newBufferedWriter(metadataFile)) {
            Map<String, Object> metadataMap = metadata.toMap();
            new Yaml().dump(metadataMap, writer);
        }

        YConfiguration instanceConfig;
        try (InputStream fis = Files.newInputStream(confFile)) {
            String subSystem = "yamcs." + name;
            String confPath = confFile.toString();
            instanceConfig = new YConfiguration(subSystem, fis, confPath);
        }

        return addInstance(name, instanceConfig, metadata);
    }

    private String deriveServerId() {
        try {
            String id;
            if (config.containsKey(SERVER_ID_KEY)) {
                id = config.getString(SERVER_ID_KEY);
            } else {
                id = InetAddress.getLocalHost().getHostName();
            }
            serverId = id;
            LOG.debug("Using serverId {}", serverId);
            return serverId;
        } catch (ConfigurationException e) {
            throw e;
        } catch (UnknownHostException e) {
            String msg = "Cannot resolve local host. Make sure it's defined properly or alternatively add 'serverId: <name>' to yamcs.yaml";
            LOG.warn(msg);
            throw new ConfigurationException(msg, e);
        }
    }

    private void deriveSecretKey() {
        if (config.containsKey(SECRET_KEY)) {
            // Should maybe only allow base64 encoded secret keys
            secretKey = config.getString(SECRET_KEY).getBytes(StandardCharsets.UTF_8);
        } else {
            LOG.warn("Generating random non-persisted secret key."
                    + " Cryptographic verifications will not work across server restarts."
                    + " Set 'secretKey: <secret>' in yamcs.yaml to avoid this message.");
            secretKey = CryptoUtils.generateRandomSecretKey();
        }
    }

    public static Set<YamcsServerInstance> getInstances() {
        return new HashSet<>(YAMCS.instances.values());
    }

    public YamcsServerInstance getInstance(String yamcsInstance) {
        return instances.get(yamcsInstance);
    }

    public static Set<InstanceTemplate> getInstanceTemplates() {
        return new HashSet<>(YAMCS.instanceTemplates.values());
    }

    public InstanceTemplate getInstanceTemplate(String name) {
        return instanceTemplates.get(name);
    }

    public static TimeService getTimeService(String yamcsInstance) {
        if (YAMCS.instances.containsKey(yamcsInstance)) {
            return YAMCS.instances.get(yamcsInstance).getTimeService();
        } else {
            if (mockupTimeService != null) {
                return mockupTimeService;
            } else {
                return realtimeTimeService; // happens from unit tests
            }
        }
    }

    public SecurityStore getSecurityStore() {
        return securityStore;
    }

    public List<ServiceWithConfig> getGlobalServices() {
        return new ArrayList<>(globalServiceList);
    }

    public ServiceWithConfig getGlobalServiceWithConfig(String serviceName) {
        if (globalServiceList == null) {
            return null;
        }

        synchronized (globalServiceList) {
            for (ServiceWithConfig swc : globalServiceList) {
                if (swc.getName().equals(serviceName)) {
                    return swc;
                }
            }
        }
        return null;
    }

    public <T extends Service> List<T> getServices(String yamcsInstance, Class<T> serviceClass) {
        YamcsServerInstance ys = getInstance(yamcsInstance);
        if (ys == null) {
            return Collections.emptyList();
        }
        return ys.getServices(serviceClass);
    }

    public static void setMockupTimeService(TimeService timeService) {
        mockupTimeService = timeService;
    }

    public YamcsService getGlobalService(String serviceName) {
        ServiceWithConfig serviceWithConfig = getGlobalServiceWithConfig(serviceName);
        return serviceWithConfig != null ? serviceWithConfig.getService() : null;
    }

    @SuppressWarnings("unchecked")
    public <T extends YamcsService> List<T> getGlobalServices(Class<T> serviceClass) {
        List<T> services = new ArrayList<>();
        if (globalServiceList != null) {
            for (ServiceWithConfig swc : globalServiceList) {
                if (serviceClass.isInstance(swc.service)) {
                    services.add((T) swc.service);
                }
            }
        }
        return services;
    }

    static ServiceWithConfig createService(String instance, String serviceClass, String serviceName, Object args)
            throws ConfigurationException, ValidationException, IOException {
        YamcsService service = null;

        // Try first to find just a no-arg constructor. This will become
        // the common case when all services are using the init method.
        if (args instanceof YConfiguration) {
            try {
                service = YObjectLoader.loadObject(serviceClass);
            } catch (ConfigurationException e) {
                // Ignore for now. Fallback to constructor initialization.
            }
        }

        if (service == null) { // "Legacy" fallback
            if (instance != null) {
                if (args == null) {
                    service = YObjectLoader.loadObject(serviceClass, instance);
                } else {
                    service = YObjectLoader.loadObject(serviceClass, instance, args);
                }
            } else {
                if (args == null) {
                    service = YObjectLoader.loadObject(serviceClass);
                } else {
                    service = YObjectLoader.loadObject(serviceClass, args);
                }
            }
        }

        if (args instanceof YConfiguration) {
            try {
                Spec spec = service.getSpec();
                if (spec != null) {
                    if (LOG.isDebugEnabled()) {
                        Map<String, Object> unsafeArgs = ((YConfiguration) args).getRoot();
                        Map<String, Object> safeArgs = spec.maskSecrets(unsafeArgs);
                        LOG.debug("Raw args for {}: {}", serviceName, safeArgs);
                    }

                    args = spec.validate((YConfiguration) args);

                    if (LOG.isDebugEnabled()) {
                        Map<String, Object> unsafeArgs = ((YConfiguration) args).getRoot();
                        Map<String, Object> safeArgs = spec.maskSecrets(unsafeArgs);
                        LOG.debug("Initializing {} with resolved args: {}", serviceName, safeArgs);
                    }
                }
                service.init(instance, (YConfiguration) args);
            } catch (InitException e) { // TODO should add this to throws instead
                throw new ConfigurationException(e);
            }
        }
        return new ServiceWithConfig(service, serviceClass, serviceName, args);
    }

    // starts a service that has stopped or not yet started
    static YamcsService startService(String instance, String serviceName, List<ServiceWithConfig> serviceList)
            throws ConfigurationException, ValidationException, IOException {
        for (int i = 0; i < serviceList.size(); i++) {
            ServiceWithConfig swc = serviceList.get(i);
            if (swc.name.equals(serviceName)) {
                switch (swc.service.state()) {
                case RUNNING:
                case STARTING:
                    // do nothing, service is already starting
                    break;
                case NEW: // not yet started, start it now
                    swc.service.startAsync();
                    break;
                case FAILED:
                case STOPPING:
                case TERMINATED:
                    // start a new one
                    swc = createService(instance, swc.serviceClass, serviceName, swc.args);
                    serviceList.set(i, swc);
                    swc.service.startAsync();
                    break;
                }
                return swc.service;
            }
        }
        return null;
    }

    public void startGlobalService(String serviceName) throws ConfigurationException, ValidationException, IOException {
        startService(null, serviceName, globalServiceList);
    }

    public CrashHandler getCrashHandler(String yamcsInstance) {
        YamcsServerInstance ys = getInstance(yamcsInstance);
        if (ys != null) {
            return ys.getCrashHandler();
        } else {
            return globalCrashHandler; // may happen if the instance name is not valid (in unit tests)
        }
    }

    public CrashHandler getGlobalCrashHandler() {
        return globalCrashHandler;
    }

    public Path getConfigDirectory() {
        return configDirectory;
    }

    public Path getDataDirectory() {
        return dataDir;
    }

    public Path getIncomingDirectory() {
        return incomingDir;
    }

    public Path getCacheDirectory() {
        return cacheDir;
    }

    /**
     * Register a listener that will be called when Yamcs has fully started. If you register a listener after Yamcs has
     * already started, your callback will not be executed.
     */
    public void addStartListener(StartListener startListener) {
        startListeners.add(startListener);
    }

    /**
     * @return the (singleton) server
     */
    public static YamcsServer getServer() {
        return YAMCS;
    }

    public static void main(String[] args) {
        // Run jcommander before setting up logging.
        // We want this to use standard streams.
        try {
            JCommander jcommander = new JCommander(YAMCS);
            jcommander.parse(args);
            if (YAMCS.help) {
                jcommander.usage();
                return;
            }
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }

        System.setProperty("jxl.nowarnings", "true");
        System.setProperty("jacorb.home", System.getProperty("user.dir"));
        System.setProperty("javax.net.ssl.trustStore", YAMCS.configDirectory.resolve("trustStore").toString());

        try {
            // Initialize logging first. It is used everywhere.
            setupLogging();

            // Bootstrap YConfiguration such that it only considers physical files.
            // Not classpath resources.
            YConfiguration.setResolver(new FileBasedConfigurationResolver(YAMCS.configDirectory));

            // Load the UTC-TAI.history file containing leap second information from the classpath
            // TODO add a flag to override this with a physical file.
            TimeEncoding.setUp();

            // Really start the server. This method will block until all initial
            // plugins, instances and services have finished starting.
            setupYamcsServer();
        } catch (Exception e) {
            LOG.error("Could not start Yamcs", ExceptionUtil.unwind(e));
            System.exit(-1);
        }

        // Output string to signal to wrapper scripts (e.g. init.d)
        // that Yamcs has fully started. If you modify this, then
        // also modify the init.d script. Remark that we use
        // System.out instead of a log statement because the init.d
        // wrapper does not know the location of the log files.
        System.out.println("Yamcs started successfully");

        YAMCS.startListeners.forEach(StartListener::onStart);
    }

    private static void setupLogging() throws SecurityException, IOException {

        // Unless JUL logging is manually configured, we will bootstrap it.
        if (System.getProperty("java.util.logging.config.file") == null) {
            if (Files.exists(YAMCS.logConfig)) {
                try (InputStream in = Files.newInputStream(YAMCS.logConfig)) {
                    LogManager.getLogManager().readConfiguration(in);
                    LOG.info("Logging enabled using {}", YAMCS.logConfig);
                }
            } else {
                // Add default console-based logging
                String configFile;
                if (YAMCS.verbose) {
                    configFile = "/default-logging/console-all.properties";
                } else {
                    configFile = "/default-logging/console-info.properties";
                }
                try (InputStream in = YConfiguration.class.getResourceAsStream(configFile)) {
                    LogManager.getLogManager().readConfiguration(in);
                }
            }
        }

        // Intercept stdout/stderr for sending to the log system. Only
        // catches line-terminated string, but this should cover most
        // uses cases.
        if (YAMCS.logOutput) {
            Logger stdoutLogger = Logger.getLogger("stdout");
            System.setOut(new PrintStream(System.out) {
                @Override
                public void println(String x) {
                    stdoutLogger.info(x);
                }
            });
            Logger stderrLogger = Logger.getLogger("stderr");
            System.setErr(new PrintStream(System.err) {
                @Override
                public void println(String x) {
                    stderrLogger.severe(x);
                }
            });
        }
    }

    public static void setupYamcsServer() throws ValidationException, IOException, PluginException {
        LOG.info("yamcs {}, build {}", YamcsVersion.VERSION, YamcsVersion.REVISION);

        YAMCS.discoverPlugins();
        YAMCS.loadConfiguration();
        YAMCS.discoverTemplates();
        YAMCS.addGlobalServicesAndInstances();
        YAMCS.loadPlugins();
        YAMCS.startServices();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String msg = String.format("Uncaught exception '%s' in thread %s: %s", e, t,
                    Arrays.toString(e.getStackTrace()));
            LOG.error(msg);
            YAMCS.globalCrashHandler.handleCrash("UncaughtException", msg);
        });
    }
}
