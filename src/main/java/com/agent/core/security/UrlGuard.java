package com.agent.core.security;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 在抓取出站 HTTP URL 之前对其进行校验，以防止服务端请求伪造（SSRF）。
 *
 * <p><b>设计缘由。</b>工具被要求抓取的 URL 来自大模型，即来自不可信输入。若不加校验，该工具
 * 就变成了信任边界内的一台 HTTP 客户端：它可能读取 {@code file:///etc/passwd}（JDK 会愉快地
 * 打开非 HTTP 协议）、访问像 {@code http://169.254.169.254/} 这样的云元数据端点，或探测未暴露在
 * 公网上的内部服务。
 *
 * <h3>它拦截的内容</h3>
 * <ul>
 *   <li>除 {@code http} 和 {@code https} 之外的任何协议——包括 {@code file}、
 *       {@code jar}、{@code ftp} 和 {@code gopher}。</li>
 *   <li>携带内嵌凭据的 URL（{@code http://user:pass@host/}）。</li>
 *   <li>解析到回环、链路本地、私网、多播或其它非公网地址的主机，除非被显式允许。</li>
 * </ul>
 *
 * <p>抓取工具<b>不会</b>自动跟随重定向：它必须通过 {@link #validate(String)} 对每个重定向目标
 * 重新校验，否则一个公网 URL 只需 {@code 302} 跳转到一个内部地址即可完全绕过本检查。
 *
 * <p>本类不可变且线程安全。
 */
public final class UrlGuard {

    /** 抓取工具仅允许打开的协议。 */
    public static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 抓取工具愿意跟随的默认重定向次数。 */
    public static final int DEFAULT_MAX_REDIRECTS = 3;

    private final boolean allowPrivateNetwork;
    private final int maxRedirects;

    /**
     * @param allowPrivateNetwork 为 true 时，非公网地址（回环、RFC1918、链路本地）将被放行而非拒绝
     * @param maxRedirects        允许跟随的最大重定向次数
     */
    public UrlGuard(boolean allowPrivateNetwork, int maxRedirects) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.maxRedirects = Math.max(0, maxRedirects);
    }

    /**
     * 采用安全默认值的防护器：屏蔽私网，最多跟随 {@value #DEFAULT_MAX_REDIRECTS} 次重定向。
     */
    public static UrlGuard defaults() {
        return new UrlGuard(false, DEFAULT_MAX_REDIRECTS);
    }

    public boolean allowPrivateNetwork() {
        return allowPrivateNetwork;
    }

    public int maxRedirects() {
        return maxRedirects;
    }

    /**
     * 校验调用方提供的 URL 并将其转换为 {@link URL}。
     *
     * @param rawUrl 原始 URL，由大模型生成
     * @return 校验通过的 URL
     * @throws SecurityException 当 URL 为空、格式错误、使用了被拦截的协议，或解析
     *                           到被拦截的地址时
     */
    public URL validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SecurityException("'url' must not be blank");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed URL: " + rawUrl, e);
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new SecurityException("Blocked URL scheme '" + uri.getScheme()
                    + "'; only http and https are allowed.");
        }
        if (uri.getHost() == null) {
            throw new SecurityException("URL has no host: " + rawUrl);
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("URLs with embedded credentials are not allowed.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new SecurityException("Cannot resolve host: " + uri.getHost());
        }
        if (addresses.length == 0) {
            throw new SecurityException("Host resolves to no address: " + uri.getHost());
        }
        if (!allowPrivateNetwork) {
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new SecurityException("Blocked host: '" + uri.getHost() + "' resolves to "
                            + address.getHostAddress() + ", which is not a public address.");
                }
            }
        }

        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new SecurityException("Malformed URL: " + rawUrl, e);
        }
    }

    /**
     * 不抛出异常的 {@link #validate(String)} 变体。
     */
    public boolean isAllowed(String rawUrl) {
        try {
            validate(rawUrl);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 判断给定地址是否为不应被智能体工具访问的非公网地址：回环、任意本地、链路本地
     * （含 {@code 169.254.169.254} 云元数据）、站点本地（RFC1918）、运营商级 NAT、多播与保留网段，
     * 以及 IPv6 唯一本地地址。
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int o1 = bytes[0] & 0xff;
            int o2 = bytes[1] & 0xff;
            int o3 = bytes[2] & 0xff;
            if (o1 == 100 && o2 >= 64 && o2 <= 127) return true;   // 100.64.0.0/10  运营商级 NAT
            if (o1 == 192 && o2 == 0 && o3 == 0) return true;      // 192.0.0.0/24   IETF 协议分配段
            if (o1 == 198 && (o2 == 18 || o2 == 19)) return true;  // 198.18.0.0/15  基准测试网段
            if (o1 >= 224) return true;                            // 224.0.0.0/4+   多播及保留网段
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true;                  // fc00::/7       IPv6 唯一本地地址
        }
        return false;
    }
}
