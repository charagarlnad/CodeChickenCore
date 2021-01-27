package codechicken.core.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.versioning.ComparableVersion;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.IFMLCallHook;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * For autodownloading stuff.
 * This is really unoriginal, mostly ripped off FML, credits to cpw.
 */
public class DepLoader implements IFMLLoadingPlugin, IFMLCallHook {
    private static DepLoadInst inst;

    public static class VersionedFile
    {
        public final Pattern pattern;
        public final String filename;
        public final ComparableVersion version;
        public final String name;

        public VersionedFile(String filename, Pattern pattern) {
            this.pattern = pattern;
            this.filename = filename;
            Matcher m = pattern.matcher(filename);
            if(m.matches()) {
                name = m.group(1);
                version = new ComparableVersion(m.group(2));
            }
            else {
                name = null;
                version = null;
            }
        }

        public boolean matches() {
            return name != null;
        }
    }

    public static class Dependency
    {
        public String url;
        public VersionedFile file;

        public String existing;
        /**
         * Flag set to add this dep to the classpath immediately because it is required for a coremod.
         */
        public boolean coreLib;

        public Dependency(String url, VersionedFile file, boolean coreLib) {
            this.url = url;
            this.file = file;
            this.coreLib = coreLib;
        }
    }

    public static class DepLoadInst {
        private File modsDir;
        private File v_modsDir;

        private Map<String, Dependency> depMap = new HashMap<String, Dependency>();

        public DepLoadInst() {
            String mcVer = (String) FMLInjectionData.data()[4];
            File mcDir = (File) FMLInjectionData.data()[6];

            modsDir = new File(mcDir, "mods");
            v_modsDir = new File(mcDir, "mods/" + mcVer);
            if (!v_modsDir.exists())
                v_modsDir.mkdirs();
        }

        private void addClasspath(String name) {
            try {
                ((LaunchClassLoader) DepLoader.class.getClassLoader()).addURL(new File(v_modsDir, name).toURI().toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        private String checkExisting(Dependency dep) {
            for (File f : modsDir.listFiles()) {
                VersionedFile vfile = new VersionedFile(f.getName(), dep.file.pattern);
                if (!vfile.matches() || !vfile.name.equals(dep.file.name))
                    continue;

                if (f.renameTo(new File(v_modsDir, f.getName())))
                    continue;

                throw new RuntimeException("Dependency " + f.getName() + " is in the main mods folder and was unable to be moved automatically, please manually move it to the /1.7.10 subdirectory.");
            }

            for (File f : v_modsDir.listFiles()) {
                VersionedFile vfile = new VersionedFile(f.getName(), dep.file.pattern);
                if (!vfile.matches() || !vfile.name.equals(dep.file.name))
                    continue;

                int cmp = vfile.version.compareTo(dep.file.version);
                if (cmp < 0) {
                    System.err.println("Warning: version of " + dep.file.name + ", " + vfile.version + " is older than request " + dep.file.version + ", this can cause serious issues!");
                }
                if (cmp > 0) {
                    System.err.println("Warning: version of " + dep.file.name + ", " + vfile.version + " is newer than request " + dep.file.version);
                }
                return f.getName();
            }
            return null;
        }

        public void load() {
            scanDepInfos();
            if (depMap.isEmpty())
                return;

            loadDeps();
            activateDeps();
        }

        private void activateDeps() {
            for (Dependency dep : depMap.values())
                if (dep.coreLib)
                    addClasspath(dep.existing);
        }

        private void loadDeps() {
            depMap.forEach((k,v) -> load(v));
        }

        private void load(Dependency dep) {
            dep.existing = checkExisting(dep);
            if (dep.existing == null)
                throw new RuntimeException("Dependency missing, you will need to install it manually: " + dep.file.name + ", however it may be downloadable here: " + dep.url);
        }

        private List<File> modFiles() {
            List<File> list = new LinkedList<File>();
            list.addAll(Arrays.asList(modsDir.listFiles()));
            list.addAll(Arrays.asList(v_modsDir.listFiles()));
            return list;
        }

        private void scanDepInfos() {
            for (File file : modFiles()) {
                if (!file.getName().endsWith(".jar") && !file.getName().endsWith(".zip"))
                    continue;

                scanDepInfo(file);
            }
        }

        private void scanDepInfo(File file) {
            try {
                ZipFile zip = new ZipFile(file);
                ZipEntry e = zip.getEntry("dependancies.info");
                if (e == null) e = zip.getEntry("dependencies.info");
                if (e != null)
                    loadJSon(zip.getInputStream(e));
                zip.close();
            } catch (Exception e) {
                System.err.println("Failed to load dependencies.info from " + file.getName() + " as JSON");
                e.printStackTrace();
            }
        }

        private void loadJSon(InputStream input) throws IOException {
            InputStreamReader reader = new InputStreamReader(input);
            JsonElement root = new JsonParser().parse(reader);
            if (root.isJsonArray())
                loadJSonArr(root);
            else
                loadJson(root.getAsJsonObject());
            reader.close();
        }

        private void loadJSonArr(JsonElement root) throws IOException {
            for (JsonElement node : root.getAsJsonArray())
                loadJson(node.getAsJsonObject());
        }

        private void loadJson(JsonObject node) throws IOException {
            boolean obfuscated = ((LaunchClassLoader) DepLoader.class.getClassLoader())
                    .getClassBytes("net.minecraft.world.World") == null;

            String testClass = node.get("class").getAsString();
            if (DepLoader.class.getResource("/" + testClass.replace('.', '/') + ".class") != null)
                return;

            String repo = node.get("repo").getAsString();
            String filename = node.get("file").getAsString();
            if (!obfuscated && node.has("dev"))
                filename = node.get("dev").getAsString();

            boolean coreLib = node.has("coreLib") && node.get("coreLib").getAsBoolean();

            Pattern pattern = null;
            try {
                if(node.has("pattern"))
                    pattern = Pattern.compile(node.get("pattern").getAsString());
            } catch (PatternSyntaxException e) {
                System.err.println("Invalid filename pattern: "+node.get("pattern"));
                e.printStackTrace();
            }
            if(pattern == null)
                pattern = Pattern.compile("(\\w+).*?([\\d\\.]+)[-\\w]*\\.[^\\d]+");

            VersionedFile file = new VersionedFile(filename, pattern);
            if (!file.matches())
                throw new RuntimeException("Invalid filename format for dependency: " + filename);

            addDep(new Dependency(repo, file, coreLib));
        }

        private void addDep(Dependency newDep) {
            if (mergeNew(depMap.get(newDep.file.name), newDep)) {
                depMap.put(newDep.file.name, newDep);
            }
        }

        private boolean mergeNew(Dependency oldDep, Dependency newDep) {
            if (oldDep == null)
                return true;

            Dependency newest = newDep.file.version.compareTo(oldDep.file.version) > 0 ? newDep : oldDep;
            newest.coreLib = newDep.coreLib || oldDep.coreLib;

            return newest == newDep;
        }
    }

    public static void load() {
        if (inst == null) {
            inst = new DepLoadInst();
            inst.load();
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return getClass().getName();
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public Void call() {
        load();

        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
