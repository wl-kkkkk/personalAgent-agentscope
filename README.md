# personalagent-agentscope

AgentScope Java（1.0.12）版本的个人知识助手：**一切皆工具 + Skill 管流程 + Hook 管审批**，
带一个和 personalrag 同风格的网页界面（登录 + 选定本地 Markdown 目录 + 预览）。

```
用户提问
  └─ ReActAgent（个人知识助手）
        ├─ 按 skill 判断走哪条路（skill 是 Markdown 写的流程说明，从 classpath 加载）
        ├─ 进入时自动改写查询（QueryRewriteHook，PreCall 阶段，必跑）
        ├─ 个人资料类 → answerByPersonalKnowledge（RAG MCP 工具，随 MCP 客户端注册进 Toolkit）
        └─ 外部信息类 → web_search → 按固定格式整理
                          → write_markdown（保存目录由用户指定）
                          → extract_keywords
                          → upload_document（UploadApprovalHook 拦截 → 人工审批 → 调 RAG 的 upload2Rag）
```

## 结构

- `config/AgentScopeConfig.java`：模型（`chatModel` / `webSearchModel`，后者开了 `enable_search`）、`Toolkit` 的装配（可共享的单例部分）。
- `config/AgentMemoryFactory.java`：短期记忆工厂，构建带自动压缩/卸载的 `AutoContextMemory`。
- `common/PromptLoader.java` / `common/ModelCaller.java`：提示词加载与模型调用，统一处理空值、超时、异常。
- `agent/KnowledgeAgentFactory.java`：**按请求**构建 agent，每次一份新 memory + 一份 `toolkit.copy()` 和对应 `SkillBox`。
- `skill/SkillCatalog.java`：启动时加载 skill，并维护"skill → 需要哪些工具"的绑定。
- `session/AgentSessionStore.java`：会话持久化，`MysqlSession` + `userId:sessionId` 的 key 规则。
- `service/ChatService.java`：`loadIfExists` → `call` → `saveTo` 的显式存取。
- `common/StreamEvent.java`：流式对话的 SSE 事件体（session / progress / chunk / approval / done / error）。
- `tool/QueryRewriteTools.java`：`rewrite_query`，结合历史对话改写检索查询。
- `hook/QueryRewriteHook.java`：每次请求进入时自动改写用户问题（Hook 保证必跑，不由模型决定）。
- `tool/WebSearchTools.java`：`web_search`。
- `tool/DocumentTools.java`：`write_markdown`（保存目录由用户指定）、`extract_keywords`。
- `tool/UploadTools.java`：`upload_document`，底层调 RAG 的 MCP 工具 `upload2Rag`。
- `service/HitlService.java`：审批后的恢复逻辑（构造匹配 id 的 `ToolResultBlock` 回填）。
- `hook/UploadApprovalHook.java`：挂在 `PostReasoningEvent` 上的 HITL 拦截点。
- `user/*`：用户模块（controller → service → repository），Sa-Token 登录 + BCrypt 密码。
- `library/*`：本地 Markdown 文件库（目录校验、扫描同步、按元信息读取正文）。
- `config/SaTokenConfig.java` / `config/PasswordEncoderConfig.java`：登录拦截与密码编码。
- `resources/static/index.html`：网页界面（登录注册 + 目录选择 + 文件列表 + Markdown 预览）。
- `resources/prompts/*.st`：`agent-system`（智能体系统提示词）、`query-rewrite`（查询改写）。
- `resources/skills/<name>/SKILL.md`：`knowledge-base-qa`、`web-knowledge-capture` 两个 skill（AgentScope 约定：每个 skill 一个目录，文件名必须是 `SKILL.md`，frontmatter 含 `name` 和 `description`）。

## 跑起来

```bash
mvn spring-boot:run          # 端口 8082
```

先建库建表（**必须执行**，脚本可重复执行）：

```bash
mysql -h 192.168.141.129 -u myuser -p --default-character-set=utf8mb4 -e "source D:/Java/personalproject/personalAgent/agentscope/docs/sql/init.sql"
```

然后浏览器打开 <http://localhost:8082/> 注册登录即可。

配置分两层，仓库里不放任何真实密钥：

