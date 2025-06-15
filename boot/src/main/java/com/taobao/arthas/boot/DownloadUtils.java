package com.taobao.arthas.boot;
// 包声明，指定该类所属的包结构

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
// 导入该类所需的所有外部类，涵盖IO操作、网络连接、文本格式化和集合等功能模块

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.IOUtils;
// 导入Arthas自定义的工具类，用于日志记录和IO操作

/**
 * 下载工具类，提供Arthas版本查询和安装包下载功能
 *
 * @author hengyunabc 2018-11-06
 */
public class DownloadUtils {
    // 类定义，DownloadUtils 作为Arthas的下载核心工具类

    // 远程API地址常量定义
    private static final String ARTHAS_VERSIONS_URL = "https://arthas.aliyun.com/api/versions";
    // Arthas所有版本列表API地址

    private static final String ARTHAS_LATEST_VERSIONS_URL = "https://arthas.aliyun.com/api/latest_version";
    // Arthas最新版本API地址

    private static final String ARTHAS_DOWNLOAD_URL = "https://arthas.aliyun.com/download/${VERSION}?mirror=${REPO}";
    // Arthas安装包下载URL模板（包含版本号和镜像参数）

    private static final int CONNECTION_TIMEOUT = 3000;
    // 网络连接超时时间（3秒）

    /**
     * 读取Arthas最新版本号
     *
     * @return 最新版本号，读取失败返回null
     */
    public static String readLatestReleaseVersion() {
        InputStream inputStream = null;
        try {
            // 打开最新版本API连接
            URLConnection connection = openURLConnection(ARTHAS_LATEST_VERSIONS_URL);
            inputStream = connection.getInputStream();
            // 读取输入流并转换为字符串（版本号）
            return IOUtils.toString(inputStream).trim();
        } catch (Throwable t) {
            // 异常处理：记录错误日志
            AnsiLog.error("无法从以下地址读取Arthas版本: " + ARTHAS_LATEST_VERSIONS_URL);
            AnsiLog.debug(t);
        } finally {
            // 确保输入流关闭
            IOUtils.close(inputStream);
        }
        return null;
    }

    /**
     * 读取Arthas所有远程版本号
     *
     * @return 版本号列表，读取失败返回null
     */
    public static List<String> readRemoteVersions() {
        InputStream inputStream = null;
        try {
            // 打开版本列表API连接
            URLConnection connection = openURLConnection(ARTHAS_VERSIONS_URL);
            inputStream = connection.getInputStream();
            // 读取输入流并按行分割版本号
            String versionsStr = IOUtils.toString(inputStream);
            String[] versions = versionsStr.split("\r\n");

            ArrayList<String> result = new ArrayList<String>();
            for (String version : versions) {
                result.add(version.trim()); // 去除版本号前后空格并添加到列表
            }
            return result;

        } catch (Throwable t) {
            // 异常处理：记录错误日志
            AnsiLog.error("无法从以下地址读取Arthas版本列表: " + ARTHAS_VERSIONS_URL);
            AnsiLog.debug(t);
        } finally {
            // 确保输入流关闭
            IOUtils.close(inputStream);
        }
        return null;
    }

    /**
     * 处理镜像仓库URL（去除末尾斜杠，切换HTTP/HTTPS协议）
     *
     * @param repoUrl 原始镜像URL
     * @param http 是否使用HTTP协议
     * @return 处理后的镜像URL
     */
    private static String getRepoUrl(String repoUrl, boolean http) {
        // 去除URL末尾的斜杠
        if (repoUrl.endsWith("/")) {
            repoUrl = repoUrl.substring(0, repoUrl.length() - 1);
        }

        // 根据参数切换HTTP/HTTPS协议
        if (http && repoUrl.startsWith("https")) {
            repoUrl = "http" + repoUrl.substring("https".length());
        }
        return repoUrl;
    }

    /**
     * 下载Arthas安装包并解压
     *
     * 解压后，unzipDir目录下会生成 Arthas 的核心文件：
     * ~/.arthas/lib/3.6.0/arthas/
     * ├── arthas-agent.jar     # Java Agent启动器
     * ├── arthas-core.jar      # 核心功能包
     * ├── arthas-spy.jar       # 字节码增强工具
     * ├── lib/                 # 依赖库
     * │   ├── byte-buddy.jar
     * │   ├── commons-cli.jar
     * │   └── ...
     * ├── bin/                 # 脚本文件
     * │   ├── as.sh
     * │   └── as.bat
     * └── conf/                # 配置文件
     *     ├── arthas.properties
     *     └── logger.xml
     *
     * @param repoMirror 镜像仓库地址
     * @param http 是否使用HTTP协议
     * @param arthasVersion Arthas版本号
     * @param savePath 保存路径
     * @throws IOException 下载或解压失败时抛出
     */
    public static void downArthasPackaging(String repoMirror, boolean http, String arthasVersion, String savePath)
            throws IOException {
        // 处理下载URL的协议（HTTP/HTTPS）
        String repoUrl = getRepoUrl(ARTHAS_DOWNLOAD_URL, http);

        // 定义解压目标目录
        File unzipDir = new File(savePath, arthasVersion + File.separator + "arthas");

        // 创建临时下载文件
        File tempFile = File.createTempFile("arthas", "arthas");

        AnsiLog.debug("Arthas下载临时文件: " + tempFile.getAbsolutePath());

        // 构建完整下载URL（替换版本号和镜像参数）
        String remoteDownloadUrl = repoUrl.replace("${REPO}", repoMirror).replace("${VERSION}", arthasVersion);
        AnsiLog.info("开始从远程服务器下载Arthas: " + remoteDownloadUrl);
        // 执行下载并保存到临时文件
        saveUrl(tempFile.getAbsolutePath(), remoteDownloadUrl, true);
        AnsiLog.info("Arthas下载成功。");
        // 解压下载的安装包到目标目录
        IOUtils.unzip(tempFile.getAbsolutePath(), unzipDir.getAbsolutePath());
    }

