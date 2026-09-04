package com.agent.core.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 对智能体被允许使用的能力进行集中、显式管理的策略。
 *
 * <p><b>设计缘由。</b>工具的能力应当由应用所有者在启动时决定，而不是由模型在运行时决定。
 * 本类将这些决策集中到一处，使得「这个智能体到底能做什么」可以通过阅读单个对象来回答。
 *
 * <p>默认配置遵循最小权限原则：
 * <ul>
 *   <li><b>命令执行默认禁用。</b>由大模型驱动的 shell 本质上就是远程代码执行，
 *       启用它必须是经过审慎考虑并记录在案的决策。</li>
 *   <li><b>允许网络抓取</b>，但仅限于公网地址（见 {@link UrlGuard}）。</li>
 *   <li><b>文件工具受限</b>于 {@link PathSandbox#currentDirectory()}。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 安全默认：不启用 shell，网络限定公网主机，文件位于工作目录内。
 * ToolPolicy policy = ToolPolicy.defaults();
 *
 * // 仅放开一组受限命令。
 * ToolPolicy policy = ToolPolicy.builder()
 *         .commandMode(ToolPolicy.CommandMode.ALLOW_LIST)
 *         .allowCommands("git", "mvn", "ls")
 *         .build();
 * }</pre>
 *
 * <p>本类不可变且线程安全。
 */
public final class ToolPolicy {

    /** shell 命令执行的处理方式。 */
    public enum CommandMode {
        /** 完全拒绝执行命令。这是默认选项。 */
        DISABLED,
        /** 仅允许执行白名单中列出的可执行文件。 */
        ALLOW_LIST
    }

    /**
     * 这些可执行文件会将命令白名单重新变成任意代码执行，因为它们都能解释命令行或标准输入中
     * 提供的新代码。即使显式列在白名单中也会被拒绝，除非应用通过
     * {@link Builder#forbidCommands(Collection)} 替换了此集合。
     */
    public static final Set<String> DEFAULT_FORBIDDEN_COMMANDS = Set.of(
            "cmd", "command", "sh", "bash", "zsh", "dash", "ksh", "fish",
            "powershell", "pwsh", "wscript", "cscript", "osascript",
            "python", "python3", "perl", "ruby", "node", "java", "scala", "groovy",
            "curl", "wget", "nc", "ncat", "netcat", "telnet", "ssh", "scp",
            "eval", "exec", "xargs", "env", "nohup", "sudo", "su"
    );

    private static final Set<String> EXECUTABLE_SUFFIXES = Set.of(".exe", ".cmd", ".bat", ".com", ".sh");

    private final CommandMode commandMode;
    private final Set<String> allowedCommands;
    private final Set<String> forbiddenCommands;
    private final boolean networkFetchEnabled;
    private final boolean privateNetworkAllowed;
    private final int maxRedirects;
    private final PathSandbox sandbox;

    private ToolPolicy(Builder builder) {
        this.commandMode = builder.commandMode;
        this.allowedCommands = Set.copyOf(builder.allowedCommands);
        this.forbiddenCommands = Set.copyOf(builder.forbiddenCommands);
        this.networkFetchEnabled = builder.networkFetchEnabled;
        this.privateNetworkAllowed = builder.privateNetworkAllowed;
        this.maxRedirects = builder.maxRedirects;
        this.sandbox = builder.sandbox;
    }

    /** 最小权限的默认策略。 */
    public static ToolPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 针对最常见非默认场景的便捷工厂方法：放行一组显式指定的可执行文件，其余保持默认。
     */
    public static ToolPolicy allowCommands(String... executables) {
        return builder()
                .commandMode(CommandMode.ALLOW_LIST)
                .allowCommands(executables)
                .build();
    }

    public CommandMode commandMode() {
        return commandMode;
    }

    /** 允许运行的可执行文件名（已转为小写）；仅在 {@link CommandMode#ALLOW_LIST} 下有意义。 */
    public Set<String> allowedCommands() {
        return allowedCommands;
    }

    /** 始终被拒绝的可执行文件，即使列在白名单中也是如此。 */
    public Set<String> forbiddenCommands() {
        return forbiddenCommands;
    }

    public boolean networkFetchEnabled() {
        return networkFetchEnabled;
    }

    public boolean privateNetworkAllowed() {
        return privateNetworkAllowed;
    }

    public int maxRedirects() {
        return maxRedirects;
    }

    /** 所有文件工具共享的文件系统沙箱。 */
    public PathSandbox sandbox() {
        return sandbox;
    }

    /** 由本策略派生出的 {@link UrlGuard}。 */
    public UrlGuard urlGuard() {
        return new UrlGuard(privateNetworkAllowed, maxRedirects);
    }

