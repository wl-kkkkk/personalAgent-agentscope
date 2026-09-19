package com.tyut.agentscope.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h2>Skill 目录</h2>
 *
 * <p>启动时从 classpath 的 {@code skills/} 加载全部 {@code SKILL.md}（每个 skill 一个目录），
 * 并维护"skill 对应哪些工具"的绑定关系：skill 激活时只暴露它自己需要的工具，
 * 减少工具数量膨胀带来的选择错误。
 *
 * <p>每次构建 agent 时用 {@link #newSkillBox(Toolkit)} 生成一份新的 SkillBox，
 * 避免多个并发会话共享同一个工具组状态。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    /** skill 名称 → 该 skill 需要用到的工具名 */
    private static final Map<String, List<String>> TOOLS_BY_SKILL = Map.of(
            "knowledge-base-qa", List.of("rewrite_query", "answerByPersonalKnowledge"),
            "web-knowledge-capture", List.of("web_search", "extract_keywords",
                    "write_markdown", "upload_document"));

    private final List<AgentSkill> skills;

    public SkillCatalog() throws IOException {
        ClasspathSkillRepository repository = new ClasspathSkillRepository("skills");
        List<AgentSkill> loaded = new ArrayList<>();
        for (String name : repository.getAllSkillNames()) {
            loaded.add(repository.getSkill(name));
        }
        if (loaded.isEmpty()) {
            log.warn("skills/ 目录下没有加载到任何 skill，请检查 SKILL.md 的目录结构与 frontmatter");
        }
        this.skills = List.copyOf(loaded);
        log.info("已加载 {} 个 skill: {}", skills.size(),
                skills.stream().map(AgentSkill::getName).toList());
        for (AgentSkill skill : skills) {
            if (!TOOLS_BY_SKILL.containsKey(skill.getName())) {
                log.warn("skill {} 没有配置工具绑定，它的可用工具不会被收窄", skill.getName());
            }
        }
    }

    public List<AgentSkill> all() {
        return skills;
    }

    /** 为某个 toolkit 构建一份绑定好工具的 SkillBox。 */
    public SkillBox newSkillBox(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("toolkit 不能为空");
        }
        SkillBox skillBox = new SkillBox(toolkit);
        for (AgentSkill skill : skills) {
            SkillBox.SkillRegistration registration = skillBox.registration().skill(skill);
            List<String> tools = TOOLS_BY_SKILL.get(skill.getName());
            if (tools != null && !tools.isEmpty()) {
                registration = registration.enableTools(tools);
            }
            registration.apply();
            log.debug("skill {} 已注册，绑定工具: {}", skill.getName(), tools);
        }
        // 必须注册"加载 skill 正文"的工具：AgentScope 的 skill 是渐进式披露，
        // SkillHook 只往提示词里注入 skill 索引（名称+描述），正文要靠这个工具读取。
        // 不注册的话，SKILL.md 里写的流程（比如"确认后调用 upload_document"）模型根本看不到。
        skillBox.registerSkillLoadTool();
        log.info("已注册 skill 加载工具, 当前工具: {}", toolkit.getToolNames());
        return skillBox;
    }
}
