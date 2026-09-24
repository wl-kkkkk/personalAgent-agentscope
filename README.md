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
- `common/StructuredModelCaller.java` / `common/JsonSupport.java`：结构化输出调用（提示词约束 + 容错 JSON 解析），对标 personalrag 里的 `.entity(Xxx.class)`。
- `agent/KnowledgeAgentFactory.java`：**按请求**构建 agent，每次一份新 memory + 一份 `toolkit.copy()` 和对应 `SkillBox`。
- `skill/SkillCatalog.java`：启动时加载 skill，并维护"skill → 需要哪些工具"的绑定。
- `session/AgentSessionStore.java`：会话持久化，`MysqlSession` + `userId:sessionId` 的 key 规则。
- `service/ChatService.java`：`loadIfExists` → `call` → `saveTo` 的显式存取。
- `common/StreamEvent.java`：流式对话的 SSE 事件体（session / progress / chunk / approval / done / error）。
- `tool/QueryRewriteTools.java`：`rewrite_query`，结合历史对话改写检索查询。
- `hook/QueryRewriteHook.java`：每次请求进入时自动改写用户问题（Hook 保证必跑，不由模型决定）。
- `intent/*` + `hook/IntentRecognitionHook.java`：意图识别，判定这一轮走知识库还是联网，详见下面「意图识别与关键词词表」。
- `keyword/*`：关键词词表（每个用户一张）+ 归一化（`Keywords`）+ frontmatter 读写（`Frontmatter`）。查询侧做匹配，入库侧做去重与沉淀。
- `tool/WebSearchTools.java`：`web_search`。
- `tool/DocumentTools.java`：`write_markdown`（保存目录由用户指定，关键词写进 frontmatter）。
- `tool/KeywordTools.java`：`extract_keywords`，提炼候选词 + 与该用户词表对比，返回"已有/新增"。
- `tool/UploadTools.java`：`upload_document`，底层调 RAG 的 MCP 工具 `upload2Rag`；`upload(...)` 返回结构化结果，供审批恢复链路判断成败。
- `tool/PersonalKnowledgeTools.java`：`query_personal_knowledge`，包装 MCP 的 `answerByPersonalKnowledge`，为的是能用 `ToolEmitter` 上报检索进度。
- `service/HitlService.java`：审批后的恢复逻辑（构造匹配 id 的 `ToolResultBlock` 回填）。
- `hook/UploadApprovalHook.java`：挂在 `PostReasoningEvent` 上的 HITL 拦截点。
- `user/*`：用户模块（controller → service → repository），Sa-Token 登录 + BCrypt 密码。
- `library/*`：本地 Markdown 文件库（目录校验、扫描同步、按元信息读取正文）。
- `config/SaTokenConfig.java` / `config/PasswordEncoderConfig.java`：登录拦截与密码编码。
- `resources/static/index.html`：网页界面（登录注册 + 目录选择 + 文件列表 + Markdown 预览）。
- `resources/prompts/*.st`：`agent-system`（智能体系统提示词）、`query-rewrite`（查询改写）、`intent-recognition`（意图识别，要求 JSON 输出）。
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

# 强制走某条检索路径（不传 = 交给意图识别判断）
curl -b cookie.txt -X POST http://localhost:8082/api/agent/ask \
  -H "Content-Type: application/json" -d '{"query":"Redis 怎么配","forceRoute":"rag"}'
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

### 工具执行中的进度（ToolEmitter）

联网检索要几十秒、关键词提炼要等一次模型调用，这期间用户原本只看得到转圈。现在工具可以主动报进度：方法上多声明一个
`ToolEmitter` 参数（框架自动注入，不需要 `@ToolParam`，也不进工具的 schema），执行到一半 `emitter.emit(ToolResultBlock.text("..."))` 即可。

```
工具 emit(chunk)
  → ActingChunkEvent（Hook 事件）
  → StreamingHook 转成流式事件：TOOL_RESULT，isLast = false
  → ChatService 按 isLast 分流：false → progress（进度提示），true → tool（工具结果，留在气泡里）
```

**框架替我们守住的那条边界**：emit 出去的片段只进 Hook 和流式事件，**不进模型上下文**——只有方法返回值才作为 tool result 交给模型。
所以进度文案可以放心写给用户看，但也别指望用它给模型递信息（那是返回值或 HINT 事件的事）。

