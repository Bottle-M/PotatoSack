package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * 解析并匹配 {@code .potatosackignore} 规则，语法为 gitignore 的实用子集
 * <p>
 * 规则文件位于服务端根目录，模式针对“相对于服务端根目录”的 Unix 风格路径进行匹配
 * （与备份时记录的文件 key、zip 内路径、deleted.files 路径一致）。
 * </p>
 *
 * <p>支持的语法：</p>
 * <ul>
 *     <li>空行、以 {@code #} 开头的行视为注释，忽略</li>
 *     <li>{@code *} 匹配单段内任意字符（不含 {@code /}）；{@code ?} 匹配单个字符（不含 {@code /}）；
 *     {@code **} 跨段匹配（仅用于含斜杠的锚定模式，由底层 PathMatcher 处理）</li>
 *     <li>行末 {@code /} 表示仅匹配目录</li>
 *     <li>行首 {@code /} 表示锚定到服务端根</li>
 *     <li>不含斜杠（或仅行末斜杠）的模式在任意层级匹配（按路径末段/basename 匹配）；
 *     含其它斜杠的模式锚定到根（按整条相对路径匹配）</li>
 *     <li>不支持 {@code !} 取反</li>
 * </ul>
 *
 * <p>匹配示例（路径均相对服务端根）：</p>
 * <ul>
 *     <li>{@code world_nether/} —— 任意层级的 {@code world_nether} 目录</li>
 *     <li>{@code *.log} —— 任意层级的 {@code .log} 文件</li>
 *     <li>{@code /world} —— 仅服务端根下的 {@code world}</li>
 *     <li>{@code world/region/r.0.0.mca} —— 仅该具体文件</li>
 * </ul>
 *
 * <p><b>已知限制：</b></p>
 * <ul>
 *     <li>大小写敏感性跟随宿主文件系统：Windows 上大小写不敏感，Linux 上大小写敏感。</li>
 *     <li>匹配用的路径在各扫描点可能不同：哈希/快照扫描用真实（解析符号链接后）路径，zip 路径扫描用逻辑路径，
 *     备份路径内若含符号链接，二者可能不一致——建议不要在备份路径内放置符号链接。</li>
 * </ul>
 */
public class IgnoreMatcher {
    /**
     * 规则文件名
     */
    public static final String FILE_NAME = ".potatosackignore";

    /**
     * 一条模式中最多允许的非末尾 {@code **} 段数量，避免变体数 2^n 组合爆炸。超过则抛出异常拒绝该模式。2^5 = 32 为变体数上限。
     */
    private static final int MAX_DOUBLE_STAR_SEGMENTS = 5;

    /**
     * 一个无规则的共享实例，{@link #isIgnored(String, boolean)} 恒返回 false
     */
    private static final IgnoreMatcher EMPTY = new IgnoreMatcher(new ArrayList<>());

    /**
     * 一条解析后的规则
     */
    @SuppressWarnings("ClassCanBeRecord")
    private static class Rule {
        final List<PathMatcher> matchers; // 一个或多个（**/ 的零目录变体）
        final boolean directoryOnly; // 是否仅匹配目录
        final boolean anchored;      // true: 锚定到根，匹配整条相对服务端根目录的路径；false: 匹配末段（任意层级）

        Rule(List<PathMatcher> matchers, boolean directoryOnly, boolean anchored) {
            this.matchers = matchers;
            this.directoryOnly = directoryOnly;
            this.anchored = anchored;
        }
    }

    private final List<Rule> rules;

    private IgnoreMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * 返回一个无规则的 IgnoreMatcher（不忽略任何东西）
     *
     * @return 空的 IgnoreMatcher
     */
    public static IgnoreMatcher empty() {
        return EMPTY;
    }

    /**
     * 是否不含任何规则
     *
     * @return 无规则返回 true
     */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 从文件读取并解析规则
     *
     * @param ignoreFile {@code .potatosackignore} 文件
     * @return 解析得到的 IgnoreMatcher
     * @throws IOException 文件读取失败，或某行模式语法非法
     */
    public static IgnoreMatcher load(File ignoreFile) throws IOException {
        List<Rule> rules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(ignoreFile))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                // 去除首尾空白
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    // 空行或注释跳过
                    continue;
                }
                try {
                    rules.add(parseRule(trimmed));
                } catch (PatternSyntaxException e) {
                    throw new IOException("Invalid pattern at line " + lineNum + " of " + ignoreFile.getName() + ": \"" + trimmed + "\" (" + e.getDescription() + ")", e);
                }
            }
        }
        return new IgnoreMatcher(rules);
    }

    /**
     * 加载服务端根目录下的默认 .potatosackignore。
     * <p>
     * 文件不存在则返回无规则实例；存在则解析，语法非法时抛出 IOException。
     * </p>
     *
     * @return IgnoreMatcher
     * @throws IOException 文件存在但读取或解析失败
     */
    public static IgnoreMatcher loadDefault() throws IOException {
        File ignoreFile = new File(PotatoSack.worldContainerDir, FILE_NAME);
        if (!ignoreFile.isFile()) {
            return empty();
        }
        return load(ignoreFile);
    }

    /**
     * 解析单行模式为 Rule
     * <p>
     * 处理行末 {@code /}（仅目录）、行首 {@code /}（锚定）、是否含斜杠（决定任意层级或锚定）。
     * </p>
     *
     * @param raw 已去除首尾空白、非空、非注释的模式行
     * @return 解析得到的 Rule
     * @throws PatternSyntaxException 模式为空或语法非法
     */
    private static Rule parseRule(String raw) {
        String pattern = raw;
        boolean directoryOnly = pattern.endsWith("/");
        if (directoryOnly) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        boolean anchored;
        if (pattern.startsWith("/")) {
            // 行首 / 表示锚定到服务端根目录
            anchored = true;
            pattern = pattern.substring(1);
        } else {
            // 含斜杠（非行末的）也锚定到根；否则任意层级（按末段匹配）
            anchored = pattern.contains("/");
        }
        if (pattern.isEmpty()) {
            // 例如一行只有 "/" 等
            throw new PatternSyntaxException("Empty ignore pattern", raw, 0);
        }
        // 对非末尾的 ** 段生成匹配变体（见 expandDoubleStarVariants），
        // 规避 JDK PathMatcher 中 ** 只匹配“一个或多个”目录、不匹配“零个”的问题
        // （否则 foo/**/bar 会漏掉 foo/bar）。每个变体编译成一个 PathMatcher，任一命中即忽略。
        List<PathMatcher> matchers = new ArrayList<>();
        for (String variant : expandDoubleStarVariants(pattern)) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
        }
        return new Rule(matchers, directoryOnly, anchored);
    }

    /**
     * 对模式中每个“非末尾的 {@code **} 段”枚举保留/删除，生成变体列表，使 {@code **} 后接斜杠的形式能匹配零个目录。
     * <p>
     * JDK PathMatcher 的 glob 中 {@code **} 只匹配“一个或多个”目录段，不匹配“零个”。
     * 因此 foo/**&#47;bar 经 PathMatcher 只能命中 foo/x/bar、foo/x/y/bar，会漏掉 foo/bar。
     * .gitignore 的 {@code **} 语义是“零或多个”，本方法通过给每个非末尾的 {@code **} 段生成“保留/删除”两种匹配模式、
     * 枚举所有组合，使并集等价于“零或多个”——任一变体命中即算命中。
     * </p>
     *
     * <p>规则：</p>
     * <ul>
     *     <li>末尾的 {@code **}（如 logs/**）不存在零目录问题（它匹配其后的内容），不变体。</li>
     *     <li>无 {@code **} 的模式直接返回单元素原模式。</li>
     *     <li>非末尾 {@code **} 段超过 5 个时抛出异常（避免 2^n 组合爆炸），2^5=32 为变体数上限。</li>
     * </ul>
     *
     * <p>示例：</p>
     * <ul>
     *     <li>foo/**&#47;bar（1 个 {@code **}）→ foo/bar（删除=零层）、foo/**&#47;bar（保留=一层或多层）</li>
     *     <li>a/**&#47;b/**&#47;c（2 个 {@code **}）→ a/b/c、a/**&#47;b/c、a/b/**&#47;c、a/**&#47;b/**&#47;c</li>
     *     <li>logs/**（末尾 {@code **}）→ logs/**（不变体）</li>
     *     <li>{@code *.log}（无 {@code **}）→ {@code *.log}</li>
     * </ul>
     *
     * @param pattern 已去掉行末斜杠和行首斜杠的模式
     * @return 变体 glob 字符串列表（至少含原模式）
     */
    private static List<String> expandDoubleStarVariants(String pattern) {
        String[] segs = pattern.split("/", -1);
        // 找出所有“非末尾”的 ** 段的下标（末尾 ** 如 logs/** 不存在零目录问题，无需变体）
        List<Integer> starIdx = new ArrayList<>();
        for (int i = 0; i < segs.length; i++) {
            if (segs[i].equals("**") && i < segs.length - 1) {
                starIdx.add(i);
            }
        }
        if (starIdx.isEmpty()) {
            // 没有 **/ 段，原样返回
            return List.of(pattern);
        }
        if (starIdx.size() > MAX_DOUBLE_STAR_SEGMENTS) {
            // 非末尾 ** 过多会导致变体数 2^n 组合爆炸，直接拒绝该模式
            throw new PatternSyntaxException(
                    "Too many ** segments (max " + MAX_DOUBLE_STAR_SEGMENTS + ")", pattern, 0);
        }
        List<String> result = new ArrayList<>();
        int n = starIdx.size();
        // 用位掩码枚举每个 ** 的“保留/删除”组合：共 2^n 种。
        // mask 的第 si 位为 1 则保留第 si 个 **（交给 PathMatcher，匹配一层或多层）；为 0 则删除该 **（零目录）。
        // 例：foo/**/bar 的 ** 是第 0 个，mask=0 删除得 foo/bar，mask=1 保留得 foo/**/bar。
        for (int mask = 0; mask < (1 << n); mask++) {
            List<String> kept = new ArrayList<>();
            int si = 0; // 当前处理到第 si 个 **（对应 starIdx.get(si)）
            for (int i = 0; i < segs.length; i++) {
                if (si < n && starIdx.get(si) == i) {
                    // 这一整段是第 si 个 **
                    if ((mask & (1 << si)) != 0) {
                        kept.add(segs[i]); // 该位为 1：保留 **
                    }
                    // 该位为 0：删除 **（零目录），不加入 kept
                    si++;
                } else {
                    // 非 ** 段，原样保留
                    kept.add(segs[i]);
                }
            }
            String variant = String.join("/", kept);
            if (!variant.isEmpty()) {
                result.add(variant);
            }
        }
        return result;
    }

    /**
     * 判断给定路径是否被忽略：路径自身命中规则，或其任一祖先目录命中规则
     * <p>
     * 祖先目录一律按目录处理（{@code isDirectory=true}），因此祖先上的“仅目录”规则也会生效；
     * 这与扫描时“目录被忽略即剪枝整个子树”的语义一致，使得能识别出文件 / 目录“位于被忽略目录之下”的情况
     * </p>
     *
     * @param relativePath 相对服务端根目录的 Unix 风格路径
     * @param isDirectory  该路径自身是否为目录（用于适配“仅目录”规则；但祖先一律视为目录）
     * @return 自身或任一祖先目录命中规则则返回 true，否则 false
     */
    public boolean isIgnored(String relativePath, boolean isDirectory) {
        if (rules.isEmpty()) {
            return false;
        }
        // 检查路径自身
        if (matchesPath(relativePath, isDirectory)) {
            return true;
        }
        // 沿祖先目录向上检查（祖先都是目录，按 isDirectory=true 判断）
        String path = relativePath;
        int slash;
        while ((slash = path.lastIndexOf('/')) >= 0) {
            path = path.substring(0, slash);
            if (path.isEmpty()) {
                break;
            }
            if (matchesPath(path, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单条路径自身是否命中任一规则（不做祖先遍历）
     * <p>
     * “仅目录”规则仅在 {@code isDirectory=true} 时生效；锚定规则按整条相对路径匹配，
     * 任意层级规则按末段（basename）匹配。
     * </p>
     *
     * @param relativePath 相对服务端根目录的 Unix 风格路径
     * @param isDirectory  该路径是否为目录
     * @return 命中任一规则则返回 true
     */
    private boolean matchesPath(String relativePath, boolean isDirectory) {
        Path path = Paths.get(relativePath);
        // 末段（basename），用于任意层级的模式匹配；为 null 时（如服务端根本身）则不按任意层级匹配
        Path name = path.getFileName();
        for (Rule rule : rules) {
            if (rule.directoryOnly && !isDirectory) {
                // 仅目录的模式不匹配文件
                continue;
            }
            if (rule.anchored) {
                // 锚定：按整条相对路径匹配，任一变体命中即可
                for (PathMatcher matcher : rule.matchers) {
                    if (matcher.matches(path)) {
                        return true;
                    }
                }
            } else {
                // 任意层级：按末段匹配
                if (name == null) {
                    continue;
                }
                for (PathMatcher matcher : rule.matchers) {
                    if (matcher.matches(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