- `application.yml`：非敏感默认值（端口、模型名、数据库地址等），随仓库走。
- `application-local.yml`：真实密钥/密码，**不进仓库**。新克隆的仓库照着 `src/main/resources/application-local.example.yml` 复制一份填上即可。

`application-local.yml` 会被自动加载，不需要额外命令，`mvn spring-boot:run` 直接跑就行。想用环境变量覆盖也可以，环境变量优先级更高：

| 变量 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | DashScope 密钥，覆盖本地配置里的值 |
| `AGENT_DB_PASSWORD` | MySQL 密码，覆盖本地配置里的值 |
| `AGENT_DB_URL` / `AGENT_DB_USER` | 可选，默认 `192.168.141.129:3306/personalagent` / `myuser` |
| `RAG_MCP_URL` | 可选，默认 `http://localhost:8080/api/mcp` |

> 启动依赖两样外部服务：**personalrag**（MCP 客户端启动时连它拉工具列表）和 **MySQL**（`MysqlSession` 构造时会校验库表）。两者没起来应用会启动失败。

对话接口（需要登录；`userId` 从登录态取，前端不传；响应统一是 `{success, message, data}`）：

```bash
# 先登录拿 cookie
curl -c cookie.txt -X POST http://localhost:8082/user/login \
  -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"123456"}'

curl -b cookie.txt -X POST http://localhost:8082/api/agent/ask \
  -H "Content-Type: application/json" -d '{"query":"今天有什么科技新闻"}'
```

首次不传 `sessionId`，服务端会生成并返回；之后每轮都要**把返回的 sessionId 带回来**，否则每轮都是新会话、记不住上下文：

```bash
curl -b cookie.txt -X POST http://localhost:8082/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"3f2a…","query":"那再详细说说第二条"}'
```

## 模型配置（含一个 404 的坑）

三个模型都在 `AgentScopeConfig` 里，各自独立、都可用环境变量覆盖：

| Bean | 配置项 | 默认值 | 用途 |
|---|---|---|---|
| `chatModel` | `app.dashscope.model` | `qwen-max` | 主对话 / 记忆压缩 |
| `webSearchModel` | 同上 + `enable_search` | | 联网检索 |
| `queryRewriteModel` | `app.dashscope.rewrite-model` | `qwen3.8-flash` | 查询改写（每次提问都调一次，用轻量快模型） |
| `titleModel` | `app.dashscope.title-model` | `qwen3.8-flash` | 会话标题生成（异步） |

⚠️ **`url error, please check url!` 这条错误很误导，它有两个原因，而且主因不是 URL：**

1. `baseUrl` 必须是 DashScope **原生**地址（代码里写死 `https://dashscope.aliyuncs.com`）。填成 OpenAI 兼容模式地址（`.../compatible-mode/v1`）会被判为 url error。
2. **端点选错才是主因**：DashScope 有「文本端点」和「多模态端点」两条路径，**模型挂到错误的端点，返回的也是这条 "url error"**，而不是"模型不存在"。

实测（用本项目配置里的 key 直接打这两个端点）：

| 模型 | 文本端点 | 多模态端点 |
|---|---|---|
| `qwen-max` | ✅ 200 | ❌ url error |
| `qwen-flash` / `qwen-turbo` / `qwen-plus` | ✅ 200 | |
| `qwen3.7-flash` / `qwen3.8-flash` / `qwen3.8-max` | ❌ url error | ✅ 200 |

结论：`qwen3.7/3.8` 这一代在 DashScope 上挂在**多模态端点**，而 AgentScope 1.0.12 的自动判定只覆盖到 `qwen3.5/3.6`（判定依据是 `qvq*`、名字含 `-vl`/`-asr`、`qwen3.5*`、`qwen3.6*`、`kimi-k2.5/2.6`）。因此 `AgentScopeConfig.endpointTypeFor(model)` 把 `qwen3.7+` 显式指到 `EndpointType.MULTIMODAL`，其余仍走 AUTO。

再遇到这类问题，最快的办法是拿模型名分别打两个端点，一眼能分清是名字问题还是端点问题；也可以把日志调成 DEBUG（`logging.level.io.agentscope.core.model: DEBUG`）看它实际请求的 URL —— 它会打印 `DashScope request to <url>`。

## 会话与标题

