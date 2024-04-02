package dev.webfx.cli.core;

import dev.webfx.cli.modulefiles.*;
import dev.webfx.cli.modulefiles.abstr.WebFxModuleFile;
import dev.webfx.cli.modulefiles.abstr.WebFxModuleFileCache;
import dev.webfx.cli.util.sort.TopologicalSort;
import dev.webfx.cli.util.splitfiles.SplitFiles;
import dev.webfx.cli.util.textfile.TextFileReaderWriter;
import dev.webfx.lib.reusablestream.ReusableStream;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * @author Bruno Salmon
 */
public class DevProjectModule extends ProjectModuleImpl {

    private final Path homeDirectory;
    private Boolean hasSourceDirectory, hasMainJavaSourceDirectory, hasMainResourcesDirectory, hasMainWebFxDirectory, hasTestJavaSourceDirectory;
    private DevJavaModuleInfoFile mainJavaModuleInfoFile;
    private WebFxModuleFile webFxModuleFile;
    private DevMavenPomModuleFile mavenPomModuleFile;
    private DevGwtModuleFile gwtModuleFile;
    private DevGwtHtmlFile gwtHtmlFile;
    // The webfx root module of the application repository which may be different from the root module in case of
    // aggregate modules with different submodules (each submodule is a different app repo with its own root webfx module).
    private DevProjectModule webFxRootModule;

    private final ReusableStream<String> fileResourcePackagesCache =
            ReusableStream.create(() -> ReusableStream.create(() -> // Using deferred creation because we can't call these methods before the constructor is executed
                            hasMainResourcesDirectory() ? SplitFiles.uncheckedWalk(getMainResourcesDirectory()) : Spliterators.emptySpliterator())
                    // We want to filter directories that are not empty. To do that by walking through files and getting their parent directory
                    .filter(path -> {
                        if (Files.isDirectory(path))
                            return false;
                        // We also ignore hidden files
                        try {
                            if (Files.isHidden(path))
                                return false;
                        } catch (IOException e) {
                            return false;
                        }
                        return true;
                    })
                    .map(Path::getParent)
                    // We remove duplicates (because the directory was repeated by the number of files in it)
                    .distinct()
                    // We get the relative path from the resource directory
                    .map(path -> getMainResourcesDirectory().relativize(path))
                    // We transform the path into a package name
                    .map(path -> path.toString().replace('/', '.')))
                    // We ignore those not following the Java package name convention (this includes META-INF)
                    .filter(pkg -> SourceVersion.isName(pkg) && !pkg.contains("$"))
                    .cache()
                    .name("resourcePackagesCache");


    /************************
     ***** Constructors *****
     ************************/

    public DevProjectModule(Path homeDirectory, ProjectModule parentModule) {
        // The module name is the name of the home directory, unless a different name is specified in webfx.xml
        super(homeDirectory.toAbsolutePath().getFileName().toString(), parentModule);
        this.homeDirectory = homeDirectory;
        // When specified, the module name specified in webfx.xml is finally preferred over the name of the home directory
        String webFxName = getWebFxModuleFile().getName();
        if (webFxName != null)
            this.name = webFxName;
    }

    /*************************
     ***** Basic getters *****
     *************************/

    public DevProjectModule getWebFxRootModule() {
        if (webFxRootModule == null) {
            ProjectModule pm = getWebFxModuleFile().fileExists() ? this : null;
            while (pm != null) {
                // We can detect the webfx root module through its break in the modules hierarchy: its parent module
                // (ex: webfx-parent) is different from the parent directory module (ex: aggregate module).
                ProjectModule parentDirectoryModule = pm.getParentDirectoryModule();
                if (parentDirectoryModule != pm.getParentModule())
                    break;
                pm = parentDirectoryModule;
            }
            webFxRootModule = (DevProjectModule) pm;
        }
        return webFxRootModule;
    }

    public WebFxModuleFile getWebFxModuleFile() {
        if (webFxModuleFile == null)
            webFxModuleFile = new WebFxModuleFileCache(new DevWebFxModuleFile(this));
        return webFxModuleFile;
    }

    public DevMavenPomModuleFile getMavenModuleFile() {
        if (mavenPomModuleFile == null)
            mavenPomModuleFile = new DevMavenPomModuleFile(this);
        return mavenPomModuleFile;
    }

    public Path getHomeDirectory() {
        return homeDirectory;
    }

    public Path getSourceDirectory() {
        return homeDirectory != null ? homeDirectory.resolve("src") : null;
    }

    @Override
    public Path getMainJavaSourceDirectory() {
        return homeDirectory != null ? homeDirectory.resolve("src/main/java/") : null;
    }

    @Override
    public Path getMainWebFxSourceDirectory() {
        return homeDirectory != null ? homeDirectory.resolve("src/main/webfx/") : null;
    }

    @Override
    public Path getTestJavaSourceDirectory() {
        return homeDirectory != null ? homeDirectory.resolve("src/test/java/") : null;
    }

    @Override
    public Path getMainResourcesDirectory() {
        return homeDirectory != null ? homeDirectory.resolve("src/main/resources/") : null;
    }

    public Path getMetaInfJavaServicesDirectory() {
        return getMainResourcesDirectory().resolve("META-INF/services/");
    }

    public DevGwtModuleFile getGwtModuleFile() {
        if (gwtModuleFile == null)
            gwtModuleFile = new DevGwtModuleFile(this);
        return gwtModuleFile;
    }

    public DevGwtHtmlFile getGwtHtmlFile() {
        if (gwtHtmlFile == null)
            gwtHtmlFile = new DevGwtHtmlFile(this);
        return gwtHtmlFile;
    }

    public DevProjectModule getOrCreateChildProjectModule(String name) {
        return getOrCreateDevProjectModule(homeDirectory.resolve(name).normalize(), this);
    }