**为什么不自己把 sink 塞进 ToolExecutionContext**：一是非流式的 `/api/agent/ask` 和审批恢复（`HitlService.approve`）根本没有 sink，
工具里得处处判空，而框架用的是 `NoOpToolEmitter`；二是 `Flux.create` 不保证多线程写 sink 的信号串行，工具一旦在虚拟线程里并发 emit
就会破坏 SSE 帧，而框架这条路是经 Hook 单点转发的；三是把传输层细节漏进了业务工具。

目前接了 `web_search`（开始检索 / 检索完成）与 `extract_keywords`（提炼中 / 候选词与新增数量）。
`write_markdown` 是毫秒级的，加了反而是噪音；`upload_document` 在审批前就被拦下、真正上传发生在 `/approve` 那个非流式请求里，要看到它的进度得把审批接口也改成 SSE。

### MCP 检索也要报进度：包一层本地工具

查知识库走的是 RAG 通过 MCP 暴露的 `answerByPersonalKnowledge`，而**远端注册进来的工具加不了参数**，也就用不上 `ToolEmitter`——可这一步恰恰是最慢的（RAG 检索 + Rerank + 生成）。

解决办法是在本地包一层 `query_personal_knowledge`（`tool/PersonalKnowledgeTools.java`）：先 emit 一条「正在检索个人知识库」，再通过 MCP 客户端调后面那个工具。注册时用 `toolkit.removeTool("answerByPersonalKnowledge")` 把 MCP 直连的那个摘掉——两个功能相同的工具同时存在，模型会随机挑一个，挑中直连的就又看不到进度了。

昵称仍然从 `ToolExecutionContext` 取，不让模型传：模型没有「当前用户是谁」的概念，让它传只会传错或者瞎编。

### 一个静默失败：工具内容是取到了、又被丢掉了

工具事件前端一个字都看不到，排查下来不是没发出来，而是**取文本的方式错了**：

```java
event.getMessage().getTextContent()   // TOOL 消息 → 永远是空串
```

`Msg.getTextContent()` 只收集 `TextBlock`，而框架给 TOOL 消息装的内容块是 `ToolResultBlock`（它是 `ContentBlock` 的子类，不是 `TextBlock` 的子类）。于是拿到空串、被 `if (text.isBlank()) return;` 整段丢掉，`TOOL_RESULT` 这个分支等于死代码。

取法要再展开一层 `ToolResultBlock.getOutput()`。顺带把工具名（框架会填进 `ToolResultBlock.name`）拼到前面，前端那行才看得出是谁在跑：`web_search · 正在联网检索：xxx`。

**这个坑为什么能躲过测试**：单测里图省事用 `Msg.builder().textContent("...")` 造消息，那是 TextBlock，测试全绿、线上全白。现在测试统一按框架的真实形状造（`ToolResultBlock` + `withIdAndName`）。

还有一个连带的小坑：`oneLine()` 对空串有「未知错误」的兜底（那是给 error 事件用的），工具事件判空必须放在调用它之前，否则会出现 `web_search · 未知错误` 这种鬼东西。

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

**审批卡片顺带确认关键词**：模型调 `upload_document` 时会带上 `extract_keywords` 给的 `keywordsText`，`ChatService.emitPendingApproval` 把它与该用户的词表比一遍，随 `approval` 事件一起推给前端（`keywords` + `existingKeywords`）。前端渲染成可勾选的标签并标出"新增/已有"，用户还能自己补词；点确认时把这批词一起提交。

审批通过后的落库顺序在 `HitlService.executeApprovedTool` 里：**先上传成功，再写词表**。上传失败就不动词表，避免留下"有词没文档"的脏数据；词表写入失败也不影响文档已经进库这个事实，只在返回文案里说明。`keywords` 传 null 表示"没确认过，用工具入参里的候选"，传空数组表示"用户把词全删了，这次不沉淀任何词"——两者语义不同，代码里分别处理。

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

## 意图识别与关键词词表

**为什么需要**：Agent 有两条检索路径（个人知识库 / 联网），光靠系统提示词描述"什么时候走哪条"，
模型会飘——问自己的笔记它也去联网，问实时新闻它也翻知识库。所以把这件事从提示词里抽出来，
做成每次必跑的 Hook + 结构化判定，和 `QueryRewriteHook` 同一个思路。

