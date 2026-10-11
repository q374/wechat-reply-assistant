# 微信 AI 表达助手 MVP 技术方案

更新时间：2026-09-29

阶段更新：下文记录 Android 客户端、可选本地后端和 AI 请求边界。当前手机直连路径允许在设置中输入 API Key，应用通过 Android Keystore 加密并在本机保存，不写入 APK；生成前显示数据发送说明，¥1 只是本机软预算而不是服务商硬上限。具体功能范围和验证口径以 README、PRD 和评测文档为准。

当前实施决定：复制为本阶段唯一微信交接方式。普通微信真机上无法安全识别消息输入框，`AccessibilityService` 写入暂缓；当前构建停用该服务并移除回填入口。下文回填方案保留为历史候选，不代表当前版本启用。

## 1. 方案目标

本方案用于支持 PRD 首版的最小可运行产品，验证以下核心流程：

> 用户从微信当前聊天中主动打开工具，填写结构化诉求，获得 1～3 条可编辑的消息建议，复制或回填到微信，最后由用户手动发送。

首版重点验证“结构化引导是否比直接使用通用 AI 更快、更容易”，不建设账号、历史记录、长期记忆或自动发送能力。

## 2. MVP 范围

### 必须实现

- Android 悬浮入口，支持用户主动打开和关闭；
- 结构化输入：沟通目的、事情背景、对方关系、可选聊天上下文、表达要求；
- 输入内容在提交前可查看和修改；
- AI 生成 1～3 条建议消息；
- 建议消息可编辑、重新生成和复制；
- 生成中、成功、空输入、网络失败、超时、重复点击、权限拒绝和回填失败状态；
- 清除本地草稿；
- 明确提示内容只是建议，最终发送由用户手动完成。

### 排序

1. 先完成复制流程；
2. 再验证微信输入框回填；
3. 暂缓历史记录、收藏、长期记忆、多轮对话、语音/图片理解、群聊、账号和支付。

## 3. 技术栈

### Android 客户端

- **Kotlin**：正式产品代码语言；
- **Jetpack Compose**：输入页、结果页、权限说明和错误状态；
- **ViewModel + Kotlin Coroutines**：页面状态和异步请求；
- **Retrofit**：调用后端 API；
- **DataStore**：保存未提交的本地草稿；
- **Foreground Service + 悬浮窗权限**：维护用户主动打开的悬浮入口。

当前 `hello-world-test` 是 Java 最小验证项目。正式 Android 项目建议直接使用 Kotlin；该测试项目只作为工具链和真机验证参考。

### AI 后端

- **Python + FastAPI**：提供轻量、无状态的生成接口；
- **Pydantic**：校验请求和响应结构；
- **模型适配层**：屏蔽具体 AI 服务商差异，后续可更换模型；
- **环境变量**：保存 AI 服务密钥，禁止写入 APK、源码和日志。

MVP 暂不引入数据库。后端默认不保存完整聊天文本，日志只记录请求状态、耗时和错误类型。

## 4. Android 模块边界

建议正式项目按以下职责组织：

```text
app/
├─ ui/              Compose 页面和组件
├─ presentation/    ViewModel、页面状态和事件
├─ data/             API 客户端、本地草稿和数据模型
├─ overlay/          悬浮入口和前台 Service
├─ autofill/         可选的微信输入框回填能力
└─ privacy/          权限说明、清除草稿和隐私提示
```

上述目录是职责边界，不要求 MVP 一开始拆成复杂的多模块 Gradle 工程。

## 5. AI 接口约定

客户端调用后端的核心接口：

```text
POST /v1/generate-replies
```

请求字段：

- `purpose`：沟通目的；
- `background`：事情背景和必要条件；
- `relationship`：对方关系；
- `context`：用户主动粘贴的聊天上下文，可为空；
- `tone_requirements`：表达要求。

响应至少包含：

- `replies`：1～3 条建议消息；
- `need_more_information`：是否需要用户补充信息；
- `message`：可展示给用户的补充说明或错误信息。

AI 生成规则：不能补造用户未提供的事实、承诺或条件；输出必须标记为建议文本；发现关键信息不足时，应要求补充而不是猜测。

## 6. 权限和隐私边界

### 悬浮窗

只用于显示用户主动打开的入口。用户拒绝权限时，应用仍应能进入普通页面，并说明开启和关闭路径。

### 回填

回填候选方案为 `AccessibilityService`，只在用户确认一条建议后，将文本写入当前微信输入框。该能力不读取全部聊天记录，不点击发送按钮。

