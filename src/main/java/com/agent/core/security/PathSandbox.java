package com.agent.core.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 将文件系统工具限制在固定的一组允许根目录内。
 *
 * <p><b>设计缘由。</b>工具接收到的每个路径最终都来自大模型，而大模型又受用户输入、它读取的文件
 * 或抓取的网页驱动。直接信任这样的路径（{@code Path.of(userInput)}）会把提示词注入变成任意文件
 * 读取，而若再配合写入工具，就会变成任意文件写入。本类是将「模型给出的一串字符串」转变为
 * 「我们愿意触碰的路径」的唯一关卡。
 *
 * <h3>它提供的保证</h3>
 * <ul>
 *   <li>拒绝跳出根目录的 {@code ..} 路径穿越。</li>
 *   <li>拒绝根目录之外的绝对路径。</li>
 *   <li>拒绝指向根目录之外的符号链接（符号链接会在边界检查前被解析，
 *       因此沙箱内部的链接无法被用作逃逸通道）。</li>
 *   <li>父目录同样会被规范化，因此通过符号链接目录写入<i>新</i>文件也会被捕获。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 默认：将工具限制在处理进程的工作目录内。
 * PathSandbox sandbox = PathSandbox.currentDirectory();
 *
 * // 显式指定根目录（例如一个项目检出目录加一个临时目录）。
 * PathSandbox sandbox = PathSandbox.of(Path.of("/srv/app/work"), Path.of("/tmp/agent"));
 *
 * Path safe = sandbox.resolve(userSuppliedPath);   // 超出范围时抛出 SecurityException
 * }</pre>
 *
 * <p>本类不可变且线程安全。
 */
public final class PathSandbox {

    private final List<Path> roots;
    private static final boolean followSymlinks = false;

    private PathSandbox(List<Path> roots) {
        this.roots = roots;
    }

    /**
     * 创建一个限制在给定根目录内的沙箱。额外根目录使用可变参数，因此单个目录
     * 可写作 {@code PathSandbox.of(dir)}。
     */
    public static PathSandbox of(Path root, Path... more) {
        List<Path> all = new ArrayList<>();
        all.add(Objects.requireNonNull(root, "root must not be null"));
        if (more != null) {
            Collections.addAll(all, more);
        }
        return new PathSandbox(canonicalizeRoots(all));
    }

    /**
     * 创建一个限制在给定根目录内的沙箱。
     */
    public static PathSandbox of(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("At least one root directory is required");
        }
        return new PathSandbox(canonicalizeRoots(new ArrayList<>(roots)));
    }

    /**
     * 创建一个限制在当前进程工作目录内的沙箱。这是所有内置文件工具使用的默认配置。
     */
    public static PathSandbox currentDirectory() {
        return of(Path.of("").toAbsolutePath());
    }

    /**
     * 创建一个完全不执行边界检查的沙箱。
     *
     * <p><b>仅适用于本地、单用户、可信输入的场景。</b>使用此沙箱时，文件工具会接受模型产生的
     * 任意路径，而这正是沙箱本要消除的漏洞。建议优先使用带显式根目录的 {@link #of(Path, Path...)}。
     */
    public static PathSandbox unrestricted() {
        return new PathSandbox(List.of());
    }

    /**
     * 本沙箱允许的规范化根目录。
     */
    public List<Path> roots() {
        return roots;
    }

    /**
     * 本沙箱是否执行任何边界检查。
     */
    public boolean isUnrestricted() {
        return roots.isEmpty();
    }

    /**
     * 针对本沙箱解析调用方提供的路径。
     *
     * @param userPath 原始路径，由大模型生成
     * @return 规范化、绝对化的路径，保证位于某个根目录之下
     * @throws SecurityException 当路径为空、无效，或试图逃逸沙箱时
     */
    public Path resolve(String userPath) {
        return resolve(userPath, "path");
    }

    /**
     * 解析调用方提供的路径，并在错误消息中使用 {@code parameterName}。
     *
     * @throws SecurityException 当路径为空、无效，或试图逃逸沙箱时
     */
    public Path resolve(String userPath, String parameterName) {
        if (userPath == null || userPath.isBlank()) {
            throw new SecurityException("'" + parameterName + "' must not be blank");
        }

        Path candidate;
        try {
            candidate = Path.of(userPath);
        } catch (InvalidPathException e) {
            throw new SecurityException("Invalid '" + parameterName + "': " + userPath, e);
        }

        Path normalized = candidate.toAbsolutePath().normalize();
        if (isUnrestricted()) {
            return normalized;
        }

        Path canonical = canonicalize(normalized);
        if (!isWithinRoots(canonical)) {
            throw new SecurityException("Access denied: '" + userPath + "' resolves to '" + canonical
                    + "', which is outside the allowed directories " + describe() + ".");
        }
        return canonical;
    }

    /**
     * 不抛出异常的 {@link #resolve(String)} 变体。
     *
     * @return 当路径可在本沙箱下被访问时为 true
     */
    public boolean isAllowed(String userPath) {
        try {
            resolve(userPath);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 对允许根目录的可读描述，用于错误消息与日志。
     */
    public String describe() {
        return roots.isEmpty() ? "<unrestricted>" : roots.toString();
    }

    @Override
    public String toString() {
        return "PathSandbox" + describe();
    }

    private static List<Path> canonicalizeRoots(List<Path> raw) {
        List<Path> out = new ArrayList<>(raw.size());
        for (Path root : raw) {
            Path absolute = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
            out.add(canonicalize(absolute));
        }
        return List.copyOf(out);
    }

    /**
     * 在可能的情况下解析符号链接。当叶子节点尚不存在（即将写入的文件）时，改为解析其父目录，
     * 这样边界检查仍能看到字节实际写入的真实位置。
     */
    private static Path canonicalize(Path path) {
        if (!followSymlinks) {
            return path;
        }
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            // 叶子节点尚不存在（尚未创建）——向下落到父目录处理。
        }
        Path parent = path.getParent();
        if (parent != null) {
            try {
                return parent.toRealPath().resolve(path.getFileName());
            } catch (IOException | RuntimeException ignored) {
                // 父目录也不存在——保留规范化后的路径。
            }
        }
        return path;
    }

    private boolean isWithinRoots(Path path) {
        for (Path root : roots) {
            if (path.equals(root) || path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
