package dev.webfx.tool.cli.util.process;

import dev.webfx.tool.cli.core.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author Bruno Salmon
 */
public class ProcessCall {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    private File workingDirectory;

    private String command;

    private Predicate<String> logLineFilter;

    private Predicate<String> resultLineFilter;

    private String lastResultLine;

    private boolean logsCalling = true;

    private boolean logsCallDuration = true;

    private StreamGobbler streamGobbler;

    private int exitCode;

    private long callDurationMillis;

    public ProcessCall() {
    }

    public ProcessCall(String command) {
        setCommand(command);
    }

    public ProcessCall setCommand(String command) {
        this.command = command;
        return this;
    }

    public ProcessCall setWorkingDirectory(Path workingDirectory) {
        return setWorkingDirectory(workingDirectory.toFile());
    }

    public ProcessCall setWorkingDirectory(File workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public File getWorkingDirectory() {
        return workingDirectory;
    }

    public ProcessCall setLogLineFilter(Predicate<String> logLineFilter) {
        this.logLineFilter = logLineFilter;
        return this;
    }

    public ProcessCall setResultLineFilter(Predicate<String> resultLineFilter) {
        this.resultLineFilter = resultLineFilter;
        return this;
    }

    public ProcessCall setLogsCall(boolean logsCalling, boolean logsCallDuration) {
        this.logsCalling = logsCalling;
        this.logsCallDuration = logsCallDuration;
        return this;
    }

    public ProcessCall executeAndWait() {
        executeAndConsume(line -> {
            if (logLineFilter == null || logLineFilter.test(line))
                Logger.log(line);
            if (resultLineFilter == null || resultLineFilter.test(line))
                lastResultLine = line;
        });
        return this;
    }

    public ProcessCall logCallCommand() {
        Logger.log((workingDirectory == null ? "" : workingDirectory) + "$ " + command);
        return this;
    }

    public ProcessCall logCallDuration() {
        Logger.log("Call duration: " + callDurationMillis + " ms");
        return this;
    }

    public ProcessCall onLastResultLine(Consumer<String> lastResultLineConsumer) {
        waitForStreamGobblerCompleted();
        lastResultLineConsumer.accept(lastResultLine);
        return this;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getLastResultLine() {
        waitForStreamGobblerCompleted();
        return lastResultLine;
    }

    private void executeAndConsume(Consumer<String> outputLineConsumer) {
        if (logsCalling)
            logCallCommand();
        if (WINDOWS)
            command = "cmd /c " + command; // Required in Windows for Path resolution (otherwise it won't find commands like mvn)
        long t0 = System.currentTimeMillis();
        try {
            Process process = new ProcessBuilder()
                    .command(command.split(" "))
                    .directory(workingDirectory)
                    .start();
            streamGobbler = new StreamGobbler(process.getInputStream(), outputLineConsumer);
            Executors.newSingleThreadExecutor().submit(streamGobbler);
            exitCode = process.waitFor();
            callDurationMillis = System.currentTimeMillis() - t0;
            if (logsCallDuration)
                logCallDuration();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void waitForStreamGobblerCompleted() {
        while (streamGobbler != null && !streamGobbler.isCompleted())
            try {
                synchronized (streamGobbler) {
                    streamGobbler.wait(1);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    public static int execute(String command) {
        return new ProcessCall(command).executeAndWait().getExitCode();
    }

}