表 `agent_conversation` 存**界面要用的元信息**（标题、创建/更新时间），会话记忆本体仍在 `agentscope_sessions`。

标题生成照 personalrag 的做法：新会话先用第一句话截 20 字当**临时标题**立刻落库（界面马上有东西显示），然后用**虚拟线程**异步调 `titleModel` 生成正式标题回写；失败只记 warn，保留临时标题，不影响对话。

`GET /api/conversations` 按最近使用时间返回当前用户的会话列表；流式响应里的 `session` 事件会带上标题，前端在流结束和 3 秒后各刷一次列表，把异步生成的正式标题取回来。

**删除会话**：`DELETE /api/conversations/{conversationId}`，界面里把鼠标移到会话条目上会出现 `×`。删除会**一次带走三样东西**：

1. `agent_conversation` 里的元信息；
2. `agentscope_sessions` 里的会话记忆（`Session.delete(key)`，key 与 ChatService 用同一套 `userId:sessionId` 规则）；
3. 该会话下挂起的 `agent_hitl_task` 审批任务，避免留下孤儿任务。

只能删自己的会话（按 `user_id` 校验，越权直接拒绝）。记忆删除失败只记 error 日志、不影响元信息删除，用户侧看到的结果是一致的。

## ⚠️ 注册工具的坑（踩过一次，务必注意）

`Toolkit.ToolRegistration` 内部**只有一个 `toolObject` 字段**，所以链式写法：

```java
toolkit.registration().tool(a).tool(b).tool(c).tool(d).apply();   // ❌ 只有 d 生效
```

**每次 `.tool()` 都是覆盖而不是累加**，结果就是"工具莫名其妙不见了"，而且不报错——表现为模型说"我无法联网/查不到"，实际是它手上根本没有那个工具。

正确写法是逐个 `apply()`：

```java
toolkit.registration().tool(a).apply();
toolkit.registration().tool(b).apply();
```

启动日志里会逐个打印 `Registered tool 'xxx' in group 'ungrouped'`，**对着日志数一遍工具是不是齐的**，是排查这类问题最快的方法。

## ⚠️ 工具参数必须加 @ToolParam（踩过一次）

AgentScope 生成工具 schema 时**只认 `@ToolParam` 注解**，光有方法参数名不够。不加注解的结果是：

```
schema: upload_document -> {type=object, properties={}}     ← 参数是空的
```

模型看不到参数定义，只能**猜参数名**。`web_search(query)` 它猜对了，`upload_document(title, markdownContent)` 它猜成了 `{content=...}` 甚至 `{}` —— 于是上传工具永远拿不到完整入参，每次都失败，而且**不报错**（工具只是返回"入参不完整"）。

所以每个工具方法的每个参数都要写清楚：

```java
@Tool(name = "upload_document", description = "...")
public String uploadDocument(
        @ToolParam(name = "title", description = "文档标题") String title,
        @ToolParam(name = "markdownContent", description = "Markdown 正文全文") String markdownContent) { ... }
```

可选参数用 `@ToolParam(name = "xxx", required = false, description = "...")`。
排查时把 `toolkit.getToolSchemas()` 打出来看一眼 `properties` 是不是空的，最直接。

## 流式对话（SSE）

接口：`POST /api/agent/stream`，`produces = text/event-stream`。

实现方式与 personalrag 一致——`Flux.create(sink -> ...)`，把回调里的内容 `sink.next(...)` 推给前端；不一样的是数据来源：这里订阅 AgentScope 的 `agent.stream(...)`（`EventType.REASONING` + `incremental(true)`）拿模型的增量文本，再逐段推。

每个事件是一行 JSON（不是裸文本）：`{"type":"chunk","text":"..."}`。**用 JSON 是因为裸文本里带换行会把 SSE 的 `data:` 帧拆断**（前端按行解析会丢内容），JSON 序列化会把换行转义掉。

事件类型：`session`（回传 sessionId）、`progress`（进度提示）、`chunk`（增量文本）、`approval`（HITL 待审批，带 taskId / toolName）、`done`、`error`。前端按类型分别处理：chunk 追加纯文本、done 时再做一次完整 Markdown 渲染。

用户校验在返回 `Flux` **之前**完成，所以未登录/参数错误会走普通 JSON 响应，而不是返回半个流。

