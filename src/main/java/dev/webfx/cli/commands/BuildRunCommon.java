package dev.webfx.cli.commands;

import dev.webfx.cli.core.*;
import dev.webfx.cli.modulefiles.DevMavenPomModuleFile;
import dev.webfx.cli.util.os.OperatingSystem;
import dev.webfx.cli.util.process.ProcessCall;
import dev.webfx.lib.reusablestream.ReusableStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Bruno Salmon
 */
final class BuildRunCommon {
    final boolean build;
    final boolean run;
    final boolean gwt;
    final boolean fatjar;
    final boolean openJfxDesktop;
    final boolean gluonDesktop;
    final boolean android;
    final boolean ios;
    final boolean locate;
    final boolean show;
    final boolean raiseExceptionIfEmpty;
    final boolean returnGluonModuleOnly;

    public BuildRunCommon(boolean build, boolean run, boolean gwt, boolean fatjar, boolean openJfxDesktop, boolean gluonDesktop, boolean android, boolean ios, boolean locate, boolean show, boolean raiseExceptionIfEmpty, boolean returnGluonModuleOnly) {
        this.build = build;
        this.run = run;
        this.gwt = gwt;
        this.fatjar = fatjar;
        this.openJfxDesktop = openJfxDesktop;
        this.gluonDesktop = gluonDesktop;
        this.android = android;
        this.ios = ios;
        this.locate = locate;
        this.show = show;
        this.raiseExceptionIfEmpty = raiseExceptionIfEmpty;
        this.returnGluonModuleOnly = returnGluonModuleOnly;
    }

    public DevProjectModule findExecutableModule(DevProjectModule workingModule, DevProjectModule topRootModule) {
        ReusableStream<DevProjectModule> executableModules = findExecutableModules(workingModule);
        if (executableModules.isEmpty()) {
            executableModules = findExecutableModules(topRootModule);
            if (!executableModules.isEmpty())
                Logger.log("NOTE: No executable module under " + workingModule + " so searching over the whole repository");
            else if (raiseExceptionIfEmpty)
                throw new CliException("No executable module found");
        }
        if (locate || show) {
            if (locate)
                executableModules.flatMap(this::getExecutableFilePath).map(ProcessCall::toShellLogCommandToken).forEach(Logger::log);
            if (show)
                executableModules.flatMap(this::getExecutableFilePath).forEach(BuildRunCommon::showFile);
            return null;
        }
        if (returnGluonModuleOnly)
            executableModules = executableModules.filter(m -> m.getTarget().hasTag(TargetTag.GLUON));
        if (executableModules.count() > 1)
            throw new CliException("Ambiguous executable modules. Please add one of the following options:\n" + executableModules.map(m -> "-M " + m.getName()).collect(Collectors.joining("\n")));
        return executableModules.findFirst().orElse(null);
    }


    ReusableStream<DevProjectModule> findExecutableModules(DevProjectModule startingModule) {
        boolean openJfx = fatjar || openJfxDesktop;
        boolean gluon = gluonDesktop || android || ios;
        return startingModule
                .getThisAndChildrenModulesInDepth()
                .filter(ProjectModule::isExecutable)
                .filter(m ->
                        m.isExecutable(Platform.GWT) ? gwt :
                        m.getTarget().hasTag(TargetTag.OPENJFX) ? openJfx :
                        m.getTarget().hasTag(TargetTag.GLUON) && gluon)
                .map(DevProjectModule.class::cast)
                ;
    }

    ReusableStream<Path> getExecutableFilePath(DevProjectModule module) {
        List<Path> executablePaths = new ArrayList<>();

        Path targetPath = module.getHomeDirectory().resolve("target");
        if (module.isExecutable(Platform.GWT)) {
            if (gwt)
                executablePaths.add(targetPath.resolve(module.getName() + "-" + module.getVersion() + "/" + module.getName().replace('-', '_') + "/index.html"));
        } else if (module.isExecutable(Platform.JRE)) {
            String applicationName = DevMavenPomModuleFile.getApplicationName(module);
            if (module.getTarget().hasTag(TargetTag.OPENJFX)) {
                if (fatjar)
                    executablePaths.add(targetPath.resolve(module.getName() + "-" + module.getVersion() + "-fat.jar"));
                if (openJfxDesktop)
                    executablePaths.add(targetPath.resolve("javapackager/" + applicationName + "/" + applicationName + (OperatingSystem.isMacOs() ? ".app" : OperatingSystem.isWindows() ? ".exe" : "")));
            } else if (module.getTarget().hasTag(TargetTag.GLUON)) {
                switch (OperatingSystem.getOsFamily()) {
                    case LINUX:
                        if (gluonDesktop)
                            executablePaths.add(targetPath.resolve("gluonfx/x86_64-linux/" + applicationName));
                        if (android)
                            executablePaths.add(targetPath.resolve("gluonfx/aarch64-android/gvm/" + applicationName + ".apk"));
                        break;
                    case MAC_OS:
                        if (gluonDesktop)
                            executablePaths.add(targetPath.resolve("gluonfx/x86_64-darwin/" + applicationName));
                        if (ios)
                            executablePaths.add(targetPath.resolve("gluonfx/arm64-ios/" + applicationName + ".ipa"));
                        break;
                    case WINDOWS:
                        if (gluonDesktop)
                            executablePaths.add(targetPath.resolve("gluonfx/x86_64-windows/" + applicationName + ".exe"));
                        break;
                }
            }
        }

        return ReusableStream.fromIterable(executablePaths);
    }

    private static void showFile(Path filePath) {
        try {
            String pathName = filePath.toString();
            if (!Files.exists(filePath))
                Logger.log("Can't reveal nonexistent file " + ProcessCall.toShellLogCommandToken(pathName));
            else if (OperatingSystem.isMacOs())
                ProcessCall.executeCommandTokens("open", "--reveal", pathName);
            else if (OperatingSystem.isLinux())
                ProcessCall.executeCommandTokens("nautilus", pathName);
            else if (OperatingSystem.isWindows())
                ProcessCall.executeCommandTokens("explorer", "/select," + ProcessCall.toShellLogCommandToken(pathName));
        } catch (Exception e) {
            throw new CliException(e.getMessage());
        }
    }

}
