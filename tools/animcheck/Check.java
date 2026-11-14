import com.dawnduskterminal.client.animation.FnBinding;
import com.dawnduskterminal.client.animation.YsmBinding;
import com.dawnduskterminal.geckolib3.core.builder.Animation;
import com.dawnduskterminal.geckolib3.core.molang.MolangParser;
import com.dawnduskterminal.geckolib3.util.json.JsonAnimationUtils;
import com.dawnduskterminal.molang.runtime.binding.ObjectBinding;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性校验：把每个动画文件按运行时那套绑定解析一遍，再逐条 Molang 表达式求解析，不吞异常。
 *
 * <p>只取真正承载表达式的三个通道（rotation / position / scale）与 timeline 关键帧，避免把片段名、
 * format_version 这类普通字符串当表达式解析。
 */
public final class Check {
    public static void main(String[] args) throws Exception {
        Map<String, ObjectBinding> extra = new HashMap<>();
        extra.put("ysm", YsmBinding.INSTANCE);
        extra.put("fn", FnBinding.INSTANCE);
        MolangParser parser = new MolangParser(extra);

        long totalExprs = 0;
        long badExprs = 0;
        long totalClips = 0;
        long badClips = 0;

        for (String arg : args) {
            String text = Files.readString(Path.of(arg), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();

            int clips = 0;
            int clipFails = 0;
            for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(root)) {
                clips++;
                try {
                    Animation animation = JsonAnimationUtils.deserializeJsonToAnimation(
                            JsonAnimationUtils.getAnimation(root, entry.getKey()), parser);
                    if (animation == null) {
                        clipFails++;
                        System.out.println("  NULL CLIP " + entry.getKey());
                    }
                } catch (Throwable error) {
                    clipFails++;
                    System.out.println("  FAIL CLIP " + entry.getKey() + " :: " + error);
                }
            }

            List<String> exprs = new ArrayList<>();
            collect(root.getAsJsonObject("animations"), exprs);
            int badExpr = 0;
            String firstBad = null;
            for (String expr : exprs) {
                if (expr.isBlank()) {
                    continue;
                }
                try {
                    parser.parseExpressionUnsafe(expr);
                } catch (Throwable error) {
                    badExpr++;
                    if (firstBad == null) {
                        String head = expr.length() > 160 ? expr.substring(0, 160) : expr;
                        firstBad = head + "\n        :: " + error;
                    }
                }
            }

            totalClips += clips;
            badClips += clipFails;
            totalExprs += exprs.size();
            badExprs += badExpr;

            System.out.printf(
                    "%-40s clips=%-3d clipFail=%-2d exprs=%-6d exprFail=%-3d%n",
                    Path.of(arg).getFileName(), clips, clipFails, exprs.size(), badExpr);
            if (firstBad != null) {
                System.out.println("    first bad expr: " + firstBad);
            }
        }

        System.out.println("----");
        System.out.println("clips=" + totalClips + " clipFailures=" + badClips);
        System.out.println("exprs=" + totalExprs + " exprFailures=" + badExprs);
    }

    private static void collect(JsonObject animations, List<String> out) {
        if (animations == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> clip : animations.entrySet()) {
            JsonObject clipObject = clip.getValue().getAsJsonObject();
            JsonObject bones = clipObject.getAsJsonObject("bones");
            if (bones != null) {
                for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                    JsonObject channels = bone.getValue().getAsJsonObject();
                    for (String channel : new String[] {"rotation", "position", "scale"}) {
                        collectAll(channels.get(channel), out);
                    }
                }
            }
            collectAll(clipObject.get("timeline"), out);
        }
    }

    private static void collectAll(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                out.add(element.getAsString());
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectAll(child, out);
            }
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            // lerp_mode 是插值模式名（catmullrom / linear），不是 Molang 表达式
            if ("lerp_mode".equals(entry.getKey())) {
                continue;
            }
            collectAll(entry.getValue(), out);
        }
    }
}