## 记忆模块

两层，都在现有骨架里接好了：

**第一层：短期记忆** — `AutoContextMemory`（`AgentMemoryFactory` 统一构建）。超过 4KB 的单条消息卸载并保留 300 字符预览；上下文上限 128K、到 75% 触发压缩；消息超 60 条压缩、保留最近 20 条。注意压缩本身是一次 LLM 调用，有额外延迟和成本；所以 `AutoContextMemory` 需要传模型。

**第二层：会话持久化** — `MysqlSession` 存 MySQL，表名是框架默认的 **`agentscope_sessions`**（复数，不是 `agentscope_session`），不存在会自动建。存取时机在 `ChatService` 里显式控制：`loadIfExists` → `call` → `saveTo`，agent 上挂了 `statePersistence(StatePersistence.memoryOnly())`。

**session key 规则**：`userId:sessionId`（`SimpleSessionKey.of("userId:sessionId")`）。框架的 `validateSessionId` 只禁止空串、含 `/` 或 `\`、超过 255 字符，冒号是合法的。userId 放在前面，方便以后按用户前缀枚举/清理该用户的会话。

**为什么 agent 不是单例**：agent 持有 memory，如果做成单例 bean，并发请求会把消息混进同一份记忆、再各自存到不同 key（串台）。所以 `KnowledgeAgentFactory` 按请求构建 agent + 新 memory。可共享的只有模型、Toolkit、SkillBox 这些"定义类"组件。

## 人工审批（HITL）

范围只限定在敏感工具（`hitl/SensitiveTools.NAMES`，目前是 `upload_document`）。

**拦截时机**：`UploadApprovalHook` 挂在 **`PostReasoningEvent`** 上——模型刚产出 tool calls、还没执行，这时才能"拦下来又不算失败"。检测到敏感工具就 `stopAgent()`，本轮到此为止。用 `PreActingEvent` 就晚了：那时工具已经进入执行阶段。

**落库**：挂起的调用由 `ChatService` 写进 `agent_hitl_task` 表（表结构见 `docs/sql/init.sql`）。记忆本身已经由 `MysqlSession` 存到 `agentscope_sessions`，所以这张表只存审批元数据：`tool_use_id` / `tool_name` / `tool_input` / 状态。

**恢复**：`POST /api/agent/approve`，`HitlService` 会

1. 按 `task_id` 取任务，状态改成 APPROVED / REJECTED；
2. 按 `userId:sessionId` 把记忆从 MySQL 载回来（挂起的 tool call 就在里面）；
3. **构造真实的 `ToolResultBlock` 回填**——审批通过就真的调用 `upload_document`，拒绝则填"未执行"；`id` 必须与挂起时 `ToolUseBlock` 的 id 完全一致；
4. 用 `ToolResultMessageBuilder.buildToolResultMsg(...)` 包成 TOOL 消息，`agent.call(toolResultMessage)` 继续推理，最后把记忆存回。

⚠️ 关键坑：框架自带的 `PendingToolRecoveryHook`（`enablePendingToolRecovery(true)`）**不是恢复机制，是兜底清理**——它会扫出没有结果的 tool call 并**自动填一个 error 结果**。所以恢复时必须先注入自己的 `ToolResultBlock`，否则那个挂起的上传会被当成孤儿调用、直接判失败。

**为什么不依赖"挂起的 tool call 一定在记忆里"**：恢复前会先检查记忆里有没有那条 assistant 消息（`PendingToolScan.containsToolUseId`）；没有的话，就用 `agent_hitl_task` 里存的 `tool_use_id` / `tool_name` / `tool_input` **重建这条消息再注入**。这条链路因此不依赖框架是否把停止时的推理消息落盘。

## Web 界面（对话 + 文件库）

页面：`http://localhost:8082/`（`resources/static/index.html`，单文件，风格与 personalrag 一致）。

左侧导航两个视图：

**💬 对话** —— 消息气泡 + 输入框（Enter 发送、Shift+Enter 换行）。会话 `sessionId` 存在浏览器 `localStorage`（按登录账号区分），刷新页面不会丢；点「新建对话」重新开一个 session。当模型要走联网沉淀、需要上传知识库时，对话里会直接出现**审批卡片**（批准上传 / 拒绝），点一下就调 `/api/agent/approve` 续跑，结果作为新消息插回对话。

