# 交付验证记录

验证日期：2026-11-23。

## 构建与自动化测试

使用 Maven Wrapper、Maven 3.9.11、Java 25 执行，源码编译目标为 Java 21。

测试阶段共 21 项，失败 0，错误 0，跳过 0；独立打包成功并生成可执行 Spring Boot JAR。

| 测试组 | 数量 | 结果 |
|---|---:|---|
| BackendIntegrationTest | 11 | 通过 |
| DeepSeekChatProviderTest | 8 | 通过 |
| PersistenceRestartTest | 1 | 通过 |
| SpeechProviderExtensionTest | 1 | 通过 |

验证命令：

```powershell
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp -DskipTests package
```

构建前应先停止正在使用同一个 target JAR 的服务，避免 Windows 文件占用阻止重新打包。

## 真实 DeepSeek 联调

通过启动后的 HTTP 服务执行注册、登录、创建会话和两轮文字对话；使用实际 DeepSeek 服务，不使用模拟回答。

| 轮次 | 状态 | 回答 |
|---|---|---|
| 1 | HTTP 200 | 好的，我记住了：你在学 Java，今天重点是接口。 |
| 2 | HTTP 200 | 你今天重点学 Java 的接口。 |

第二轮正确引用第一轮的“Java 接口”学习重点；历史接口返回 4 条消息。

同时通过以下 HTTP 检查：

- 注册返回 201，登录返回 200，退出后凭证返回 401。
- 能力接口声明使用浏览器语音；未配置的合成接口返回 501。
- 不受信任的网页来源返回 403 JSON，响应体请求 ID 与响应头一致。
- OpenAPI JSON 成功生成并保存到 docs/openapi.json。

模型默认使用普通输出模式，兼容 JSON 和纯文本。测试发现强制 JSON 模式可能返回空白，因此将该模式保留为可选配置；空白或截断回答始终返回错误，不保存半轮消息。

## 语音验证边界

已验证语音接口占位行为和供应商替换机制。未接入实际云端或离线语音供应商，未进行浏览器麦克风及音频播放的端到端测试；这些能力需要网页接入后验证。

密钥、真实登录凭证、数据库和完整运行日志均不包含在交付压缩包中。