### 三层设计

```
用户提问
  └─ QueryRewriteHook（优先级 50）：先改写查询，补全指代
        └─ IntentRecognitionHook（优先级 60，在改写之后）
              ├─ 取该用户词表（KeywordLibrary，60s 本地缓存）
              ├─ 一次结构化调用：{intent, route, matchedKeywords, reasoning, confidence}
              ├─ 写进 RouteState（每请求一份，通过 ToolExecutionContext 共享给工具）
              └─ 在用户消息末尾附一行【本轮路由】指令
                    ├─ 工具层硬拦截：路由=知识库时 web_search 直接拒调
                    └─ 模型层：按指令走对应 skill
```

**优先级为什么是 60**：`QueryRewriteHook` 是 50，先跑。意图识别要拿改写后的查询去匹配词表，
否则"那个东西怎么配"这类带指代的问题匹配不上任何关键词（AgentScope 的 Hook 是值越小越先跑）。

### 词表怎么参与判定

提示词里带上该用户的完整词表，模型做的是**匹配**而不是**生成**：`matchedKeywords` 只能从清单里
原样选。模型编出来的词会在 `Keywords.intersect` 里被过滤掉，所以词表始终是权威的，
不会被模型的临时发挥污染。词表为空（新用户）也照常判，提示词里标注"词表为空"，
退化成只看问题本身。

### 降级策略（对齐 personalrag 的 QueryRouter）

模型没返回可用 JSON、`route` 取值非法、置信度低于 0.5 —— 三种情况一律降级到知识库检索。
理由是方向上不对称：知识库漏检了还能再联网，反过来白白丢掉用户已有资料。

### 用户可以强制指定

界面上有「自动判断 / 只查知识库 / 强制联网」三档，对应请求体的 `forceRoute`：
`rag` / `web` / 不传。强制时**不调模型**，直接用用户的选择，省掉一次调用。

### 硬拦截而不只是提示

只注入提示词是不够的——ReAct 的模型有权自己决定调哪个工具。所以判定结果同时写进 `RouteState`，
由工具层读：`web_search` 在路由为知识库时直接返回"本轮不联网"。
只拦这一个方向：用户问自己资料时联网既浪费又容易跑偏，反过来允许查知识库反而是意外收获。

### 词表接口

```bash
curl -b cookie.txt http://localhost:8082/api/keywords                      # 看词表
curl -b cookie.txt -X POST http://localhost:8082/api/keywords \
  -H "Content-Type: application/json" -d '{"keywords":["RAG","向量检索"]}'  # 加词（已存在的忽略）
curl -b cookie.txt -X DELETE "http://localhost:8082/api/keywords?keyword=RAG"
```

归一化用的是 NFKC + 小写 + 去空白（`Keywords.normalize`），所以 `RAG` / `rag` / `ＲＡＧ` 在库里是同一个词，
唯一键 `(user_id, normalized)` 挡住重复写法；匹配时也不会因为大小写不同而漏掉。

### 结构化输出怎么实现的

AgentScope 1.0.12 的 `Model` 接口没有 Spring AI 那样的 `.entity()`。它自己的结构化输出
（`StructuredOutputCapableAgent`）是走临时 tool + `tool_choice` 实现的，那是给"整个 agent 的最终输出"
用的，套在 Hook 的单次分类调用上太重。所以这里用同样的三段式：