**📂 文件库** —— 左上角显示当前选定的 Markdown 目录（长路径在框内滚动、超出裁掉），下面文件列表纵向滚动，右侧渲染 Markdown 预览。

**用户昵称会写进系统提示词**（`{{nickname}}` → `agent-system.st`）：`answerByPersonalKnowledge(nickname, query)` 需要昵称，之前 agent 根本不知道当前用户是谁、只能瞎猜，现在由登录态注入。

**登录**：与 personalrag 同一套（Sa-Token + `{success, message, data}` 返回体），接口是 `/user/register`、`/user/login`、`/user/logout`、`/user/is-login`、`/user/current`。区别是密码用 **BCrypt 哈希**存库，不像 personalrag 那样存明文。

**用户表**：结构与 `personalrag.user_info` 一致，但建在 agentscope 自己的库 `personalagent` 里，额外多一列 `root_folder`。两个模块本来就是独立项目，共用 RAG 的库会把它们又绑一起；想单点登录的话，把 `AGENT_DB_URL` 指到 `personalrag` 库即可。

**文件元信息**：`agent_markdown_file` 只存元信息（相对路径、绝对路径、文件名、大小、修改时间），**正文不入库** —— 这行指向本地文件，打开时按绝对路径读。唯一键是 `(user_id, path_hash)`，`path_hash` 是绝对路径的 SHA-256（避免超长路径进索引）；同步时按它 upsert，所以文件 id 稳定、不会每次刷新都变。

**界面行为**：

- 顶部显示当前目录，点「更改文件夹」填服务器上的绝对路径；换目录会**先更新 `user_info.root_folder`，再清掉该用户旧的文件记录，然后按新目录重新扫描**。
- 左上角固定显示选中的目录（长路径在框内滚动、超出裁掉），下面是文件列表（纵向滚动）。
- 右侧按 `marked` 渲染 Markdown，点击文件时按元信息 id 读取本地正文。

**边界处理**：目录为空/不存在/不是文件夹/无读权限都直接拒绝并提示；扫描有深度（5 层）和数量（500 个）上限，避免误选整个磁盘；读文件做**路径越权校验**（只能读当前根目录内的文件）与大小上限（2MB）；当前用户从登录态取，前端不传 userId。

## 待完善

- 会话清理：每开一次新会话就多一行数据，建议按更新时间做 TTL 清理（`MysqlSession.delete/clearAllSessions/truncateAllSessions`）。
- 审批任务的清理与幂等：目前同一 `tool_use_id` 唯一键防重，过期未审批的任务需要另做 TTL。

## 已完成（本轮）