    /**
     * 从URL下载文件并保存到本地
     *
     * @param filename 本地保存文件名
     * @param urlString 远程URL地址
     * @param printProgress 是否显示下载进度
     * @throws IOException 下载失败时抛出
     */
    private static void saveUrl(final String filename, final String urlString, boolean printProgress)
            throws IOException {
        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try {
            // 打开URL连接
            URLConnection connection = openURLConnection(urlString);
            in = new BufferedInputStream(connection.getInputStream());
            // 获取文件大小（从HTTP头获取）
            List<String> values = connection.getHeaderFields().get("Content-Length");
            int fileSize = 0;
            if (values != null && !values.isEmpty()) {
                String contentLength = values.get(0);
                if (contentLength != null) {
                    fileSize = Integer.parseInt(contentLength); // 解析内容长度为整数
                }
            }

            fout = new FileOutputStream(filename); // 创建文件输出流

            final byte[] data = new byte[1024 * 1024]; // 1MB缓冲区
            int totalCount = 0; // 已下载字节数
            int count; // 单次读取字节数
            long lastPrintTime = System.currentTimeMillis(); // 上次进度打印时间
            // 循环读取并写入文件
            while ((count = in.read(data, 0, data.length)) != -1) {
                totalCount += count;
                // 显示下载进度（每秒更新一次）
                if (printProgress) {
                    long now = System.currentTimeMillis();
                    if (now - lastPrintTime > 1000) {
                        AnsiLog.info("文件大小: {}, 已下载: {}, 下载中...", formatFileSize(fileSize),
                                formatFileSize(totalCount));
                        lastPrintTime = now;
                    }
                }
                fout.write(data, 0, count); // 写入文件
            }
        } catch (javax.net.ssl.SSLException e) {
            // 处理SSL异常（提示用户尝试HTTP协议）
            AnsiLog.error("TLS连接错误，请尝试添加--use-http参数。");
            AnsiLog.error("URL: " + urlString);
            AnsiLog.error(e);
        } finally {
            // 确保输入流和输出流关闭
            IOUtils.close(in);
            IOUtils.close(fout);
        }
    }

    /**
     * 打开URL连接并处理重定向
     *
     * @param url URL地址
     * @return 处理后的URLConnection对象
     * @throws MalformedURLException URL格式错误时抛出
     * @throws IOException 连接失败时抛出
     */
    private static URLConnection openURLConnection(String url) throws MalformedURLException, IOException {
        URLConnection connection = new URL(url).openConnection(); // 打开URL连接
        if (connection instanceof HttpURLConnection) {
            connection.setConnectTimeout(CONNECTION_TIMEOUT); // 设置连接超时
            // 处理HTTP重定向（3xx状态码）
            int status = ((HttpURLConnection) connection).getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String newUrl = connection.getHeaderField("Location"); // 获取重定向URL
                    AnsiLog.debug("尝试打开URL: {}, 重定向到: {}", url, newUrl);
                    return openURLConnection(newUrl); // 递归处理重定向
                }
            }
        }
        return connection;
    }

    /**
     * 格式化文件大小（转换为KB/MB/GB/TB）
     *
     * @param size 字节数
     * @return 格式化后的文件大小字符串
     */
    private static String formatFileSize(long size) {
        String hrSize;

        double b = size;
        double k = size / 1024.0;
        double m = ((size / 1024.0) / 1024.0);
        double g = (((size / 1024.0) / 1024.0) / 1024.0);
        double t = ((((size / 1024.0) / 1024.0) / 1024.0) / 1024.0);

        DecimalFormat dec = new DecimalFormat("0.00"); // 两位小数格式

        // 根据文件大小选择合适的单位
        if (t > 1) {
            hrSize = dec.format(t).concat(" TB");
        } else if (g > 1) {
            hrSize = dec.format(g).concat(" GB");
        } else if (m > 1) {
            hrSize = dec.format(m).concat(" MB");
        } else if (k > 1) {
            hrSize = dec.format(k).concat(" KB");
        } else {
            hrSize = dec.format(b).concat(" Bytes");
        }

        return hrSize;
    }
}