    DevProjectModule getOrCreateDevProjectModule(Path homeDirectory, DevProjectModule parentModule) {
        return getModuleRegistry().getOrCreateDevProjectModule(homeDirectory, parentModule);
    }

    public boolean hasSourceDirectory() {
        if (hasSourceDirectory == null)
            hasSourceDirectory = pathExists(getSourceDirectory());
        return hasSourceDirectory;
    }

    private static boolean pathExists(Path path) {
        return path != null && Files.exists(path);
    }

    @Override
    public boolean hasMainJavaSourceDirectory() {
        if (hasMainJavaSourceDirectory == null)
            hasMainJavaSourceDirectory = hasSourceDirectory() && pathExists(getMainJavaSourceDirectory());
        return hasMainJavaSourceDirectory;
    }

    @Override
    public boolean hasMainResourcesDirectory() {
        if (hasMainResourcesDirectory == null)
            hasMainResourcesDirectory = hasSourceDirectory() && pathExists(getMainResourcesDirectory());
        return hasMainResourcesDirectory;
    }

    @Override
    public boolean hasMainWebFxSourceDirectory() {
        if (hasMainWebFxDirectory == null)
            hasMainWebFxDirectory = hasSourceDirectory() && pathExists(getMainWebFxSourceDirectory());
        return hasMainWebFxDirectory;
    }

    @Override
    public ReusableStream<String> getFileResourcePackages() {
        return fileResourcePackagesCache;
    }

    public boolean hasTestJavaSourceDirectory() {
        if (hasTestJavaSourceDirectory == null)
            hasTestJavaSourceDirectory = hasSourceDirectory() && pathExists(getTestJavaSourceDirectory());
        return hasTestJavaSourceDirectory;
    }

    public DevJavaModuleInfoFile getMainJavaModuleFile() {
        if (mainJavaModuleInfoFile == null)
            mainJavaModuleInfoFile = new DevJavaModuleInfoFile(this);
        return mainJavaModuleInfoFile;
    }

    @Override
    public ReusableStream<String> getSubdirectoriesChildrenModules() {
        return ReusableStream.create(() -> SplitFiles.uncheckedWalk(getHomeDirectory(), 1))
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(getHomeDirectory()))
                .filter(path -> Files.exists(path.resolve("webfx.xml")) || Files.exists(path.resolve("pom.xml")))
                .map(path -> path.toFile().getName())
                .sorted(Module::compareModuleNames)
                .cache();
    }

    public DevRootModule getRootModule() {
        return (DevRootModule) super.getRootModule();
    }


    boolean isModuleUnderRootHomeDirectory(Module module) {
        Path homeDirectory = module instanceof DevProjectModule ? ((DevProjectModule) module).getHomeDirectory() : null;
        return homeDirectory != null && homeDirectory.startsWith(getRootModule().getHomeDirectory());
    }

    public void rename(String newName) {
        name = newName;
        artifactId = null;
    }

    public Path getGwtExecutableFilePath() {
        return getHomeDirectory().resolve("target").resolve(getName() + "-" + getVersion() + "/" + getName().replace('-', '_') + "/index.html");
    }

    private LinkedHashMap<String, Path> moduleWebFxPaths;
    public LinkedHashMap<String, Path> collectThisAndTransitiveWebFXPaths(boolean canUseCache) {
        if (moduleWebFxPaths != null)
            return moduleWebFxPaths;

        moduleWebFxPaths = new LinkedHashMap<>();

        String moduleCacheName = getHomeDirectory().toAbsolutePath().toString().replace('/', '~') + "-transitive-webfx.txt";
        Path moduleCacheFile = WebFXHiddenFolder.getCacheFolder().resolve(moduleCacheName);
        boolean cacheRead = false;
        if (canUseCache && Files.exists(moduleCacheFile)) {
            try (Scanner scanner = new Scanner(moduleCacheFile)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    int p = line.indexOf(':');
                    String moduleName = line.substring(0, p);
                    String webfxPathUri = line.substring(p + 1);
                    // URI stored in cache so that we can retrieve the path even if inside jar or zip files
                    moduleWebFxPaths.put(moduleName, Paths.get(new URI(webfxPathUri)));
                }
                cacheRead = true;
            } catch (Exception ignored) {
            }
        }
        if (!cacheRead) {
            // Creating the dependency graph of the transitive modules (i.e. list of dependencies for each module)
            Map<Module, List<Module>> dependencyGraph =
                    ModuleDependency.createDependencyGraph(getMainJavaSourceRootAnalyzer().getTransitiveDependencies());
            // We sort these transitive modules in the order explained above (most dependent modules first, starting
            // with the executable module). Configuration values will be considered only once in the merge, i.e. the
            // first time they will appear in that order, and the consequent occurrences will be commented in the merged
            // configuration file.
            List<Module> sortedModules = TopologicalSort.sortDesc(dependencyGraph);

            sortedModules.forEach(m -> {
                if (m instanceof ProjectModule) {
                    ProjectModule pm = (ProjectModule) m;
                    if (pm.hasMainWebFxSourceDirectory()) {
                        moduleWebFxPaths.put(pm.getName(), pm.getMainWebFxSourceDirectory());
                    }
                }
            });

            StringBuilder sb = new StringBuilder();
            moduleWebFxPaths.forEach((moduleName, webfxPath) -> {
                if (sb.length() > 0)
                    sb.append("\n");
                // URI stored in cache so that we can retrieve the path even if inside jar or zip files
                sb.append(moduleName).append(':').append(webfxPath.toUri());
            });
            TextFileReaderWriter.writeTextFile(sb.toString(), moduleCacheFile, true, true);
        }

        return moduleWebFxPaths;
    }

}