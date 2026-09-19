package com.tyut.agentscope.library.controller;

import com.tyut.agentscope.common.ApiResponse;
import com.tyut.agentscope.library.model.FileContentVO;
import com.tyut.agentscope.library.model.FileItemVO;
import com.tyut.agentscope.library.model.FolderRequest;
import com.tyut.agentscope.library.model.FolderVO;
import com.tyut.agentscope.library.service.LibraryService;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <h2>本地 Markdown 文件库接口</h2>
 *
 * <p>当前用户从登录态取，不接受前端传 userId，避免越权查别人的文件。
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private static final Logger log = LoggerFactory.getLogger(LibraryController.class);

    private final LibraryService libraryService;
    private final AuthService authService;

    public LibraryController(LibraryService libraryService, AuthService authService) {
        this.libraryService = libraryService;
        this.authService = authService;
    }

    /** 当前选定的根目录 */
    @GetMapping("/folder")
    public ApiResponse<FolderVO> getFolder() {
        try {
            return ApiResponse.ok(libraryService.getFolder(authService.currentUserId()));
        } catch (Exception e) {
            log.warn("获取根目录失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 更换根目录：会同步更新数据库中的目录配置与文件元信息 */
    @PostMapping("/folder")
    public ApiResponse<FolderVO> changeFolder(@RequestBody FolderRequest request) {
        try {
            return ApiResponse.ok("目录已更新",
                    libraryService.changeFolder(authService.currentUserId(), request.folder()));
        } catch (Exception e) {
            log.warn("更换根目录失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 列出根目录下的 Markdown 文件（顺带同步元信息） */
    @GetMapping("/files")
    public ApiResponse<List<FileItemVO>> listFiles() {
        try {
            return ApiResponse.ok(libraryService.listFiles(authService.currentUserId()));
        } catch (Exception e) {
            log.warn("列出文件失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 按元信息 id 读取本地文件正文 */
    @GetMapping("/files/{id}")
    public ApiResponse<FileContentVO> readFile(@PathVariable long id) {
        try {
            return ApiResponse.ok(libraryService.readFile(authService.currentUserId(), id));
        } catch (Exception e) {
            log.warn("读取文件失败: id={}, {}", id, e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }
}
