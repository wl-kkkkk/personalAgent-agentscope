package com.tyut.agentscope.library.service.impl;

import com.tyut.agentscope.library.entity.MarkdownFileMeta;
import com.tyut.agentscope.library.model.FileContentVO;
import com.tyut.agentscope.library.model.FileItemVO;
import com.tyut.agentscope.library.model.FolderVO;
import com.tyut.agentscope.library.repository.MarkdownFileRepository;
import com.tyut.agentscope.library.service.LibraryService;
import com.tyut.agentscope.user.entity.UserInfo;
import com.tyut.agentscope.user.repository.UserInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <h2>本地 Markdown 文件库</h2>
 *
 * <p>用户在界面上选定一个根目录后，这里负责：扫描目录下的 Markdown、把元信息同步进库、
 * 以及按元信息读取本地文件正文（正文不入库）。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>目录为空、不存在、不是文件夹、没有读权限：给出中文提示，不写库；</li>
 *   <li>换目录：先更新 user_info.root_folder，再清掉旧的元信息记录，最后按新目录重建；</li>
 *   <li>扫描深度与文件数都设上限，避免误选到超大目录（如整个 C 盘）把服务拖死；</li>
 *   <li>读文件做路径越权校验：只能读当前根目录内的文件；</li>
 *   <li>单个文件超过上限不返回正文，避免把接口撑爆。</li>
 * </ul>
 */
@Service
public class LibraryServiceImpl implements LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryServiceImpl.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SCAN_DEPTH = 5;
    private static final int MAX_FILES = 500;
    private static final long MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private final UserInfoRepository userInfoRepository;
    private final MarkdownFileRepository markdownFileRepository;

    public LibraryServiceImpl(UserInfoRepository userInfoRepository,
                              MarkdownFileRepository markdownFileRepository) {
        this.userInfoRepository = userInfoRepository;
        this.markdownFileRepository = markdownFileRepository;
    }

    @Override
    public FolderVO getFolder(String userId) {
        String root = currentRoot(userId);
        if (root == null) {
            return new FolderVO(null, 0);
        }
        return new FolderVO(root, markdownFileRepository.findByUser(userId).size());
    }

    @Override
    public FolderVO changeFolder(String userId, String folder) {
        Path root = validateFolder(folder);
        userInfoRepository.updateRootFolder(userId, root.toString());
        int removed = markdownFileRepository.deleteByUser(userId);
        log.info("用户 {} 更换根目录为 {}, 清理旧记录 {} 条", userId, root, removed);
        int count = sync(userId, root);
        return new FolderVO(root.toString(), count);
    }

    @Override
    public List<FileItemVO> listFiles(String userId) {
        String root = currentRoot(userId);
        if (root == null) {
            log.info("用户 {} 还没有选择根目录，返回空列表", userId);
            return List.of();
        }
        Path rootPath = Path.of(root);
        if (!Files.isDirectory(rootPath)) {
            log.warn("用户 {} 的根目录已不存在: {}", userId, root);
            return List.of();
        }
        sync(userId, rootPath);
        return markdownFileRepository.findByUser(userId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public FileContentVO readFile(String userId, long fileId) {
        MarkdownFileMeta meta = markdownFileRepository.findByIdAndUser(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或不属于当前用户"));

        Path file = Path.of(meta.absolutePath()).toAbsolutePath().normalize();
        String root = currentRoot(userId);
        if (root == null || !file.startsWith(Path.of(root).toAbsolutePath().normalize())) {
            log.warn("拒绝越权读取: userId={}, path={}", userId, file);
            throw new IllegalArgumentException("该文件不在当前根目录内，拒绝读取");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件已被移动或删除: " + meta.relativePath());
        }

        long size = meta.fileSize();
        if (size > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("文件过大（" + (size / 1024) + "KB），超过"
                    + (MAX_CONTENT_BYTES / 1024 / 1024) + "MB 上限，暂不支持在线预览");
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            log.info("读取文件成功: userId={}, file={}", userId, meta.relativePath());
            return new FileContentVO(meta.id(), meta.fileName(), meta.relativePath(),
                    meta.absolutePath(), meta.fileSize(), format(meta.lastModified()), content);
        } catch (IOException e) {
            log.error("读取文件失败: {}", file, e);
            throw new IllegalStateException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private String currentRoot(String userId) {
        return userInfoRepository.findById(userId)
                .map(UserInfo::rootFolder)
                .filter(folder -> folder != null && !folder.isBlank())
                .orElse(null);
    }

    private Path validateFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new IllegalArgumentException("目录不能为空");
        }
        Path root;
        try {
            root = Path.of(folder.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("目录路径不合法: " + folder);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("目录不存在: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("这不是一个文件夹: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new IllegalArgumentException("目录没有读取权限: " + root);
        }
        return root;
    }

    /** 扫描目录并同步元信息，返回扫到的文件数。 */
    private int sync(String userId, Path root) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isMarkdown)
                    .limit(MAX_FILES)
                    .forEach(found::add);
        } catch (IOException e) {
            log.error("扫描目录失败: {}", root, e);
            throw new IllegalStateException("扫描目录失败: " + e.getMessage(), e);
        }

        List<String> keepHashes = new ArrayList<>(found.size());
        for (Path path : found) {
            Path absolute = path.toAbsolutePath().normalize();
            keepHashes.add(sha256(absolute.toString()));
            markdownFileRepository.upsert(new MarkdownFileMeta(
                    null, userId, root.toString(),
                    root.relativize(absolute).toString(),
                    absolute.toString(),
                    absolute.getFileName().toString(),
                    sizeOf(absolute), lastModifiedOf(absolute)), sha256(absolute.toString()));
        }
        markdownFileRepository.deleteMissing(userId, keepHashes);
        log.info("同步完成: userId={}, root={}, 文件数={}", userId, root, found.size());
        return found.size();
    }

    private boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("读取文件大小失败: {}", path);
            return 0L;
        }
    }

    private LocalDateTime lastModifiedOf(Path path) {
        try {
            Instant instant = Files.getLastModifiedTime(path).toInstant();
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (IOException e) {
            log.warn("读取文件修改时间失败: {}", path);
            return null;
        }
    }

    private FileItemVO toItem(MarkdownFileMeta meta) {
        return new FileItemVO(meta.id(), meta.fileName(), meta.relativePath(),
                meta.fileSize(), format(meta.lastModified()));
    }

    private String format(LocalDateTime time) {
        return time == null ? null : TIME_FORMAT.format(time);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