- **Markdown 保存目录对齐**：`write_markdown` 的默认目录原来只是配置里的 `app.knowledge.dir`，跟用户在界面「更改文件夹」设置的目录是两处，导致保存的文件在文件库里看不到。现在把 `user_info.root_folder` 注入系统提示词（`{{rootFolder}}`），agent 写文档时直接用它；设置页与文件库看到的是同一个目录。
- **修复"工具莫名消失"**：链式 `toolkit.registration().tool(a).tool(b)...` 只会保留最后一个，导致 `web_search`/`rewrite_query`/`write_markdown`/`extract_keywords` 全都没注册上（启动日志里只打印了 `upload_document`）。改成逐个 `apply()`，并用探针验证 agent 能真正调用联网检索。
- 新增两个**手动探针**（`@Tag("probe")`，默认跳过）：`WebSearchProbeTest` 直接打联网模型看原始返回，`AgentToolProbeTest` 按线上方式装配 toolkit+agent 跑一次；跑法 `mvn test -DexcludedGroups= -Dgroups=probe`。
- 联网工具不再吞异常：失败时原样带出异常类型与消息，界面工具结果行和日志都能看到。
- `agentscope_sessions` 改用本项目所在的库（框架默认会建到名为 `agentscope` 的库里），建表语句补进 `init.sql`。
- 网页界面加上对话：导航切换「对话 / 文件库」，消息气泡、HITL 审批卡片、`sessionId` 本地留存；`/api/agent/ask`、`/api/agent/approve` 改为从登录态取用户、返回统一响应体，并做了越权审批校验。
- Web 界面 + 登录 + 本地 Markdown 文件库：`user/*`、`library/*`、`static/index.html`；建表脚本合并成 `docs/sql/init.sql`（建库 + `user_info` + `agent_markdown_file` + `agent_hitl_task`）。
- `upload_document` 接上 RAG 真实上传：底层调 MCP 工具 `upload2Rag(documentName, markdownContent)`；`upload2Rag` 同时加进 `SensitiveTools`，防止模型绕过包装工具直接调 MCP。
- skill 绑定工具：`SkillCatalog` 维护"skill → 工具"映射，激活某个 skill 时只暴露它需要的工具。
- 工具集改为**共享基础 toolkit**：`toolkit.copy()` 不复制 MCP 管理器、有丢工具的风险，而 skill 绑定工具组那段实际是空操作、`SkillHook` 也只注入提示词，所以复制没有收益。每个 agent 仍然各自一份 `SkillBox`。
- 查询改写改为**每次必跑**：`QueryRewriteHook` 挂在 `PreCallEvent`，改写用户输入后再交给 agent；历史从 `event.getMemory()` 直接取，不用模型传参。`rewrite_query` 工具仍保留，供模型对子问题单独改写。提示词在 `resources/prompts/query-rewrite.st`。
- 系统提示词与两个 skill 全部按统一规范重写（定位 → 策略 → 执行规则 → 示例 → 占位符）。
- Markdown 保存目录改为用户可选（`write_markdown` 的 `outputDir` 参数，不传则用默认目录）。
- 边界处理补齐：空查询/空内容直接拒绝、文件名冲突不覆盖、非法目录、模型超时（60s）、MCP 上传超时（2min）、工具入参缺失等，并统一用日志记录。

## 排查手段

- **工具齐不齐**：启动日志会逐个打印 `Registered tool 'xxx' in group 'ungrouped'`，对着数一遍即可；也可以打印 `toolkit.getToolSchemas()` 看 `properties` 是不是空的（空的就说明参数没加 `@ToolParam`）。
- **单个调用通不通**：跑探针 `mvn test -Dprobe.excluded.groups= -Dgroups=probe`（默认不执行，避免每次构建都真调 API）。
- **看实际请求**：把 `logging.level.io.agentscope.core.model` 调成 DEBUG，会打印 `DashScope request to <url>`。

## 面试准备

### 项目介绍（约 30~60 秒）

一句话定位：基于 AgentScope Java 1.0 的个人知识助手，用 ReAct Agent 编排工具完成「查知识库 / 联网检索并沉淀知识」，知识库能力通过 MCP 复用已有的 RAG 服务。

```
这是我的第二个项目，一个个人知识助手 Agent。业务上就两件事：用户问自己的资料时查个人知识库，问实时信息时联网检索，并把检索到的知识整理成 Markdown 文档沉淀回知识库。

我刻意用两套技术栈实现了同一个业务：一套是 AgentScope Java 的 ReAct Agent + Skill + Tool，另一套是 Spring AI Alibaba 的 StateGraph 工作流，两个模块完全独立、可以对比效果。

知识库能力我没有重写，而是通过 MCP 协议接入上一个 RAG 项目暴露的服务，Agent 直接调用它的查询工具和上传工具，所以 RAG 那套检索、rerank、向量化都在后端复用。

核心工作有三块：一是 HITL 人工审批，知识入库前必须人工确认；二是两层记忆，短期用自动压缩控制上下文、长期落 MySQL 做会话持久化；三是把流程从提示词里抽出来做成 Skill，让模型按需加载。

工程上还踩了一批「静默失败」的坑，比如工具链式注册会互相覆盖、工具参数没加注解导致模型瞎猜参数名、DashScope 端点选错报的却是 url error，这些我都做成了可复跑的探针测试。
```

### 整体链路

```
用户提问 → 查询改写（PreCall Hook 自动执行，必跑）→ ReAct Agent 自主决策
  ├─ 个人资料类 → 调 MCP 的 answerByPersonalKnowledge 查知识库
  └─ 实时信息类 → web_search 联网检索 → 按固定格式整理 → write_markdown 落盘
                                        → upload_document 上传（触发人工审批）
```