1. 提示词里写明 JSON 字段约束；
2. `JsonSupport` 容错解析——剥掉 ```json 围栏、从第一个 `{` 开始做括号配对扫描（能正确处理字符串里的括号和转义）；
3. 映射成 record，字段缺失由紧凑构造器兜底。

`app.llm.json-mode=true` 会额外带 DashScope 的 `response_format={"type":"json_object"}`，约束更硬；
但 DashScope 部分端点（本项目的 qwen3.8 系列走的是多模态端点）不一定吃这个参数，所以默认关闭。

## 待完善

- 会话清理：每开一次新会话就多一行数据，建议按更新时间做 TTL 清理（`MysqlSession.delete/clearAllSessions/truncateAllSessions`）。
- 审批任务的清理与幂等：目前同一 `tool_use_id` 唯一键防重，过期未审批的任务需要另做 TTL。
- 路由指令是附在用户消息末尾进记忆的；更干净的做法是按路由收窄工具集，但当前 agent 复用共享 toolkit，暂不支持。
- 本地 Markdown 的 frontmatter 写的是"提炼时的候选词"；用户在审批卡片上增删后，上传进 RAG 的那份会同步成最终列表，本地文件不会跟着改（两边可能不一致）。要一致的话，得让恢复链路把落盘路径也带上、审批后回写本地文件。
- 关键词目前只参与"意图识别时的话题匹配"，还没接进 RAG 的检索（按关键词过滤/加权）。那一步要改 personalrag：`answerByPersonalKnowledge` 加可选参数 + 检索器读它。

## 已完成（本轮）

- **Markdown 保存目录对齐**：`write_markdown` 的默认目录原来只是配置里的 `app.knowledge.dir`，跟用户在界面「更改文件夹」设置的目录是两处，导致保存的文件在文件库里看不到。现在把 `user_info.root_folder` 注入系统提示词（`{{rootFolder}}`），agent 写文档时直接用它；设置页与文件库看到的是同一个目录。
- **修复"工具莫名消失"**：链式 `toolkit.registration().tool(a).tool(b)...` 只会保留最后一个，导致 `web_search`/`rewrite_query`/`write_markdown`/`extract_keywords` 全都没注册上（启动日志里只打印了 `upload_document`）。改成逐个 `apply()`，并用探针验证 agent 能真正调用联网检索。
- 新增两个**手动探针**（`@Tag("probe")`，默认跳过）：`WebSearchProbeTest` 直接打联网模型看原始返回，`AgentToolProbeTest` 按线上方式装配 toolkit+agent 跑一次；跑法 `mvn test -DexcludedGroups= -Dgroups=probe`。
- 联网工具不再吞异常：失败时原样带出异常类型与消息，界面工具结果行和日志都能看到。
- `agentscope_sessions` 改用本项目所在的库（框架默认会建到名为 `agentscope` 的库里），建表语句补进 `init.sql`。
- 网页界面加上对话：导航切换「对话 / 文件库」，消息气泡、HITL 审批卡片、`sessionId` 本地留存；`/api/agent/ask`、`/api/agent/approve` 改为从登录态取用户、返回统一响应体，并做了越权审批校验。
- Web 界面 + 登录 + 本地 Markdown 文件库：`user/*`、`library/*`、`static/index.html`；建表脚本合并成 `docs/sql/init.sql`（建库 + `user_info` + `agent_markdown_file` + `agent_hitl_task` + `agent_keyword`）。
- **意图识别 Hook + 关键词词表**：`hook/IntentRecognitionHook`（优先级 60，在查询改写之后）拿该用户词表做结构化判定，输出 `{intent, route, matchedKeywords, reasoning, confidence}`；结果写进每请求一份的 `RouteState`，工具层据此硬拦截 `web_search`，同时附一行【本轮路由】指令给模型。词表落在 `agent_keyword` 表（NFKC 归一化 + 唯一键去重，60s 本地缓存），配 `/api/keywords` 增删查；界面加了「自动判断 / 只查知识库 / 强制联网」三档，强制时不调模型。结构化输出走 `StructuredModelCaller` + `JsonSupport`（提示词约束 + 括号配对容错解析），不依赖 AgentScope 的 agent 级 StructuredOutput。
- **入库侧关键词链路**：`extract_keywords` 重写为"提炼 + 对比"（结构化 JSON：`candidates` / `existing` / `new` / `keywordsText`，集合运算放服务端，不让模型自己算）。`write_markdown` 多了 `keywords` 参数，写进 Markdown 的 frontmatter（只动开头那个区块，不碰正文）。`upload_document` 带上关键词，审批卡片渲染成可勾选标签、标出"新增/已有"、支持自己补词；审批通过后先上传、成功再写词表（来源标记 `upload`）。`DocumentTools` 本来塞着两个能力，这次把关键词拆成独立的 `KeywordTools`。
- `upload_document` 接上 RAG 真实上传：底层调 MCP 工具 `upload2Rag(documentName, markdownContent)`；`upload2Rag` 同时加进 `SensitiveTools`，防止模型绕过包装工具直接调 MCP。
- skill 绑定工具：`SkillCatalog` 维护"skill → 工具"映射，激活某个 skill 时只暴露它需要的工具。
- 工具集改为**共享基础 toolkit**：`toolkit.copy()` 不复制 MCP 管理器、有丢工具的风险，而 skill 绑定工具组那段实际是空操作、`SkillHook` 也只注入提示词，所以复制没有收益。每个 agent 仍然各自一份 `SkillBox`。
- **工具进度上报 + 修复工具内容不显示**：工具方法加 `ToolEmitter` 参数即可 emit 进度（框架自动注入、不进 schema、chunk 不进模型上下文），`ChatService` 按事件的 `isLast` 分流成 progress / tool 两类。同时修掉一个既有静默失败：TOOL 消息的内容块是 `ToolResultBlock`，而 `Msg.getTextContent()` 只认 `TextBlock`，工具事件因此取到空串被整段丢掉——前端从来没显示过工具调用。另外把 MCP 的 `answerByPersonalKnowledge` 摘掉、换成能报进度的本地包装工具 `query_personal_knowledge`。
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
第一个项目是一个个人知识助手 Agent，整体流程是：用户提出问题后，系统先进行查询重写和意图识别，根据用户需求选择个人知识库或者联网搜索；如果是个人知识查询，就检索已有知识，如果联网搜索，会人工审核把有价值的内容整理沉淀回知识库。

知识库部分是复用我之前的 RAG 项目，没有在 Agent 里重复实现。RAG 侧主要完成文档解析、上传、父子分块、向量化、混合检索、Rerank 和 Query Router 等能力，然后通过 MCP 将知识查询和文档上传封装成工具提供给 Agent，这样 Agent 可以直接调用。


在 Agent 侧，我主要做的首先是查询重写和结构化意图识别（是通过hook机制实现的），并结合关键词词表做进一步路由；然后使用 AgentScope 的渐进式披露的机制，通过 Skill 和 Tool 将不同任务拆分，让 Agent 根据任务按需加载能力。

另外一个重点是 HITL，我自己基于 AgentScope 的 Hook 机制做了工具级人工审批：Agent 产生 Tool Call 后先拦截并挂起，人工确认后再恢复执行，同时把审批状态和挂起信息持久化，保证中断后可以继续运行。

最后在记忆方面，按请求构建 Agent，使用 AutoContextMemory 管理短期上下文，通过 MysqlSession 持久化长期会话，避免不同会话之间出现记忆串台。
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

### Hook 体系与自建的三个 Hook

```
AgentScope 的 Hook 是贯穿 Agent 生命周期的拦截点：所有事件都从统一的 onEvent 进来，按 priority 从小到大执行（默认 100）。
事件分两类：带 setter 的能改内容（改输入消息、改工具入参、改工具结果），不带的就是只读通知；另外还有 stopAgent 这种打断能力。
我按这个特点把三件事做成了 Hook，选它们的关键理由是「这件事必须每轮都发生」——交给模型当工具用，它一定会偷懒跳过。

第一个是查询改写（QueryRewriteHook，优先级 50，挂 PreCallEvent）：用户问题进来先结合最近几轮对话，改写成适合检索的查询。
边界上只处理 role=USER 且带文本的消息，所以 HITL 恢复时那条工具结果消息不会被误改写；改写结果为空、或和原问题一样，就保留原消息不动，避免把内容改丢。

第二个是意图识别（IntentRecognitionHook，优先级 60，也在 PreCallEvent）：判断这一轮该查个人知识库还是联网检索。
优先级比改写大，是为了排在改写之后——它要拿改写后的查询去匹配关键词词表，「那个东西怎么配」这类带指代的问题才匹配得上。
这个 Hook 和另外两个不一样：它带着 userId 和「用户是否强制指定路由」，所以我没做成单例 Bean，而是每次请求 new 一个。
做单例就得用 ThreadLocal 或者可变字段，在响应式链路下会串台。
判定结果同时走两条路：附一行【本轮路由】指令给模型，以及写进一个每请求一份的 RouteState，通过 ToolExecutionContext 共享给工具层，
让 web_search 在路由为知识库时直接拒调。只注入提示词是不够的——ReAct 的模型有权自己选择工具。

第三个是 HITL 拦截（UploadApprovalHook，PostReasoningEvent）：扫到敏感工具就 stopAgent。
时机是关键：PostReasoning 时模型刚产出 tool call、还没执行，拦住既不会执行、也不会被算成失败；挂 PreActingEvent 就晚了，那时工具已经进入执行阶段。
这个 Hook 只负责「停下」，挂起落库和审批后的恢复分别在 ChatService 和 HitlService 里做——Hook 拿不到 session 上下文，这两件事塞进去反而难写。

框架自带的几个 Hook 也要知道：
SkillHook 负责把 skill 的索引（名称+描述）注入提示词，正文仍然靠工具按需加载，这是渐进式披露；
PendingToolRecoveryHook 不是恢复机制而是兜底清理，它会扫出没有结果的 tool call 自动填一个 error 结果，
所以 HITL 恢复必须先注入自己的 ToolResultBlock，否则挂起的上传会被当成孤儿调用、直接判失败；
StructuredOutputHook 是框架做 agent 级结构化输出的实现（走临时 generate_response 工具 + tool_choice），我没有用它——
它面向「整个 Agent 的最终输出」，而我只是要在 Hook 里做一次分类调用，套上去太重，我的结构化输出是提示词约束 + 容错 JSON 解析；
另外还有 StreamingHook（流式事件分发）和 JsonlTraceExporter（把 trace 落成 JSONL，接可观测性用）。
```

**三个自建 Hook 的分工**

| Hook | 优先级 | 挂的事件 | 做什么 | 设计要点 |
|---|---|---|---|---|
| `QueryRewriteHook` | 50 | `PreCallEvent` | 结合历史把问题改写成检索查询 | 必须每轮跑，不能交给模型决定；只处理 USER 文本消息 |
| `IntentRecognitionHook` | 60 | `PreCallEvent` | 结构化意图识别 + 词表匹配，决定知识库还是联网 | 每请求一个实例（带 userId / 强制路由）；结果同时给模型和工具层 |
| `UploadApprovalHook` | 100 | `PostReasoningEvent` | 敏感工具挂起等人工审批 | 此刻 tool call 尚未执行，拦下不算失败；只负责停，落库与恢复在外层 |

**事件清单：Hook 到底能拦什么**

| 事件 | 可做的事 | 典型用途 |
|---|---|---|
| `PreCallEvent` | `setInputMessages` | 一轮调用的入口：查询改写、意图识别 |
| `PreReasoningEvent` | `setInputMessages` / `setGenerateOptions` | 每轮推理前改上下文、改生成参数 |
| `PostReasoningEvent` | `setReasoningMessage` / `stopAgent` | 模型产出 tool call 后、执行前：HITL 拦截 |
| `PreActingEvent` | `setToolUse` | 工具即将执行：改/补入参（注入鉴权、修正参数） |
| `PostActingEvent` | `setToolResult` / `setToolResultMsg` / `stopAgent` | 工具执行完：改写结果、脱敏 |
| `PostCallEvent` | `setFinalMessage` | 一轮结束前改最终回答 |
| `PreSummaryEvent` / `PostSummaryEvent` | `setInputMessages` / `setSummaryMessage` | 记忆压缩前后（对应 AutoContextMemory 的压缩调用） |
| `ReasoningChunkEvent` / `ActingChunkEvent` / `ErrorEvent` | 只读 | 流式输出、错误上报、埋点 |

优先级约定：**数值越小越先执行**，默认 100；框架建议 51~100 放校验与预处理、101~500 放业务逻辑、501~1000 放日志与埋点。本项目的三个 Hook 分别是 50 / 60 / 100。

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

工具执行中的进度用框架注入的 ToolEmitter 上报：工具方法多声明一个 ToolEmitter 参数就能 emit 中间片段，这些片段只进 Hook 和流式事件、不进模型上下文，只有返回值才交给模型。到了 ChatService 再按事件的 isLast 分流——中间片段 isLast=false 当进度提示，工具返回的那条 isLast=true 才留在工具结果区。同一件事本来可以「往 ToolExecutionContext 里塞一个 sink」，但那样非流式路径和审批恢复路径都没有 sink，还得处理多线程写 sink 破坏 SSE 帧的问题，框架这条路把这两样都避开了。
```
