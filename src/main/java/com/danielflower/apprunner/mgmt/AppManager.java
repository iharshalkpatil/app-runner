package com.danielflower.apprunner.mgmt;

import com.danielflower.apprunner.FileSandbox;
import com.danielflower.apprunner.problems.AppRunnerException;
import com.danielflower.apprunner.runners.AppRunner;
import com.danielflower.apprunner.runners.RunnerProvider;
import com.danielflower.apprunner.runners.Waiter;
import com.danielflower.apprunner.web.WebServer;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.StoredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.danielflower.apprunner.FileSandbox.dirPath;

public class AppManager implements AppDescription {
    public static final Logger log = LoggerFactory.getLogger(AppManager.class);

    public static AppManager create(String gitUrl, FileSandbox fileSandbox, String name) {
        File root = fileSandbox.appDir(name);
        File gitDir = fileSandbox.appDir(name, "repo");
        File instanceDir = fileSandbox.appDir(name, "instances");

        Git git;
        try {
            try {
                git = Git.open(gitDir);
            } catch (RepositoryNotFoundException e) {
                git = Git.cloneRepository()
                    .setURI(gitUrl)
                    .setBare(false)
                    .setDirectory(gitDir)
                    .call();
            }
        } catch (IOException | GitAPIException e) {
            throw new AppRunnerException("Could not open or create git repo at " + gitDir, e);
        }
        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", "origin", "url", gitUrl);
        try {
            config.save();
        } catch (IOException e) {
            throw new AppRunnerException("Error while setting remove on Git repo at " + gitDir, e);
        }
        log.info("Created app manager for " + name + " in dir " + root);
        return new AppManager(name, gitUrl, git, instanceDir);
    }

    private final String gitUrl;
    private final String name;
    private final Git git;
    private final File instanceDir;
    private final List<AppChangeListener> listeners = new ArrayList<>();
    private AppRunner currentRunner;
    private String latestBuildLog;
    private final CircularFifoQueue<String> consoleLog = new CircularFifoQueue<>(5000);

    private AppManager(String name, String gitUrl, Git git, File instanceDir) {
        this.gitUrl = gitUrl;
        this.name = name;
        this.git = git;
        this.instanceDir = instanceDir;
    }

    public String name() {
        return name;
    }

    public String gitUrl() {
        return gitUrl;
    }

    public String latestBuildLog() {
        return latestBuildLog;
    }

    public String latestConsoleLog() {
        synchronized (consoleLog) {
            return consoleLog.stream().collect(Collectors.joining());
        }
    }

    public synchronized void stopApp() throws Exception {
        if (currentRunner != null) {
            currentRunner.shutdown();
            currentRunner = null;
        }
    }

    public synchronized void update(RunnerProvider runnerProvider, InvocationOutputHandler outputHandler) throws Exception {
        clearLogs();

        InvocationOutputHandler buildLogHandler = line -> {
            outputHandler.consumeLine(line);
            latestBuildLog += line + "\n";
        };

        // Well this is complicated.
        // Basically, we want the build log to contain a bit of the startup, and then detach itself.
        AtomicReference<InvocationOutputHandler> buildLogHandle = new AtomicReference<>(buildLogHandler);
        InvocationOutputHandler consoleLogHandler = line -> {
            InvocationOutputHandler another = buildLogHandle.get();
            if (another != null) {
                another.consumeLine(StringUtils.stripEnd(line, "\r\n"));
            }
            synchronized (consoleLog) {
                consoleLog.add(line);
            }
        };


        buildLogHandler.consumeLine("Fetching latest changes from git...");
        git.pull().setRemote("origin").call();
        File id = copyToNewInstanceDir();
        buildLogHandler.consumeLine("Created new instance in " + dirPath(id));

        AppRunner oldRunner = currentRunner;
        currentRunner = runnerProvider.runnerFor(name(), id);
        int port = WebServer.getAFreePort();

        HashMap<String, String> envVarsForApp = createAppEnvVars(port, name);

        try (Waiter startupWaiter = Waiter.waitForApp(name, port)) {
            currentRunner.start(buildLogHandler, consoleLogHandler, envVarsForApp, startupWaiter);
        }

        buildLogHandle.set(null);

        for (AppChangeListener listener : listeners) {
            listener.onAppStarted(name, new URL("http://localhost:" + port + "/" + name));
        }
        if (oldRunner != null) {
            buildLogHandler.consumeLine("Shutting down previous version");
            log.info("Shutting down previous version of " + name);
            oldRunner.shutdown();
            buildLogHandler.consumeLine("Deployment complete.");



            // TODO: delete old instance dir
        }
    }

    public void clearLogs() {
        latestBuildLog = "";
        synchronized (consoleLog) {
            consoleLog.clear();
        }
    }

    public static HashMap<String, String> createAppEnvVars(int port, String name) {
        HashMap<String, String> envVarsForApp = new HashMap<>(System.getenv());
        envVarsForApp.put("APP_PORT", String.valueOf(port));
        envVarsForApp.put("APP_NAME", name);
        envVarsForApp.put("APP_ENV", "prod");
        return envVarsForApp;
    }

    public void addListener(AppChangeListener appChangeListener) {
        listeners.add(appChangeListener);
    }

    public interface AppChangeListener {
        void onAppStarted(String name, URL newUrl);
    }

    private File copyToNewInstanceDir() throws IOException {
        File dest = new File(instanceDir, String.valueOf(System.currentTimeMillis()));
        dest.mkdir();
        FileUtils.copyDirectory(git.getRepository().getWorkTree(), dest, pathname -> !pathname.getName().equals(".git"));
        return dest;
    }

    public static String nameFromUrl(String gitUrl) {
        String name = StringUtils.removeEndIgnoreCase(StringUtils.removeEnd(gitUrl, "/"), ".git");
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return name;
    }
}
