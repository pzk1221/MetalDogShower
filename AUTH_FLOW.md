# 认证、持久会话与手表同步

## 官方认证事实

官方 Android APK `com.jinshugou.app` 4.1.52 的静态实现提供手机号短信验证码登录：

1. `GET user/0/0/public/validateCodeMark?phone=…`
2. 计算验证码请求 `key`
3. `GET user/0/0/public/validateCode?phone=…&app_id=null&key=…`
4. `POST user/0/0/public/yzmLogin`
5. 从成功响应字段 `token` 取值，并在受保护请求中发送自定义 HTTP 头 `token`

`key` 算法来自官方 APK：把 `phone + ":" + mark` 的第 `i` 个 JavaScript UTF-16 code unit 与 `((i % 5) + 1)` 异或，拼回字符串后计算小写 MD5 十六进制。它只复现官方正常验证码客户端行为；验证码仍由用户本人接收和填写。

官方实现未发现 `refresh_token`、刷新端点或轮换流程，401 时会清除 token 并返回登录。因此 v0.2 不实现“刷新一次再重放”，也不假设 token 有固定 5 分钟寿命。官方 token 的实际有效期未知；本地会话一直保留到用户退出、存储不可恢复或官方返回 401。

## 手机端状态机

```text
未登录
  → 为浴室1/浴室2识别并保存 1–2 条设备路由
  → 用户主动请求 OTP（无自动重试）
  → 用户填写 OTP（不持久化）
  → 官方 HTTPS 登录
  → 生成新的 UUID credentialId
  → 手机 Keystore 加密持久化 token + credentialId + 路由
  → 手机可独立查询/控制
  → 用户需要时手动同步到手表；手机会话继续保留
```

手机密文中的持久会话包含：

- 当前代际的 `credentialId`；
- 官方 token；
- 1–2 条带固定槽位的浴室路由；
- 仅供 UI 展示的 `watchBound` 同步标记。

手机号与 OTP 不进入持久会话。登录成功后界面会清空手机号与 OTP；OTP 登录失败也会清空 OTP。手机主 Activity 与小组件确认 Activity 都设置 `FLAG_SECURE`。

`watchBound=false` 不阻止手机控制，它只表示当前 `credentialId + 路由集合` 尚未被手表确认保存。登录后替换、添加或删除浴室路由时，手机保留同一个官方 token，但生成新的 `credentialId`、把 `watchBound` 置为 false，并对旧 `credentialId` 尝试一次手表清除通知。这样，迟到的旧通知不能删除新一代配置。

## 手机持久加密

手机使用不可导出的 AndroidKeyStore AES-256/GCM 密钥加密完整会话：

- 随机 12 字节 IV，由 Cipher 生成；
- 128 位 GCM tag；
- 本地 AAD：`MetalDogPhoneSession:v1`；
- SharedPreferences 仅保存格式版本、IV 和认证密文；
- 密钥失效、密文损坏或格式异常时 fail closed：删除该会话并要求重新 OTP；
- 退出时先移除进程内会话，再删除偏好和/或 Keystore key；
- 备份与设备迁移被禁用。

这里没有 5 分钟本地 TTL。60 秒仅是单次手机到手表同步操作的总超时，不是官方 token 或手机会话的有效期。

## 同一个 token、两份独立密文

手机和手表使用的是同一次官方 OTP 登录返回的同一个 token 值，以便两端都能直接访问官方服务。同步不是远程引用，也不是把手机密文复制给手表：

1. 手机在自己的 Keystore 密钥下持久保存一份会话密文。
2. 同步时，手机只在受限生命周期内取得 token 字节副本并建立一次性加密 envelope。
3. 手表解密后，立即用手表自己的不可导出 Keystore AES-256/GCM 密钥重新加密保存。
4. 两端密钥、IV、AAD、文件和密文互不相同；任何一端都不能直接解开另一端的本地密文。

因此“共享登录”表示共享服务端 credential，而不是共享本地加密材料。

