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
@Command(name = "update", description = "Update the build chain from webfx.xml files. If no specific Update option is specified, run them all.")
public final class Update extends CommonSubcommand implements Runnable {

    @Option(names={"-c", "--clean-snapshots"}, description = "Clean m2 snapshots related to this project first.")
    boolean cleanSnapshots;

    @Option(names={"-p", "--pom"}, description = "Update pom.xml files.")
    boolean pom;

    @Option(names={"-o", "--module-info"}, description = "Update module-info.java files.")
    boolean moduleInfo;

    @Option(names={"-t", "--meta-inf"}, description = "Update META-INF/services files.")
    boolean metaInfServices;

    @Option(names={"-h", "--html"}, description = "Update index.html files.")
    boolean indexHtml;

    @Option(names={"-x", "--gwt-xml"}, description = "Update module.gwt.xml files.")
    boolean gwtXml;

    @Option(names={"-s", "--gwt-super"}, description = "Update GWT super source files.")
    boolean gwtSuperSources;

    @Option(names={"-e", "--gwt-embed"}, description = "Update GWT embed resource files.")
    boolean gwtEmbedResource;

    @Option(names={"-a", "--graalvm"}, description = "Update GraalVM files (Gluon).")
    boolean graalvm;

    @Option(names={"-m", "--meta"}, description = "Update meta.properties files.")
    boolean meta;

    @Option(names={"-f", "--conf"}, description = "Update WebFX configuration files.")
    boolean conf;

    @Option(names={"-i", "--i18n"}, description = "Update WebFX i18n files.")
    boolean i18n;

    @Override
    public void run() {
        setUpLogger();
        UpdateTasks tasks = new UpdateTasks();
        tasks.pom = pom;
        tasks.moduleInfo = moduleInfo;
        tasks.metaInfServices = metaInfServices;
        tasks.indexHtml = indexHtml;
        tasks.gwtXml = gwtXml;
        tasks.gwtSuperSources = gwtSuperSources;
        tasks.gwtEmbedResource = gwtEmbedResource;
        tasks.graalvm = graalvm;
        tasks.meta = meta;
        tasks.conf = conf;
        tasks.i18n = i18n;
        execute(cleanSnapshots, tasks, getWorkspace());
    }

    public static void execute(boolean cleanSnapshots, UpdateTasks tasks, CommandWorkspace workspace) {

        boolean previousCleanSnapshots = MavenUtil.isCleanM2Snapshots();
        MavenUtil.setCleanM2Snapshots(cleanSnapshots);

        tasks.enableAllTasksIfUnset();

        try (TextFileThreadTransaction transaction = TextFileThreadTransaction.open()) {

            executeUpdateTasks(workspace.getWorkingDevProjectModule(), tasks);

            transaction.commit(); // Write files generated by previous operation if no exception have been raised
            int operationsCount = transaction.executedOperationsCount();
            if (operationsCount == 0)
                log("Nothing to update - All files are already up-to-date ✅ ");
            else
                log(operationsCount + " files updated");
        }

        MavenUtil.setCleanM2Snapshots(previousCleanSnapshots);
    }

    static void executeUpdateTasks(DevProjectModule workingModule, UpdateTasks tasks) {
        // Generate meta file for executable modules (dev.webfx.platform.meta.exe/exe.properties) <- always present
        // and config file for executable modules (dev.webfx.platform.conf/src-root.properties) <- present only when using modules with config
        if (tasks.meta || tasks.conf || tasks.i18n)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .filter(ProjectModule::isExecutable)
                    .forEach(module -> {
                        if (tasks.meta)
                            MetaFileGenerator.generateExecutableModuleMetaResourceFile(module);
                        if (tasks.conf)
                            RootConfigFileGenerator.generateExecutableModuleConfigurationResourceFile(module, !tasks.pom);
                        if (tasks.i18n)
                            RootI18nFileGenerator.generateExecutableModuleI18nResourceFile(module, !tasks.pom);
                    });

        // Generating or updating Maven module files (pom.xml)
        if (tasks.pom)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .forEach(m -> m.getMavenModuleFile().updateAndWrite());

        // Generating files for Java modules (module-info.java and META-INF/services)
        if (tasks.moduleInfo || tasks.metaInfServices)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .filter(DevProjectModule::hasMainJavaSourceDirectory)
                    // Skipping module-info.java for some special cases
                    .filter(m -> !SpecificModules.skipJavaModuleInfo(m.getName()))
                    .forEach(m -> {
                        boolean jre = m.getTarget().isAnyPlatformSupported(Platform.JRE); // => module-info.java + META-INF/services for GraalVM
                        boolean gwt = m.getTarget().hasTag(TargetTag.GWT);
                        boolean j2cl = m.getTarget().hasTag(TargetTag.J2CL);
                        boolean teavm = m.getTarget().isAnyPlatformSupported(Platform.TEAVM); // => META-INF/services for TeaVM
                        boolean web = gwt || j2cl || m.getTarget().hasTag(TargetTag.EMUL);
                        if (jre && !web) // Not for TeaVM because the TeaVM modules in module-info.java are not recognised by JPMS
                            JavaFilesGenerator.generateModuleInfoJavaFile(m);
                        if (jre /* for GraalVM */ || teavm)
                            JavaFilesGenerator.generateMetaInfServicesFiles(m);
                    });

        if (tasks.gwtXml || tasks.indexHtml || tasks.gwtSuperSources || tasks.gwtEmbedResource)
            // Generate files for executable GWT modules (module.gwt.xml, index.html, super sources, service loader, resource bundle)
            getWorkingAndChildrenModulesInDepth(workingModule)
                    .filter(m -> m.isExecutable(Platform.GWT) || m.isExecutable(Platform.J2CL))
                    .forEach(GwtJ2clFilesGenerator::generateGwtJ2clFiles);

        // Generate files for executable Gluon modules (graalvm_config/reflection.json)
        if (tasks.graalvm)
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

        private boolean
                pom,
                moduleInfo,
                metaInfServices,
                indexHtml,
                gwtXml,
                gwtSuperSources,
                gwtEmbedResource,
                graalvm,
                meta,
                conf,
                i18n;

        void enableAllTasksIfUnset() {
            if (!pom &&
                !moduleInfo &&
                !metaInfServices &&
                !indexHtml &&
                !gwtXml &&
                !gwtSuperSources &&
                !gwtEmbedResource &&
                !graalvm &&
                !meta &&
                !conf &&
                !i18n) {
                    pom =
                    moduleInfo =
                    metaInfServices =
                    indexHtml =
                    gwtXml =
                    gwtSuperSources =
                    gwtEmbedResource =
                    graalvm =
                    meta =
                    conf =
                    i18n =
                    true;
            }
        }
    }
}
