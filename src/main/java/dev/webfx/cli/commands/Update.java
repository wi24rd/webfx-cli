package dev.webfx.cli.commands;

import dev.webfx.cli.core.*;
import dev.webfx.cli.sourcegenerators.*;
import dev.webfx.cli.util.textfile.TextFileThreadTransaction;
import dev.webfx.lib.reusablestream.ReusableStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * @author Bruno Salmon
 */
@Command(name = "update", description = "Update the build chain from webfx.xml files.")
public final class Update extends CommonSubcommand implements Runnable {

    @Option(names={"-o", "--only"}, arity = "1..*", description = "Run only the specified update tasks *.")
    String[] only;

    @Option(names={"-s", "--skip"}, arity = "1..*", description = "Skip the specified update tasks *.")
    String[] skip;

    @Option(names={"-c", "--clean-snapshots"}, description = "Clean m2 snapshots related to this project first.")
    boolean cleanSnapshots;

    @Override
    public void run() {
        setUpLogger();
        execute(only, skip, cleanSnapshots, getWorkspace());
    }

    public static void execute(String[] only, String[] skip, boolean cleanSnapshots, CommandWorkspace workspace) {
        UpdateTasks tasks = new UpdateTasks(only == null);
        tasks.processTaskFlags(only, true);
        tasks.processTaskFlags(skip, false);

        boolean previousCleanSnapshots = MavenUtil.isCleanM2Snapshots();
        MavenUtil.setCleanM2Snapshots(cleanSnapshots);

        try (TextFileThreadTransaction transaction = TextFileThreadTransaction.open()) {

            executeUpdateTasks(workspace.getWorkingDevProjectModule(), tasks);

            transaction.commit(); // Write files generated by previous operation if no exception have been raised
            int operationsCount = transaction.executedOperationsCount();
            if (operationsCount == 0)
                log("Nothing to update - All build files are already up-to-date ✅ ");
            else
                log(operationsCount + " files updated");
        }

        MavenUtil.setCleanM2Snapshots(previousCleanSnapshots);
    }

    static void executeUpdateTasks(DevProjectModule workingModule, UpdateTasks tasks) {
        // Generate meta file for executable modules (dev.webfx.platform.meta.exe/exe.properties) <- always present
        // and config file for executable modules (dev.webfx.platform.conf/src-root.properties) <- present only when using modules with config
        getWorkingAndChildrenModulesInDepth(workingModule)
                .filter(ProjectModule::isExecutable)
                .forEach(module -> {
                    MetaFileGenerator.generateExecutableModuleMetaResourceFile(module);
                    RootConfigFileGenerator.generateExecutableModuleConfigurationResourceFile(module);
                });

        // Generating or updating Maven module files (pom.xml)
        if (tasks.mavenPom)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .forEach(m -> m.getMavenModuleFile().updateAndWrite());

        // Generating files for Java modules (module-info.java and META-INF/services)
        if (tasks.moduleInfoJava || tasks.metaInfServices)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .filter(DevProjectModule::hasMainJavaSourceDirectory)
                    .forEach(m -> {
                        boolean jre = m.getTarget().isAnyPlatformSupported(Platform.JRE); // => module-info.java + META-INF/services for GraalVM
                        boolean teavm = m.getTarget().isAnyPlatformSupported(Platform.TEAVM); // => META-INF/services for TeaVM
                        if (jre) // Not for TeaVM because the TeaVM modules in module-info.java are not recognised by JPMS
                            JavaFilesGenerator.generateModuleInfoJavaFile(m);
                        if (jre /* for GraalVM */ || teavm)
                            JavaFilesGenerator.generateMetaInfServicesFiles(m);
                    });

        if (tasks.gwtXml || tasks.indexHtml || tasks.gwtSuperSources || tasks.gwtServiceLoader || tasks.gwtResourceBundles)
            // Generate files for executable GWT modules (module.gwt.xml, index.html, super sources, service loader, resource bundle)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .filter(m -> m.isExecutable(Platform.GWT))
                    .forEach(GwtFilesGenerator::generateGwtFiles);

        // Generate files for executable Gluon modules (graalvm_config/reflection.json)
        getWorkingAndChildrenModulesInDepth(workingModule)
                .filter(m -> m.isExecutable(Platform.JRE))
                .filter(m -> m.getTarget().hasTag(TargetTag.GLUON))
                .forEach(GluonFilesGenerator::generateGraalVmReflectionJson);
    }

    private static ReusableStream<DevProjectModule> getWorkingAndChildrenModules(DevProjectModule workingModule) {
        return workingModule
                .getThisAndChildrenModules()
                .filter(DevProjectModule.class::isInstance)
                .map(DevProjectModule.class::cast);
    }

    private static ReusableStream<DevProjectModule> getWorkingAndChildrenModulesInDepth(DevProjectModule workingModule) {
        return workingModule
                .getThisAndChildrenModulesInDepth()
                .filter(DevProjectModule.class::isInstance)
                .map(DevProjectModule.class::cast);
    }

    final static class UpdateTasks {

        private Boolean
                webfxXml,
                mavenPom,
                moduleInfoJava,
                metaInfServices,
                indexHtml,
                gwtXml,
                gwtSuperSources,
                gwtServiceLoader,
                gwtResourceBundles;

        private static final String[] TASK_WORDS = {
                "pom.xml",
                "module-info.java",
                "meta-inf/services",
                "index.html",
                "gwt.xml",
                "gwt-super-sources",
                "gwt-service-loader",
                "gwt-resource-bundles",
        };

        private static final char[] TASK_LETTERS = {
                'p', // mavenPom
                'j', // moduleInfoJava
                'm', // metaInfServices
                'h', // indexHtml
                'g', // gwtXml
                's', // gwtSuperSources
                'l', // gwtServiceLoader
                'b', // gwtResourceBundles
                'w', // webfx.xml
        };

        public UpdateTasks(boolean enableAllTasks) {
            if (enableAllTasks)
                for (int i = 0; i < TASK_LETTERS.length; i++)
                    enableTask(i, true);
        }

        private void processTaskFlags(String[] flags, boolean value) {
            if (flags != null)
                for (String flag : flags)
                    processTaskFlag(flag, value);
        }

        private void processTaskFlag(String flag, boolean value) {
            for (int taskIndex = 0; taskIndex < TASK_WORDS.length; taskIndex++)
                if (flag.equalsIgnoreCase(TASK_WORDS[taskIndex])) {
                    enableTask(taskIndex, value);
                    return;
                }
            if (!processTaskLetters(flag, value))
                throw new UnresolvedException("Unrecognized task " + flag);
        }

        private boolean processTaskLetters(String flag, boolean value) {
            for (int taskIndex = 0; taskIndex < TASK_WORDS.length; taskIndex++)
                if (flag.charAt(0) == TASK_LETTERS[taskIndex]) {
                    enableTask(taskIndex, value);
                    if (flag.length() > 1)
                        return processTaskLetters(flag.substring(1), value);
                    return true;
                }
            return false;
        }

        private boolean enableTask(int taskIndex, boolean value) {
            switch (taskIndex) {
                case 0: return mavenPom = value;
                case 1: return moduleInfoJava = value;
                case 2: return metaInfServices = value;
                case 3: return indexHtml = value;
                case 4: return gwtXml = value;
                case 5: return gwtSuperSources = value;
                case 6: return gwtServiceLoader = value;
                case 7: return gwtResourceBundles = value;
                case 8: return webfxXml = value;
            }
            return false;
        }
    }
}