## Provisioning 外层 v1、内层 v2

Data Layer 外层握手和 envelope 仍为协议 v1：

- 消息路径：`/provision/request`、`/provision/public-key`、`/provision/envelope`、`/provision/result`；
- 外层字段 `version=1`；
- 动态 AAD：`MetalDogShowerProvisioning:v1:<requestId>:<challengeBase64>`；
- Watch 生成不可导出的 RSA-2048 私钥与 32 字节随机 challenge；
- 手机生成临时 AES-256 key 与 12 字节 GCM IV；
- AES key 使用 RSA-OAEP 包裹，内容摘要 SHA-256、MGF1 摘要 SHA-1、默认空标签；
- 待处理会话绑定 source node、`requestId` 与 challenge，单次消费并持久拒绝重放；
- 一次同步总时限 60 秒，各响应等待上限 20 秒，超时不自动重发 envelope。

外层解密后的敏感 JSON 已升级为 v2：

```json
{
  "version": 2,
  "token": "<REDACTED>",
  "credentialId": "<UUID>",
  "devices": [
    {
      "slot": 1,
      "brandId": 1041,
      "stadiumId": 1234,
      "deviceId": "<CANONICAL_DEVICE_ID_1>",
      "deviceName": "浴室1"
    },
    {
      "slot": 2,
      "brandId": 1041,
      "stadiumId": 1234,
      "deviceId": "<CANONICAL_DEVICE_ID_2>",
      "deviceName": "浴室2"
    }
  ]
}
```

`devices` 必须有 1–2 项，槽位只能为 1 或 2，槽位和 `deviceId` 均不得重复。Watch 仍接受已发布的内层 v1 单设备明文，把它规范化为槽位 1，并为旧配置生成、记住一个本地 `credentialId`；新同步一律发送内层 v2。这里的“内层 v2”不改变外层 `version=1`、路径或 AAD。

## 401、退出与双向 best-effort 失效

手机受保护请求返回 401：

```text
不重试原请求
  → 仅在 credentialId 仍匹配时清除手机会话
  → 清除小组件的已知状态
  → 对当前已连接的 Wear 节点做一轮 /session/clear best-effort（每节点最多一次）
  → 要求用户重新 OTP 登录
```

手表受保护请求返回 401：

```text
不重试原请求
  → 清除手表本地配置和内存 token
  → 对当前已连接的手机节点做一轮 /session/invalid best-effort（每节点最多一次）
  → 要求用户在手机重新 OTP 登录并重新同步
```

手机主动退出或路由代际轮换也会用旧 `credentialId` 发送同类 secret-free 清除通知。通知 payload 只含外层 `version=1` 和 canonical `credentialId`，不含 token、手机号或设备路由。

失效同步有意采用一次 best-effort：只枚举发送当时的已连接节点，每个节点最多一次 send attempt，没有 ACK、重试、后台队列或服务器撤销端点。它**不是离线原子操作**。接收端只在本地当前 `credentialId` 精确相等时清除，因此迟到的旧代通知不能误删后续登录；没有收到通知的离线端可能暂时保留旧密文，并在下一次官方 401、自行清除或重新同步时收敛。

## 跨设备控制约束

手机主界面与小组件共享手机进程内互斥锁；手表也有自己的互斥锁和 RequestGate。但这些锁无法跨设备协调，官方 switch 接口也没有已验证的 Idempotency-Key。因此不得同时从手机和手表控制同一间浴室。选择浴室、确认路由、刷新状态与现场观察始终是用户责任。

## 本轮验证边界

v0.2 本轮没有安装到手机或手表，没有真实网络、OTP、状态或控制请求。当前单元测试计数为 core 17/0、mobile 9/0、Wear Fake 9/0、Wear Real 9/0；构建与 lint 细节见 [TEST_REPORT.md](TEST_REPORT.md)。本页描述的是当前源码的状态机和安全边界，不把代码存在等同于真实服务已回归验证。