```
入口是流式对话接口，收到问题后先过一个 PreCall Hook：它拿最近的对话历史把问题改写成适合检索的查询，这一步是 Hook 不是 Tool，因为它必须每次都执行、不能交给模型决定。

改写后的查询交给 ReAct Agent，模型自己判断走哪条路：个人资料类调 MCP 的知识库查询工具，实时信息类先联网检索、再按固定格式整理、写成本地 Markdown，最后调上传工具。

上传是敏感操作，走到这一步会挂起等人工审批，审批通过后才真正入库。
```

### HITL 人工审批（挂起与恢复）

```
上传工具属于敏感操作，我做成了工具级的人工审批。

拦截时机是关键：我挂在 PostReasoningEvent 上，这时模型刚产出 tool call、还没执行，拦住既不会执行、也不会被算成失败；如果挂 PreActingEvent 就晚了，工具已经进入执行阶段。

检测到敏感工具就调 stopAgent 把这一轮停下来，把挂起的 tool_use_id、工具名、入参落库，前端在对话里渲染出审批卡片；审批通过或拒绝后调恢复接口。

恢复的做法是构造一个 id 完全相同的 ToolResultBlock 回填，再用 ToolResultMessageBuilder 包成 TOOL 消息交给 agent 继续推理。

这里有个坑：框架自带的 PendingToolRecoveryHook 不是恢复机制，而是兜底清理——它会扫出没有结果的 tool call、自动填一个 error 结果。所以恢复时必须先注入自己的结果，否则挂起的上传会被当成孤儿调用、直接判失败。

另外我没有假设「挂起的 tool call 一定在记忆里」：恢复前先检查记忆里有没有这条 assistant 消息，没有就用落库的 tool_use_id / 工具名 / 入参把它重建出来再注入，这样即使框架没把停止时的推理消息落盘，配对也能成立。
```

### 两层记忆

```
短期记忆用 AutoContextMemory：单条消息超过 4KB 就卸载、只留 300 字符预览；上下文到窗口的 75%、或消息超过 60 条就触发压缩，保留最近 20 条。压缩本身是一次 LLM 调用，所以它必须绑定模型。

长期记忆用框架的 MysqlSession 落到 MySQL，session key 用 userId:sessionId 两级拼起来，保证不同用户、不同会话互相隔离，userId 放在前面以后也方便按用户枚举和清理。

存取时机我放在调用点显式做：loadIfExists → call → saveTo，没有用 Hook。原因是 Hook 是单例、拿不到 session key；而且如果 PreCall 再加载一次，会把 HITL 恢复时注入的那条消息覆盖掉。

还有一个必须按请求创建 agent 的原因：agent 持有 memory，如果做成单例 Bean，两个用户并发提问时消息会混进同一份记忆、再各自存到不同的 key，直接串台。可共享的只有模型、工具集这些「定义类」组件。
```

### Skill、工具与流式

```
流程不写在系统提示词里，而是做成 Skill：框架只把 skill 的索引（名字+描述）注入提示词，正文用 load_skill_through_path 工具按需加载，避免提示词膨胀。

工具用 @Tool 注解注册，参数必须用 @ToolParam 标注名字和描述——不写的话框架生成出来的 schema 里参数是空的，模型只能猜参数名，上传工具就是这么「一直失败但不报错」的。

还有个坑：ToolRegistration 内部只有一个工具字段，链式 .tool(a).tool(b) 会互相覆盖，最后只有最后一个生效。这类问题都属于静默失败，所以我把它们做成了可复跑的探针测试：一个直接打模型看原始返回，一个按线上方式装配 agent 跑一遍，配合启动日志里逐个打印的 Registered tool 确认工具是否齐全。

对话返回用 SSE 流式，用 Flux.create 拿 sink、订阅 AgentScope 的事件流逐段推。事件体用一行 JSON 而不是裸文本，因为裸文本里带换行会把 SSE 的 data 帧拆断、前端按行解析会丢内容。事件分成三类：思考过程只做「正在思考」提示，工具结果（含失败原因）直接展示便于排查，最终回答才是用户要看的内容——我一开始只订阅了思考事件，结果用户看到的全是模型的内心独白。
```
