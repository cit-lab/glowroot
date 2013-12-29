/*
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.container.javaagent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.MainEntryPoint;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.ClassPath;
import org.glowroot.container.Container;
import org.glowroot.container.SpyingLogFilter;
import org.glowroot.container.SpyingLogFilter.MessageCount;
import org.glowroot.container.SpyingLogFilterCheck;
import org.glowroot.container.TempDirs;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.javaagent.JavaagentConfigService.GetUiPortCommand;
import org.glowroot.container.trace.TraceService;
import org.glowroot.markers.ThreadSafe;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class JavaagentContainer implements Container {

    private static final Logger logger = LoggerFactory.getLogger(JavaagentContainer.class);

    private final File dataDir;
    private final boolean deleteDataDirOnClose;
    private final boolean shared;

    private final ServerSocket serverSocket;
    private final SocketCommander socketCommander;
    private final ExecutorService consolePipeExecutorService;
    private final Process process;
    private final ConsoleOutputPipe consoleOutputPipe;
    private final JavaagentHttpClient httpClient;
    private final JavaagentConfigService configService;
    private final JavaagentTraceService traceService;
    private final Thread shutdownHook;

    public static JavaagentContainer create() throws Exception {
        return new JavaagentContainer(null, false, false, false);
    }

    public static JavaagentContainer createWithFileDb() throws Exception {
        return new JavaagentContainer(null, true, false, false);
    }

    public static JavaagentContainer createWithFileDb(File dataDir) throws Exception {
        return new JavaagentContainer(dataDir, true, false, false);
    }

    public JavaagentContainer(@Nullable File dataDir, boolean useFileDb, boolean shared,
            final boolean captureConsoleOutput) throws Exception {
        if (dataDir == null) {
            this.dataDir = TempDirs.createTempDir("glowroot-test-datadir");
            deleteDataDirOnClose = true;
        } else {
            this.dataDir = dataDir;
            deleteDataDirOnClose = false;
        }
        this.shared = shared;
        // need to start socket listener before spawning process so process can connect to socket
        serverSocket = new ServerSocket(0);
        // default to port 0 (any available)
        File configFile = new File(this.dataDir, "config.json");
        if (!configFile.exists()) {
            Files.write("{\"ui\":{\"port\":0}}", configFile, Charsets.UTF_8);
        }
        List<String> command = buildCommand(serverSocket.getLocalPort(), this.dataDir, useFileDb);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        final Process process = processBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        InputStream in = process.getInputStream();
        if (in == null) {
            // process.getInputStream() only returns null if ProcessBuilder.redirectOutput() is used
            // to redirect output to a file
            throw new AssertionError("Process.getInputStream() returned null");
        }
        consoleOutputPipe = new ConsoleOutputPipe(in, System.out, captureConsoleOutput);
        consolePipeExecutorService.submit(consoleOutputPipe);
        this.process = process;
        Socket socket = serverSocket.accept();
        ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
        socketCommander = new SocketCommander(objectOut, objectIn);
        int uiPort = getUiPort(socketCommander);
        if (uiPort == SocketCommandProcessor.NO_PORT) {
            socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN);
            socketCommander.close();
            process.waitFor();
            serverSocket.close();
            consolePipeExecutorService.shutdownNow();
            throw new StartupFailedException();
        }
        httpClient = new JavaagentHttpClient(uiPort);
        this.configService = new JavaagentConfigService(httpClient,
                new JavaagentContainerGetUiPort(socketCommander));
        traceService = new JavaagentTraceService(httpClient);
        shutdownHook = new ShutdownHookThread(socketCommander);
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        if (SpyingLogFilterCheck.isSpyingLogFilterEnabled()) {
            socketCommander.sendCommand(ImmutableList.of(
                    SocketCommandProcessor.ADD_EXPECTED_LOG_MESSAGE, loggerName, partialMessage));
        } else {
            throw new AssertionError(SpyingLogFilter.class.getSimpleName() + " is not enabled");
        }
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {
        socketCommander.sendCommand(ImmutableList.of(SocketCommandProcessor.EXECUTE_APP,
                appUnderTestClass.getName()));
        // wait for all traces to be stored
        Stopwatch stopwatch = new Stopwatch().start();
        while (traceService.getNumPendingCompleteTraces() > 0 && stopwatch.elapsed(SECONDS) < 5) {
            Thread.sleep(10);
        }
    }

    public void interruptAppUnderTest() throws Exception {
        socketCommander.sendCommand(SocketCommandProcessor.INTERRUPT);
    }

    public TraceService getTraceService() {
        return traceService;
    }

    public int getUiPort() throws Exception {
        return getUiPort(socketCommander);
    }

    public void checkAndReset() throws Exception {
        traceService.assertNoActiveTraces();
        traceService.deleteAllSnapshots();
        configService.resetAllConfig();
        // check and reset log messages
        if (SpyingLogFilterCheck.isSpyingLogFilterEnabled()) {
            MessageCount logMessageCount = (MessageCount) socketCommander
                    .sendCommand(SocketCommandProcessor.CLEAR_LOG_MESSAGES);
            if (logMessageCount == null) {
                throw new AssertionError("Command returned null: "
                        + SocketCommandProcessor.CLEAR_LOG_MESSAGES);
            }
            if (logMessageCount.getExpectedCount() > 0) {
                throw new AssertionError("One or more expected messages were not logged");
            }
            if (logMessageCount.getUnexpectedCount() > 0) {
                throw new AssertionError("One or more unexpected messages were logged");
            }
        }
    }

    public void close() throws Exception {
        close(false);
    }

    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared container and will be closed at the end of the run
            return;
        }
        socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN);
        cleanup();
    }

    public void kill() throws Exception {
        socketCommander.sendKillCommand();
        cleanup();
    }

    public List<String> getUnexpectedConsoleLines() {
        List<String> unexpectedLines = Lists.newArrayList();
        Splitter splitter = Splitter.on(Pattern.compile("\r?\n")).omitEmptyStrings();
        String capturedOutput = consoleOutputPipe.getCapturedOutput();
        if (capturedOutput == null) {
            throw new IllegalStateException("JavaagentContainer was created with"
                    + " captureConsoleOutput=false");
        }
        for (String line : splitter.split(capturedOutput)) {
            if (line.contains("Glowroot started") || line.contains("Glowroot listening")) {
                continue;
            }
            unexpectedLines.add(line);
        }
        return unexpectedLines;
    }

    private void cleanup() throws Exception {
        socketCommander.close();
        process.waitFor();
        serverSocket.close();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        httpClient.close();
        if (deleteDataDirOnClose) {
            TempDirs.deleteRecursively(dataDir);
        }
    }

    private static int getUiPort(SocketCommander socketCommander) throws Exception {
        Integer port = (Integer) socketCommander.sendCommand(SocketCommandProcessor.GET_PORT);
        if (port == null) {
            throw new AssertionError("Command returned null: " + SocketCommandProcessor.GET_PORT);
        }
        return port;
    }

    public static void main(String[] args) throws Exception {
        try {
            int port = Integer.parseInt(args[0]);
            Socket socket = new Socket((String) null, port);
            ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
            new Thread(new SocketHeartbeat(objectOut)).start();
            new Thread(new SocketCommandProcessor(objectIn, objectOut)).start();
        } catch (Throwable t) {
            // log error and exit gracefully
            logger.error(t.getMessage(), t);
        }
        // spin a bit to so that caller can capture a trace with <multiple root nodes> if desired
        for (int i = 0; i < 1000; i++) {
            metricOne();
            metricTwo();
            Thread.sleep(1);
        }
        // do not close socket since program is still running after main returns
    }

    private static void metricOne() throws InterruptedException {
        Thread.sleep(1);
    }

    private static void metricTwo() throws InterruptedException {
        Thread.sleep(1);
    }

    private static List<String> buildCommand(int containerPort, File dataDir, boolean useFileDb)
            throws Exception {
        List<String> command = Lists.newArrayList();
        String javaExecutable = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        String classpath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classpath);
        command.addAll(getJavaAgentsFromCurrentJvm());
        File javaagentJarFile = ClassPath.getGlowrootCoreJarFile();
        if (javaagentJarFile == null) {
            // create jar file in data dir since that gets cleaned up at end of test already
            javaagentJarFile = DelegatingJavaagent.createDelegatingJavaagentJarFile(dataDir);
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + MainEntryPoint.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        command.add("-Dglowroot.data.dir=" + dataDir.getAbsolutePath());
        if (!useFileDb) {
            command.add("-Dglowroot.internal.h2.memdb=true");
        }
        Integer aggregateInterval =
                Integer.getInteger("glowroot.internal.collector.aggregateInterval");
        if (aggregateInterval != null) {
            command.add("-Dglowroot.internal.collector.aggregateInterval=" + aggregateInterval);
        }
        command.add(JavaagentContainer.class.getName());
        command.add(Integer.toString(containerPort));
        return command;
    }

    private static List<String> getJavaAgentsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> javaAgents = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:")) {
                // pass on the jacoco agent in particular
                javaAgents.add(argument);
            }
        }
        return javaAgents;
    }

    private static class JavaagentContainerGetUiPort implements GetUiPortCommand {

        private final SocketCommander socketCommander;

        private JavaagentContainerGetUiPort(SocketCommander socketCommander) {
            this.socketCommander = socketCommander;
        }

        public int getUiPort() throws Exception {
            return JavaagentContainer.getUiPort(socketCommander);
        }
    }

    private static class ShutdownHookThread extends Thread {

        private final SocketCommander socketCommander;

        private ShutdownHookThread(SocketCommander socketCommander) {
            this.socketCommander = socketCommander;
        }

        @Override
        public void run() {
            try {
                socketCommander.sendKillCommand();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @ThreadSafe
    private static class ConsoleOutputPipe implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        // the one place ever that StringBuffer's synchronization is useful :-)
        @Nullable
        private final StringBuffer capturedOutput;

        private ConsoleOutputPipe(InputStream in, OutputStream out, boolean captureOutput) {
            this.in = in;
            this.out = out;
            if (captureOutput) {
                capturedOutput = new StringBuffer();
            } else {
                capturedOutput = null;
            }
        }

        public void run() {
            byte[] buffer = new byte[100];
            try {
                while (true) {
                    int n = in.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    if (capturedOutput != null) {
                        // intentionally using platform default charset
                        capturedOutput.append(new String(buffer, 0, n));
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

        @Nullable
        private String getCapturedOutput() {
            return capturedOutput == null ? null : capturedOutput.toString();
        }
    }
}