package ai.rrw.kine.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Adds the running release's mixins to kine.mixins.json's (versions/README.md). A mixin whose target differs between
 * releases lives in its epoch folder (package ai.rrw.kine.mixin.&lt;epoch&gt;) or, when every release of a family shares
 * it, in the family folder (ai.rrw.kine.mixin.f&lt;family&gt;, e.g. f26), and the epoch's resource
 * kine-mixins-&lt;epoch&gt;.txt lists the ones its releases apply, relative to ai.rrw.kine.mixin ("v26_1.X", "f26.Y").
 * Only the running epoch's are applied, so the others' targets are never looked up. Mixins in kine.mixins.json itself
 * apply on every release.
 */
public final class KineMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        String epoch = GameVersion.epoch();
        List<String> out = new ArrayList<>();
        String name = "kine-mixins-" + epoch + ".txt";
        try (InputStream in = KineMixinPlugin.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);   // relative to ai.rrw.kine.mixin
            }
        } catch (IOException e) {
            throw new IllegalStateException("kine: can't read " + name, e);
        }
        return out;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