    /**
     * 判断给定的命令行是否可在本策略下执行。
     *
     * @return 仅当处于 {@link CommandMode#ALLOW_LIST} 模式，且可执行文件在白名单中、且本身
     *         不是会重新开启任意代码执行的解释器时，才返回 true
     */
    public boolean allowsCommand(String commandLine) {
        if (commandMode != CommandMode.ALLOW_LIST) {
            return false;
        }
        String executable = executableOf(commandLine);
        if (executable == null || executable.isBlank()) {
            return false;
        }
        if (forbiddenCommands.contains(executable)) {
            return false;
        }
        return allowedCommands.contains(executable);
    }

    /**
     * 从命令行中提取小写的执行文件名：去除周围的引号、目录部分以及 Windows 可执行文件后缀。
     *
     * @return 执行文件名；当命令行空白时返回 null
     */
    public static String executableOf(String commandLine) {
        if (commandLine == null) {
            return null;
        }
        String token = commandLine.trim();
        if (token.isEmpty()) {
            return null;
        }

        int cut = token.length();
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                cut = i;
                break;
            }
        }
        token = token.substring(0, cut);
        token = stripQuotes(token);
        if (token.isEmpty()) {
            return null;
        }

        String name = token.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.toLowerCase(Locale.ROOT);
        for (String suffix : EXECUTABLE_SUFFIXES) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "ToolPolicy[commandMode=" + commandMode
                + ", allowedCommands=" + allowedCommands
                + ", networkFetchEnabled=" + networkFetchEnabled
                + ", privateNetworkAllowed=" + privateNetworkAllowed
                + ", sandbox=" + sandbox + "]";
    }

    /**
     * 用于构建 {@link ToolPolicy} 的建造器。默认值为最小权限配置。
     */
    public static final class Builder {

        private CommandMode commandMode = CommandMode.DISABLED;
        private final Set<String> allowedCommands = new LinkedHashSet<>();
        private Set<String> forbiddenCommands = new LinkedHashSet<>(DEFAULT_FORBIDDEN_COMMANDS);
        private boolean networkFetchEnabled = true;
        private boolean privateNetworkAllowed = false;
        private int maxRedirects = UrlGuard.DEFAULT_MAX_REDIRECTS;
        private PathSandbox sandbox = PathSandbox.currentDirectory();

        private Builder() {}

        /**
         * shell 命令执行的处理方式。默认值：{@link CommandMode#DISABLED}。
         */
        public Builder commandMode(CommandMode commandMode) {
            this.commandMode = commandMode != null ? commandMode : CommandMode.DISABLED;
            return this;
        }

        /**
         * 在 {@link CommandMode#ALLOW_LIST} 模式下允许运行的可执行文件。
         * 名称按大小写不敏感匹配，且忽略目录与 {@code .exe} 后缀。
         */
        public Builder allowCommands(String... executables) {
            if (executables != null) {
                for (String executable : executables) {
                    String name = executableOf(executable);
                    if (name != null && !name.isBlank()) {
                        allowedCommands.add(name);
                    }
                }
            }
            return this;
        }

        /** {@link #allowCommands(String...)} 的集合版本。 */
        public Builder allowCommands(Collection<String> executables) {
            if (executables != null) {
                allowCommands(executables.toArray(new String[0]));
            }
            return this;
        }

        /**
         * 替换「即便在白名单中也会被拒绝」的可执行文件集合（见
         * {@link #DEFAULT_FORBIDDEN_COMMANDS}）。传入空集合可关闭该防护。
         */
        public Builder forbidCommands(Collection<String> executables) {
            this.forbiddenCommands = executables == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(executables);
            return this;
        }

        /** 是否允许一切出站的 HTTP 抓取。默认值：true。 */
        public Builder networkFetchEnabled(boolean enabled) {
            this.networkFetchEnabled = enabled;
            return this;
        }

        /**
         * 抓取是否可以指向非公网地址（回环、RFC1918、链路本地）。
         * 默认值：false——启用它将重新打开针对内部服务的 SSRF 漏洞。
         */
        public Builder privateNetworkAllowed(boolean allowed) {
            this.privateNetworkAllowed = allowed;
            return this;
        }

        /** 抓取可以跟随的最大重定向次数。默认值：{@value UrlGuard#DEFAULT_MAX_REDIRECTS}。 */
        public Builder maxRedirects(int maxRedirects) {
            this.maxRedirects = Math.max(0, maxRedirects);
            return this;
        }

        /** 应用到所有文件工具的文件系统沙箱。默认值：工作目录。 */
        public Builder sandbox(PathSandbox sandbox) {
            if (sandbox != null) {
                this.sandbox = sandbox;
            }
            return this;
        }

        public ToolPolicy build() {
            return new ToolPolicy(this);
        }
    }
}
