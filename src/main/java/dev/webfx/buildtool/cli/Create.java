package dev.webfx.buildtool.cli;

import dev.webfx.buildtool.ProjectModule;
import dev.webfx.buildtool.RootModule;
import dev.webfx.buildtool.TargetTag;
import dev.webfx.buildtool.util.textfile.ResourceTextFileReader;
import dev.webfx.buildtool.util.textfile.TextFileReaderWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * @author Bruno Salmon
 */
@Command(name = "create", description = "Create WebFX module(s).",
subcommands = {
        Create.Project.class,
        Create.Application.class,
        Create.Module.class,
})
class Create extends CommonSubcommand {

    static abstract class CreateSubCommand extends CommonSubcommand implements Callable<Void> {

        @Option(names = {"-p", "--project"}, arity = "0..1", fallbackValue = "!", description = "Create as a separate new project.")
        String project;

        private ProjectModule createModule(String name, boolean aggregate) {
            Path projectDirectoryPath = project != null ? getWorkspaceDirectoryPath().resolve(project) : getProjectDirectoryPath();
            Path modulePath = projectDirectoryPath.resolve(name);
            ProjectModule module = getModuleRegistry().getOrCreateProjectModule(modulePath);
            module.getMavenModuleFile().setAggregate(aggregate);
            ProjectModule parentModule = module.getParentModule();
            if (parentModule != null)
                parentModule.getMavenModuleFile().addModule(module);
            return module;
        }

        ProjectModule createAggregateModule(String name, boolean writePom) {
            ProjectModule module = createModule(name, true);
            if (writePom)
                module.getMavenModuleFile().writeFile();
            return module;
        }

        ProjectModule createSourceModule(String name, String templateFileName, String fullClassName, boolean executable) throws IOException {
            ProjectModule module = createModule(name, false);
            Path modulePath = module.getHomeDirectory();
            Path sourcePath = modulePath.resolve("src/main/java");
            Path resourcesPath = modulePath.resolve("src/main/resources");
            Path testPath = modulePath.resolve("src/test/java");
            Files.createDirectories(sourcePath);
            Files.createDirectories(resourcesPath);
            Files.createDirectories(testPath);
            if (templateFileName != null && fullClassName != null) {
                int p = fullClassName.lastIndexOf('.');
                String packageName = fullClassName.substring(0, p);
                String className = fullClassName.substring(p + 1);
                Path packagePath = sourcePath.resolve(packageName.replace('.', '/'));
                Path javaFilePath = packagePath.resolve(className + ".java");
                String template = ResourceTextFileReader.readTemplate(templateFileName)
                        .replace("${package}", packageName)
                        .replace("${class}", className);
                if (!Files.exists(javaFilePath))
                    TextFileReaderWriter.writeTextFile(template, javaFilePath);
                if (template.contains("javafx.application.Application"))
                    module.getWebFxModuleFile().addProvider("javafx.application.Application", fullClassName);
            }
            module.getWebFxModuleFile().setExecutable(executable);
            module.getWebFxModuleFile().writeFile();
            return module;
        }
    }

    @Command(name = "project", description = "Create a new project.")
    static class Project extends CreateSubCommand {

        @Option(names = {"-i", "--inline"}, description = "Inline the WebFX parent pom instead of referencing it.")
        private boolean inline;

        @Parameters(paramLabel = "groupId", description = "GroupId of the project artifact.")
        private String groupId;

        @Parameters(paramLabel = "artifactId", description = "ArtifactId of the project artifact.")
        private String artifactId;

        @Parameters(paramLabel = "version", description = "Version of the project artifact.")
        private String version;

        @Override
        public Void call() {
            project = artifactId;
            RootModule module = (RootModule) createAggregateModule("", false);
            module.setGroupId(groupId);
            module.setArtifactId(artifactId);
            module.setVersion(version);
            module.setInlineWebfxParent(inline);
            module.getMavenModuleFile().writeFile();
            return null;
        }
    }

    @Command(name = "module", description = "Create a single generic module.")
    static class Module extends CreateSubCommand {

        @Parameters(paramLabel = "name", description = "Name of the new module.")
        private String name;

        @Option(names={"-c", "--class"}, description = "Fully qualified class name.")
        private String moduleClassName;

        @Option(names={"-a", "--aggregate"}, description = "Will create an aggregate pom.xml module.")
        private boolean aggregate;

        @Override
        public Void call() throws Exception {
            ProjectModule module = aggregate ? createAggregateModule(name, true) : createSourceModule(name, ResourceTextFileReader.readTemplate("Class.java"), moduleClassName, false);
            module.getParentModule().getMavenModuleFile().writeFile();
            return null;
        }
    }

    @Command(name = "application", description = "Create modules for a WebFX application.")
    static class Application extends CreateSubCommand {
        @Option(names = {"-g", "--gluon"}, description = "Also create the gluon module.")
        private boolean gluon;

        @Parameters(paramLabel = "prefix", arity = "0..1", description = "Prefix of the modules that will be created.")
        private String prefix;

        @Option(names={"-c", "--class"}, description = "Fully qualified JavaFX Application class name.")
        private String javaFxApplication;

        @Option(names = {"-w", "--helloWorld"}, description = "Use hello world code template.")
        private boolean helloWorld;

        @Override
        public Void call() throws Exception {
            if ("!".equals(project))
                project = prefix;
            if (prefix == null) {
                if (project != null)
                    prefix = project;
                else
                    prefix = getModuleRegistry().getOrCreateProjectModule(getProjectDirectoryPath()).getName();
            }
            ProjectModule applicationModule = createTagModule(null);
            createTagModule(TargetTag.OPENJFX);
            createTagModule(TargetTag.GWT);
            if (gluon)
                createTagModule(TargetTag.GLUON);
            applicationModule.getParentModule().getMavenModuleFile().writeFile();
            return null;
        }

        private ProjectModule createTagModule(TargetTag targetTag) throws IOException {
            if (targetTag == null)
                return createSourceModule(prefix + "-application", helloWorld ? "JavaFxHelloWorldApplication.java" : "JavaFxApplication.java", javaFxApplication, false);
            return createSourceModule(prefix + "-application-" + targetTag.name().toLowerCase(), null, null, true);
        }
    }
}
