package com.intellij.javascript.karma.server;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.javascript.karma.KarmaConfig;
import com.intellij.javascript.karma.coverage.KarmaCoveragePeer;
import com.intellij.javascript.karma.execution.KarmaServerSettings;
import com.intellij.javascript.karma.util.StreamEventListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class KarmaServer {

  private static final Logger LOG = Logger.getInstance(KarmaServer.class);

  private final KarmaProcessOutputManager myProcessOutputManager;
  private final KarmaJsSourcesLocator myKarmaJsSourcesLocator;
  private final KarmaServerState myState;
  private final KarmaCoveragePeer myCoveragePeer;

  private final KarmaServerSettings myServerSettings;

  private List<Runnable> myOnPortBoundCallbacks = Lists.newCopyOnWriteArrayList();
  private List<Runnable> myOnBrowsersReadyCallbacks = Lists.newCopyOnWriteArrayList();

  private Integer myExitCode = null;
  private final List<KarmaServerTerminatedListener> myTerminationCallbacks = Lists.newCopyOnWriteArrayList();

  private final Map<String, StreamEventHandler> myHandlers = ContainerUtil.newConcurrentMap();
  private final MyDisposable myDisposable;
  private final KarmaServerRestarter myRestarter;
  private final int myProcessHashCode;
  private boolean myLastTestRunWithTestNameFilter;

  public KarmaServer(@NotNull Project project, @NotNull KarmaServerSettings serverSettings) throws IOException {
    myServerSettings = serverSettings;
    myKarmaJsSourcesLocator = new KarmaJsSourcesLocator();
    myCoveragePeer = serverSettings.isWithCoverage() ? new KarmaCoveragePeer() : null;
    KillableColoredProcessHandler processHandler = startServer(serverSettings, myKarmaJsSourcesLocator, myCoveragePeer);
    myProcessHashCode = System.identityHashCode(processHandler.getProcess());
    File configurationFile = myServerSettings.getConfigurationFile();
    myState = new KarmaServerState(this, configurationFile);
    myProcessOutputManager = new KarmaProcessOutputManager(processHandler, myState::onStandardOutputLineAvailable);
    registerStreamEventHandlers();
    myProcessOutputManager.startNotify();

    myDisposable = new MyDisposable();
    Disposer.register(project, myDisposable);
    myRestarter = new KarmaServerRestarter(configurationFile, myDisposable);

    final int processHashCode = System.identityHashCode(processHandler.getProcess());
    LOG.info("Karma server " + processHashCode + " started successfully: " + processHandler.getCommandLine());
    processHandler.addProcessListener(new ProcessAdapter() {
      @Override
      public void processTerminated(@NotNull final ProcessEvent event) {
        LOG.info("Karma server " + processHashCode + " terminated with exit code " + event.getExitCode());
        Disposer.dispose(myDisposable);
        fireOnTerminated(event.getExitCode());
      }
    });
  }

  private void registerStreamEventHandlers() {
    if (myCoveragePeer != null) {
      myCoveragePeer.registerEventHandlers(this);
    }

    myProcessOutputManager.addStreamEventListener(new StreamEventListener() {
      @Override
      public void on(@NotNull String eventType, @NotNull String eventBody) {
        LOG.info("Processing Karma event " + eventType + " " + eventBody);
        JsonElement jsonElement;
        try {
          JsonParser jsonParser = new JsonParser();
          jsonElement = jsonParser.parse(eventBody);
        }
        catch (Exception e) {
          LOG.warn("Cannot parse message from karma server:" +
                   " (eventType: " + eventType + ", eventBody: " + eventBody + ")");
          return;
        }
        StreamEventHandler handler = myHandlers.get(eventType);
        if (handler != null) {
          handler.handle(jsonElement);
        }
        else {
          LOG.warn("Cannot find handler for " + eventType);
        }
      }
    });
  }

  @NotNull
  public KarmaServerSettings getServerSettings() {
    return myServerSettings;
  }

  @NotNull
  public KarmaServerRestarter getRestarter() {
    return myRestarter;
  }

  @Nullable
  public KarmaCoveragePeer getCoveragePeer() {
    return myCoveragePeer;
  }

  @NotNull
  public KarmaJsSourcesLocator getKarmaJsSourcesLocator() {
    return myKarmaJsSourcesLocator;
  }

  public void registerStreamEventHandler(@NotNull StreamEventHandler handler) {
    myHandlers.put(handler.getEventType(), handler);
  }

  @NotNull
  private static KillableColoredProcessHandler startServer(@NotNull KarmaServerSettings serverSettings,
                                                           @NotNull KarmaJsSourcesLocator sourcesLocator,
                                                           @Nullable KarmaCoveragePeer coveragePeer) throws IOException {
    GeneralCommandLine commandLine = new GeneralCommandLine();
    serverSettings.getEnvData().configureCommandLine(commandLine, true);
    commandLine.withWorkDirectory(serverSettings.getConfigurationFile().getParentFile());
    commandLine.setRedirectErrorStream(true);
    commandLine.setExePath(serverSettings.getNodeInterpreter().getInterpreterSystemDependentPath());
    List<String> nodeOptionList = ParametersListUtil.parse(serverSettings.getNodeOptions().trim());
    commandLine.addParameters(nodeOptionList);
    File serverFile = sourcesLocator.getServerAppFile();
    //try {
    //  NodeCommandLineUtil.addNodeOptionsForDebugging(commandLine, Collections.emptyList(), 34598, true,
    //                                                 serverSettings.getNodeInterpreter(), true);
    //}
    //catch (ExecutionException e) {
    //  throw new IOException(e);
    //}
    commandLine.addParameter(serverFile.getAbsolutePath());
    commandLine.addParameter("--karmaPackageDir=" + serverSettings.getKarmaPackage().getSystemDependentPath());
    commandLine.addParameter("--configFile=" + serverSettings.getConfigurationFilePath());
    String browsers = serverSettings.getBrowsers();
    if (!StringUtil.isEmptyOrSpaces(browsers)) {
      commandLine.addParameter("--browsers=" + browsers);
    }
    if (coveragePeer != null) {
      commandLine.addParameter("--coverageTempDir=" + coveragePeer.getCoverageTempDir());
    }
    if (serverSettings.isDebug()) {
      commandLine.addParameter("--debug=true");
    }
    commandLine.setCharset(CharsetToolkit.UTF8_CHARSET);

    KillableColoredProcessHandler processHandler;
    try {
      processHandler = new KillableColoredProcessHandler(commandLine, true);
    }
    catch (ExecutionException e) {
      throw new IOException("Can not start Karma server: " + commandLine.getCommandLineString(), e);
    }
    processHandler.setShouldDestroyProcessRecursively(true);
    ProcessTerminatedListener.attach(processHandler);
    return processHandler;
  }

  public void shutdownAsync() {
    LOG.info("Shutting down asynchronously Karma server " + myProcessHashCode);
    ApplicationManager.getApplication().executeOnPooledThread(this::shutdown);
  }

  private void shutdown() {
    ProcessHandler processHandler = myProcessOutputManager.getProcessHandler();
    if (!processHandler.isProcessTerminated()) {
      ScriptRunnerUtil.terminateProcessHandler(processHandler, 1000, null);
    }
  }

  @NotNull
  public KarmaProcessOutputManager getProcessOutputManager() {
    return myProcessOutputManager;
  }

  public boolean isPortBound() {
    return myState.getServerPort() != -1;
  }

  public int getServerPort() {
    return myState.getServerPort();
  }

  /**
   * Executes {@code callback} in EDT when the server port is bound.
   */
  public void onPortBound(@NotNull final Runnable callback) {
    UIUtil.invokeLaterIfNeeded(() -> {
      if (myOnPortBoundCallbacks != null) {
        myOnPortBoundCallbacks.add(callback);
      }
      else {
        callback.run();
      }
    });
  }

  void fireOnPortBound() {
    UIUtil.invokeLaterIfNeeded(() -> {
      List<Runnable> callbacks = ContainerUtil.newArrayList(myOnPortBoundCallbacks);
      myOnPortBoundCallbacks.clear();
      myOnPortBoundCallbacks = null;
      for (Runnable callback : callbacks) {
        callback.run();
      }
    });
  }

  public boolean areBrowsersReady() {
    return myState.areBrowsersReady();
  }

  /**
   * Executes {@code callback} in EDT when at least one browser is captured and all config.browsers are captured.
   */
  public void onBrowsersReady(@NotNull final Runnable callback) {
    UIUtil.invokeLaterIfNeeded(() -> {
      if (myOnBrowsersReadyCallbacks != null) {
        myOnBrowsersReadyCallbacks.add(callback);
      }
      else {
        callback.run();
      }
    });
  }

  void fireOnBrowsersReady(boolean browsersReady) {
    UIUtil.invokeLaterIfNeeded(() -> {
      if (browsersReady) {
        List<Runnable> callbacks = ContainerUtil.newArrayList(myOnBrowsersReadyCallbacks);
        myOnBrowsersReadyCallbacks.clear();
        myOnBrowsersReadyCallbacks = null;
        for (Runnable callback : callbacks) {
          callback.run();
        }
      }
      else {
        myOnBrowsersReadyCallbacks = Lists.newCopyOnWriteArrayList();
      }
    });
  }

  /**
   * Executes {@code terminationCallback} in EDT when the server is shut down.
   */
  public void onTerminated(@NotNull final KarmaServerTerminatedListener terminationCallback) {
    UIUtil.invokeLaterIfNeeded(() -> {
      if (myExitCode != null) {
        terminationCallback.onTerminated(myExitCode);
      }
      else {
        myTerminationCallbacks.add(terminationCallback);
      }
    });
  }

  public void removeTerminatedListener(@NotNull final KarmaServerTerminatedListener listener) {
    UIUtil.invokeLaterIfNeeded(() -> myTerminationCallbacks.remove(listener));
  }

  private void fireOnTerminated(final int exitCode) {
    UIUtil.invokeLaterIfNeeded(() -> {
      myExitCode = exitCode;
      List<KarmaServerTerminatedListener> listeners = ContainerUtil.newArrayList(myTerminationCallbacks);
      myTerminationCallbacks.clear();
      for (KarmaServerTerminatedListener listener : listeners) {
        listener.onTerminated(exitCode);
      }
    });
  }

  @Nullable
  public KarmaConfig getKarmaConfig() {
    return myState.getKarmaConfig();
  }

  @NotNull
  public String formatUrlWithoutUrlRoot(@NotNull String path) {
    return formatUrl(path, false);
  }

  @SuppressWarnings("SameParameterValue")
  @NotNull
  public String formatUrl(@NotNull String path) {
    return formatUrl(path, true);
  }

  @NotNull
  private String formatUrl(@NotNull String path, boolean withUrlRoot) {
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    KarmaConfig config = myState.getKarmaConfig();
    if (config != null) {
      String baseUrl = config.getProtocol() + "//" + config.getHostname() + ":" + getServerPort();
      String urlRoot = config.getUrlRoot();
      if (!withUrlRoot || "/".equals(urlRoot)) {
        return baseUrl + path;
      }
      return baseUrl + config.getUrlRoot() + path;
    }
    LOG.error("Karma config not ready");
    return "http://localhost:" + getServerPort() + path;
  }

  @NotNull
  public ProcessHandler getProcessHandler() {
    return myProcessOutputManager.getProcessHandler();
  }

  public void setLastTestRunWithTestNameFilter(boolean lastTestRunWithTestNameFilter) {
    myLastTestRunWithTestNameFilter = lastTestRunWithTestNameFilter;
  }

  public boolean isLastTestRunWithTestNameFilter() {
    return myLastTestRunWithTestNameFilter;
  }

  private class MyDisposable implements Disposable {

    private final boolean myDisposed = false;

    @Override
    public void dispose() {
      if (myDisposed) {
        return;
      }
      LOG.info("Disposing Karma server " + myProcessHashCode);
      if (myCoveragePeer != null) {
        FileUtil.asyncDelete(myCoveragePeer.getCoverageTempDir());
      }
      UIUtil.invokeLaterIfNeeded(() -> {
        if (myOnPortBoundCallbacks != null) {
          myOnPortBoundCallbacks.clear();
        }
        if (myOnBrowsersReadyCallbacks != null) {
          myOnBrowsersReadyCallbacks.clear();
        }
      });
      shutdown();
    }
  }
}