由于 Android 版本、手机厂商和微信版本可能影响辅助能力，复制功能必须始终作为稳定兜底。回填失败时要提示用户改用复制。

### 网络和数据

- 只有用户点击生成后才提交输入内容；
- 聊天上下文只能来自用户主动输入或粘贴；
- 默认不读取剪贴板；复制时只写入用户选择的文本；
- 默认不将完整聊天文本保存到云端；
- 日志不得包含完整聊天文本、生成结果或 API 密钥；
- 使用 HTTPS 传输；
- 不提供自动发送入口。

## 7. 实施顺序

1. 创建正式 Kotlin Android 项目，完成普通页面和本地草稿；
2. 使用假数据跑通输入、加载、结果、编辑和复制流程；
3. 增加空输入、超时、网络失败、重复点击和重试；
4. 接入 FastAPI 和真实 AI 服务，先验证“向房东申请延长居住”和“向 HR 询问福利待遇”；
5. 加入悬浮入口，在真机上验证从微信进入工具的流程；
6. 评估并实现 AccessibilityService 回填；
7. 完成权限说明、清除草稿和“不自动发送”验收。

## 8. 验收标准

- 用户能够在 2 分钟内完成一次输入、生成、编辑和复制；
- 至少一条建议可以被用户修改后直接使用；
- 生成失败时显示可理解的原因，并能重试；
- 权限拒绝后仍有明确的关闭或替代路径；
- 回填完成后只停留在微信输入框，不触发发送；
- 应用没有自动发送入口；
- 不出现未经用户提交的聊天文本上传；
- 日志不包含完整聊天文本或 API 密钥。

## 9. 当前技术取舍

- 选择 Kotlin/Compose，是为了降低正式 Android 项目的后续维护成本；
- 选择独立 FastAPI 后端，是为了保护 AI 密钥、统一模型调用并控制数据边界；
- 暂不使用数据库，是因为首版没有历史记录和账号需求；
- 复制优先于回填，是因为复制跨设备和微信版本更稳定，辅助功能回填需要额外权限并存在兼容性风险。

## 10. 尚待确认

- Android 最低支持版本；
- 最终 AI 服务商和模型；
- FastAPI 的部署位置；
- AccessibilityService 回填是否进入复制主路径稳定后的后续原型；
- 正式项目的包名、签名和发布方式。

## 11. 本轮审核结论

审核日期：2026-09-28

### MVP 范围已确认

- 必须通过的主流程是：悬浮入口 → 结构化输入 → 生成 → 编辑 → 复制；
- 生成中、成功、空输入、网络失败、超时、重复点击、权限拒绝和明确的重试路径属于首轮范围；
- 本地草稿清除、隐私说明和“不自动发送”边界属于首轮范围；
- 历史记录、长期记忆、多轮对话、群聊、语音/图片理解、账号和支付不进入首轮。

### 回填范围已统一

微信输入框回填保留为后续验证能力。首轮可以提供演示，但复制流程必须独立可用，回填的权限、兼容性或失败不阻断首轮 MVP 验收；任何情况下都不能操作微信发送按钮。

### 建议技术路线已确认

Android 采用 Kotlin + Jetpack Compose，使用 ViewModel、Coroutines、Retrofit 和 DataStore；悬浮入口使用前台 Service 与悬浮窗权限；AI 请求通过 FastAPI 无状态后端转发，暂不使用数据库。该路线作为 MVP 实施基线，具体 AI 服务商、部署位置和 Android 支持版本仍在实施前确定。

## 12. 实施参数定案

### Android 支持版本

- `minSdk = 26`（Android 8.0）：覆盖悬浮窗、前台服务和 DataStore 所需的基础平台能力；
- `compileSdk = 35`；
- `targetSdk = 35`：与当前项目已安装的 `android-35` 和 Build Tools 35.0.0 对齐，用于 MVP 真机验证；
- 暂不以 Google Play 上架为本阶段目标。上架前需安装 API 36，并将 `targetSdk` 提升到 36，重新完成兼容性验证。

### AI 服务商和模型

- 服务商：**DeepSeek 官方 API**；
- MVP 模型：**`deepseek-flash`，非思考模式**（官方当前模型名；旧名称仅作兼容）；
- 接口协议：后端使用 OpenAI Chat Completions 兼容协议调用；
- 适配方式：客户端不接触服务商 API，FastAPI 通过环境变量读取 `DEEPSEEK_API_KEY`、`AI_BASE_URL` 和 `AI_MODEL`；
- 后端必须校验模型响应，无法解析为预期结构时返回明确失败，不把原始模型输出直接当作成功结果。

