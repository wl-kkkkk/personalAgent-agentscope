package com.tyut.agentscope;

import com.tyut.agentscope.tool.DocumentTools;
import io.agentscope.core.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保存目录的回归测试：用户在界面设置的目录（ToolExecutionContext）始终优先，
 * 模型传的 outputDir 仅在 context 无有效值时兜底。
 * 旧逻辑是 outputDir 优先，导致模型传错/传残时文件落不到用户设置的目录。
 */
class DocumentToolsDirTest {

    @Test
    void contextTakesPriorityOverOutputDir() throws Exception {
        Path userFolder = Files.createTempDirectory("user-md-dir");
        Path modelPassedDir = Files.createTempDirectory("model-passed-dir");
        Path fallbackFolder = Files.createTempDirectory("server-default-dir");
        DocumentTools tools = new DocumentTools(fallbackFolder.toString(), null, null);

        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(DocumentTools.ROOT_FOLDER_KEY, userFolder.toString())
                .build();

        String path = tools.writeMarkdown("测试标题", "# 正文内容", modelPassedDir.toString(), context);

        assertThat(Path.of(path)).exists();
        assertThat(path).startsWith(userFolder.toRealPath().toString());
        assertThat(Files.list(userFolder).count()).isEqualTo(1);
    }

    @Test
    void fallsBackToUserFolderFromContext() throws Exception {
        Path userFolder = Files.createTempDirectory("user-md-dir-2");
        Path fallbackFolder = Files.createTempDirectory("server-default-dir-2");
        DocumentTools tools = new DocumentTools(fallbackFolder.toString(), null, null);

        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(DocumentTools.ROOT_FOLDER_KEY, userFolder.toString())
                .build();

        String path = tools.writeMarkdown("测试标题", "# 正文内容", null, context);

        assertThat(Path.of(path)).exists();
        assertThat(path).startsWith(userFolder.toRealPath().toString());
        assertThat(Files.list(userFolder).count()).isEqualTo(1);
    }

    @Test
    void outputDirUsedWhenContextIsEmpty() throws Exception {
        Path explicitFolder = Files.createTempDirectory("explicit-md-dir");
        Path fallbackFolder = Files.createTempDirectory("server-default-dir-3");
        DocumentTools tools = new DocumentTools(fallbackFolder.toString(), null, null);

        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(DocumentTools.ROOT_FOLDER_KEY, "")
                .build();

        String path = tools.writeMarkdown("测试标题", "# 正文内容", explicitFolder.toString(), context);

        assertThat(path).startsWith(explicitFolder.toRealPath().toString());
    }
}