选择 Flash 非思考模式是因为本产品是短文本整理和改写，首版更看重响应速度和成本；模型名称保留在环境变量中，后续可在不改客户端的情况下切换。

### 后端部署位置

- 部署平台：**腾讯云轻量应用服务器（中国大陆地域）**；
- 部署方式：单台实例运行 Docker Compose，FastAPI 为无状态容器；
- 入口：Caddy 或 Nginx 终止 TLS，应用只监听内网端口；
- 暂不配置数据库、对象存储和用户账号系统；
- 防火墙只开放 HTTPS 端口，SSH 仅用于维护；
- 域名、备案和云账号是部署前置条件，当前尚未创建。

### 隐私和数据配置

- 只有用户点击“生成回复”后才上传五类输入字段；
- 后端不持久化请求正文、聊天上下文和模型结果；
- 应用日志只保留请求 ID、状态码、耗时、错误类型和用量统计，不记录正文、结果或密钥；
- 日志保留 7 天后自动删除；开发环境禁止输出请求体；
- 全部 API 请求使用 HTTPS；AI 密钥只放在服务器环境变量或密钥配置中；
- Android 草稿只保存在应用私有存储，提供“清除本地草稿”，不默认读取剪贴板；
- 首次生成前展示隐私说明，明确内容会发送至 DeepSeek API；
- 外部测试前必须再次核对 DeepSeek 当前数据处理条款，未完成核对前不上传真实敏感聊天内容。

### 正式项目包名和签名

- `namespace` / `applicationId`：**`com.replyassistant.android`**；
- 应用显示名：**微信表达助手**；
- `versionCode = 1`，`versionName = 0.1.0`；
- 本地开发和真机验证使用 Gradle debug 签名；
- 发布签名使用单独的 release keystore，保存于项目外的受保护目录（建议 `.secrets/replyassistant-release.jks`），密码通过环境变量或本机密钥管理提供；
- keystore、密码和签名配置不得进入 Git、APK 以外的交付文档或日志；
- 包名确定后不再随意修改，避免后续升级和数据迁移问题。

### 选型核对依据

- Android API 26： [Android 8.0 官方 API 说明](https://developer.android.com/about/versions/oreo/android-8.0)；
- Google Play target API 要求： [官方 target API 说明](https://developer.android.com/google/play/requirements/target-sdk)；
- DeepSeek 模型和接口： [官方模型列表](https://api-docs.deepseek.com/api/list-models/) 与 [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)；
- 腾讯云部署能力： [轻量应用服务器 Docker 容器](https://cloud.tencent.com/document/product/1207/60329) 与 [HTTPS 配置](https://cloud.tencent.com/document/product/1207/84359)。

## 13. 前置工具核对结果

核对日期：2026-09-28

### DeepSeek 数据处理条款

已阅读 DeepSeek 官方中文版隐私政策和开放平台服务条款。当前结论：

- DeepSeek 的隐私政策适用于其 API，并说明会处理用户输入、网络信息等数据；
- 使用本产品时，项目方仍是面向最终用户的数据处理说明责任主体；
- 产品首次生成前必须告知用户输入内容会发送至 DeepSeek API；
- 在外部测试前只使用虚构或脱敏内容，不上传真实姓名、联系方式、地址、合同、工资等敏感信息；
- 后端自身不保存请求正文和模型结果，不能据此承诺 DeepSeek 服务端不处理或不保留数据；具体使用以当时有效的官方条款为准。

核对材料： [DeepSeek 隐私政策](https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html)、[DeepSeek 开放平台 API 文档](https://api-docs.deepseek.com/api/deepseek-api/)、[DeepSeek 开放平台服务条款](https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html)。

### Release keystore

已使用 Android Studio 自带 JDK 的 `keytool 25.0.3` 生成并验证 release keystore：

- 文件：`.secrets/replyassistant-release.jks`；
- 别名：`replyassistant`；
- 类型：PKCS12；
- 算法：RSA 2048；
- 有效期：10000 天；
- 密码文件：`.secrets/replyassistant-release.password`，仅保存在本机，未纳入 Git。

keystore 和密码文件都已被 `.gitignore` 排除。交付或换机前必须将两者一并备份到受保护的位置；不要把密码写入 Gradle、提交记录或聊天消息。

